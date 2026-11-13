package com.example

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class ChaquopyPythonBridge(private val context: Context) {

    companion object {
        private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
        private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
        private const val PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture"
        private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
        private const val CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
        private const val PREFS_NAME = "copyflow_sandbox_prefs"
        private const val KEY_DOWNLOADS_TREE_URI = "downloads_tree_uri"
    }

    init {
        syncSkillAssetsToFilesDir()
    }

    private fun syncSkillAssetsToFilesDir() {
        val skillDir = File(context.filesDir, "skill")
        if (!skillDir.exists()) skillDir.mkdirs()
        val assetFiles = listOf(
            "inspect_template.py",
            "clone_docx_framework.py",
            "SKILL.md",
            "openxml_cloning_guide.md"
        )
        for (name in assetFiles) {
            runCatching {
                context.assets.open("skill/$name").use { input ->
                    FileOutputStream(File(skillDir, name)).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    fun getSkillSystemPromptAddendum(): String {
        return """
You are CopyFlow, a deterministic on-device OpenXML Document Layout Cloner (`template.docx` + `source.docx` in Chat Sandbox).
STRICT EXECUTION & ANTI-LOOP RULES:
1. INTERNAL THOUGHT (`<think>...</think>`): Max 2 short lines (under 20 words). State ONLY the current sandbox state and next tool action.
2. ZERO SUMMARIZATION LOOPS: NEVER write prose summarizing `inspect_template`. Never ask the user to confirm before cloning.
3. WORKFLOW:
   - Step 1 (Inspect): Emit `<think>Inspecting template.docx and source.docx.</think>` followed immediately by:
     <tool_call>{"name": "inspect_template", "arguments": {"template_path": "template.docx", "source_path": "source.docx"}}</tool_call>
   - Step 2 (Clone): When `<tool_response>` has `"status": "READY_TO_CLONE"`, emit `<think>AST ready. Executing clone_document.</think>` followed immediately by:
     <tool_call>{"name": "clone_document", "arguments": {"template_path": "template.docx", "source_path": "source.docx", "spec_json": {}}}</tool_call>
   - Step 3 (Final Report): When `clone_document` returns `"status": "SUCCESS"`, output ONLY a compact 4-bullet Markdown verification report (under 60 words). No conversational filler.
        """.trimIndent()
    }

    /**
     * Returns the persistent per-chat sandbox directory: `filesDir/chat_sandboxes/<sessionId>/`.
     */
    fun getChatSandboxDir(sessionId: String): File {
        val root = File(context.filesDir, "chat_sandboxes")
        val dir = File(root, sessionId.ifBlank { "default_session" })
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Persists SAF directory permission (e.g. Downloads folder) so CopyFlow can directly copy
     * documents from that directory into the Chat Sandbox whenever needed.
     */
    fun saveGrantedDirectoryTree(treeUri: Uri): String {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DOWNLOADS_TREE_URI, treeUri.toString())
            .apply()
        val doc = DocumentFile.fromTreeUri(context, treeUri)
        return doc?.name ?: treeUri.lastPathSegment ?: "Granted Folder"
    }

    fun getGrantedDirectoryName(): String? {
        val uriStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DOWNLOADS_TREE_URI, null) ?: return null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        val doc = DocumentFile.fromTreeUri(context, uri)
        return if (doc != null && doc.canRead()) (doc.name ?: uri.lastPathSegment) else null
    }

    /**
     * Copies an attached SAF `content://` URI into the per-chat sandbox directory
     * (`filesDir/chat_sandboxes/<sessionId>/`), saving BOTH:
     * 1) the clean original filename (e.g. `ALU_4Bit_Report_Master.docx`)
     * 2) a canonical role alias (`template.docx` / `source.docx`)
     * so tools and the LLM can reference either without path typos!
     */
    suspend fun copySafUriToChatSandbox(
        uri: Uri,
        sessionId: String,
        role: AttachmentRole
    ): File = withContext(Dispatchers.IO) {
        // Persist read permission on the picked document URI if supported by the SAF provider
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val sandboxDir = getChatSandboxDir(sessionId)
        val rawDisplayName = resolveDisplayName(uri) ?: "document_${System.currentTimeMillis()}.docx"
        val cleanName = rawDisplayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val destFile = File(sandboxDir, cleanName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        } ?: throw IllegalStateException("Cannot open InputStream for URI: $uri")

        if (!destFile.exists() || destFile.length() == 0L) {
            throw IllegalStateException("Copied sandbox file is empty: ${destFile.absolutePath}")
        }

        // Also write canonical role alias (`template.docx` or `source.docx`) in the same chat sandbox
        createRoleAliasInSandbox(destFile, sandboxDir, role)
        destFile
    }

    fun createRoleAliasInSandbox(sourceFile: File, sandboxDir: File, role: AttachmentRole): File {
        val ext = sourceFile.extension.lowercase().ifBlank { "docx" }
        val aliasName = when (role) {
            AttachmentRole.TEMPLATE -> "template.$ext"
            AttachmentRole.SOURCE -> "source.$ext"
            AttachmentRole.REFERENCE_IMAGE -> "reference_image.$ext"
        }
        val aliasFile = File(sandboxDir, aliasName)
        if (aliasFile.absolutePath != sourceFile.absolutePath) {
            runCatching {
                sourceFile.copyTo(aliasFile, overwrite = true)
            }
        }
        return aliasFile
    }

    /**
     *Migrates any draft sandbox files from `fromSessionId` to `toSessionId` when a chat session is created.
     */
    fun migrateSandboxFiles(fromSessionId: String, toSessionId: String) {
        if (fromSessionId == toSessionId) return
        val fromDir = getChatSandboxDir(fromSessionId)
        val toDir = getChatSandboxDir(toSessionId)
        val files = fromDir.listFiles() ?: return
        for (f in files) {
            if (f.isFile) {
                runCatching {
                    f.copyTo(File(toDir, f.name), overwrite = true)
                }
            }
        }
    }

    /**
     * Bulletproof file resolver that locates a document even if the 0.8B LLM mistyped the package path
     * (e.g. `com.aisstudio` instead of `com.aistudio`), included a timestamp prefix, passed only the
     * filename (`ALU_4Bit_Report_Master.docx`), or referenced a file in the user's Downloads folder.
     */
    fun resolveSandboxFile(
        requestedPath: String,
        sessionId: String?,
        expectedRole: AttachmentRole,
        fallbackBoundPath: String?
    ): File? {
        val sandboxDir = if (!sessionId.isNullOrBlank()) getChatSandboxDir(sessionId) else null

        // 1. Direct path check if valid
        if (requestedPath.isNotBlank()) {
            val direct = File(requestedPath)
            if (direct.exists() && direct.isFile && direct.length() > 0L) {
                return copyIntoSandboxIfExternal(direct, sandboxDir, expectedRole)
            }
        }

        // Extract clean filename and strip any 10-14 digit timestamp prefix (e.g. "1794466467348_Name.docx")
        val rawLeaf = requestedPath.substringAfterLast('/').substringAfterLast('\\').trim()
        val strippedLeaf = rawLeaf.replace(Regex("^\\d{10,15}_"), "")

        // 2. Check inside the current session's Chat Sandbox directory
        if (sandboxDir != null && sandboxDir.exists()) {
            for (candidateName in listOf(rawLeaf, strippedLeaf).filter { it.isNotBlank() }) {
                val f = File(sandboxDir, candidateName)
                if (f.exists() && f.isFile && f.length() > 0L) return f
            }
            // Case-insensitive match in sandboxDir
            sandboxDir.listFiles()?.firstOrNull { f ->
                f.isFile && (f.name.equals(rawLeaf, ignoreCase = true) ||
                    f.name.equals(strippedLeaf, ignoreCase = true) ||
                    f.name.replace(Regex("^\\d{10,15}_"), "").equals(strippedLeaf, ignoreCase = true))
            }?.let { return it }
        }

        // 3. Check fallbackBoundPath (the session's bound templatePath / sourcePath)
        if (!fallbackBoundPath.isNullOrBlank()) {
            val boundFile = File(fallbackBoundPath)
            if (boundFile.exists() && boundFile.isFile && boundFile.length() > 0L) {
                return copyIntoSandboxIfExternal(boundFile, sandboxDir, expectedRole)
            }
            val boundLeaf = fallbackBoundPath.substringAfterLast('/').replace(Regex("^\\d{10,15}_"), "")
            if (sandboxDir != null && boundLeaf.isNotBlank()) {
                val inSandbox = File(sandboxDir, boundLeaf)
                if (inSandbox.exists() && inSandbox.length() > 0L) return inSandbox
            }
        }

        // 4. Check canonical role alias inside the current session's Chat Sandbox (`template.docx`, `source.docx`)
        if (sandboxDir != null && sandboxDir.exists()) {
            val prefix = when (expectedRole) {
                AttachmentRole.TEMPLATE -> "template."
                AttachmentRole.SOURCE -> "source."
                AttachmentRole.REFERENCE_IMAGE -> "reference_image."
            }
            sandboxDir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(prefix) && it.length() > 0L }
                ?.let { return it }
        }

        // 5. Search across all chat_sandboxes and cacheDir/attachments for matching filename
        val allInternalDirs = buildList {
            File(context.filesDir, "chat_sandboxes").listFiles()?.filter { it.isDirectory }?.let { addAll(it) }
            add(File(context.cacheDir, "attachments"))
        }
        for (dir in allInternalDirs) {
            if (!dir.exists()) continue
            val match = dir.listFiles()?.firstOrNull { f ->
                val fClean = f.name.replace(Regex("^\\d{10,15}_"), "")
                f.isFile && f.length() > 0L && (
                    (strippedLeaf.isNotBlank() && fClean.equals(strippedLeaf, ignoreCase = true)) ||
                        (rawLeaf.isNotBlank() && f.name.equals(rawLeaf, ignoreCase = true))
                    )
            }
            if (match != null) {
                return copyIntoSandboxIfExternal(match, sandboxDir, expectedRole)
            }
        }

        // 6. Check Granted SAF Directory (e.g. Downloads folder granted by user)
        val grantedMatch = findAndCopyFromGrantedSafFolder(strippedLeaf.ifBlank { rawLeaf }, sandboxDir, expectedRole)
        if (grantedMatch != null) return grantedMatch

        // 7. Check public Downloads folder directly (`/storage/emulated/0/Download`) if readable
        runCatching {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir != null && downloadsDir.exists() && downloadsDir.canRead()) {
                for (name in listOf(strippedLeaf, rawLeaf).filter { it.isNotBlank() }) {
                    val dlFile = File(downloadsDir, name)
                    if (dlFile.exists() && dlFile.isFile && dlFile.canRead()) {
                        return copyIntoSandboxIfExternal(dlFile, sandboxDir, expectedRole)
                    }
                }
            }
        }

        return null
    }

    private fun copyIntoSandboxIfExternal(
        file: File,
        sandboxDir: File?,
        role: AttachmentRole
    ): File {
        if (sandboxDir == null) return file
        if (file.parentFile?.absolutePath == sandboxDir.absolutePath) {
            createRoleAliasInSandbox(file, sandboxDir, role)
            return file
        }
        return try {
            val cleanName = file.name.replace(Regex("^\\d{10,15}_"), "")
            val target = File(sandboxDir, cleanName)
            file.copyTo(target, overwrite = true)
            createRoleAliasInSandbox(target, sandboxDir, role)
            target
        } catch (_: Exception) {
            file
        }
    }

    private fun findAndCopyFromGrantedSafFolder(
        fileName: String,
        sandboxDir: File?,
        role: AttachmentRole
    ): File? {
        if (fileName.isBlank() || sandboxDir == null) return null
        val uriStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DOWNLOADS_TREE_URI, null) ?: return null
        return runCatching {
            val treeUri = Uri.parse(uriStr)
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val matchedDoc = rootDoc.listFiles().firstOrNull { doc ->
                doc.isFile && doc.name.equals(fileName, ignoreCase = true)
            } ?: return null

            val destFile = File(sandboxDir, matchedDoc.name ?: fileName)
            context.contentResolver.openInputStream(matchedDoc.uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (destFile.exists() && destFile.length() > 0L) {
                createRoleAliasInSandbox(destFile, sandboxDir, role)
                destFile
            } else {
                null
            }
        }.getOrNull()
    }

    private fun resolveDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    /**
     * Tool 1: Executes `inspect_template(template_path, source_path, max_chars=12000)` using
     * verified Chat Sandbox files, and caches `inspection_state.json` inside the session sandbox
     * so `Qwen3.5-0.8B` receives a compact ~180-token digest instead of 2,500+ tokens of raw XML.
     */
    suspend fun inspectTemplate(
        templatePath: String,
        sourcePath: String,
        sessionId: String? = null,
        fallbackTemplatePath: String? = null,
        fallbackSourcePath: String? = null,
        maxChars: Int = 12000
    ): String = withContext(Dispatchers.IO) {
        val resolvedTpl = resolveSandboxFile(
            requestedPath = templatePath,
            sessionId = sessionId,
            expectedRole = AttachmentRole.TEMPLATE,
            fallbackBoundPath = fallbackTemplatePath
        )
        val resolvedSrc = resolveSandboxFile(
            requestedPath = sourcePath,
            sessionId = sessionId,
            expectedRole = AttachmentRole.SOURCE,
            fallbackBoundPath = fallbackSourcePath
        )

        // Write `inspection_state.json` into the active Chat Sandbox so both `cloneDocument`
        // and `buildCompactToolDigestForModel` have instant access to the parsed AST summary.
        if (!sessionId.isNullOrBlank()) {
            runCatching {
                writeSandboxInspectionCache(
                    sandboxDir = getChatSandboxDir(sessionId),
                    templateFile = resolvedTpl,
                    sourceFile = resolvedSrc
                )
            }
        }

        val actualTplPath = resolvedTpl?.absolutePath ?: ""
        val actualSrcPath = resolvedSrc?.absolutePath ?: ""

        if (actualTplPath.isNotBlank() || actualSrcPath.isNotBlank()) {
            val chaquopyOut = tryInvokeChaquopy(
                moduleName = "inspect_template",
                funcName = "inspect_template",
                args = arrayOf(actualTplPath, actualSrcPath, maxChars)
            )
            if (chaquopyOut != null) {
                return@withContext chaquopyOut
            }
        }

        val sb = StringBuilder()
        if (resolvedTpl != null && resolvedTpl.exists()) {
            sb.append("SANDBOX TEMPLATE FILE: ${resolvedTpl.name} (${resolvedTpl.length()} bytes)\n")
            val lower = resolvedTpl.name.lowercase()
            when {
                lower.endsWith(".docx") -> sb.append(inspectDocxNative(resolvedTpl, "TEMPLATE"))
                lower.endsWith(".pdf") -> sb.append(inspectPdfNative(resolvedTpl, "TEMPLATE"))
                else -> sb.append("Unsupported file type for TEMPLATE (${resolvedTpl.name}). Provide .docx or .pdf\n")
            }
        } else {
            sb.append("WARNING: No Reference Template file found in Chat Sandbox (requested: '$templatePath'). Attach a Template .docx/.pdf using the + button.\n")
        }

        if (resolvedSrc != null && resolvedSrc.exists()) {
            sb.append("\nSANDBOX SOURCE FILE: ${resolvedSrc.name} (${resolvedSrc.length()} bytes)\n")
            val lower = resolvedSrc.name.lowercase()
            when {
                lower.endsWith(".docx") -> sb.append(inspectDocxNative(resolvedSrc, "SOURCE"))
                lower.endsWith(".pdf") -> sb.append(inspectPdfNative(resolvedSrc, "SOURCE"))
                else -> sb.append("Unsupported file type for SOURCE (${resolvedSrc.name}). Provide .docx or .pdf\n")
            }
        } else {
            sb.append("\nWARNING: No Source Content file found in Chat Sandbox (requested: '$sourcePath'). Attach a Source .docx/.pdf using the + button.\n")
        }

        val fullReport = sb.toString()
        if (fullReport.length > maxChars) {
            fullReport.substring(0, maxChars) + "\n...[TRUNCATED TO $maxChars CHARS]"
        } else {
            fullReport
        }
    }

    /**
     * Extracts template typography/color defaults and source block counts and stores them in
     * `filesDir/chat_sandboxes/<sessionId>/inspection_state.json`.
     */
    private fun writeSandboxInspectionCache(
        sandboxDir: File,
        templateFile: File?,
        sourceFile: File?
    ): JSONObject {
        val stateJson = JSONObject()
        val hasTemplate = templateFile != null && templateFile.exists()
        val hasSource = sourceFile != null && sourceFile.exists()
        stateJson.put("status", if (hasTemplate && hasSource) "READY_TO_CLONE" else "MISSING_FILES")
        stateJson.put("template_file", templateFile?.name ?: "missing")
        stateJson.put("source_file", sourceFile?.name ?: "missing")

        if (hasTemplate && templateFile != null) {
            val defaults = if (templateFile.name.lowercase().endsWith(".docx")) {
                extractTemplateStyleDefaults(templateFile)
            } else {
                JSONObject().apply {
                    put("font", "Calibri")
                    put("body_sz", 21)
                    put("h1_sz", 32)
                    put("h1_color", "1B365D")
                    put("h2_sz", 26)
                    put("h2_color", "2C5282")
                    put("h3_sz", 23)
                    put("h3_color", "2D3748")
                    put("body_color", "222222")
                    put("line_spacing", 276)
                }
            }
            stateJson.put("extracted_styles", defaults)
        }

        if (hasSource && sourceFile != null) {
            val (blocks, mediaMap) = runCatching { extractSourceBlocksNative(sourceFile) }
                .getOrDefault(emptyList<SourceBlock>() to emptyMap())
            val counts = JSONObject().apply {
                put("total_blocks", blocks.size)
                put("headings", blocks.count { it.type == "h1" || it.type == "h2" || it.type == "h3" })
                put("paragraphs", blocks.count { it.type == "body" || it.type == "fig_caption" || it.type == "tbl_caption" })
                put("bullets", blocks.count { it.type == "bullet" })
                put("tables", blocks.count { it.type == "table" })
                put("images", mediaMap.size)
            }
            stateJson.put("source_blocks_cached", counts)
        }

        val cacheFile = File(sandboxDir, "inspection_state.json")
        cacheFile.writeText(stateJson.toString(2), Charsets.UTF_8)
        return stateJson
    }

    /**
     * Builds a compact ~160-token tool output digest specifically for the 0.8B model's context window
     * so we never flood Qwen3.5-0.8B with 2,500+ tokens of raw XML inspection logs.
     */
    fun buildCompactToolDigestForModel(
        toolName: String?,
        sessionId: String,
        templatePath: String?,
        sourcePath: String?,
        rawToolOutput: String?,
        isAlreadyClonedLaterInTurn: Boolean
    ): String {
        val sandboxDir = getChatSandboxDir(sessionId)
        return when (toolName) {
            "inspect_template" -> {
                if (isAlreadyClonedLaterInTurn) {
                    // Compact older inspection turns to 1 line once cloning has already succeeded
                    """{"tool":"inspect_template","status":"ARCHIVED_INSPECTION","note":"Template styles and source blocks already cached in sandbox."}"""
                } else {
                    val cacheFile = File(sandboxDir, "inspection_state.json")
                    val cached = if (cacheFile.exists()) {
                        runCatching { JSONObject(cacheFile.readText(Charsets.UTF_8)) }.getOrNull()
                    } else {
                        null
                    } ?: runCatching {
                        val tpl = resolveSandboxFile(templatePath ?: "template.docx", sessionId, AttachmentRole.TEMPLATE, templatePath)
                        val src = resolveSandboxFile(sourcePath ?: "source.docx", sessionId, AttachmentRole.SOURCE, sourcePath)
                        writeSandboxInspectionCache(sandboxDir, tpl, src)
                    }.getOrDefault(JSONObject())

                    cached.put(
                        "next_required_action",
                        """DO NOT SUMMARIZE. Emit <tool_call>{"name": "clone_document", "arguments": {"template_path": "template.docx", "source_path": "source.docx", "spec_json": {}}}</tool_call> immediately."""
                    )
                    cached.toString()
                }
            }
            "clone_document", "run_custom_openxml_python" -> {
                val parsed = runCatching { JSONObject(rawToolOutput ?: "{}") }.getOrNull()
                if (parsed != null && parsed.length() > 0) {
                    val compact = JSONObject().apply {
                        put("status", parsed.optString("status", "SUCCESS"))
                        put("output_file", parsed.optString("output_path", "").substringAfterLast('/'))
                        put("paragraphs_created", parsed.optInt("paragraphs_created", 0))
                        put("tables_created", parsed.optInt("tables_created", 0))
                        put("images_embedded", parsed.optInt("images_embedded", 0))
                        put("font_family", parsed.optString("font_family", "Calibri"))
                        put("page_size", parsed.optString("page_size", "Letter"))
                        put("directive", "DONE. Output a compact 4-bullet Markdown summary (under 60 words) of the cloned .docx metrics. Zero tool calls.")
                    }
                    compact.toString()
                } else {
                    (rawToolOutput ?: "SUCCESS").take(320)
                }
            }
            else -> (rawToolOutput ?: "").take(320)
        }
    }

    /**
     * Tool 2: Executes `clone_document(template_path, source_path, output_path, spec_json)` using
     * verified Chat Sandbox files and merges any cached `inspection_state.json` style defaults.
     */
    suspend fun cloneDocument(
        templatePath: String,
        sourcePath: String,
        outputPath: String,
        specJson: String = "{}",
        sessionId: String? = null,
        fallbackTemplatePath: String? = null,
        fallbackSourcePath: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val resolvedTpl = resolveSandboxFile(
            requestedPath = templatePath,
            sessionId = sessionId,
            expectedRole = AttachmentRole.TEMPLATE,
            fallbackBoundPath = fallbackTemplatePath
        ) ?: throw IllegalStateException(
            "Reference Template document not found in Chat Sandbox (requested '$templatePath'). Please attach the template .docx file first."
        )

        val resolvedSrc = resolveSandboxFile(
            requestedPath = sourcePath,
            sessionId = sessionId,
            expectedRole = AttachmentRole.SOURCE,
            fallbackBoundPath = fallbackSourcePath
        ) ?: throw IllegalStateException(
            "Source Content document not found in Chat Sandbox (requested '$sourcePath'). Please attach the source .docx/.pdf file first."
        )

        // Merge cached `extracted_styles` from `inspection_state.json` with any model-provided `spec_json` overrides
        val mergedSpecObj = JSONObject()
        if (!sessionId.isNullOrBlank()) {
            val cacheFile = File(getChatSandboxDir(sessionId), "inspection_state.json")
            if (cacheFile.exists()) {
                runCatching {
                    val cachedRoot = JSONObject(cacheFile.readText(Charsets.UTF_8))
                    val cachedStyles = cachedRoot.optJSONObject("extracted_styles")
                    if (cachedStyles != null) {
                        val keys = cachedStyles.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            mergedSpecObj.put(k, cachedStyles.get(k))
                        }
                    }
                }
            }
        }
        val modelSpec = runCatching { JSONObject(specJson) }.getOrDefault(JSONObject())
        val overrideKeys = modelSpec.keys()
        while (overrideKeys.hasNext()) {
            val k = overrideKeys.next()
            mergedSpecObj.put(k, modelSpec.get(k))
        }

        val chaquopyOut = tryInvokeChaquopy(
            moduleName = "clone_docx_framework",
            funcName = "clone_document",
            args = arrayOf(resolvedTpl.absolutePath, resolvedSrc.absolutePath, outputPath, mergedSpecObj.toString())
        )
        if (chaquopyOut != null) {
            return@withContext runCatching { JSONObject(chaquopyOut) }.getOrElse {
                JSONObject().apply {
                    put("status", "SUCCESS")
                    put("output_path", outputPath)
                    put("message", chaquopyOut)
                }
            }
        }

        executeOpenXmlCloneNative(
            templateFile = resolvedTpl,
            sourceFile = resolvedSrc,
            outputFile = File(outputPath),
            specObj = mergedSpecObj
        )
    }

    /**
     * Tool 3: Executes `run_custom_openxml_python(script_code, template_path, source_path, output_path)`.
     */
    suspend fun runCustomOpenXmlPython(
        scriptCode: String,
        templatePath: String,
        sourcePath: String,
        outputPath: String,
        sessionId: String? = null,
        fallbackTemplatePath: String? = null,
        fallbackSourcePath: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val resolvedTpl = resolveSandboxFile(
            requestedPath = templatePath,
            sessionId = sessionId,
            expectedRole = AttachmentRole.TEMPLATE,
            fallbackBoundPath = fallbackTemplatePath
        ) ?: throw IllegalStateException("Template file not found in Chat Sandbox.")

        val resolvedSrc = resolveSandboxFile(
            requestedPath = sourcePath,
            sessionId = sessionId,
            expectedRole = AttachmentRole.SOURCE,
            fallbackBoundPath = fallbackSourcePath
        ) ?: throw IllegalStateException("Source file not found in Chat Sandbox.")

        val chaquopyOut = tryInvokeChaquopy(
            moduleName = "clone_docx_framework",
            funcName = "run_custom_openxml_python",
            args = arrayOf(scriptCode, resolvedTpl.absolutePath, resolvedSrc.absolutePath, outputPath)
        )
        if (chaquopyOut != null) {
            return@withContext JSONObject(chaquopyOut)
        }

        val extractedSpec = parseSpecOverridesFromScript(scriptCode)
        executeOpenXmlCloneNative(
            templateFile = resolvedTpl,
            sourceFile = resolvedSrc,
            outputFile = File(outputPath),
            specObj = extractedSpec
        )
    }

    private fun tryInvokeChaquopy(
        moduleName: String,
        funcName: String,
        args: Array<Any>
    ): String? {
        return try {
            val pyClass = Class.forName("com.chaquo.python.Python")
            val isStartedMethod = pyClass.getMethod("isStarted")
            val started = isStartedMethod.invoke(null) as Boolean
            if (!started) {
                val platformClass = Class.forName("com.chaquo.python.android.AndroidPlatform")
                val platformCtor = platformClass.getConstructor(Context::class.java)
                val platform = platformCtor.newInstance(context)
                val pyPlatformClass = Class.forName("com.chaquo.python.Platform")
                val startMethod = pyClass.getMethod("start", pyPlatformClass)
                startMethod.invoke(null, platform)
            }
            val getInstanceMethod = pyClass.getMethod("getInstance")
            val pyInstance = getInstanceMethod.invoke(null)
            val getModuleMethod = pyClass.getMethod("getModule", String::class.java)
            val moduleObj = getModuleMethod.invoke(pyInstance, moduleName)
            val pyObjectClass = Class.forName("com.chaquo.python.PyObject")
            val callAttrMethod = pyObjectClass.getMethod("callAttr", String::class.java, Array<Any>::class.java)
            val resultObj = callAttrMethod.invoke(moduleObj, funcName, args)
            resultObj?.toString()
        } catch (_: ClassNotFoundException) {
            null
        } catch (e: Throwable) {
            throw IllegalStateException("Chaquopy Python execution error in $moduleName.$funcName: ${e.message}", e)
        }
    }

    private fun inspectDocxNative(file: File, label: String): String {
        val out = StringBuilder()
        out.append("======================================================================\n")
        out.append("DOCX INSPECTION: $label (${file.name})\n")
        out.append("======================================================================\n")

        ZipFile(file).use { zf ->
            val entryNames = zf.entries().asSequence().map { it.name }.sorted().toList()
            out.append("\n[1] ARCHIVE CONTENTS (${entryNames.size} files):\n")
            for (name in entryNames) {
                val entry = zf.getEntry(name)
                out.append("  - $name (${entry?.size ?: 0} bytes)\n")
            }

            val docEntry = zf.getEntry("word/document.xml")
                ?: throw IllegalStateException("${file.name} is missing word/document.xml")

            val docDom = parseXmlStream(zf.getInputStream(docEntry))
            val bodyList = docDom.getElementsByTagNameNS(W_NS, "body")
            val bodyEl = if (bodyList.length > 0) bodyList.item(0) as Element else null

            // Section / Page geometry
            out.append("\n[2] PAGE SETUP & SECTION PROPERTIES:\n")
            val sectList = docDom.getElementsByTagNameNS(W_NS, "sectPr")
            for (i in 0 until sectList.length) {
                val sect = sectList.item(i) as Element
                val pgSz = sect.getElementsByTagNameNS(W_NS, "pgSz").item(0) as? Element
                val pgMar = sect.getElementsByTagNameNS(W_NS, "pgMar").item(0) as? Element
                val cols = sect.getElementsByTagNameNS(W_NS, "cols").item(0) as? Element
                out.append("  Section ${i + 1}:\n")
                if (pgSz != null) {
                    val wTwips = pgSz.getAttributeNS(W_NS, "w").ifEmpty { pgSz.getAttribute("w:w") }.toIntOrNull() ?: 12240
                    val hTwips = pgSz.getAttributeNS(W_NS, "h").ifEmpty { pgSz.getAttribute("w:h") }.toIntOrNull() ?: 15840
                    out.append(
                        "    Page Size: w=$wTwips twips (${String.format("%.2f", wTwips / 1440.0)}\"), " +
                            "h=$hTwips twips (${String.format("%.2f", hTwips / 1440.0)}\")\n"
                    )
                }
                if (pgMar != null) {
                    val top = pgMar.getAttributeNS(W_NS, "top").ifEmpty { pgMar.getAttribute("w:top") }
                    val bottom = pgMar.getAttributeNS(W_NS, "bottom").ifEmpty { pgMar.getAttribute("w:bottom") }
                    val left = pgMar.getAttributeNS(W_NS, "left").ifEmpty { pgMar.getAttribute("w:left") }
                    val right = pgMar.getAttributeNS(W_NS, "right").ifEmpty { pgMar.getAttribute("w:right") }
                    out.append("    Margins (twips): top=$top, bottom=$bottom, left=$left, right=$right\n")
                }
                if (cols != null) {
                    val num = cols.getAttributeNS(W_NS, "num").ifEmpty { cols.getAttribute("w:num").ifEmpty { "1" } }
                    val space = cols.getAttributeNS(W_NS, "space").ifEmpty { cols.getAttribute("w:space") }
                    out.append("    Columns: num=$num, space=$space\n")
                }
            }

            // Styles
            val stylesEntry = zf.getEntry("word/styles.xml")
            if (stylesEntry != null) {
                out.append("\n[3] DEFINED STYLES (word/styles.xml):\n")
                val stylesDom = parseXmlStream(zf.getInputStream(stylesEntry))
                val styleNodes = stylesDom.getElementsByTagNameNS(W_NS, "style")
                for (i in 0 until minOf(styleNodes.length, 40)) {
                    val st = styleNodes.item(i) as Element
                    val styleId = st.getAttributeNS(W_NS, "styleId").ifEmpty { st.getAttribute("w:styleId") }
                    val stType = st.getAttributeNS(W_NS, "type").ifEmpty { st.getAttribute("w:type") }
                    val nameEl = st.getElementsByTagNameNS(W_NS, "name").item(0) as? Element
                    val nameVal = nameEl?.getAttributeNS(W_NS, "val")?.ifEmpty { nameEl.getAttribute("w:val") } ?: styleId
                    val rFonts = st.getElementsByTagNameNS(W_NS, "rFonts").item(0) as? Element
                    val fontAscii = rFonts?.getAttributeNS(W_NS, "ascii")?.ifEmpty { rFonts.getAttribute("w:ascii") } ?: ""
                    val szEl = st.getElementsByTagNameNS(W_NS, "sz").item(0) as? Element
                    val szVal = szEl?.getAttributeNS(W_NS, "val")?.ifEmpty { szEl.getAttribute("w:val") } ?: ""
                    val colorEl = st.getElementsByTagNameNS(W_NS, "color").item(0) as? Element
                    val colorVal = colorEl?.getAttributeNS(W_NS, "val")?.ifEmpty { colorEl.getAttribute("w:val") } ?: ""
                    out.append("  Style '$styleId' ($stType) name='$nameVal' font=$fontAscii sz=$szVal color=$colorVal\n")
                }
            }

            // Body paragraphs & tables
            out.append("\n[4] BODY STRUCTURE (Paragraphs & Tables):\n")
            if (bodyEl != null) {
                val children = bodyEl.childNodes
                var pIndex = 0
                var tblIndex = 0
                for (i in 0 until children.length) {
                    val child = children.item(i)
                    if (child.nodeType != Node.ELEMENT_NODE) continue
                    val el = child as Element
                    when (el.localName) {
                        "p" -> {
                            pIndex++
                            val text = extractElementText(el).trim()
                            val pStyleEl = el.getElementsByTagNameNS(W_NS, "pStyle").item(0) as? Element
                            val pStyle = pStyleEl?.getAttributeNS(W_NS, "val")?.ifEmpty { pStyleEl.getAttribute("w:val") } ?: "Normal"
                            val hasDrawing = el.getElementsByTagNameNS(W_NS, "drawing").length > 0
                            if (text.isNotEmpty() || hasDrawing) {
                                val snippet = if (text.length > 95) text.take(95) + "..." else text
                                out.append("  P#$pIndex [style=$pStyle${if (hasDrawing) ", +IMAGE" else ""}]: \"$snippet\"\n")
                            }
                        }
                        "tbl" -> {
                            tblIndex++
                            val rows = el.getElementsByTagNameNS(W_NS, "tr")
                            out.append("  TABLE #$tblIndex (${rows.length} rows):\n")
                            for (r in 0 until minOf(rows.length, 4)) {
                                val tr = rows.item(r) as Element
                                val cells = tr.getElementsByTagNameNS(W_NS, "tc")
                                val cellTexts = (0 until cells.length).map { c ->
                                    extractElementText(cells.item(c) as Element).trim().take(30)
                                }
                                out.append("    Row $r: ${cellTexts.joinToString(" | ")}\n")
                            }
                        }
                    }
                }
            }
        }
        return out.toString()
    }

    private fun inspectPdfNative(file: File, label: String): String {
        val out = StringBuilder()
        out.append("======================================================================\n")
        out.append("PDF INSPECTION: $label (${file.name})\n")
        out.append("======================================================================\n")

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val pageCount = renderer.pageCount
                out.append("Total Pages: $pageCount\n")
                for (i in 0 until minOf(pageCount, 10)) {
                    renderer.openPage(i).use { page ->
                        val widthPt = page.width
                        val heightPt = page.height
                        out.append(
                            "  Page ${i + 1}: ${widthPt}x${heightPt} pt " +
                                "(${String.format("%.2f", widthPt / 72.0)}\" x ${String.format("%.2f", heightPt / 72.0)}\")\n"
                        )
                    }
                }
            }
        }
        return out.toString()
    }

    private data class SourceBlock(
        val type: String,
        val text: String = "",
        val headers: List<String> = emptyList(),
        val rows: List<List<String>> = emptyList(),
        val mediaName: String = "",
        val widthEmu: Long = 4_572_000L,
        val heightEmu: Long = 2_743_200L
    )

    private fun extractSourceBlocksNative(sourceFile: File): Pair<List<SourceBlock>, Map<String, ByteArray>> {
        val blocks = mutableListOf<SourceBlock>()
        val mediaMap = LinkedHashMap<String, ByteArray>()

        if (!sourceFile.exists()) {
            throw IllegalStateException("Source file does not exist: ${sourceFile.absolutePath}")
        }

        if (sourceFile.name.lowercase().endsWith(".pdf")) {
            ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    blocks.add(SourceBlock(type = "h1", text = sourceFile.nameWithoutExtension))
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            blocks.add(
                                SourceBlock(
                                    type = "body",
                                    text = "Source PDF Page ${i + 1} (${page.width}x${page.height} pt) imported from ${sourceFile.name}."
                                )
                            )
                        }
                    }
                }
            }
            return blocks to mediaMap
        }

        ZipFile(sourceFile).use { zf ->
            val relTargetById = HashMap<String, String>()
            val relsEntry = zf.getEntry("word/_rels/document.xml.rels")
            if (relsEntry != null) {
                val relsDom = parseXmlStream(zf.getInputStream(relsEntry))
                val relNodes = relsDom.getElementsByTagName("Relationship")
                for (i in 0 until relNodes.length) {
                    val rEl = relNodes.item(i) as Element
                    val id = rEl.getAttribute("Id")
                    val target = rEl.getAttribute("Target")
                    if (id.isNotEmpty() && target.isNotEmpty()) {
                        relTargetById[id] = target
                    }
                }
            }

            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.startsWith("word/media/") && !entry.isDirectory) {
                    val fname = entry.name.substringAfterLast('/')
                    zf.getInputStream(entry).use { input ->
                        mediaMap[fname] = input.readBytes()
                    }
                }
            }

            val docEntry = zf.getEntry("word/document.xml")
                ?: throw IllegalStateException("Source DOCX ${sourceFile.name} is missing word/document.xml")
            val docDom = parseXmlStream(zf.getInputStream(docEntry))
            val bodyEl = docDom.getElementsByTagNameNS(W_NS, "body").item(0) as? Element
                ?: return blocks to mediaMap

            val children = bodyEl.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeType != Node.ELEMENT_NODE) continue
                val el = node as Element
                when (el.localName) {
                    "p" -> {
                        val pStyleEl = el.getElementsByTagNameNS(W_NS, "pStyle").item(0) as? Element
                        val pStyle = (pStyleEl?.getAttributeNS(W_NS, "val")?.ifEmpty { pStyleEl.getAttribute("w:val") } ?: "").lowercase()
                        val hasNumPr = el.getElementsByTagNameNS(W_NS, "numPr").length > 0

                        val blips = el.getElementsByTagNameNS(A_NS, "blip")
                        if (blips.length > 0) {
                            val blip = blips.item(0) as Element
                            val embedId = blip.getAttributeNS(R_NS, "embed").ifEmpty { blip.getAttribute("r:embed") }
                            val target = relTargetById[embedId] ?: ""
                            val mediaName = target.substringAfterLast('/')
                            val extent = el.getElementsByTagNameNS(WP_NS, "extent").item(0) as? Element
                            val cx = extent?.getAttribute("cx")?.toLongOrNull() ?: 4_572_000L
                            val cy = extent?.getAttribute("cy")?.toLongOrNull() ?: 2_743_200L
                            if (mediaName.isNotEmpty() && mediaMap.containsKey(mediaName)) {
                                blocks.add(
                                    SourceBlock(
                                        type = "image",
                                        mediaName = mediaName,
                                        widthEmu = cx,
                                        heightEmu = cy
                                    )
                                )
                            }
                        }

                        val text = extractElementText(el).trim()
                        if (text.isNotEmpty()) {
                            val blockType = when {
                                "heading1" in pStyle || pStyle == "h1" || pStyle == "title" -> "h1"
                                "heading2" in pStyle || pStyle == "h2" || pStyle == "subtitle" -> "h2"
                                "heading3" in pStyle || pStyle == "h3" -> "h3"
                                hasNumPr || "list" in pStyle || text.startsWith("• ") -> "bullet"
                                text.startsWith("Figure ") && ":" in text.take(25) -> "fig_caption"
                                text.startsWith("Table ") && ":" in text.take(25) -> "tbl_caption"
                                else -> "body"
                            }
                            blocks.add(
                                SourceBlock(
                                    type = blockType,
                                    text = text.removePrefix("• ").trim()
                                )
                            )
                        }
                    }
                    "tbl" -> {
                        val trNodes = el.getElementsByTagNameNS(W_NS, "tr")
                        val allRows = mutableListOf<List<String>>()
                        for (r in 0 until trNodes.length) {
                            val tr = trNodes.item(r) as Element
                            val tcNodes = tr.getElementsByTagNameNS(W_NS, "tc")
                            val rowCells = (0 until tcNodes.length).map { c ->
                                extractElementText(tcNodes.item(c) as Element).trim()
                            }
                            if (rowCells.any { it.isNotEmpty() }) {
                                allRows.add(rowCells)
                            }
                        }
                        if (allRows.isNotEmpty()) {
                            blocks.add(
                                SourceBlock(
                                    type = "table",
                                    headers = allRows.first(),
                                    rows = if (allRows.size > 1) allRows.drop(1) else emptyList()
                                )
                            )
                        }
                    }
                }
            }
        }

        return blocks to mediaMap
    }

    private fun executeOpenXmlCloneNative(
        templateFile: File,
        sourceFile: File,
        outputFile: File,
        specObj: JSONObject
    ): JSONObject {
        if (!templateFile.exists()) {
            throw IllegalStateException("Template file does not exist: ${templateFile.absolutePath}")
        }
        if (!sourceFile.exists()) {
            throw IllegalStateException("Source file does not exist: ${sourceFile.absolutePath}")
        }

        val templateDefaults = extractTemplateStyleDefaults(templateFile)
        val font = specObj.optString("font", templateDefaults.optString("font", "Calibri"))
        val bodySz = specObj.optInt("body_sz", templateDefaults.optInt("body_sz", 21))
        val h1Sz = specObj.optInt("h1_sz", templateDefaults.optInt("h1_sz", 32))
        val h1Color = specObj.optString("h1_color", templateDefaults.optString("h1_color", "1B365D")).removePrefix("#")
        val h2Sz = specObj.optInt("h2_sz", templateDefaults.optInt("h2_sz", 26))
        val h2Color = specObj.optString("h2_color", templateDefaults.optString("h2_color", "2C5282")).removePrefix("#")
        val h3Sz = specObj.optInt("h3_sz", templateDefaults.optInt("h3_sz", 23))
        val h3Color = specObj.optString("h3_color", templateDefaults.optString("h3_color", "2D3748")).removePrefix("#")
        val bodyColor = specObj.optString("body_color", templateDefaults.optString("body_color", "222222")).removePrefix("#")
        val lineSpacing = specObj.optInt("line_spacing", templateDefaults.optInt("line_spacing", 276))
        val thFill = specObj.optString("th_fill", h1Color).removePrefix("#")
        val thColor = specObj.optString("th_color", "FFFFFF").removePrefix("#")
        val zebraFill = specObj.optString("zebra_fill", "F4F6F9").removePrefix("#")
        val borderColor = specObj.optString("border_color", "CBD5E0").removePrefix("#")

        val (blocks, mediaMap) = extractSourceBlocksNative(sourceFile)

        val workDir = File(context.cacheDir, "openxml_work_${System.currentTimeMillis()}")
        if (workDir.exists()) workDir.deleteRecursively()
        workDir.mkdirs()

        try {
            ZipFile(templateFile).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val outEntryFile = File(workDir, entry.name)
                    if (entry.isDirectory) {
                        outEntryFile.mkdirs()
                    } else {
                        outEntryFile.parentFile?.mkdirs()
                        zf.getInputStream(entry).use { input ->
                            FileOutputStream(outEntryFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }

            val docXmlFile = File(workDir, "word/document.xml")
            if (!docXmlFile.exists()) {
                throw IllegalStateException("Template is not a valid OpenXML .docx (missing word/document.xml)")
            }

            val docDom = FileInputStream(docXmlFile).use { parseXmlStream(it) }
            val bodyEl = docDom.getElementsByTagNameNS(W_NS, "body").item(0) as? Element
                ?: throw IllegalStateException("Template word/document.xml is missing <w:body>")

            var savedSectPr: Node? = null
            val childNodes = bodyEl.childNodes
            val toRemove = mutableListOf<Node>()
            for (i in 0 until childNodes.length) {
                val node = childNodes.item(i)
                if (node.nodeType == Node.ELEMENT_NODE && node.localName == "sectPr") {
                    savedSectPr = node.cloneNode(true)
                }
                toRemove.add(node)
            }
            for (n in toRemove) {
                bodyEl.removeChild(n)
            }

            var paragraphsCreated = 0
            var tablesCreated = 0
            var imagesEmbedded = 0
            var docPrIdCounter = 100

            for (block in blocks) {
                when (block.type) {
                    "h1" -> {
                        bodyEl.appendChild(
                            buildStyledParagraph(
                                doc = docDom,
                                text = block.text,
                                font = font,
                                szHalfPt = h1Sz,
                                colorHex = h1Color,
                                bold = true,
                                italic = false,
                                spaceBefore = 280,
                                spaceAfter = 120,
                                lineSpacing = 240,
                                jc = "left",
                                keepNext = true,
                                keepLines = true
                            )
                        )
                        paragraphsCreated++
                    }
                    "h2" -> {
                        bodyEl.appendChild(
                            buildStyledParagraph(
                                doc = docDom,
                                text = block.text,
                                font = font,
                                szHalfPt = h2Sz,
                                colorHex = h2Color,
                                bold = true,
                                italic = false,
                                spaceBefore = 220,
                                spaceAfter = 80,
                                lineSpacing = 240,
                                jc = "left",
                                keepNext = true,
                                keepLines = true
                            )
                        )
                        paragraphsCreated++
                    }
                    "h3" -> {
                        bodyEl.appendChild(
                            buildStyledParagraph(
                                doc = docDom,
                                text = block.text,
                                font = font,
                                szHalfPt = h3Sz,
                                colorHex = h3Color,
                                bold = true,
                                italic = false,
                                spaceBefore = 160,
                                spaceAfter = 60,
                                lineSpacing = 240,
                                jc = "left",
                                keepNext = true,
                                keepLines = true
                            )
                        )
                        paragraphsCreated++
                    }
                    "bullet" -> {
                        bodyEl.appendChild(
                            buildStyledParagraph(
                                doc = docDom,
                                text = "•  ${block.text}",
                                font = font,
                                szHalfPt = bodySz,
                                colorHex = bodyColor,
                                bold = false,
                                italic = false,
                                spaceBefore = 0,
                                spaceAfter = 80,
                                lineSpacing = lineSpacing,
                                jc = "both",
                                keepNext = false,
                                keepLines = false,
                                indentLeft = 360,
                                indentHanging = 180
                            )
                        )
                        paragraphsCreated++
                    }
                    "fig_caption" -> {
                        bodyEl.appendChild(
                            buildStyledParagraph(
                                doc = docDom,
                                text = block.text,
                                font = font,
                                szHalfPt = (bodySz - 3).coerceAtLeast(16),
                                colorHex = "555555",
                                bold = false,
                                italic = true,
                                spaceBefore = 60,
                                spaceAfter = 180,
                                lineSpacing = 240,
                                jc = "center",
                                keepNext = false,
                                keepLines = true
                            )
                        )
                        paragraphsCreated++
                    }
                    "tbl_caption" -> {
                        bodyEl.appendChild(
                            buildStyledParagraph(
                                doc = docDom,
                                text = block.text,
                                font = font,
                                szHalfPt = (bodySz - 2).coerceAtLeast(16),
                                colorHex = h1Color,
                                bold = true,
                                italic = false,
                                spaceBefore = 180,
                                spaceAfter = 80,
                                lineSpacing = 240,
                                jc = "left",
                                keepNext = true,
                                keepLines = true
                            )
                        )
                        paragraphsCreated++
                    }
                    "table" -> {
                        bodyEl.appendChild(
                            buildDataTable(
                                doc = docDom,
                                headers = block.headers,
                                rows = block.rows,
                                font = font,
                                cellSz = (bodySz - 2).coerceAtLeast(16),
                                thFill = thFill,
                                thColor = thColor,
                                zebraFill = zebraFill,
                                borderColor = borderColor,
                                bodyColor = bodyColor
                            )
                        )
                        tablesCreated++
                    }
                    "image" -> {
                        val imgBytes = mediaMap[block.mediaName]
                        if (imgBytes != null) {
                            val mediaDir = File(workDir, "word/media")
                            mediaDir.mkdirs()
                            val safeMediaName = "cloned_${imagesEmbedded + 1}_${block.mediaName}"
                            FileOutputStream(File(mediaDir, safeMediaName)).use { it.write(imgBytes) }

                            val rId = addImageRelationshipSafe(workDir, safeMediaName)
                            docPrIdCounter++
                            bodyEl.appendChild(
                                buildInlineImageParagraph(
                                    doc = docDom,
                                    rId = rId,
                                    widthEmu = block.widthEmu,
                                    heightEmu = block.heightEmu,
                                    docPrId = docPrIdCounter,
                                    name = safeMediaName
                                )
                            )
                            imagesEmbedded++
                            paragraphsCreated++
                        }
                    }
                    else -> {
                        bodyEl.appendChild(
                            buildStyledParagraph(
                                doc = docDom,
                                text = block.text,
                                font = font,
                                szHalfPt = bodySz,
                                colorHex = bodyColor,
                                bold = false,
                                italic = false,
                                spaceBefore = 0,
                                spaceAfter = 140,
                                lineSpacing = lineSpacing,
                                jc = "both",
                                keepNext = false,
                                keepLines = false
                            )
                        )
                        paragraphsCreated++
                    }
                }
            }

            if (savedSectPr != null) {
                bodyEl.appendChild(savedSectPr)
            }

            writeXmlDocument(docDom, docXmlFile)
            outputFile.parentFile?.mkdirs()
            repackageDocxDirectory(workDir, outputFile)

            val result = JSONObject()
            result.put("status", "success")
            result.put("template_used", templateFile.name)
            result.put("source_used", sourceFile.name)
            result.put("output_path", outputFile.absolutePath)
            result.put("output_size_bytes", outputFile.length())
            result.put("blocks_cloned", blocks.size)
            result.put("paragraphs_created", paragraphsCreated)
            result.put("tables_created", tablesCreated)
            result.put("images_embedded", imagesEmbedded)
            result.put("applied_font", font)
            result.put("applied_h1_color", "#$h1Color")
            return result
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    private fun extractTemplateStyleDefaults(templateFile: File): JSONObject {
        val defaults = JSONObject()
        defaults.put("font", "Calibri")
        defaults.put("body_sz", 21)
        defaults.put("h1_sz", 32)
        defaults.put("h1_color", "1B365D")
        defaults.put("h2_sz", 26)
        defaults.put("h2_color", "2C5282")
        defaults.put("h3_sz", 23)
        defaults.put("h3_color", "2D3748")
        defaults.put("body_color", "222222")
        defaults.put("line_spacing", 276)

        if (!templateFile.name.lowercase().endsWith(".docx")) return defaults

        runCatching {
            ZipFile(templateFile).use { zf ->
                val stylesEntry = zf.getEntry("word/styles.xml") ?: return@use
                val dom = parseXmlStream(zf.getInputStream(stylesEntry))
                val styleNodes = dom.getElementsByTagNameNS(W_NS, "style")
                for (i in 0 until styleNodes.length) {
                    val st = styleNodes.item(i) as Element
                    val styleId = (st.getAttributeNS(W_NS, "styleId").ifEmpty { st.getAttribute("w:styleId") }).lowercase()
                    val rFonts = st.getElementsByTagNameNS(W_NS, "rFonts").item(0) as? Element
                    val asciiFont = rFonts?.getAttributeNS(W_NS, "ascii")?.ifEmpty { rFonts.getAttribute("w:ascii") }
                    val szEl = st.getElementsByTagNameNS(W_NS, "sz").item(0) as? Element
                    val szVal = szEl?.getAttributeNS(W_NS, "val")?.ifEmpty { szEl.getAttribute("w:val") }?.toIntOrNull()
                    val colorEl = st.getElementsByTagNameNS(W_NS, "color").item(0) as? Element
                    val colorVal = colorEl?.getAttributeNS(W_NS, "val")?.ifEmpty { colorEl.getAttribute("w:val") }

                    if (styleId == "normal") {
                        if (!asciiFont.isNullOrBlank()) defaults.put("font", asciiFont)
                        if (szVal != null) defaults.put("body_sz", szVal)
                        if (!colorVal.isNullOrBlank() && colorVal != "auto") defaults.put("body_color", colorVal)
                    } else if ("heading1" in styleId || styleId == "h1") {
                        if (szVal != null) defaults.put("h1_sz", szVal)
                        if (!colorVal.isNullOrBlank() && colorVal != "auto") defaults.put("h1_color", colorVal)
                    } else if ("heading2" in styleId || styleId == "h2") {
                        if (szVal != null) defaults.put("h2_sz", szVal)
                        if (!colorVal.isNullOrBlank() && colorVal != "auto") defaults.put("h2_color", colorVal)
                    }
                }
            }
        }
        return defaults
    }

    private fun buildStyledParagraph(
        doc: Document,
        text: String,
        font: String,
        szHalfPt: Int,
        colorHex: String,
        bold: Boolean,
        italic: Boolean,
        spaceBefore: Int,
        spaceAfter: Int,
        lineSpacing: Int,
        jc: String,
        keepNext: Boolean,
        keepLines: Boolean,
        indentLeft: Int? = null,
        indentHanging: Int? = null
    ): Element {
        val p = doc.createElementNS(W_NS, "w:p")
        val pPr = doc.createElementNS(W_NS, "w:pPr")

        val spacing = doc.createElementNS(W_NS, "w:spacing")
        spacing.setAttributeNS(W_NS, "w:before", spaceBefore.toString())
        spacing.setAttributeNS(W_NS, "w:after", spaceAfter.toString())
        spacing.setAttributeNS(W_NS, "w:line", lineSpacing.toString())
        spacing.setAttributeNS(W_NS, "w:lineRule", "auto")
        pPr.appendChild(spacing)

        val jcEl = doc.createElementNS(W_NS, "w:jc")
        jcEl.setAttributeNS(W_NS, "w:val", jc)
        pPr.appendChild(jcEl)

        if (indentLeft != null) {
            val ind = doc.createElementNS(W_NS, "w:ind")
            ind.setAttributeNS(W_NS, "w:left", indentLeft.toString())
            if (indentHanging != null) {
                ind.setAttributeNS(W_NS, "w:hanging", indentHanging.toString())
            }
            pPr.appendChild(ind)
        }

        if (keepNext) {
            pPr.appendChild(doc.createElementNS(W_NS, "w:keepNext"))
        }
        if (keepLines) {
            pPr.appendChild(doc.createElementNS(W_NS, "w:keepLines"))
        }

        p.appendChild(pPr)
        p.appendChild(buildRun(doc, text, font, szHalfPt, colorHex, bold, italic))
        return p
    }

    private fun buildRun(
        doc: Document,
        text: String,
        font: String,
        szHalfPt: Int,
        colorHex: String,
        bold: Boolean,
        italic: Boolean
    ): Element {
        val r = doc.createElementNS(W_NS, "w:r")
        val rPr = doc.createElementNS(W_NS, "w:rPr")

        val rFonts = doc.createElementNS(W_NS, "w:rFonts")
        rFonts.setAttributeNS(W_NS, "w:ascii", font)
        rFonts.setAttributeNS(W_NS, "w:hAnsi", font)
        rFonts.setAttributeNS(W_NS, "w:cs", font)
        rPr.appendChild(rFonts)

        val sz = doc.createElementNS(W_NS, "w:sz")
        sz.setAttributeNS(W_NS, "w:val", szHalfPt.toString())
        rPr.appendChild(sz)

        val szCs = doc.createElementNS(W_NS, "w:szCs")
        szCs.setAttributeNS(W_NS, "w:val", szHalfPt.toString())
        rPr.appendChild(szCs)

        val color = doc.createElementNS(W_NS, "w:color")
        color.setAttributeNS(W_NS, "w:val", colorHex.removePrefix("#"))
        rPr.appendChild(color)

        if (bold) {
            rPr.appendChild(doc.createElementNS(W_NS, "w:b"))
        }
        if (italic) {
            rPr.appendChild(doc.createElementNS(W_NS, "w:i"))
        }

        r.appendChild(rPr)

        val t = doc.createElementNS(W_NS, "w:t")
        if (text.startsWith(" ") || text.endsWith(" ")) {
            t.setAttribute("xml:space", "preserve")
        }
        t.textContent = text
        r.appendChild(t)
        return r
    }

    private fun buildDataTable(
        doc: Document,
        headers: List<String>,
        rows: List<List<String>>,
        font: String,
        cellSz: Int,
        thFill: String,
        thColor: String,
        zebraFill: String,
        borderColor: String,
        bodyColor: String
    ): Element {
        val tbl = doc.createElementNS(W_NS, "w:tbl")
        val tblPr = doc.createElementNS(W_NS, "w:tblPr")

        val tblW = doc.createElementNS(W_NS, "w:tblW")
        tblW.setAttributeNS(W_NS, "w:w", "5000")
        tblW.setAttributeNS(W_NS, "w:type", "pct")
        tblPr.appendChild(tblW)

        val tblBorders = doc.createElementNS(W_NS, "w:tblBorders")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            val b = doc.createElementNS(W_NS, "w:$side")
            b.setAttributeNS(W_NS, "w:val", "single")
            b.setAttributeNS(W_NS, "w:sz", "4")
            b.setAttributeNS(W_NS, "w:space", "0")
            b.setAttributeNS(W_NS, "w:color", borderColor)
            tblBorders.appendChild(b)
        }
        tblPr.appendChild(tblBorders)
        tbl.appendChild(tblPr)

        val colCount = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
        val colWidthTwips = 9360 / colCount

        if (headers.isNotEmpty()) {
            val tr = doc.createElementNS(W_NS, "w:tr")
            val trPr = doc.createElementNS(W_NS, "w:trPr")
            trPr.appendChild(doc.createElementNS(W_NS, "w:tblHeader"))
            trPr.appendChild(doc.createElementNS(W_NS, "w:cantSplit"))
            tr.appendChild(trPr)

            for (c in 0 until colCount) {
                val cellText = headers.getOrElse(c) { "" }
                tr.appendChild(
                    buildTableCell(
                        doc = doc,
                        text = cellText,
                        widthTwips = colWidthTwips,
                        fillHex = thFill,
                        textColorHex = thColor,
                        font = font,
                        szHalfPt = cellSz,
                        bold = true
                    )
                )
            }
            tbl.appendChild(tr)
        }

        for ((rIdx, row) in rows.withIndex()) {
            val tr = doc.createElementNS(W_NS, "w:tr")
            val trPr = doc.createElementNS(W_NS, "w:trPr")
            trPr.appendChild(doc.createElementNS(W_NS, "w:cantSplit"))
            tr.appendChild(trPr)

            val bgFill = if (rIdx % 2 == 1) zebraFill else "FFFFFF"
            for (c in 0 until colCount) {
                val cellText = row.getOrElse(c) { "" }
                tr.appendChild(
                    buildTableCell(
                        doc = doc,
                        text = cellText,
                        widthTwips = colWidthTwips,
                        fillHex = bgFill,
                        textColorHex = bodyColor,
                        font = font,
                        szHalfPt = cellSz,
                        bold = false
                    )
                )
            }
            tbl.appendChild(tr)
        }

        return tbl
    }

    private fun buildTableCell(
        doc: Document,
        text: String,
        widthTwips: Int,
        fillHex: String,
        textColorHex: String,
        font: String,
        szHalfPt: Int,
        bold: Boolean
    ): Element {
        val tc = doc.createElementNS(W_NS, "w:tc")
        val tcPr = doc.createElementNS(W_NS, "w:tcPr")

        val tcW = doc.createElementNS(W_NS, "w:tcW")
        tcW.setAttributeNS(W_NS, "w:w", widthTwips.toString())
        tcW.setAttributeNS(W_NS, "w:type", "dxa")
        tcPr.appendChild(tcW)

        val shd = doc.createElementNS(W_NS, "w:shd")
        shd.setAttributeNS(W_NS, "w:val", "clear")
        shd.setAttributeNS(W_NS, "w:color", "auto")
        shd.setAttributeNS(W_NS, "w:fill", fillHex)
        tcPr.appendChild(shd)

        tc.appendChild(tcPr)
        tc.appendChild(
            buildStyledParagraph(
                doc = doc,
                text = text,
                font = font,
                szHalfPt = szHalfPt,
                colorHex = textColorHex,
                bold = bold,
                italic = false,
                spaceBefore = 40,
                spaceAfter = 40,
                lineSpacing = 240,
                jc = "left",
                keepNext = false,
                keepLines = true
            )
        )
        return tc
    }

    private fun addImageRelationshipSafe(workDir: File, mediaFilename: String): String {
        val relsFile = File(workDir, "word/_rels/document.xml.rels")
        relsFile.parentFile?.mkdirs()
        val relsDom = if (relsFile.exists()) {
            FileInputStream(relsFile).use { parseXmlStream(it) }
        } else {
            val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val d = dbf.newDocumentBuilder().newDocument()
            val root = d.createElementNS(PKG_REL_NS, "Relationships")
            d.appendChild(root)
            d
        }

        val relNodes = relsDom.getElementsByTagName("Relationship")
        var maxId = 0
        for (i in 0 until relNodes.length) {
            val rEl = relNodes.item(i) as Element
            val idStr = rEl.getAttribute("Id")
            if (idStr.startsWith("rId")) {
                val num = idStr.removePrefix("rId").toIntOrNull() ?: 0
                if (num > maxId) maxId = num
            }
        }
        val newRId = "rId${maxId + 1}"
        val newRel = relsDom.createElementNS(PKG_REL_NS, "Relationship")
        newRel.setAttribute("Id", newRId)
        newRel.setAttribute("Type", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image")
        newRel.setAttribute("Target", "media/$mediaFilename")
        relsDom.documentElement.appendChild(newRel)
        writeXmlDocument(relsDom, relsFile)

        val ctFile = File(workDir, "[Content_Types].xml")
        if (ctFile.exists()) {
            val ext = mediaFilename.substringAfterLast('.', "png").lowercase()
            val mime = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                else -> "image/png"
            }
            val ctDom = FileInputStream(ctFile).use { parseXmlStream(it) }
            val defaults = ctDom.getElementsByTagName("Default")
            var hasExt = false
            for (i in 0 until defaults.length) {
                val dEl = defaults.item(i) as Element
                if (dEl.getAttribute("Extension").lowercase() == ext) {
                    hasExt = true
                    break
                }
            }
            if (!hasExt) {
                val defEl = ctDom.createElementNS(CT_NS, "Default")
                defEl.setAttribute("Extension", ext)
                defEl.setAttribute("ContentType", mime)
                ctDom.documentElement.appendChild(defEl)
                writeXmlDocument(ctDom, ctFile)
            }
        }

        return newRId
    }

    private fun buildInlineImageParagraph(
        doc: Document,
        rId: String,
        widthEmu: Long,
        heightEmu: Long,
        docPrId: Int,
        name: String
    ): Element {
        val drawingXml = """
            <w:p xmlns:w="$W_NS" xmlns:wp="$WP_NS" xmlns:a="$A_NS" xmlns:pic="$PIC_NS" xmlns:r="$R_NS">
              <w:pPr>
                <w:spacing w:before="160" w:after="80" w:line="240" w:lineRule="auto"/>
                <w:jc w:val="center"/>
                <w:keepNext/>
              </w:pPr>
              <w:r>
                <w:drawing>
                  <wp:inline distT="0" distB="0" distL="0" distR="0">
                    <wp:extent cx="$widthEmu" cy="$heightEmu"/>
                    <wp:docPr id="$docPrId" name="$name"/>
                    <wp:cNvGraphicFramePr>
                      <a:graphicFrameLocks noChangeAspect="1"/>
                    </wp:cNvGraphicFramePr>
                    <a:graphic>
                      <a:graphicData uri="$PIC_NS">
                        <pic:pic>
                          <pic:nvPicPr>
                            <pic:cNvPr id="$docPrId" name="$name"/>
                            <pic:cNvPicPr/>
                          </pic:nvPicPr>
                          <pic:blipFill>
                            <a:blip r:embed="$rId"/>
                            <a:stretch><a:fillRect/></a:stretch>
                          </pic:blipFill>
                          <pic:spPr>
                            <a:xfrm>
                              <a:off x="0" y="0"/>
                              <a:ext cx="$widthEmu" cy="$heightEmu"/>
                            </a:xfrm>
                            <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                          </pic:spPr>
                        </pic:pic>
                      </a:graphicData>
                    </a:graphic>
                  </wp:inline>
                </w:drawing>
              </w:r>
            </w:p>
        """.trimIndent()
        val parsed = parseXmlStream(ByteArrayInputStream(drawingXml.toByteArray(Charsets.UTF_8)))
        return doc.importNode(parsed.documentElement, true) as Element
    }

    private fun parseSpecOverridesFromScript(scriptCode: String): JSONObject {
        val spec = JSONObject()
        val regex = Regex("""["'](font|body_sz|h1_sz|h1_color|h2_sz|h2_color|h3_sz|h3_color|body_color|line_spacing|th_fill|th_color|zebra_fill|border_color)["']\s*:\s*["']?([^"',}\n]+)["']?""")
        for (match in regex.findAll(scriptCode)) {
            val key = match.groupValues[1]
            val rawVal = match.groupValues[2].trim()
            val intVal = rawVal.toIntOrNull()
            if (intVal != null) {
                spec.put(key, intVal)
            } else {
                spec.put(key, rawVal)
            }
        }
        return spec
    }

    private fun repackageDocxDirectory(workDir: File, outputDocx: File) {
        ZipOutputStream(FileOutputStream(outputDocx)).use { zos ->
            val allFiles = workDir.walkTopDown().filter { it.isFile }.toList()
            for (file in allFiles) {
                val relPath = file.relativeTo(workDir).invariantSeparatorsPath
                val entry = ZipEntry(relPath)
                zos.putNextEntry(entry)
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }

    private fun parseXmlStream(inputStream: java.io.InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        return factory.newDocumentBuilder().parse(inputStream)
    }

    private fun writeXmlDocument(doc: Document, outFile: File) {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.STANDALONE, "yes")
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        FileOutputStream(outFile).use { fos ->
            transformer.transform(DOMSource(doc), StreamResult(fos))
            fos.fd.sync()
        }
    }

    private fun extractElementText(element: Element): String {
        val sb = StringBuilder()
        val tNodes = element.getElementsByTagNameNS(W_NS, "t")
        for (i in 0 until tNodes.length) {
            sb.append(tNodes.item(i).textContent ?: "")
        }
        return sb.toString()
    }
}
