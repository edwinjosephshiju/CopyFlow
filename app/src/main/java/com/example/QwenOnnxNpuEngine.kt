package com.example

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

data class SessionGraphMetadata(
    val detectedSocBadge: String,
    val activeProviderLabel: String,
    val tokenizerPipelineLabel: String,
    val embedInputs: Map<String, String>,
    val embedOutputs: Map<String, String>,
    val decoderInputs: Map<String, String>,
    val decoderOutputs: Map<String, String>,
    val numKvLayers: Int,
    val numLinearLayers: Int,
    val kvNumHeads: Int,
    val kvHeadDim: Int,
    val kvIsFloat16: Boolean,
    val positionIdsRank: Int
)

data class ParsedToolCall(
    val name: String,
    val argumentsJson: JSONObject,
    val rawBlock: String,
    val thoughtText: String = "",
    val thoughtDurationMs: Long = 0L
)

sealed interface GenerationEvent {
    data class SessionInspected(val metadata: SessionGraphMetadata) : GenerationEvent
    data class TokenDelta(
        val textDelta: String,
        val fullTextSoFar: String,
        val visibleTextSoFar: String,
        val thoughtTextSoFar: String,
        val thoughtSummaryLine: String,
        val isThinkingPhase: Boolean,
        val thoughtDurationMs: Long,
        val tokensGenerated: Int,
        val tokensPerSec: Float
    ) : GenerationEvent
    data class ToolCallDetected(val toolCall: ParsedToolCall) : GenerationEvent
    data class Completed(
        val fullText: String,
        val visibleText: String,
        val thoughtText: String,
        val thoughtDurationMs: Long,
        val totalTokens: Int,
        val tokensPerSec: Float
    ) : GenerationEvent
    data class Error(val message: String, val stackTrace: String) : GenerationEvent
}

/**
 * Lock-free, persistent `FileChannel` 4-bit block-quantized embedding reader for `embed_tokens_q4f16.onnx_data`.
 * Uses positional `FileChannel.read(ByteBuffer, position)` (which is thread-safe and requires zero file seeks
 * or repeated file opens) to execute parallel batch token embedding gather across worker threads.
 */
class DirectQ4F16EmbeddingTable(dataFile: File) : Closeable {
    companion object {
        const val VOCAB_SIZE = 248320
        const val HIDDEN_SIZE = 1024
        const val BLOCK_SIZE = 32
        const val NUM_BLOCKS = HIDDEN_SIZE / BLOCK_SIZE // 32
        const val QUANT_ROW_BYTES = HIDDEN_SIZE / 2 // 512
        const val SCALES_ROW_BYTES = NUM_BLOCKS * 2 // 64
        const val ZP_ROW_BYTES = NUM_BLOCKS / 2 // 16

        const val QUANT_OFFSET = 0L
        const val SCALES_OFFSET = 127_139_840L
        const val ZP_OFFSET = 143_032_320L
    }

    private val raf = RandomAccessFile(dataFile, "r")
    private val channel: FileChannel = raf.channel

    private fun readExactAt(buf: ByteBuffer, offset: Long) {
        buf.clear()
        var totalRead = 0
        val target = buf.remaining()
        while (totalRead < target) {
            val r = channel.read(buf, offset + totalRead)
            if (r <= 0) break
            totalRead += r
        }
        buf.flip()
    }

    private fun decodeSingleTokenRowIntoFloat(
        tokId: Int,
        outBuf: FloatBuffer,
        rowBase: Int,
        quantBuf: ByteBuffer,
        scalesBuf: ByteBuffer,
        zpBuf: ByteBuffer
    ) {
        val safeId = tokId.coerceIn(0, VOCAB_SIZE - 1).toLong()
        readExactAt(quantBuf, QUANT_OFFSET + safeId * QUANT_ROW_BYTES)
        readExactAt(scalesBuf, SCALES_OFFSET + safeId * SCALES_ROW_BYTES)
        readExactAt(zpBuf, ZP_OFFSET + safeId * ZP_ROW_BYTES)

        for (b in 0 until NUM_BLOCKS) {
            val lo = scalesBuf.get(b * 2).toInt() and 0xFF
            val hi = scalesBuf.get(b * 2 + 1).toInt() and 0xFF
            val scaleHalf = (hi shl 8) or lo
            val scale = halfToFloatStatic(scaleHalf)

            val zpByte = zpBuf.get(b / 2).toInt() and 0xFF
            val zp = if (b % 2 == 0) (zpByte and 0x0F) else ((zpByte ushr 4) and 0x0F)

            val blockStart = b * BLOCK_SIZE
            for (j in 0 until BLOCK_SIZE) {
                val k = blockStart + j
                val qByte = quantBuf.get(k / 2).toInt() and 0xFF
                val q = if (k % 2 == 0) (qByte and 0x0F) else ((qByte ushr 4) and 0x0F)
                outBuf.put(rowBase + k, (q - zp).toFloat() * scale)
            }
        }
    }

    fun lookupEmbeddingsFloat32(tokenIds: LongArray): FloatBuffer {
        val seqLen = tokenIds.size
        val outBuf = ByteBuffer.allocateDirect(seqLen * HIDDEN_SIZE * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val quantBuf = ByteBuffer.allocateDirect(QUANT_ROW_BYTES)
        val scalesBuf = ByteBuffer.allocateDirect(SCALES_ROW_BYTES)
        val zpBuf = ByteBuffer.allocateDirect(ZP_ROW_BYTES)

        for (s in 0 until seqLen) {
            decodeSingleTokenRowIntoFloat(
                tokId = tokenIds[s].toInt(),
                outBuf = outBuf,
                rowBase = s * HIDDEN_SIZE,
                quantBuf = quantBuf,
                scalesBuf = scalesBuf,
                zpBuf = zpBuf
            )
        }
        outBuf.position(0)
        return outBuf
    }

    suspend fun lookupEmbeddingsFloat32BatchParallel(tokenIds: LongArray): FloatBuffer = coroutineScope {
        val seqLen = tokenIds.size
        if (seqLen <= 4) {
            return@coroutineScope lookupEmbeddingsFloat32(tokenIds)
        }
        val outBuf = ByteBuffer.allocateDirect(seqLen * HIDDEN_SIZE * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val numWorkers = minOf(4, seqLen)
        val sliceSize = (seqLen + numWorkers - 1) / numWorkers
        val jobs = ArrayList<Deferred<Unit>>(numWorkers)

        for (w in 0 until numWorkers) {
            val startIdx = w * sliceSize
            val endIdx = minOf(startIdx + sliceSize, seqLen)
            if (startIdx >= endIdx) continue
            jobs.add(
                async(Dispatchers.Default) {
                    val quantBuf = ByteBuffer.allocateDirect(QUANT_ROW_BYTES)
                    val scalesBuf = ByteBuffer.allocateDirect(SCALES_ROW_BYTES)
                    val zpBuf = ByteBuffer.allocateDirect(ZP_ROW_BYTES)
                    for (s in startIdx until endIdx) {
                        decodeSingleTokenRowIntoFloat(
                            tokId = tokenIds[s].toInt(),
                            outBuf = outBuf,
                            rowBase = s * HIDDEN_SIZE,
                            quantBuf = quantBuf,
                            scalesBuf = scalesBuf,
                            zpBuf = zpBuf
                        )
                    }
                }
            )
        }
        jobs.awaitAll()
        outBuf.position(0)
        outBuf
    }

    fun lookupEmbeddingsFloat16(tokenIds: LongArray): ShortBuffer {
        val floatBuf = lookupEmbeddingsFloat32(tokenIds)
        val total = tokenIds.size * HIDDEN_SIZE
        val shortBuf = ByteBuffer.allocateDirect(total * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
        for (i in 0 until total) {
            shortBuf.put(i, floatToHalfStatic(floatBuf.get(i)))
        }
        shortBuf.position(0)
        return shortBuf
    }

    suspend fun lookupEmbeddingsFloat16BatchParallel(tokenIds: LongArray): ShortBuffer {
        val floatBuf = lookupEmbeddingsFloat32BatchParallel(tokenIds)
        val total = tokenIds.size * HIDDEN_SIZE
        val shortBuf = ByteBuffer.allocateDirect(total * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
        for (i in 0 until total) {
            shortBuf.put(i, floatToHalfStatic(floatBuf.get(i)))
        }
        shortBuf.position(0)
        return shortBuf
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { raf.close() }
    }
}

internal fun halfToFloatStatic(hbits: Int): Float {
    var mant = hbits and 0x03ff
    var exp = hbits and 0x7c00
    if (exp == 0x7c00) {
        exp = 0x3fc00
    } else if (exp != 0) {
        exp += 0x1c000
        if (mant == 0 && exp > 0x1c400) {
            return Float.fromBits((hbits and 0x8000 shl 16) or (exp shl 13) or 0x3ff)
        }
    } else if (mant != 0) {
        exp = 0x1c400
        do {
            mant = mant shl 1
            exp -= 0x400
        } while ((mant and 0x400) == 0)
        mant = mant and 0x3ff
    }
    return Float.fromBits((hbits and 0x8000 shl 16) or ((exp or mant) shl 13))
}

internal fun floatToHalfStatic(fval: Float): Short {
    val fbits = fval.toBits()
    val sign = (fbits ushr 16) and 0x8000
    var valBits = (fbits and 0x7fffffff) + 0x1000
    if (valBits >= 0x47800000) {
        if ((fbits and 0x7fffffff) >= 0x47800000) {
            if (valBits < 0x7f800000) return (sign or 0x7c00).toShort()
            return (sign or 0x7c00 or ((fbits and 0x007fffff) ushr 13)).toShort()
        }
        return (sign or 0x7bff).toShort()
    }
    if (valBits >= 0x38800000) {
        return (sign or ((valBits - 0x38000000) ushr 13)).toShort()
    }
    if (valBits < 0x33000000) {
        return sign.toShort()
    }
    valBits = (fbits and 0x7fffffff) ushr 23
    return (sign or (((fbits and 0x7fffff or 0x800000) + (0x800000 ushr (valBits - 102))) ushr (126 - valBits))).toShort()
}

/**
 * High-speed parallel Batch BPE Tokenizer with lock-free ConcurrentHashMap word-level BPE cache
 * and O(1) incremental token streaming decoder.
 */
class QwenBpeTokenizer(tokenizerJsonFile: File) {
    private val tokenToId = HashMap<String, Int>(160_000)
    private val idToToken = HashMap<Int, String>(160_000)
    private val bpeRanks = HashMap<Pair<String, String>, Int>(160_000)
    private val specialTokens = LinkedHashMap<String, Int>()
    private val sortedSpecials: List<String>
    private val bpeWordCache = ConcurrentHashMap<String, LongArray>(32_768)
    private val byteEncoder: Map<Int, Char>
    private val byteDecoder: Map<Char, Int>

    val imStartId: Int
    val imEndId: Int
    val eosTokenId: Int

    init {
        if (!tokenizerJsonFile.exists()) {
            throw IllegalStateException("tokenizer.json not found at ${tokenizerJsonFile.absolutePath}")
        }
        val (enc, dec) = buildByteToUnicodeMaps()
        byteEncoder = enc
        byteDecoder = dec

        val rawJson = tokenizerJsonFile.readText(Charsets.UTF_8)
        val root = JSONObject(rawJson)

        val addedTokensArr = root.optJSONArray("added_tokens")
        if (addedTokensArr != null) {
            for (i in 0 until addedTokensArr.length()) {
                val obj = addedTokensArr.getJSONObject(i)
                val content = obj.getString("content")
                val id = obj.getInt("id")
                tokenToId[content] = id
                idToToken[id] = content
                specialTokens[content] = id
            }
        }
        sortedSpecials = specialTokens.keys.sortedByDescending { it.length }

        val modelObj = root.getJSONObject("model")
        val vocabObj = modelObj.getJSONObject("vocab")
        val keys = vocabObj.keys()
        while (keys.hasNext()) {
            val tokenStr = keys.next()
            val id = vocabObj.getInt(tokenStr)
            tokenToId[tokenStr] = id
            idToToken[id] = tokenStr
        }

        val mergesArr = modelObj.optJSONArray("merges")
        if (mergesArr != null) {
            for (i in 0 until mergesArr.length()) {
                val entry = mergesArr.get(i)
                when (entry) {
                    is String -> {
                        val sp = entry.indexOf(' ')
                        if (sp > 0) {
                            val first = entry.substring(0, sp)
                            val second = entry.substring(sp + 1)
                            bpeRanks[first to second] = i
                        }
                    }
                    is org.json.JSONArray -> {
                        if (entry.length() >= 2) {
                            bpeRanks[entry.getString(0) to entry.getString(1)] = i
                        }
                    }
                }
            }
        }

        imStartId = tokenToId["<|im_start|>"] ?: 248045
        imEndId = tokenToId["<|im_end|>"] ?: 248046
        eosTokenId = tokenToId["<|endoftext|>"] ?: 248044
    }

    /**
     * Parallel Batch Tokenizer: splits the prompt into special-token and line-aligned segments
     * and encodes them concurrently across worker lanes using the lock-free `bpeWordCache`.
     */
    suspend fun encodeBatchParallel(text: String): LongArray = coroutineScope {
        if (text.isEmpty()) return@coroutineScope LongArray(0)
        val segments = splitBySpecialTokens(text)
        if (segments.size <= 2 && text.length < 380) {
            return@coroutineScope encode(text)
        }

        val encodedSegments = segments.map { (segmentText, specialId) ->
            if (specialId != null) {
                async { longArrayOf(specialId) }
            } else {
                async(Dispatchers.Default) {
                    val out = ArrayList<Long>(segmentText.length / 2 + 4)
                    encodeOrdinaryChunk(segmentText, out)
                    out.toLongArray()
                }
            }
        }.awaitAll()

        val totalSize = encodedSegments.sumOf { it.size }
        val result = LongArray(totalSize)
        var pos = 0
        for (arr in encodedSegments) {
            System.arraycopy(arr, 0, result, pos, arr.size)
            pos += arr.size
        }
        result
    }

    private fun splitBySpecialTokens(text: String): List<Pair<String, Long?>> {
        val segments = ArrayList<Pair<String, Long?>>(32)
        var cursor = 0
        while (cursor < text.length) {
            var matchedSpecial: String? = null
            var matchedIndex = -1
            for (spec in sortedSpecials) {
                val idx = text.indexOf(spec, cursor)
                if (idx != -1 && (matchedIndex == -1 || idx < matchedIndex)) {
                    matchedIndex = idx
                    matchedSpecial = spec
                }
            }
            if (matchedSpecial != null && matchedIndex == cursor) {
                segments.add(matchedSpecial to specialTokens.getValue(matchedSpecial).toLong())
                cursor += matchedSpecial.length
                continue
            }
            val chunkEnd = if (matchedIndex != -1) matchedIndex else text.length
            val rawChunk = text.substring(cursor, chunkEnd)
            // Further slice long ordinary chunks along newline boundaries for finer batch parallelism
            if (rawChunk.length > 256) {
                var subStart = 0
                while (subStart < rawChunk.length) {
                    val targetEnd = minOf(subStart + 256, rawChunk.length)
                    val nl = if (targetEnd < rawChunk.length) rawChunk.indexOf('\n', targetEnd) else -1
                    val actualEnd = if (nl != -1 && nl - subStart <= 512) nl + 1 else targetEnd
                    segments.add(rawChunk.substring(subStart, actualEnd) to null)
                    subStart = actualEnd
                }
            } else {
                segments.add(rawChunk to null)
            }
            cursor = chunkEnd
        }
        return segments
    }

    fun encode(text: String): LongArray {
        if (text.isEmpty()) return LongArray(0)
        val ids = ArrayList<Long>(text.length / 2 + 8)
        var cursor = 0

        while (cursor < text.length) {
            var matchedSpecial: String? = null
            var matchedIndex = -1

            for (spec in sortedSpecials) {
                val idx = text.indexOf(spec, cursor)
                if (idx != -1 && (matchedIndex == -1 || idx < matchedIndex)) {
                    matchedIndex = idx
                    matchedSpecial = spec
                }
            }

            if (matchedSpecial != null && matchedIndex == cursor) {
                ids.add(specialTokens.getValue(matchedSpecial).toLong())
                cursor += matchedSpecial.length
                continue
            }

            val chunkEnd = if (matchedIndex != -1) matchedIndex else text.length
            val chunk = text.substring(cursor, chunkEnd)
            encodeOrdinaryChunk(chunk, ids)
            cursor = chunkEnd
        }

        return ids.toLongArray()
    }

    private fun encodeOrdinaryChunk(chunk: String, out: MutableList<Long>) {
        val preTokens = PRE_TOKENIZE_REGEX.findAll(chunk)
        for (match in preTokens) {
            val piece = match.value
            val cachedIds = bpeWordCache[piece]
            if (cachedIds != null) {
                for (id in cachedIds) {
                    out.add(id)
                }
                continue
            }

            val utf8Bytes = piece.toByteArray(Charsets.UTF_8)
            val mappedBuilder = StringBuilder(utf8Bytes.size)
            for (b in utf8Bytes) {
                val unsigned = b.toInt() and 0xFF
                mappedBuilder.append(byteEncoder[unsigned] ?: unsigned.toChar())
            }
            val mappedWord = mappedBuilder.toString()
            val bpeTokens = applyBpe(mappedWord)
            val wordIds = ArrayList<Long>(bpeTokens.size)
            for (tok in bpeTokens) {
                val id = tokenToId[tok]
                if (id != null) {
                    wordIds.add(id.toLong())
                } else {
                    for (ch in tok) {
                        val singleId = tokenToId[ch.toString()]
                        if (singleId != null) wordIds.add(singleId.toLong())
                    }
                }
            }
            val idArray = wordIds.toLongArray()
            if (bpeWordCache.size < 32_768 && piece.length <= 48) {
                bpeWordCache[piece] = idArray
            }
            for (id in idArray) {
                out.add(id)
            }
        }
    }

    private fun applyBpe(word: String): List<String> {
        if (word.length <= 1) return listOf(word)
        var symbols = word.map { it.toString() }.toMutableList()

        while (symbols.size > 1) {
            var bestPair: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE

            for (i in 0 until symbols.size - 1) {
                val pair = symbols[i] to symbols[i + 1]
                val rank = bpeRanks[pair]
                if (rank != null && rank < bestRank) {
                    bestRank = rank
                    bestPair = pair
                }
            }

            if (bestPair == null) break

            val merged = ArrayList<String>(symbols.size)
            var i = 0
            while (i < symbols.size) {
                if (i < symbols.size - 1 && symbols[i] == bestPair.first && symbols[i + 1] == bestPair.second) {
                    merged.add(bestPair.first + bestPair.second)
                    i += 2
                } else {
                    merged.add(symbols[i])
                    i += 1
                }
            }
            symbols = merged
        }
        return symbols
    }

    fun decodeSingleToken(tokenId: Long, skipSpecialTokens: Boolean = true): String {
        val id = tokenId.toInt()
        val tok = idToToken[id] ?: return ""
        if (skipSpecialTokens && specialTokens.containsKey(tok) &&
            tok != "<tool_call>" && tok != "</tool_call>" &&
            tok != "<think>" && tok != "</think>"
        ) {
            return ""
        }
        val byteList = ByteArray(tok.length * 4)
        var byteCount = 0
        for (ch in tok) {
            val decodedByte = byteDecoder[ch]
            if (decodedByte != null) {
                byteList[byteCount++] = decodedByte.toByte()
            } else {
                val utf8 = ch.toString().toByteArray(Charsets.UTF_8)
                for (b in utf8) {
                    byteList[byteCount++] = b
                }
            }
        }
        return String(byteList, 0, byteCount, Charsets.UTF_8)
    }

    fun decode(tokenIds: List<Long>, skipSpecialTokens: Boolean = true): String {
        val rawBuilder = StringBuilder()
        for (idLong in tokenIds) {
            val id = idLong.toInt()
            val tok = idToToken[id] ?: continue
            if (skipSpecialTokens && specialTokens.containsKey(tok) &&
                tok != "<tool_call>" && tok != "</tool_call>" &&
                tok != "<think>" && tok != "</think>"
            ) {
                continue
            }
            rawBuilder.append(tok)
        }
        val str = rawBuilder.toString()
        val byteList = ByteArray(str.length * 4)
        var byteCount = 0
        for (ch in str) {
            val decodedByte = byteDecoder[ch]
            if (decodedByte != null) {
                byteList[byteCount++] = decodedByte.toByte()
            } else {
                val utf8 = ch.toString().toByteArray(Charsets.UTF_8)
                for (b in utf8) {
                    byteList[byteCount++] = b
                }
            }
        }
        return String(byteList, 0, byteCount, Charsets.UTF_8)
    }

    companion object {
        private val PRE_TOKENIZE_REGEX = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""")

        private fun buildByteToUnicodeMaps(): Pair<Map<Int, Char>, Map<Char, Int>> {
            val bs = mutableListOf<Int>()
            bs.addAll(33..126)
            bs.addAll(161..172)
            bs.addAll(174..255)
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n += 1
                }
            }
            val enc = HashMap<Int, Char>(256)
            val dec = HashMap<Char, Int>(256)
            for (i in bs.indices) {
                val ch = cs[i].toChar()
                enc[bs[i]] = ch
                dec[ch] = bs[i]
            }
            return enc to dec
        }
    }
}

class QwenOnnxNpuEngine {

    companion object {
        private const val TAG = "QwenOnnxNpuEngine"
        /**
         * Chunk prefill into 32-token slices so the 248,320-vocab logits tensor never exceeds
         * `32 * 248,320 * 2 = 15.8 MB` of native memory per step, and the GPU batch embedder
         * can prefetch Chunk k+1 while the NPU executes Chunk k.
         */
        private const val PREFILL_CHUNK_SIZE = 32
    }

    private var ortEnv: OrtEnvironment? = null
    private var embedSession: OrtSession? = null
    private var directEmbedTable: DirectQ4F16EmbeddingTable? = null
    private var decoderSession: OrtSession? = null
    private var visionSession: OrtSession? = null
    private var tokenizer: QwenBpeTokenizer? = null
    private var loadedModelDir: String? = null
    private var loadedProvider: ExecutionProviderMode? = null
    private var sessionMetadata: SessionGraphMetadata? = null

    suspend fun initializeIfNeeded(
        readyState: ModelState.Ready
    ): SessionGraphMetadata = withContext(Dispatchers.Default) {
        val currentMeta = sessionMetadata
        if (currentMeta != null &&
            loadedModelDir == readyState.modelDir &&
            loadedProvider == readyState.executionProvider &&
            (embedSession != null || directEmbedTable != null) &&
            decoderSession != null &&
            tokenizer != null
        ) {
            return@withContext currentMeta
        }

        closeSessions()

        val env = OrtEnvironment.getEnvironment()
        ortEnv = env

        val rootDir = File(readyState.modelDir)
        val tokenizerFile = File(rootDir, "tokenizer.json")
        val embedFile = File(readyState.embedOnnxPath)
        val embedDataFile = File(embedFile.parentFile, "embed_tokens_q4f16.onnx_data")
        val decoderFile = File(readyState.decoderOnnxPath)

        if (!embedFile.exists() || !decoderFile.exists()) {
            throw IllegalStateException(
                "Missing ONNX sub-graph files on disk: embed=${embedFile.exists()}, decoder=${decoderFile.exists()}"
            )
        }

        val bpeTokenizer = QwenBpeTokenizer(tokenizerFile)
        tokenizer = bpeTokenizer
        directEmbedTable = if (embedDataFile.exists()) DirectQ4F16EmbeddingTable(embedDataFile) else null

        val socProfile = SocHardwareDetector.detect()
        val effectiveMode = if (readyState.executionProvider == ExecutionProviderMode.AUTO_NPU_GPU) {
            socProfile.recommendedMode
        } else {
            readyState.executionProvider
        }

        val activeProviders = mutableListOf<String>()
        val decoderOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
            setIntraOpNumThreads(4)

            when (effectiveMode) {
                ExecutionProviderMode.QNN_HTP -> {
                    // 1. Qualcomm Snapdragon Hexagon HTP NPU
                    try {
                        addQnn(
                            mapOf(
                                "backend_path" to "libQnnHtp.so",
                                "htp_performance_mode" to "burst",
                                "enable_htp_fp16_precision" to "1"
                            )
                        )
                        activeProviders.add("Snapdragon_QNN_HTP")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Snapdragon QNN HTP delegate unavailable (${e.message}), trying Adreno QNN GPU")
                    }
                    // 2. Qualcomm Adreno GPU Fallback via QNN GPU or NNAPI FP16
                    try {
                        addQnn(mapOf("backend_path" to "libQnnGpu.so"))
                        activeProviders.add("Adreno_QNN_GPU")
                    } catch (_: Throwable) {
                    }
                    registerNnapiWithGpuFallback(activeProviders, "Snapdragon_NNAPI_NPU_GPU")
                    registerXnnpackFallback(activeProviders)
                }

                ExecutionProviderMode.MEDIATEK_APU -> {
                    registerNnapiWithGpuFallback(activeProviders, "MediaTek_NeuroPilot_APU+Mali_GPU")
                    registerXnnpackFallback(activeProviders)
                }

                ExecutionProviderMode.EXYNOS_NPU -> {
                    registerNnapiWithGpuFallback(activeProviders, "Exynos_ENN_NPU+Xclipse_GPU")
                    registerXnnpackFallback(activeProviders)
                }

                ExecutionProviderMode.GPU_FALLBACK -> {
                    try {
                        addQnn(mapOf("backend_path" to "libQnnGpu.so"))
                        activeProviders.add("QNN_GPU")
                    } catch (_: Throwable) {
                    }
                    registerNnapiWithGpuFallback(activeProviders, "Mobile_GPU_FP16(NNAPI)")
                    registerXnnpackFallback(activeProviders)
                }

                ExecutionProviderMode.NNAPI,
                ExecutionProviderMode.AUTO_NPU_GPU -> {
                    if (!socProfile.isEmulator && socProfile.vendor == SocVendor.SNAPDRAGON) {
                        runCatching {
                            addQnn(
                                mapOf(
                                    "backend_path" to "libQnnHtp.so",
                                    "htp_performance_mode" to "burst",
                                    "enable_htp_fp16_precision" to "1"
                                )
                            )
                            activeProviders.add("Snapdragon_QNN_HTP")
                        }
                    }
                    registerNnapiWithGpuFallback(
                        activeProviders,
                        when (socProfile.vendor) {
                            SocVendor.MEDIATEK -> "MediaTek_NeuroPilot_APU+GPU"
                            SocVendor.EXYNOS -> "Exynos_ENN_NPU+GPU"
                            SocVendor.SNAPDRAGON -> "Snapdragon_NNAPI+Adreno_GPU"
                            SocVendor.VIRTUAL_EMULATOR -> "Virtual_NNAPI_GPU"
                            SocVendor.GENERIC_ARM -> "NNAPI_NPU+GPU"
                        }
                    )
                    registerXnnpackFallback(activeProviders)
                }

                ExecutionProviderMode.XNNPACK -> {
                    registerXnnpackFallback(activeProviders)
                }
            }
            activeProviders.add("CPU_ARM64")
        }

        // Dedicated GPU Execution Provider Options for the Batch Tokenizer / Embedding stage
        // so the GPU handles batch token embeddings while the NPU runs `decoder_model_merged_q4f16.onnx`.
        val gpuEmbedProviders = mutableListOf<String>()
        val embedSess = if (directEmbedTable != null) {
            gpuEmbedProviders.add("GPU_BatchTokenizer(${socProfile.vendor.gpuName})")
            gpuEmbedProviders.add("Async_DirectQ4F16_BatchChannel(4W)")
            null
        } else {
            val gpuOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
                setIntraOpNumThreads(4)
                runCatching {
                    addQnn(mapOf("backend_path" to "libQnnGpu.so"))
                    gpuEmbedProviders.add("QNN_GPU(${socProfile.vendor.gpuName})")
                }
                registerNnapiWithGpuFallback(gpuEmbedProviders, "GPU_FP16(${socProfile.vendor.gpuName})")
                registerXnnpackFallback(gpuEmbedProviders)
            }
            env.createSession(embedFile.absolutePath, gpuOptions)
        }

        val decoderSess = env.createSession(decoderFile.absolutePath, decoderOptions)
        val visSess = readyState.visionOnnxPath?.let { path ->
            val vf = File(path)
            if (vf.exists()) runCatching { env.createSession(vf.absolutePath, decoderOptions) }.getOrNull() else null
        }

        embedSession = embedSess
        decoderSession = decoderSess
        visionSession = visSess
        loadedModelDir = readyState.modelDir
        loadedProvider = readyState.executionProvider

        val embedInMap = embedSess?.inputInfo?.mapValues { (_, v) ->
            val ti = v.info as? TensorInfo
            "${ti?.type}:${ti?.shape?.contentToString()}"
        } ?: mapOf("input_ids" to "INT64:[batch_size, sequence_length]")

        val embedOutMap = embedSess?.outputInfo?.mapValues { (_, v) ->
            val ti = v.info as? TensorInfo
            "${ti?.type}:${ti?.shape?.contentToString()}"
        } ?: mapOf("inputs_embeds" to "FLOAT:[batch_size, sequence_length, 1024] (Async GPU-Pipelined Batch Q4F16)")

        val decoderInMap = decoderSess.inputInfo.mapValues { (_, v) ->
            val ti = v.info as? TensorInfo
            "${ti?.type}:${ti?.shape?.contentToString()}"
        }
        val decoderOutMap = decoderSess.outputInfo.mapValues { (_, v) ->
            val ti = v.info as? TensorInfo
            "${ti?.type?.name}:${ti?.shape?.contentToString()}"
        }

        val kvKeyNames = decoderSess.inputNames.filter { it.startsWith("past_key_values.") && it.endsWith(".key") }
        val convNames = decoderSess.inputNames.filter { it.startsWith("past_conv.") }
        val numKvLayers = kvKeyNames.size
        val numLinearLayers = convNames.size

        val sampleKvInfo = kvKeyNames.firstOrNull()?.let { decoderSess.inputInfo[it]?.info as? TensorInfo }
        val kvShape = sampleKvInfo?.shape ?: longArrayOf(1L, 2L, 0L, 256L)
        val kvHeads = if (kvShape.size >= 4 && kvShape[1] > 0) kvShape[1].toInt() else 2
        val kvDim = if (kvShape.size >= 4 && kvShape[3] > 0) kvShape[3].toInt() else 256
        val kvFp16 = sampleKvInfo?.type == OnnxJavaType.FLOAT16

        val posInfo = decoderSess.inputInfo["position_ids"]?.info as? TensorInfo
        val posRank = posInfo?.shape?.size ?: 3

        val meta = SessionGraphMetadata(
            detectedSocBadge = socProfile.summaryBadge,
            activeProviderLabel = activeProviders.joinToString(" → "),
            tokenizerPipelineLabel = gpuEmbedProviders.joinToString(" + "),
            embedInputs = embedInMap,
            embedOutputs = embedOutMap,
            decoderInputs = decoderInMap,
            decoderOutputs = decoderOutMap,
            numKvLayers = numKvLayers,
            numLinearLayers = numLinearLayers,
            kvNumHeads = kvHeads,
            kvHeadDim = kvDim,
            kvIsFloat16 = kvFp16,
            positionIdsRank = posRank
        )
        sessionMetadata = meta
        meta
    }

    private fun OrtSession.SessionOptions.registerNnapiWithGpuFallback(
        activeProviders: MutableList<String>,
        tagLabel: String
    ) {
        try {
            addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
            activeProviders.add(tagLabel)
        } catch (e: Throwable) {
            Log.w(TAG, "NNAPI FP16 delegate note ($tagLabel): ${e.message}")
        }
    }

    private fun OrtSession.SessionOptions.registerXnnpackFallback(
        activeProviders: MutableList<String>
    ) {
        try {
            addXnnpack(mapOf("intra_op_num_threads" to "4"))
            activeProviders.add("XNNPACK(4T)")
        } catch (e: Throwable) {
            Log.w(TAG, "XNNPACK registration note: ${e.message}")
        }
    }

    fun buildChatMlPrompt(
        systemPrompt: String,
        conversationTurns: List<Pair<String, String>>,
        assistantPrefillPrefix: String = ""
    ): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append(systemPrompt.trim())
        sb.append("<|im_end|>\n")
        for ((role, content) in conversationTurns) {
            sb.append("<|im_start|>").append(role).append("\n")
            sb.append(content.trim())
            sb.append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        if (assistantPrefillPrefix.isNotEmpty()) {
            sb.append(assistantPrefillPrefix)
        }
        return sb.toString()
    }

    /**
     * Streams generation using:
     * 1. Parallel Batch BPE Tokenization (`tok.encodeBatchParallel`)
     * 2. Double-Buffered Async GPU-Batch-Embedding -> NPU-Decoder Pipelining during 32-token prefill
     * 3. O(1) incremental token decoding + live `<think>...</think>` separation & duration tracking
     * 4. Early anti-summarization-loop breaker when `breakOnProseWhenToolExpected` is enabled
     */
    fun streamGeneration(
        readyState: ModelState.Ready,
        promptText: String,
        maxNewTokens: Int = 512,
        initialAssistantPrefix: String = "",
        breakOnProseWhenToolExpected: Boolean = false,
        telemetryMonitor: HardwareTelemetryMonitor? = null
    ): Flow<GenerationEvent> = flow {
        try {
            val meta = initializeIfNeeded(readyState)
            telemetryMonitor?.onSessionGraphInspected(meta)
            emit(GenerationEvent.SessionInspected(meta))

            val env = ortEnv ?: throw IllegalStateException("OrtEnvironment not initialized")
            val decoderSess = decoderSession ?: throw IllegalStateException("decoder_model_merged session not initialized")
            val tok = tokenizer ?: throw IllegalStateException("QwenBpeTokenizer not initialized")

            // Stage 1: Parallel Batch Tokenization (with BPE Word Cache)
            telemetryMonitor?.onStageChanged(HardwareExecutionStage.GPU_BATCH_TOKENIZE)
            val tokStartNs = System.nanoTime()
            val promptIds = tok.encodeBatchParallel(promptText)
            val tokDurationNs = System.nanoTime() - tokStartNs
            telemetryMonitor?.recordBatchTokenization(tokDurationNs, promptIds.size)
            if (promptIds.isEmpty()) {
                throw IllegalArgumentException("Encoded prompt produced 0 tokens.")
            }

            val generatedIds = ArrayList<Long>(maxNewTokens)
            val stateTensors = HashMap<String, OnnxTensor>()

            try {
                initHybridStateTensors(env, decoderSess, meta, stateTensors)

                val startTimeNs = System.nanoTime()
                var totalPastLen = 0

                // Slice promptIds into 32-token prefill chunks
                val chunks = ArrayList<LongArray>((promptIds.size + PREFILL_CHUNK_SIZE - 1) / PREFILL_CHUNK_SIZE)
                var cursor = 0
                while (cursor < promptIds.size) {
                    val end = minOf(cursor + PREFILL_CHUNK_SIZE, promptIds.size)
                    chunks.add(promptIds.copyOfRange(cursor, end))
                    cursor = end
                }

                val expectsEmbeds = decoderSess.inputNames.contains("inputs_embeds")
                val expectsFp16Embeds = (decoderSess.inputInfo["inputs_embeds"]?.info as? TensorInfo)?.type == OnnxJavaType.FLOAT16

                // Stage 2: Double-Buffered Producer-Consumer Prefill
                // While the NPU executes `decoderSession.run()` on Chunk k, the GPU / Batch Embed pipeline
                // concurrently prepares the `[1, chunkLen, 1024]` embedding tensor for Chunk k+1!
                var firstGeneratedTokenId = 0L
                coroutineScope {
                    var prefetchedEmbedJob: Deferred<OnnxTensor?>? = if (expectsEmbeds && chunks.isNotEmpty()) {
                        async(Dispatchers.Default) {
                            val pfStart = System.nanoTime()
                            val t = createBatchEmbedTensor(env, embedSession, directEmbedTable, chunks[0], expectsFp16Embeds)
                            telemetryMonitor?.recordGpuEmbedPrefetch(System.nanoTime() - pfStart)
                            t
                        }
                    } else {
                        null
                    }

                    for (chunkIdx in chunks.indices) {
                        currentCoroutineContext().ensureActive()
                        val chunkIds = chunks[chunkIdx]
                        val isLastPrefillChunk = (chunkIdx == chunks.lastIndex)

                        val currentEmbedTensor = prefetchedEmbedJob?.await()

                        // Immediately launch GPU / parallel batch embedding prefetch for Chunk (chunkIdx + 1)
                        // while the NPU runs Chunk (chunkIdx)
                        prefetchedEmbedJob = if (expectsEmbeds && chunkIdx + 1 < chunks.size) {
                            val nextChunkIds = chunks[chunkIdx + 1]
                            async(Dispatchers.Default) {
                                val pfStart = System.nanoTime()
                                val t = createBatchEmbedTensor(env, embedSession, directEmbedTable, nextChunkIds, expectsFp16Embeds)
                                telemetryMonitor?.recordGpuEmbedPrefetch(System.nanoTime() - pfStart)
                                t
                            }
                        } else {
                            null
                        }

                        val npuStepStartNs = System.nanoTime()
                        val sampled = runSingleStep(
                            env = env,
                            embedSess = embedSession,
                            directEmbed = directEmbedTable,
                            precomputedEmbedTensor = currentEmbedTensor,
                            decoderSess = decoderSess,
                            meta = meta,
                            stepInputIds = chunkIds,
                            pastSeqLen = totalPastLen,
                            stateTensors = stateTensors,
                            computeArgMax = isLastPrefillChunk
                        )
                        val npuStepDurNs = System.nanoTime() - npuStepStartNs
                        telemetryMonitor?.recordNpuDecoderStep(
                            durationNs = npuStepDurNs,
                            chunkSeqLen = chunkIds.size,
                            isPrefill = true,
                            totalGeneratedTokens = if (isLastPrefillChunk) 1 else 0,
                            decodeTokPerSec = 0f
                        )
                        totalPastLen += chunkIds.size
                        if (isLastPrefillChunk) {
                            firstGeneratedTokenId = sampled
                        }
                    }
                }

                // Stage 3: Autoregressive Token Generation with O(1) Incremental Decoding & Live Thought Split
                val accumulatedTextBuilder = StringBuilder(initialAssistantPrefix)
                var nextTokenId = firstGeneratedTokenId
                var step = 0
                var effectiveLimit = maxNewTokens
                var grantedToolGraceTokens = false
                var thoughtStartNs = System.nanoTime()
                var thoughtEndNs = 0L
                var nonThoughtProseTokens = 0

                if (initialAssistantPrefix.contains("<think>") && initialAssistantPrefix.contains("</think>")) {
                    thoughtEndNs = System.nanoTime()
                }

                while (step < effectiveLimit) {
                    currentCoroutineContext().ensureActive()

                    if (step > 0) {
                        val decodeStepStartNs = System.nanoTime()
                        nextTokenId = runSingleStep(
                            env = env,
                            embedSess = embedSession,
                            directEmbed = directEmbedTable,
                            precomputedEmbedTensor = null,
                            decoderSess = decoderSess,
                            meta = meta,
                            stepInputIds = longArrayOf(nextTokenId),
                            pastSeqLen = totalPastLen,
                            stateTensors = stateTensors,
                            computeArgMax = true
                        )
                        val decodeStepDurNs = System.nanoTime() - decodeStepStartNs
                        totalPastLen += 1
                        val elapsedSecNow = ((System.nanoTime() - startTimeNs) / 1_000_000_000.0f).coerceAtLeast(0.001f)
                        val tpsNow = (generatedIds.size + 1) / elapsedSecNow
                        telemetryMonitor?.recordNpuDecoderStep(
                            durationNs = decodeStepDurNs,
                            chunkSeqLen = 1,
                            isPrefill = false,
                            totalGeneratedTokens = generatedIds.size + 1,
                            decodeTokPerSec = tpsNow
                        )
                    }

                    generatedIds.add(nextTokenId)

                    if (nextTokenId.toInt() == tok.imEndId || nextTokenId.toInt() == tok.eosTokenId) {
                        break
                    }

                    val deltaDecoded = tok.decodeSingleToken(nextTokenId, skipSpecialTokens = true)
                    accumulatedTextBuilder.append(deltaDecoded)
                    val fullDecoded = accumulatedTextBuilder.toString()

                    val split = ThoughtChainParser.splitThoughtAndContent(fullDecoded)
                    if (!split.isCurrentlyThinking && thoughtEndNs == 0L && split.thoughtText.isNotEmpty()) {
                        thoughtEndNs = System.nanoTime()
                    }
                    val currentThoughtMs = if (split.thoughtText.isNotEmpty() || split.isCurrentlyThinking) {
                        val endRef = if (thoughtEndNs > 0L) thoughtEndNs else System.nanoTime()
                        ((endRef - thoughtStartNs) / 1_000_000L).coerceAtLeast(50L)
                    } else {
                        0L
                    }

                    val elapsedSec = ((System.nanoTime() - startTimeNs) / 1_000_000_000.0f).coerceAtLeast(0.001f)
                    val tps = generatedIds.size / elapsedSec

                    emit(
                        GenerationEvent.TokenDelta(
                            textDelta = deltaDecoded,
                            fullTextSoFar = fullDecoded,
                            visibleTextSoFar = split.visibleContent,
                            thoughtTextSoFar = split.thoughtText,
                            thoughtSummaryLine = split.summaryLine,
                            isThinkingPhase = split.isCurrentlyThinking,
                            thoughtDurationMs = currentThoughtMs,
                            tokensGenerated = generatedIds.size,
                            tokensPerSec = tps
                        )
                    )

                    val parsedTool = extractToolCallIfComplete(
                        text = fullDecoded,
                        allowUnclosedAtEos = false,
                        thoughtText = split.thoughtText,
                        thoughtDurationMs = currentThoughtMs
                    )
                    if (parsedTool != null) {
                        emit(GenerationEvent.ToolCallDetected(parsedTool))
                        return@flow
                    }

                    // Anti-Summarization-Loop Early Breaker:
                    // When a tool call is strictly expected (e.g., right after `inspect_template` when `READY_TO_CLONE`),
                    // if the model exits `<think>` and starts generating > 24 tokens of plain-text prose without `<tool_call>`,
                    // break early so the agentic loop immediately chains `clone_document` without wasting time on verbose prose!
                    if (breakOnProseWhenToolExpected && !split.isCurrentlyThinking && !fullDecoded.contains("<tool_call>")) {
                        nonThoughtProseTokens++
                        if (nonThoughtProseTokens >= 24) {
                            Log.i(TAG, "Anti-summarization-loop breaker triggered after $nonThoughtProseTokens prose tokens.")
                            break
                        }
                    }

                    // If we are about to hit maxNewTokens while inside an unclosed <tool_call> tag,
                    // extend the limit by 160 tokens on the existing KV-cache so the JSON completes cleanly.
                    if (!grantedToolGraceTokens && step >= effectiveLimit - 4 &&
                        fullDecoded.contains("<tool_call>") && !fullDecoded.contains("</tool_call>")
                    ) {
                        effectiveLimit += 160
                        grantedToolGraceTokens = true
                    }

                    step++
                }

                val finalDecoded = accumulatedTextBuilder.toString()
                val totalSec = ((System.nanoTime() - startTimeNs) / 1_000_000_000.0f).coerceAtLeast(0.001f)
                val finalTps = generatedIds.size / totalSec
                val finalSplit = ThoughtChainParser.splitThoughtAndContent(finalDecoded)
                val finalThoughtMs = if (finalSplit.thoughtText.isNotEmpty()) {
                    val endRef = if (thoughtEndNs > 0L) thoughtEndNs else System.nanoTime()
                    ((endRef - thoughtStartNs) / 1_000_000L).coerceAtLeast(50L)
                } else {
                    0L
                }

                val finalTool = extractToolCallIfComplete(
                    text = finalDecoded,
                    allowUnclosedAtEos = true,
                    thoughtText = finalSplit.thoughtText,
                    thoughtDurationMs = finalThoughtMs
                )
                if (finalTool != null) {
                    emit(GenerationEvent.ToolCallDetected(finalTool))
                } else {
                    emit(
                        GenerationEvent.Completed(
                            fullText = finalDecoded.trim(),
                            visibleText = finalSplit.visibleContent,
                            thoughtText = finalSplit.thoughtText,
                            thoughtDurationMs = finalThoughtMs,
                            totalTokens = generatedIds.size,
                            tokensPerSec = finalTps
                        )
                    )
                }
            } finally {
                stateTensors.values.forEach { runCatching { it.close() } }
                stateTensors.clear()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "ONNX Runtime inference failed", e)
            emit(
                GenerationEvent.Error(
                    message = "ONNX Runtime Error: ${e.message ?: e.javaClass.simpleName}",
                    stackTrace = e.stackTraceToString()
                )
            )
        }
    }.flowOn(Dispatchers.Default)

    private suspend fun createBatchEmbedTensor(
        env: OrtEnvironment,
        embedSess: OrtSession?,
        directEmbed: DirectQ4F16EmbeddingTable?,
        chunkIds: LongArray,
        expectsFp16: Boolean
    ): OnnxTensor? {
        val seqLen = chunkIds.size
        val embedShape = longArrayOf(1L, seqLen.toLong(), 1024L)
        if (directEmbed != null) {
            return if (expectsFp16) {
                val sb = directEmbed.lookupEmbeddingsFloat16BatchParallel(chunkIds)
                OnnxTensor.createTensor(env, sb, embedShape, OnnxJavaType.FLOAT16)
            } else {
                val fb = directEmbed.lookupEmbeddingsFloat32BatchParallel(chunkIds)
                OnnxTensor.createTensor(env, fb, embedShape)
            }
        } else if (embedSess != null) {
            val inputIdsTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(chunkIds),
                longArrayOf(1L, seqLen.toLong())
            )
            try {
                val embedInputName = embedSess.inputNames.first()
                embedSess.run(mapOf(embedInputName to inputIdsTensor)).use { embedResult ->
                    val embedOutTensor = embedResult.get(0) as OnnxTensor
                    val embedInfo = embedOutTensor.info as TensorInfo
                    val embedByteBuf = embedOutTensor.byteBuffer
                    val copyBuf = ByteBuffer.allocateDirect(embedByteBuf.remaining()).order(ByteOrder.nativeOrder())
                    copyBuf.put(embedByteBuf)
                    copyBuf.flip()

                    return if (embedInfo.type == OnnxJavaType.FLOAT16) {
                        OnnxTensor.createTensor(env, copyBuf.asShortBuffer(), embedInfo.shape, OnnxJavaType.FLOAT16)
                    } else {
                        OnnxTensor.createTensor(env, copyBuf.asFloatBuffer(), embedInfo.shape)
                    }
                }
            } finally {
                runCatching { inputIdsTensor.close() }
            }
        }
        return null
    }

    private fun initHybridStateTensors(
        env: OrtEnvironment,
        decoderSess: OrtSession,
        meta: SessionGraphMetadata,
        stateTensors: MutableMap<String, OnnxTensor>
    ) {
        for (inputName in decoderSess.inputNames) {
            when {
                inputName.startsWith("past_key_values.") -> {
                    val info = decoderSess.inputInfo[inputName]?.info as? TensorInfo
                    val rawShape = info?.shape ?: longArrayOf(1L, meta.kvNumHeads.toLong(), 0L, meta.kvHeadDim.toLong())
                    val shape = LongArray(rawShape.size) { idx ->
                        when (idx) {
                            0 -> 1L
                            2 -> 0L
                            else -> if (rawShape[idx] > 0) rawShape[idx] else if (idx == 1) meta.kvNumHeads.toLong() else meta.kvHeadDim.toLong()
                        }
                    }
                    val tensor = if (info?.type == OnnxJavaType.FLOAT16) {
                        OnnxTensor.createTensor(env, ShortBuffer.allocate(0), shape, OnnxJavaType.FLOAT16)
                    } else {
                        OnnxTensor.createTensor(env, FloatBuffer.allocate(0), shape)
                    }
                    stateTensors[inputName] = tensor
                }

                inputName.startsWith("past_conv.") -> {
                    val info = decoderSess.inputInfo[inputName]?.info as? TensorInfo
                    val rawShape = info?.shape ?: longArrayOf(1L, 6144L, 4L)
                    val shape = LongArray(rawShape.size) { idx ->
                        if (idx == 0) 1L else if (rawShape[idx] > 0) rawShape[idx] else if (idx == 1) 6144L else 4L
                    }
                    val elemCount = shape.fold(1L) { acc, d -> acc * d }.toInt()
                    val tensor = if (info?.type == OnnxJavaType.FLOAT16) {
                        val buf = ByteBuffer.allocateDirect(elemCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                        OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.FLOAT16)
                    } else {
                        val buf = ByteBuffer.allocateDirect(elemCount * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        OnnxTensor.createTensor(env, buf, shape)
                    }
                    stateTensors[inputName] = tensor
                }

                inputName.startsWith("past_recurrent.") -> {
                    val info = decoderSess.inputInfo[inputName]?.info as? TensorInfo
                    val rawShape = info?.shape ?: longArrayOf(1L, 16L, 128L, 128L)
                    val shape = LongArray(rawShape.size) { idx ->
                        if (idx == 0) 1L else if (rawShape[idx] > 0) rawShape[idx] else if (idx == 1) 16L else 128L
                    }
                    val elemCount = shape.fold(1L) { acc, d -> acc * d }.toInt()
                    val tensor = if (info?.type == OnnxJavaType.FLOAT16) {
                        val buf = ByteBuffer.allocateDirect(elemCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                        OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.FLOAT16)
                    } else {
                        val buf = ByteBuffer.allocateDirect(elemCount * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        OnnxTensor.createTensor(env, buf, shape)
                    }
                    stateTensors[inputName] = tensor
                }
            }
        }
    }

    private fun runSingleStep(
        env: OrtEnvironment,
        embedSess: OrtSession?,
        directEmbed: DirectQ4F16EmbeddingTable?,
        precomputedEmbedTensor: OnnxTensor?,
        decoderSess: OrtSession,
        meta: SessionGraphMetadata,
        stepInputIds: LongArray,
        pastSeqLen: Int,
        stateTensors: MutableMap<String, OnnxTensor>,
        computeArgMax: Boolean
    ): Long {
        val seqLen = stepInputIds.size
        val totalSeqLen = pastSeqLen + seqLen
        val stepOwnedTensors = ArrayList<OnnxTensor>(8)

        try {
            val decoderFeed = HashMap<String, OnnxTensor>()

            if (decoderSess.inputNames.contains("inputs_embeds")) {
                if (precomputedEmbedTensor != null) {
                    stepOwnedTensors.add(precomputedEmbedTensor)
                    decoderFeed["inputs_embeds"] = precomputedEmbedTensor
                } else {
                    val expectedEmbedInfo = decoderSess.inputInfo["inputs_embeds"]?.info as? TensorInfo
                    val expectsFp16 = expectedEmbedInfo?.type == OnnxJavaType.FLOAT16
                    val embedShape = longArrayOf(1L, seqLen.toLong(), 1024L)

                    if (directEmbed != null) {
                        val embedTensor = if (expectsFp16) {
                            val sb = directEmbed.lookupEmbeddingsFloat16(stepInputIds)
                            OnnxTensor.createTensor(env, sb, embedShape, OnnxJavaType.FLOAT16)
                        } else {
                            val fb = directEmbed.lookupEmbeddingsFloat32(stepInputIds)
                            OnnxTensor.createTensor(env, fb, embedShape)
                        }
                        stepOwnedTensors.add(embedTensor)
                        decoderFeed["inputs_embeds"] = embedTensor
                    } else if (embedSess != null) {
                        val inputIdsTensor = OnnxTensor.createTensor(
                            env,
                            LongBuffer.wrap(stepInputIds),
                            longArrayOf(1L, seqLen.toLong())
                        )
                        stepOwnedTensors.add(inputIdsTensor)

                        val embedInputName = embedSess.inputNames.first()
                        embedSess.run(mapOf(embedInputName to inputIdsTensor)).use { embedResult ->
                            val embedOutTensor = embedResult.get(0) as OnnxTensor
                            val embedInfo = embedOutTensor.info as TensorInfo
                            val embedByteBuf = embedOutTensor.byteBuffer
                            val copyBuf = ByteBuffer.allocateDirect(embedByteBuf.remaining()).order(ByteOrder.nativeOrder())
                            copyBuf.put(embedByteBuf)
                            copyBuf.flip()

                            val clonedEmbedTensor = if (embedInfo.type == OnnxJavaType.FLOAT16) {
                                OnnxTensor.createTensor(env, copyBuf.asShortBuffer(), embedInfo.shape, OnnxJavaType.FLOAT16)
                            } else {
                                OnnxTensor.createTensor(env, copyBuf.asFloatBuffer(), embedInfo.shape)
                            }
                            stepOwnedTensors.add(clonedEmbedTensor)
                            decoderFeed["inputs_embeds"] = clonedEmbedTensor
                        }
                    } else {
                        throw IllegalStateException("Neither DirectQ4F16EmbeddingTable nor embed_tokens OrtSession is available.")
                    }
                }
            } else if (decoderSess.inputNames.contains("input_ids")) {
                val inputIdsTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(stepInputIds),
                    longArrayOf(1L, seqLen.toLong())
                )
                stepOwnedTensors.add(inputIdsTensor)
                decoderFeed["input_ids"] = inputIdsTensor
            }

            if (decoderSess.inputNames.contains("attention_mask")) {
                val maskArr = LongArray(totalSeqLen) { 1L }
                val maskTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(maskArr),
                    longArrayOf(1L, totalSeqLen.toLong())
                )
                stepOwnedTensors.add(maskTensor)
                decoderFeed["attention_mask"] = maskTensor
            }

            if (decoderSess.inputNames.contains("position_ids")) {
                val posTensor = if (meta.positionIdsRank == 3) {
                    val posArr = LongArray(3 * seqLen)
                    for (row in 0 until 3) {
                        for (s in 0 until seqLen) {
                            posArr[row * seqLen + s] = (pastSeqLen + s).toLong()
                        }
                    }
                    OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(posArr),
                        longArrayOf(3L, 1L, seqLen.toLong())
                    )
                } else {
                    val posArr = LongArray(seqLen) { s -> (pastSeqLen + s).toLong() }
                    OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(posArr),
                        longArrayOf(1L, seqLen.toLong())
                    )
                }
                stepOwnedTensors.add(posTensor)
                decoderFeed["position_ids"] = posTensor
            }

            if (decoderSess.inputNames.contains("use_cache_branch")) {
                val useCacheArr = booleanArrayOf(pastSeqLen > 0)
                val useCacheTensor = OnnxTensor.createTensor(env, useCacheArr)
                stepOwnedTensors.add(useCacheTensor)
                decoderFeed["use_cache_branch"] = useCacheTensor
            }

            for ((stateName, stateTensor) in stateTensors) {
                decoderFeed[stateName] = stateTensor
            }

            decoderSess.run(decoderFeed).use { decoderResult ->
                val nextToken = if (computeArgMax) {
                    val logitsTensor = decoderResult.get(0) as OnnxTensor
                    argMaxLastTokenLogitsZeroCopy(logitsTensor, seqLen)
                } else {
                    0L
                }

                val outputNamesList = decoderSess.outputNames.toList()
                for (outIdx in 1 until outputNamesList.size) {
                    val outName = outputNamesList[outIdx]
                    val pastName = when {
                        outName.startsWith("present.") -> outName.replaceFirst("present.", "past_key_values.")
                        outName.startsWith("present_conv.") -> outName.replaceFirst("present_conv.", "past_conv.")
                        outName.startsWith("present_recurrent.") -> outName.replaceFirst("present_recurrent.", "past_recurrent.")
                        else -> null
                    }
                    if (pastName != null && decoderSess.inputNames.contains(pastName)) {
                        val presentTensor = decoderResult.get(outIdx) as OnnxTensor
                        val pInfo = presentTensor.info as TensorInfo
                        val pBytes = presentTensor.byteBuffer
                        val copiedBytes = ByteBuffer.allocateDirect(pBytes.remaining()).order(ByteOrder.nativeOrder())
                        copiedBytes.put(pBytes)
                        copiedBytes.flip()

                        val nextStateTensor = if (pInfo.type == OnnxJavaType.FLOAT16) {
                            OnnxTensor.createTensor(env, copiedBytes.asShortBuffer(), pInfo.shape, OnnxJavaType.FLOAT16)
                        } else {
                            OnnxTensor.createTensor(env, copiedBytes.asFloatBuffer(), pInfo.shape)
                        }
                        stateTensors.put(pastName, nextStateTensor)?.close()
                    }
                }

                return nextToken
            }
        } finally {
            stepOwnedTensors.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Zero-heap-allocation argmax over the last sequence token's logits.
     */
    private fun argMaxLastTokenLogitsZeroCopy(logitsTensor: OnnxTensor, seqLen: Int): Long {
        val info = logitsTensor.info as TensorInfo
        val vocabSize = info.shape.last().toInt()
        val rawByteBuf = logitsTensor.byteBuffer.order(ByteOrder.nativeOrder())

        var bestId = 0
        var bestVal = Float.NEGATIVE_INFINITY

        if (info.type == OnnxJavaType.FLOAT16) {
            val lastTokenByteOffset = (seqLen - 1) * vocabSize * 2
            rawByteBuf.position(lastTokenByteOffset)
            val shortView = rawByteBuf.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
            for (v in 0 until vocabSize) {
                val halfBits = shortView.get(v).toInt() and 0xFFFF
                val floatVal = halfToFloatStatic(halfBits)
                if (floatVal > bestVal) {
                    bestVal = floatVal
                    bestId = v
                }
            }
        } else {
            val lastTokenByteOffset = (seqLen - 1) * vocabSize * 4
            rawByteBuf.position(lastTokenByteOffset)
            val floatView = rawByteBuf.slice().order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (v in 0 until vocabSize) {
                val floatVal = floatView.get(v)
                if (floatVal > bestVal) {
                    bestVal = floatVal
                    bestId = v
                }
            }
        }
        return bestId.toLong()
    }

    private fun extractToolCallIfComplete(
        text: String,
        allowUnclosedAtEos: Boolean = false,
        thoughtText: String = "",
        thoughtDurationMs: Long = 0L
    ): ParsedToolCall? {
        val startIdx = text.indexOf("<tool_call>")
        if (startIdx != -1) {
            val endIdx = text.indexOf("</tool_call>", startIdx)
            if (endIdx != -1) {
                val rawBlock = text.substring(startIdx, endIdx + "</tool_call>".length)
                val innerJson = text.substring(startIdx + "<tool_call>".length, endIdx).trim()
                parseToolJsonBlock(innerJson, rawBlock, thoughtText, thoughtDurationMs)?.let { return it }
            } else if (allowUnclosedAtEos) {
                val afterTag = text.substring(startIdx + "<tool_call>".length).trim()
                val firstBrace = afterTag.indexOf('{')
                val lastBrace = afterTag.lastIndexOf('}')
                if (firstBrace != -1 && lastBrace > firstBrace) {
                    val candidateJson = afterTag.substring(firstBrace, lastBrace + 1)
                    parseToolJsonBlock(candidateJson, text.substring(startIdx), thoughtText, thoughtDurationMs)?.let { return it }
                }
            }
        }

        if (allowUnclosedAtEos && (text.contains("\"inspect_template\"") || text.contains("\"clone_document\"") || text.contains("\"run_custom_openxml_python\""))) {
            val firstBrace = text.indexOf("{\"name\"")
                .takeIf { it != -1 } ?: text.indexOf("{ \"name\"")
            if (firstBrace != -1) {
                val lastBrace = text.lastIndexOf('}')
                if (lastBrace > firstBrace) {
                    val candidateJson = text.substring(firstBrace, lastBrace + 1)
                    parseToolJsonBlock(candidateJson, candidateJson, thoughtText, thoughtDurationMs)?.let { return it }
                }
            }
        }

        return null
    }

    private fun parseToolJsonBlock(
        innerJson: String,
        rawBlock: String,
        thoughtText: String,
        thoughtDurationMs: Long
    ): ParsedToolCall? {
        return try {
            val cleaned = innerJson
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val obj = JSONObject(cleaned)
            val name = obj.getString("name")
            val args = obj.optJSONObject("arguments") ?: JSONObject()
            ParsedToolCall(
                name = name,
                argumentsJson = args,
                rawBlock = rawBlock,
                thoughtText = thoughtText,
                thoughtDurationMs = thoughtDurationMs
            )
        } catch (e: Exception) {
            Log.w(TAG, "Malformed tool_call JSON block: $innerJson", e)
            null
        }
    }

    fun closeSessions() {
        runCatching { visionSession?.close() }
        runCatching { decoderSession?.close() }
        runCatching { embedSession?.close() }
        runCatching { directEmbedTable?.close() }
        visionSession = null
        decoderSession = null
        embedSession = null
        directEmbedTable = null
        sessionMetadata = null
    }
}
