package com.example

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class SocVendor(val displayName: String, val npuName: String, val gpuName: String) {
    SNAPDRAGON("Qualcomm Snapdragon", "Hexagon HTP / QNN NPU", "Adreno GPU"),
    MEDIATEK("MediaTek Dimensity / Helio", "NeuroPilot APU", "ARM Mali / Immortalis GPU"),
    EXYNOS("Samsung Exynos / Tensor", "Exynos Neural Engine (ENN)", "Samsung Xclipse / Mali GPU"),
    VIRTUAL_EMULATOR("Virtual Android / Cloud Emulator", "Virtualized NNAPI", "VirGL / SwiftShader GPU"),
    GENERIC_ARM("ARM64 SoC", "Android NNAPI NPU", "OpenCL / Vulkan Mobile GPU")
}

data class SocHardwareProfile(
    val vendor: SocVendor,
    val socModel: String,
    val hardwareBoard: String,
    val isEmulator: Boolean,
    val recommendedMode: ExecutionProviderMode
) {
    val summaryBadge: String
        get() = "${vendor.displayName} ($socModel)"
}

object SocHardwareDetector {
    fun detect(): SocHardwareProfile {
        val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER ?: ""
        } else {
            ""
        }
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL ?: ""
        } else {
            ""
        }
        val hardware = Build.HARDWARE ?: ""
        val board = Build.BOARD ?: ""
        val fingerprint = Build.FINGERPRINT ?: ""
        val product = Build.PRODUCT ?: ""

        val combined = "$socManufacturer $socModel $hardware $board $product".lowercase()
        val isEmulator = combined.contains("ranchu") ||
            combined.contains("goldfish") ||
            combined.contains("cutf") ||
            combined.contains(" vsoc") ||
            combined.contains("sdk_gphone") ||
            fingerprint.lowercase().contains("generic") ||
            fingerprint.lowercase().contains("emulator")

        val vendor = when {
            isEmulator -> SocVendor.VIRTUAL_EMULATOR
            combined.contains("qualcomm") || combined.contains("qti") || combined.contains("qcom") ||
                combined.contains("sm8") || combined.contains("sm7") || combined.contains("sdm") ||
                combined.contains("snapdragon") || combined.contains("lahaina") ||
                combined.contains("kalama") || combined.contains("pineapple") || combined.contains("taro") ->
                SocVendor.SNAPDRAGON
            combined.contains("mediatek") || combined.contains("mtk") || combined.contains("mt6") ||
                combined.contains("dimensity") || combined.contains("helio") ->
                SocVendor.MEDIATEK
            combined.contains("samsung") || combined.contains("exynos") || combined.contains("s5e") ||
                combined.contains("erd") || combined.contains("tensor") || combined.contains("gs101") ||
                combined.contains("gs201") || combined.contains("zuma") ->
                SocVendor.EXYNOS
            else -> SocVendor.GENERIC_ARM
        }

        val resolvedModel = when {
            socModel.isNotBlank() && socModel != Build.UNKNOWN -> socModel
            hardware.isNotBlank() -> hardware
            else -> board.ifBlank { "ARM64" }
        }

        val recommended = when (vendor) {
            SocVendor.SNAPDRAGON -> ExecutionProviderMode.QNN_HTP
            SocVendor.MEDIATEK -> ExecutionProviderMode.MEDIATEK_APU
            SocVendor.EXYNOS -> ExecutionProviderMode.EXYNOS_NPU
            SocVendor.VIRTUAL_EMULATOR -> ExecutionProviderMode.AUTO_NPU_GPU
            SocVendor.GENERIC_ARM -> ExecutionProviderMode.AUTO_NPU_GPU
        }

        return SocHardwareProfile(
            vendor = vendor,
            socModel = resolvedModel,
            hardwareBoard = "$hardware / $board",
            isEmulator = isEmulator,
            recommendedMode = recommended
        )
    }
}

enum class ExecutionProviderMode(val label: String, val description: String) {
    AUTO_NPU_GPU(
        label = "Auto Multi-SoC NPU → GPU → XNNPACK",
        description = "Auto-detects Snapdragon QNN, MediaTek NeuroPilot, or Exynos ENN NPU with Mobile GPU & XNNPACK fallback"
    ),
    QNN_HTP(
        label = "Snapdragon Hexagon NPU + Adreno GPU",
        description = "Qualcomm QNN HTP (SM8550/SM8650/SM8750/SM7xxx) → Adreno GPU FP16 → XNNPACK"
    ),
    MEDIATEK_APU(
        label = "MediaTek NeuroPilot APU + Mali/Immortalis GPU",
        description = "MediaTek Dimensity/Helio APU delegate (NeuroPilot NNAPI) → Mali GPU FP16 → XNNPACK"
    ),
    EXYNOS_NPU(
        label = "Samsung Exynos ENN NPU + Xclipse/Mali GPU",
        description = "Samsung Exynos / Tensor ENN NPU delegate → Xclipse/Mali GPU FP16 → XNNPACK"
    ),
    GPU_FALLBACK(
        label = "Mobile GPU Fallback (Adreno / Mali / Xclipse)",
        description = "FP16-relaxed GPU acceleration via NNAPI GPU delegate + 4-thread XNNPACK fallback"
    ),
    NNAPI(
        label = "Android NNAPI Hardware Delegate + XNNPACK",
        description = "Generic Android Neural Networks API NPU/GPU accelerator + XNNPACK"
    ),
    XNNPACK(
        label = "ARMv9 XNNPACK SIMD (4 Threads)",
        description = "Pure 4-bit quantized MatMulNBits SIMD CPU/XNNPACK execution"
    )
}

sealed interface ModelState {
    data class NotLoaded(
        val reason: String = "Action Required: Download Qwen3.5-0.8B-ONNX from Hugging Face or select a local model directory in Settings."
    ) : ModelState

    data class Downloading(
        val currentFileName: String,
        val fileIndex: Int,
        val totalFiles: Int,
        val fileBytesRead: Long,
        val fileTotalBytes: Long,
        val overallBytesRead: Long
    ) : ModelState

    data class Verifying(
        val stepDescription: String
    ) : ModelState

    data class Ready(
        val modelDir: String,
        val embedOnnxPath: String,
        val decoderOnnxPath: String,
        val visionOnnxPath: String?,
        val totalSizeBytes: Long,
        val decoderDataSha256Prefix: String,
        val executionProvider: ExecutionProviderMode,
        val visionEnabled: Boolean
    ) : ModelState

    data class Error(
        val message: String,
        val stackTrace: String = ""
    ) : ModelState
}

data class HfModelArtifact(
    val relativePath: String,
    val minBytes: Long,
    val requiredForText: Boolean
)

class QwenModelManager(private val context: Context) {

    companion object {
        const val HF_REPO_ID = "onnx-community/Qwen3.5-0.8B-ONNX"
        const val HF_BASE_URL = "https://huggingface.co/$HF_REPO_ID/resolve/main"

        val ARTIFACTS = listOf(
            HfModelArtifact("config.json", minBytes = 200L, requiredForText = true),
            HfModelArtifact("tokenizer_config.json", minBytes = 200L, requiredForText = true),
            HfModelArtifact("tokenizer.json", minBytes = 1_000_000L, requiredForText = true),
            HfModelArtifact("onnx/embed_tokens_q4f16.onnx", minBytes = 1_000L, requiredForText = true),
            HfModelArtifact("onnx/embed_tokens_q4f16.onnx_data", minBytes = 10_000_000L, requiredForText = true),
            HfModelArtifact("onnx/decoder_model_merged_q4f16.onnx", minBytes = 10_000L, requiredForText = true),
            HfModelArtifact("onnx/decoder_model_merged_q4f16.onnx_data", minBytes = 100_000_000L, requiredForText = true),
            HfModelArtifact("onnx/vision_encoder_q4f16.onnx", minBytes = 5_000L, requiredForText = false),
            HfModelArtifact("onnx/vision_encoder_q4f16.onnx_data", minBytes = 10_000_000L, requiredForText = false)
        )
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotLoaded())
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    @Volatile
    private var cancelRequested = false

    fun getModelRootDir(): File {
        val base = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        val dir = File(base, "qwen3.5_0.8b")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val onnxDir = File(dir, "onnx")
        if (!onnxDir.exists()) {
            onnxDir.mkdirs()
        }
        return dir
    }

    fun cancelDownload() {
        cancelRequested = true
    }

    suspend fun inspectDiskAndRefreshState(
        provider: ExecutionProviderMode,
        includeVision: Boolean
    ): ModelState = withContext(Dispatchers.IO) {
        try {
            val root = getModelRootDir()
            val missingRequired = mutableListOf<String>()
            var totalBytes = 0L

            for (artifact in ARTIFACTS) {
                if (!artifact.requiredForText && !includeVision) continue
                val targetFile = File(root, artifact.relativePath)
                if (!targetFile.exists() || targetFile.length() < artifact.minBytes) {
                    if (artifact.requiredForText) {
                        missingRequired.add("${artifact.relativePath} (requires >= ${artifact.minBytes / 1024} KB)")
                    }
                } else {
                    totalBytes += targetFile.length()
                }
            }

            if (missingRequired.isNotEmpty()) {
                val state = ModelState.NotLoaded(
                    reason = "Model files missing or incomplete in ${root.absolutePath}: ${missingRequired.joinToString(", ")}"
                )
                _modelState.value = state
                return@withContext state
            }

            _modelState.value = ModelState.Verifying("Verifying ONNX companion weight headers and SHA-256...")

            val embedOnnx = File(root, "onnx/embed_tokens_q4f16.onnx")
            val decoderOnnx = File(root, "onnx/decoder_model_merged_q4f16.onnx")
            val decoderData = File(root, "onnx/decoder_model_merged_q4f16.onnx_data")
            val visionOnnx = File(root, "onnx/vision_encoder_q4f16.onnx")
            val visionData = File(root, "onnx/vision_encoder_q4f16.onnx_data")

            val shaPrefix = computeHeaderSha256(decoderData, maxBytes = 4 * 1024 * 1024)
            val hasVision = visionOnnx.exists() && visionData.exists() && visionData.length() >= 10_000_000L

            val ready = ModelState.Ready(
                modelDir = root.absolutePath,
                embedOnnxPath = embedOnnx.absolutePath,
                decoderOnnxPath = decoderOnnx.absolutePath,
                visionOnnxPath = if (hasVision && includeVision) visionOnnx.absolutePath else null,
                totalSizeBytes = totalBytes,
                decoderDataSha256Prefix = shaPrefix,
                executionProvider = provider,
                visionEnabled = hasVision && includeVision
            )
            _modelState.value = ready
            ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = ModelState.Error(
                message = "Model verification failed: ${e.message ?: e.javaClass.simpleName}",
                stackTrace = e.stackTraceToString()
            )
            _modelState.value = err
            err
        }
    }

    suspend fun downloadFromHuggingFace(
        provider: ExecutionProviderMode,
        includeVision: Boolean
    ): ModelState = withContext(Dispatchers.IO) {
        cancelRequested = false
        val root = getModelRootDir()
        val artifactsToFetch = ARTIFACTS.filter { it.requiredForText || includeVision }
        var cumulativeBytes = 0L

        try {
            for ((index, artifact) in artifactsToFetch.withIndex()) {
                if (cancelRequested) {
                    val state = ModelState.NotLoaded("Download cancelled by user.")
                    _modelState.value = state
                    return@withContext state
                }

                val targetFile = File(root, artifact.relativePath)
                targetFile.parentFile?.mkdirs()

                if (targetFile.exists() && targetFile.length() >= artifact.minBytes) {
                    cumulativeBytes += targetFile.length()
                    continue
                }

                val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
                if (partFile.exists()) {
                    partFile.delete()
                }

                val url = "$HF_BASE_URL/${artifact.relativePath}"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "CopyFlow-Android-ONNX/2.0")
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException(
                            "HTTP ${response.code} downloading ${artifact.relativePath} from $url: ${response.message}"
                        )
                    }

                    val body = response.body
                        ?: throw IllegalStateException("Empty HTTP response body for ${artifact.relativePath}")
                    val contentLength = body.contentLength()

                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var fileBytesRead = 0L
                            var lastEmitBytes = 0L

                            while (true) {
                                if (cancelRequested) {
                                    partFile.delete()
                                    val state = ModelState.NotLoaded("Download cancelled by user.")
                                    _modelState.value = state
                                    return@withContext state
                                }
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                fileBytesRead += read
                                cumulativeBytes += read

                                if (fileBytesRead - lastEmitBytes >= 256 * 1024 || fileBytesRead == contentLength) {
                                    lastEmitBytes = fileBytesRead
                                    _modelState.value = ModelState.Downloading(
                                        currentFileName = artifact.relativePath,
                                        fileIndex = index + 1,
                                        totalFiles = artifactsToFetch.size,
                                        fileBytesRead = fileBytesRead,
                                        fileTotalBytes = contentLength,
                                        overallBytesRead = cumulativeBytes
                                    )
                                }
                            }
                            output.fd.sync()
                        }
                    }
                }

                if (partFile.length() < artifact.minBytes) {
                    val actualLen = partFile.length()
                    partFile.delete()
                    throw IllegalStateException(
                        "Downloaded file ${artifact.relativePath} is incomplete ($actualLen bytes < required ${artifact.minBytes} bytes)."
                    )
                }

                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!partFile.renameTo(targetFile)) {
                    throw IllegalStateException("Failed to move ${partFile.absolutePath} to ${targetFile.absolutePath}")
                }
            }

            inspectDiskAndRefreshState(provider = provider, includeVision = includeVision)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = ModelState.Error(
                message = "Hugging Face download failed: ${e.message ?: e.javaClass.simpleName}",
                stackTrace = e.stackTraceToString()
            )
            _modelState.value = err
            err
        }
    }

    suspend fun sideloadFromSafDirectory(
        treeUri: Uri,
        provider: ExecutionProviderMode,
        includeVision: Boolean
    ): ModelState = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalArgumentException("Unable to access selected SAF directory: $treeUri")

            val destRoot = getModelRootDir()
            val allDocs = mutableListOf<DocumentFile>()
            collectSafFiles(rootDoc, allDocs)

            if (allDocs.isEmpty()) {
                throw IllegalArgumentException("Selected directory contains no readable files.")
            }

            val relevantDocs = allDocs.filter { doc ->
                val name = doc.name ?: return@filter false
                name.endsWith(".onnx") || name.endsWith(".onnx_data") || name.endsWith(".json")
            }

            if (relevantDocs.isEmpty()) {
                throw IllegalArgumentException(
                    "No .onnx, .onnx_data, or tokenizer .json files found in the selected directory."
                )
            }

            var overallCopied = 0L
            for ((idx, doc) in relevantDocs.withIndex()) {
                val name = doc.name ?: continue
                val destFile = if (name.endsWith(".onnx") || name.endsWith(".onnx_data")) {
                    File(File(destRoot, "onnx"), name)
                } else {
                    File(destRoot, name)
                }
                destFile.parentFile?.mkdirs()

                val totalLen = doc.length()
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val r = input.read(buf)
                            if (r == -1) break
                            output.write(buf, 0, r)
                            copied += r
                            overallCopied += r
                            if (copied % (512 * 1024) == 0L) {
                                _modelState.value = ModelState.Downloading(
                                    currentFileName = "Copying $name",
                                    fileIndex = idx + 1,
                                    totalFiles = relevantDocs.size,
                                    fileBytesRead = copied,
                                    fileTotalBytes = totalLen,
                                    overallBytesRead = overallCopied
                                )
                            }
                        }
                        output.fd.sync()
                    }
                } ?: throw IllegalStateException("ContentResolver returned null InputStream for ${doc.uri}")
            }

            inspectDiskAndRefreshState(provider = provider, includeVision = includeVision)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = ModelState.Error(
                message = "Local directory sideload failed: ${e.message ?: e.javaClass.simpleName}",
                stackTrace = e.stackTraceToString()
            )
            _modelState.value = err
            err
        }
    }

    suspend fun sideloadSingleFile(
        fileUri: Uri,
        provider: ExecutionProviderMode,
        includeVision: Boolean
    ): ModelState = withContext(Dispatchers.IO) {
        try {
            val fileName = resolveFileName(fileUri)
                ?: throw IllegalArgumentException("Could not resolve file name for $fileUri")
            val destRoot = getModelRootDir()
            val destFile = if (fileName.endsWith(".onnx") || fileName.endsWith(".onnx_data")) {
                File(File(destRoot, "onnx"), fileName)
            } else {
                File(destRoot, fileName)
            }
            destFile.parentFile?.mkdirs()

            context.contentResolver.openInputStream(fileUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r == -1) break
                        output.write(buf, 0, r)
                        copied += r
                        if (copied % (512 * 1024) == 0L) {
                            _modelState.value = ModelState.Downloading(
                                currentFileName = "Copying $fileName",
                                fileIndex = 1,
                                totalFiles = 1,
                                fileBytesRead = copied,
                                fileTotalBytes = -1L,
                                overallBytesRead = copied
                            )
                        }
                    }
                    output.fd.sync()
                }
            } ?: throw IllegalStateException("Unable to open InputStream for $fileUri")

            inspectDiskAndRefreshState(provider = provider, includeVision = includeVision)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = ModelState.Error(
                message = "File sideload failed: ${e.message ?: e.javaClass.simpleName}",
                stackTrace = e.stackTraceToString()
            )
            _modelState.value = err
            err
        }
    }

    fun getArtifactStatusList(): List<Pair<HfModelArtifact, Long>> {
        val root = getModelRootDir()
        return ARTIFACTS.map { artifact ->
            val file = File(root, artifact.relativePath)
            val len = if (file.exists()) file.length() else 0L
            artifact to len
        }
    }

    private fun collectSafFiles(dir: DocumentFile, out: MutableList<DocumentFile>) {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                collectSafFiles(child, out)
            } else if (child.isFile) {
                out.add(child)
            }
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun computeHeaderSha256(file: File, maxBytes: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(64 * 1024)
            var remaining = maxBytes
            while (remaining > 0) {
                val toRead = minOf(buffer.size, remaining)
                val read = fis.read(buffer, 0, toRead)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}
