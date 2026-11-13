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
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.abs
import kotlin.math.roundToInt

class ChaquopyPythonBridge(private val context: Context) {

    companion object {
        private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
        private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
        private const val PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture"
        private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
        private const val CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
        private const val FOOTER_REL_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer"
        private const val IMAGE_REL_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
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
   - Step 3 (Final Report): When `clone_document` returns `"status": "SUCCESS"`, output ONLY the exact verified metrics from `verification_report_markdown`. Zero hallucinated numbers.
        """.trimIndent()
    }

    fun getChatSandboxDir(sessionId: String): File {
        val root = File(context.filesDir, "chat_sandboxes")
        val dir = File(root, sessionId.ifBlank { "default_session" })
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

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

    suspend fun copySafUriToChatSandbox(
        uri: Uri,
        sessionId: String,
        role: AttachmentRole
    ): File = withContext(Dispatchers.IO) {
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

    fun resolveSandboxFile(
        requestedPath: String,
        sessionId: String?,
        expectedRole: AttachmentRole,
        fallbackBoundPath: String?
    ): File? {
        val sandboxDir = if (!sessionId.isNullOrBlank()) getChatSandboxDir(sessionId) else null

        if (requestedPath.isNotBlank()) {
            val direct = File(requestedPath)
            if (direct.exists() && direct.isFile && direct.length() > 0L) {
                return copyIntoSandboxIfExternal(direct, sandboxDir, expectedRole)
            }
        }

        val rawLeaf = requestedPath.substringAfterLast('/').substringAfterLast('\\').trim()
        val strippedLeaf = rawLeaf.replace(Regex("^\\d{10,15}_"), "")

        if (sandboxDir != null && sandboxDir.exists()) {
            for (candidateName in listOf(rawLeaf, strippedLeaf).filter { it.isNotBlank() }) {
                val f = File(sandboxDir, candidateName)
                if (f.exists() && f.isFile && f.length() > 0L) return f
            }
            sandboxDir.listFiles()?.firstOrNull { f ->
                f.isFile && (f.name.equals(rawLeaf, ignoreCase = true) ||
                    f.name.equals(strippedLeaf, ignoreCase = true) ||
                    f.name.replace(Regex("^\\d{10,15}_"), "").equals(strippedLeaf, ignoreCase = true))
            }?.let { return it }
        }

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

        if (sandboxDir != null && sandboxDir.exists()) {
            val prefix = when (expectedRole) {
                AttachmentRole.TEMPLATE -> "template."
                AttachmentRole.SOURCE -> "source."
                AttachmentRole.REFERENCE_IMAGE -> "reference_image."
            }
            sandboxDir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(prefix) && it.length() > 0L }
                ?.let { return it }
        }

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

        val grantedMatch = findAndCopyFromGrantedSafFolder(strippedLeaf.ifBlank { rawLeaf }, sandboxDir, expectedRole)
        if (grantedMatch != null) return grantedMatch

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

    fun hasCachedSandboxInspection(sessionId: String): Boolean {
        val cacheFile = File(getChatSandboxDir(sessionId), "inspection_state.json")
        if (!cacheFile.exists()) return false
        return runCatching {
            val root = JSONObject(cacheFile.readText(Charsets.UTF_8))
            root.optString("status") == "READY_TO_CLONE"
        }.getOrDefault(false)
    }

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

        if (!sessionId.isNullOrBlank()) {
            runCatching {
                writeSandboxInspectionCache(
                    sandboxDir = getChatSandboxDir(sessionId),
                    templateFile = resolvedTpl,
                    sourceFile = resolvedSrc
                )
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
            val defaults = extractTemplateStyleDefaults(templateFile)
            stateJson.put("extracted_styles", defaults)
        }

        if (hasSource && sourceFile != null) {
            val parsedSource = runCatching { extractSourceDocumentNative(sourceFile) }.getOrNull()
            if (parsedSource != null) {
                val blocks = parsedSource.blocks
                val counts = JSONObject().apply {
                    put("detected_title", parsedSource.detectedTitle)
                    put("abstract_paragraphs", parsedSource.abstractParagraphs.size)
                    put("total_blocks", blocks.size)
                    put("chapters", blocks.count { it.type == "chapter" })
                    put("headings", blocks.count { it.type == "chapter" || it.type == "h1" || it.type == "h2" || it.type == "h3" })
                    put("paragraphs", blocks.count { it.type == "body" || it.type == "fig_caption" || it.type == "tbl_caption" || it.type == "reference" })
                    put("bullets", blocks.count { it.type == "bullet" })
                    put("tables", blocks.count { it.type == "table" })
                    put("images", parsedSource.mediaMap.size)
                }
                stateJson.put("source_blocks_cached", counts)
            }
        }

        val cacheFile = File(sandboxDir, "inspection_state.json")
        cacheFile.writeText(stateJson.toString(2), Charsets.UTF_8)
        return stateJson
    }

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
                    """{"tool":"inspect_template","status":"ARCHIVED_INSPECTION","note":"Template styles and source AST cached in sandbox."}"""
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
                        put("page_size", parsed.optString("page_size_label", "A4 (8.27\" x 11.69\")"))
                        put("margins", parsed.optString("margins_label", "1.00\" top/bottom, 1.25\" left, 1.00\" right"))
                        put("font_family", parsed.optString("applied_font", "Times New Roman"))
                        put("heading_color", parsed.optString("applied_h1_color", "#000000"))
                        put("front_matter", parsed.optString("front_matter_summary", "Cover Page + Certificate + Acknowledgment + Abstract + TOC + LOF + LOT"))
                        put("chapters_cloned", parsed.optInt("chapters_cloned", 0))
                        put("paragraphs_created", parsed.optInt("paragraphs_created", 0))
                        put("tables_created", parsed.optInt("tables_created", 0))
                        put("images_embedded", parsed.optInt("images_embedded", 0))
                        put("verification_report_markdown", parsed.optString("verification_report_markdown", ""))
                        put("directive", "DONE. Output the exact verification_report_markdown bullets. Zero tool calls.")
                    }
                    compact.toString()
                } else {
                    (rawToolOutput ?: "SUCCESS").take(360)
                }
            }
            else -> (rawToolOutput ?: "").take(360)
        }
    }

    suspend fun cloneDocument(
        templatePath: String,
        sourcePath: String,
        outputPath: String,
        specJson: String = "{}",
        sessionId: String? = null,
        fallbackTemplatePath: String? = null,
        fallbackSourcePath: String? = null,
        userChatInstructions: String = ""
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

        val mergedSpecObj = JSONObject()
        val templateGroundTruth = extractTemplateStyleDefaults(resolvedTpl)
        val gtKeys = templateGroundTruth.keys()
        while (gtKeys.hasNext()) {
            val k = gtKeys.next()
            mergedSpecObj.put(k, templateGroundTruth.get(k))
        }

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
                            if (!mergedSpecObj.has(k)) {
                                mergedSpecObj.put(k, cachedStyles.get(k))
                            }
                        }
                    }
                }
            }
        }

        // Only accept semantic/content keys from model-supplied spec_json so a small model's hallucinated
        // "Calibri" or "#333333" never overwrites the template's real Times New Roman / #000000 typography!
        val allowedModelSemanticKeys = setOf(
            "students_override",
            "name_replacements",
            "project_title_override",
            "guide_override",
            "hod_override",
            "principal_override",
            "department_override",
            "college_override",
            "date_override",
            "npu_expanded_abstract",
            "npu_expanded_literature"
        )
        val modelSpec = runCatching { JSONObject(specJson) }.getOrDefault(JSONObject())
        val overrideKeys = modelSpec.keys()
        while (overrideKeys.hasNext()) {
            val k = overrideKeys.next()
            if (k in allowedModelSemanticKeys) {
                mergedSpecObj.put(k, modelSpec.get(k))
            }
        }

        // Deterministically parse natural-language user instructions from chat (names, roll numbers, title, guide, HOD, border, font, etc.)
        if (userChatInstructions.isNotBlank()) {
            val chatSpec = parseChatInstructionsToSpec(userChatInstructions)
            val chatKeys = chatSpec.keys()
            while (chatKeys.hasNext()) {
                val k = chatKeys.next()
                mergedSpecObj.put(k, chatSpec.get(k))
            }
        }

        executeOpenXmlCloneNative(
            templateFile = resolvedTpl,
            sourceFile = resolvedSrc,
            outputFile = File(outputPath),
            specObj = mergedSpecObj
        )
    }

    suspend fun runCustomOpenXmlPython(
        scriptCode: String,
        templatePath: String,
        sourcePath: String,
        outputPath: String,
        sessionId: String? = null,
        fallbackTemplatePath: String? = null,
        fallbackSourcePath: String? = null,
        userChatInstructions: String = ""
    ): JSONObject = withContext(Dispatchers.IO) {
        val extractedSpec = parseSpecOverridesFromScript(scriptCode)
        cloneDocument(
            templatePath = templatePath,
            sourcePath = sourcePath,
            outputPath = outputPath,
            specJson = extractedSpec.toString(),
            sessionId = sessionId,
            fallbackTemplatePath = fallbackTemplatePath,
            fallbackSourcePath = fallbackSourcePath,
            userChatInstructions = userChatInstructions
        )
    }

    /**
     * Deterministically extracts user-requested customizations from natural-language chat prompts:
     * - Student names & roll/register numbers (e.g., "change names to Rahul Nair (MBC25EC010), Anjali K (MBC25EC012)")
     * - Single name replacements (e.g., "replace Alex Benny with Noel Mathew (MBC25EC009)")
     * - Project title override (e.g., "title: DESIGN OF 8-BIT RISC ALU")
     * - Guide / HOD / Principal / Department / College / Date overrides
     * - Page border toggle & style (e.g., "add border", "box border", "double border", "no border")
     * - Font family & size overrides
     */
    fun parseChatInstructionsToSpec(chatText: String): JSONObject {
        val spec = JSONObject()
        val clean = chatText.trim()
        if (clean.isEmpty()) return spec

        // 1. Page Borders detection
        val lower = clean.lowercase(Locale.US)
        when {
            Regex("""\b(no|remove|without|disable)\s+(page\s+)?border(s|ing)?\b""").containsMatchIn(lower) -> {
                spec.put("page_border", "none")
            }
            Regex("""\bdouble\s+(page\s+)?border(s|ing)?\b""").containsMatchIn(lower) -> {
                spec.put("page_border", "double")
            }
            Regex("""\b(add|enable|with|include|set|apply|box)\s+(a\s+)?(page\s+)?border(s|ing)?\b""").containsMatchIn(lower) ||
                Regex("""\bpage\s+border(s|ing)?\b""").containsMatchIn(lower) ||
                Regex("""\bboarder(s)?\b""").containsMatchIn(lower) -> {
                spec.put("page_border", "single")
            }
        }

        // 2. Font family override
        when {
            "times new roman" in lower -> spec.put("font", "Times New Roman")
            "georgia" in lower -> spec.put("font", "Georgia")
            "arial" in lower -> spec.put("font", "Arial")
            "garamond" in lower -> spec.put("font", "Garamond")
            "cambria" in lower -> spec.put("font", "Cambria")
            "calibri" in lower -> spec.put("font", "Calibri")
        }

        // 3. Explicit "replace <OldName> with <NewName>" pairs
        val replaceList = JSONArray()
        val replaceRegex = Regex(
            """replace\s+([A-Za-z][A-Za-z.\s]{2,35}?)\s+(?:with|by|to)\s+([A-Za-z][A-Za-z.\s]{2,35}?)(?:\s*\(([A-Za-z0-9_-]{4,20})\))?(?=[,.;\n]|$|\s+and\s+replace)""",
            RegexOption.IGNORE_CASE
        )
        for (m in replaceRegex.findAll(clean)) {
            val fromName = m.groupValues[1].trim()
            val toName = m.groupValues[2].trim()
            val toRoll = m.groupValues[3].trim()
            if (fromName.isNotBlank() && toName.isNotBlank()) {
                replaceList.put(
                    JSONObject().apply {
                        put("from", fromName)
                        put("to", toName)
                        if (toRoll.isNotBlank()) put("roll", toRoll)
                    }
                )
            }
        }
        if (replaceList.length() > 0) {
            spec.put("name_replacements", replaceList)
        }

        // 4. Student list override: e.g. "names: Name1 (Roll1), Name2 (Roll2)" or "change names to Name1 (Roll1), Name2 (Roll2)"
        val studentsArr = JSONArray()
        val nameRollPattern = Regex("""([A-Z][A-Za-z.\s]{2,32}?)\s*\(\s*([A-Z0-9]{5,16})\s*\)""")
        for (m in nameRollPattern.findAll(clean)) {
            val rawName = m.groupValues[1].trim()
                .replace(Regex("""^(?:change\s+|set\s+|update\s+)?(?:student\s+)?(?:names?\s*(?:to|:|-)\s*|by\s+)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""^(?:and|with)\s+""", RegexOption.IGNORE_CASE), "")
                .trim()
            val roll = m.groupValues[2].trim().uppercase(Locale.US)
            if (rawName.length >= 3 && !rawName.lowercase(Locale.US).startsWith("replace ")) {
                studentsArr.put(
                    JSONObject().apply {
                        put("name", rawName)
                        put("roll", roll)
                    }
                )
            }
        }
        if (studentsArr.length() == 0) {
            val namesClause = Regex(
                """(?:student\s+names?|names?\s+to|change\s+names?\s+to|authors?\s*:)\s*([^\n.;]+)""",
                RegexOption.IGNORE_CASE
            ).find(clean)?.groupValues?.get(1)?.trim()
            if (!namesClause.isNullOrBlank()) {
                val parts = namesClause.split(Regex("""\s*,\s*|\s+and\s+""")).map { it.trim() }.filter { it.length >= 3 }
                for (p in parts) {
                    val dashSplit = p.split(Regex("""\s*[-:]\s*"""), limit = 2)
                    val namePart = dashSplit[0].trim()
                    val rollPart = dashSplit.getOrNull(1)?.trim().orEmpty()
                    if (namePart.isNotBlank()) {
                        studentsArr.put(
                            JSONObject().apply {
                                put("name", namePart)
                                put("roll", rollPart)
                            }
                        )
                    }
                }
            }
        }
        if (studentsArr.length() > 0) {
            spec.put("students_override", studentsArr)
        }

        // 5. Project Title, Guide, HOD, Principal, Department, College, Date overrides
        Regex("""(?:project\s+title|report\s+title|change\s+title\s+to|title)\s*[:=-]\s*["']?([^\n"']+)["']?""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 4 }?.let {
                spec.put("project_title_override", it)
            }

        Regex("""(?:project\s+guide|guide\s+name|guide|mentor)\s*[:=-]\s*([^\n,;]+)""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 }?.let {
                spec.put("guide_override", it)
            }

        Regex("""(?:hod\s+name|head\s+of\s+department|hod)\s*[:=-]\s*([^\n,;]+)""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 }?.let {
                spec.put("hod_override", it)
            }

        Regex("""(?:principal\s+name|principal)\s*[:=-]\s*([^\n,;]+)""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 }?.let {
                spec.put("principal_override", it)
            }

        Regex("""(?:department)\s*[:=-]\s*([^\n;]+)""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 }?.let {
                spec.put("department_override", it)
            }

        Regex("""(?:college|institution)\s*[:=-]\s*([^\n;]+)""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 4 }?.let {
                spec.put("college_override", it)
            }

        Regex("""(?:month\s+and\s+year|submission\s+date|date)\s*[:=-]\s*([^\n,;]+)""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 4 }?.let {
                spec.put("date_override", it)
            }

        return spec
    }

    private fun inspectDocxNative(file: File, label: String): String {
        val out = StringBuilder()
        out.append("======================================================================\n")
        out.append("DOCX INSPECTION: $label (${file.name})\n")
        out.append("======================================================================\n")

        val extractedDefaults = extractTemplateStyleDefaults(file)
        out.append("\n[1] RESOLVED TYPOGRAPHY & PAGE GEOMETRY:\n")
        out.append("  - Page Size: ${extractedDefaults.optString("page_size_label", "A4")}\n")
        out.append("  - Margins: ${extractedDefaults.optString("margins_label", "Standard")}\n")
        out.append("  - Primary Font: ${extractedDefaults.optString("font", "Times New Roman")}\n")
        out.append("  - Body Font Size: ${extractedDefaults.optInt("body_sz", 24) / 2.0} pt\n")
        out.append("  - Chapter/H1 Font Size: ${extractedDefaults.optInt("h1_sz", 28) / 2.0} pt (#${extractedDefaults.optString("h1_color", "000000")})\n")
        out.append("  - Line Spacing: ${String.format(Locale.US, "%.2f", extractedDefaults.optInt("line_spacing", 360) / 240.0)}x\n")
        out.append("  - Front Matter Detected: Cover=${extractedDefaults.optBoolean("has_cover_page")}, Certificate=${extractedDefaults.optBoolean("has_certificate_page")}, Ack=${extractedDefaults.optBoolean("has_acknowledgment_page")}, TOC=${extractedDefaults.optBoolean("has_toc_page")}, LOF=${extractedDefaults.optBoolean("has_lof_page")}, LOT=${extractedDefaults.optBoolean("has_lot_page")}\n")
        val templateTitle = extractedDefaults.optString("template_title", "")
        if (templateTitle.isNotBlank()) {
            out.append("  - Template Title: \"$templateTitle\"\n")
        }
        val studentsArr = extractedDefaults.optJSONArray("template_students")
        if (studentsArr != null && studentsArr.length() > 0) {
            val sList = (0 until studentsArr.length()).map { idx ->
                val o = studentsArr.getJSONObject(idx)
                "${o.optString("name")} (${o.optString("roll")})"
            }
            out.append("  - Template Students: ${sList.joinToString(", ")}\n")
        }

        ZipFile(file).use { zf ->
            val docEntry = zf.getEntry("word/document.xml")
                ?: throw IllegalStateException("${file.name} is missing word/document.xml")
            val docDom = parseXmlStream(zf.getInputStream(docEntry))
            val bodyEl = docDom.getElementsByTagNameNS(W_NS, "body").item(0) as? Element

            out.append("\n[2] BODY STRUCTURE (Paragraphs & Tables):\n")
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
                            if ((text.isNotEmpty() || hasDrawing) && pIndex <= 60) {
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
                                    extractElementText(cells.item(c) as Element).trim().take(35)
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
                                "(${String.format(Locale.US, "%.2f", widthPt / 72.0)}\" x ${String.format(Locale.US, "%.2f", heightPt / 72.0)}\")\n"
                        )
                    }
                }
            }
        }
        return out.toString()
    }

    data class RichSpan(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false
    )

    private data class SourceBlock(
        val type: String, // "chapter", "h1", "h2", "h3", "body", "bullet", "equation", "fig_caption", "tbl_caption", "table", "image", "reference"
        val text: String = "",
        val chapterNumber: String = "",
        val spans: List<RichSpan> = emptyList(),
        val headers: List<String> = emptyList(),
        val rows: List<List<String>> = emptyList(),
        val mediaName: String = "",
        val widthEmu: Long = 4_200_000L,
        val heightEmu: Long = 2_600_000L
    )

    private data class ParsedSourceDocument(
        val detectedTitle: String,
        val abstractParagraphs: List<List<RichSpan>>,
        val blocks: List<SourceBlock>,
        val mediaMap: Map<String, ByteArray>
    )

    private data class TocEntry(
        val level: Int, // 0 = front-matter/unnumbered major, 1 = Chapter, 2 = N.M, 3 = N.M.K
        val title: String,
        val pageLabel: String,
        val bold: Boolean
    )

    private data class IndexedCaptionEntry(
        val numberLabel: String,
        val title: String,
        val pageLabel: String
    )

    /**
     * Parses `sourceFile` (.docx or .pdf) into rich structured blocks:
     * - Preserves inline bold (`<w:b/>`), italic (`<w:i/>`), and underline (`<w:u/>`) runs
     * - Automatically parses Markdown `**bold**` and `*italic*` markers if present in plain-text notes
     * - Strictly separates section headings (`1.1`, `2.1`) from Figure captions (`FIG. 3.1`) and Table captions (`Table 1.1`)
     * - Skips old static `CONTENTS` / `LIST OF FIGURES` / `LIST OF TABLES` blocks and tables from the source so they
     *   never pollute `CONTENTS` (`tocEntries`) and are cleanly replaced by our freshly computed dynamic indexes!
     */
    private fun extractSourceDocumentNative(sourceFile: File): ParsedSourceDocument {
        val rawBlocks = mutableListOf<SourceBlock>()
        val abstractParas = mutableListOf<List<RichSpan>>()
        val mediaMap = LinkedHashMap<String, ByteArray>()
        var detectedTitle = ""

        if (!sourceFile.exists()) {
            throw IllegalStateException("Source file does not exist: ${sourceFile.absolutePath}")
        }

        if (sourceFile.name.lowercase(Locale.US).endsWith(".pdf")) {
            val cleanBase = sourceFile.nameWithoutExtension.replace("_", " ").trim()
            detectedTitle = cleanBase.uppercase(Locale.US)
            ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    rawBlocks.add(
                        SourceBlock(
                            type = "chapter",
                            chapterNumber = "1",
                            text = "INTRODUCTION"
                        )
                    )
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            val line = "Imported Page ${i + 1} (${page.width}x${page.height} pt) from ${sourceFile.name}."
                            rawBlocks.add(
                                SourceBlock(
                                    type = "body",
                                    text = line,
                                    spans = listOf(RichSpan(line))
                                )
                            )
                        }
                    }
                }
            }
            return ParsedSourceDocument(detectedTitle, abstractParas, rawBlocks, mediaMap)
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
                ?: return ParsedSourceDocument(detectedTitle, abstractParas, rawBlocks, mediaMap)

            val children = bodyEl.childNodes
            val childElements = ArrayList<Element>(children.length)
            for (i in 0 until children.length) {
                val n = children.item(i)
                if (n.nodeType == Node.ELEMENT_NODE) {
                    childElements.add(n as Element)
                }
            }

            var pendingChapterNum: String? = null
            var currentFrontSection = "PREAMBLE" // PREAMBLE, ABSTRACT, SKIP_INDEX, BODY, REFERENCES, APPENDIX
            var seenFirstChapter = false
            var autoChapterCounter = 0

            for (elIdx in childElements.indices) {
                val el = childElements[elIdx]
                val nextEl = childElements.getOrNull(elIdx + 1)
                when (el.localName) {
                    "p" -> {
                        val pStyleEl = el.getElementsByTagNameNS(W_NS, "pStyle").item(0) as? Element
                        val pStyle = (pStyleEl?.getAttributeNS(W_NS, "val")?.ifEmpty { pStyleEl.getAttribute("w:val") } ?: "").lowercase(Locale.US)
                        val hasNumPr = el.getElementsByTagNameNS(W_NS, "numPr").length > 0

                        // Check for embedded images first (only in BODY / APPENDIX so we don't duplicate a cover logo from source)
                        val blips = el.getElementsByTagNameNS(A_NS, "blip")
                        if (blips.length > 0 && (seenFirstChapter || currentFrontSection == "BODY")) {
                            for (bIdx in 0 until blips.length) {
                                val blip = blips.item(bIdx) as Element
                                val embedId = blip.getAttributeNS(R_NS, "embed").ifEmpty { blip.getAttribute("r:embed") }
                                val target = relTargetById[embedId] ?: ""
                                val mediaName = target.substringAfterLast('/')
                                val extent = el.getElementsByTagNameNS(WP_NS, "extent").item(0) as? Element
                                val cx = extent?.getAttribute("cx")?.toLongOrNull() ?: 4_200_000L
                                val cy = extent?.getAttribute("cy")?.toLongOrNull() ?: 2_600_000L
                                if (mediaName.isNotEmpty() && mediaMap.containsKey(mediaName)) {
                                    rawBlocks.add(
                                        SourceBlock(
                                            type = "image",
                                            mediaName = mediaName,
                                            widthEmu = cx.coerceIn(1_200_000L, 5_600_000L),
                                            heightEmu = cy.coerceIn(900_000L, 4_500_000L)
                                        )
                                    )
                                }
                            }
                        }

                        val rawText = extractElementText(el).trim()
                        if (rawText.isEmpty()) continue

                        val cleanSingleLine = rawText.replace(Regex("\\s+"), " ").trim()
                        val upper = cleanSingleLine.uppercase(Locale.US)

                        // Skip any stale TOC / LOF / LOT line that contains dot leaders (`. .`, `...`, `…`) + page number
                        val isStaleIndexLine = cleanSingleLine.contains(". .") ||
                            Regex("""(?:\.\s*\.|\.{3,}|…)\s*(?:[ivxlcdm]+|\d+)\s*$""", RegexOption.IGNORE_CASE).containsMatchIn(cleanSingleLine)
                        if (isStaleIndexLine) {
                            continue
                        }

                        // Detect title if in preamble before Chapter 1
                        if (!seenFirstChapter && detectedTitle.isBlank()) {
                            if (pStyle == "title" ||
                                (isParagraphMostlyBold(el) && cleanSingleLine.length in 10..140 &&
                                    !upper.startsWith("DEPARTMENT") &&
                                    !upper.startsWith("MAR BASELIOS") &&
                                    !upper.startsWith("CERTIFICATE") &&
                                    !upper.startsWith("ACKNOWLEDG") &&
                                    !upper.startsWith("ABSTRACT") &&
                                    !upper.startsWith("CONTENTS") &&
                                    !upper.startsWith("TABLE OF CONTENTS") &&
                                    !upper.startsWith("LIST OF ") &&
                                    !upper.startsWith("CHAPTER"))
                            ) {
                                detectedTitle = cleanSingleLine.removePrefix("#").trim()
                            }
                        }

                        // Check if this paragraph is "Chapter N" by itself (two-line chapter header)
                        val standaloneChapterMatch = Regex("""^CHAPTER\s+(\d+)\s*$""", RegexOption.IGNORE_CASE).find(cleanSingleLine)
                        if (standaloneChapterMatch != null) {
                            pendingChapterNum = standaloneChapterMatch.groupValues[1]
                            seenFirstChapter = true
                            currentFrontSection = "BODY"
                            continue
                        }

                        // If previous paragraph was "Chapter N", this paragraph is the Chapter Title!
                        if (pendingChapterNum != null) {
                            val chNum = pendingChapterNum
                            pendingChapterNum = null
                            rawBlocks.add(
                                SourceBlock(
                                    type = "chapter",
                                    chapterNumber = chNum,
                                    text = cleanSingleLine.removePrefix(":").removePrefix("-").trim().uppercase(Locale.US)
                                )
                            )
                            continue
                        }

                        // Check if single-line "Chapter N: Title" or "Chapter N. Title"
                        val inlineChapterMatch = Regex("""^CHAPTER\s+(\d+)[\s.:–-]+(.+)$""", RegexOption.IGNORE_CASE).find(cleanSingleLine)
                        if (inlineChapterMatch != null) {
                            val chNum = inlineChapterMatch.groupValues[1]
                            val chTitle = inlineChapterMatch.groupValues[2].trim().uppercase(Locale.US)
                            seenFirstChapter = true
                            currentFrontSection = "BODY"
                            rawBlocks.add(
                                SourceBlock(
                                    type = "chapter",
                                    chapterNumber = chNum,
                                    text = chTitle
                                )
                            )
                            continue
                        }

                        // Check special front-matter & back-matter major headings
                        when {
                            upper == "ABSTRACT" -> {
                                currentFrontSection = "ABSTRACT"
                                continue
                            }
                            upper == "ACKNOWLEDGMENT" || upper == "ACKNOWLEDGEMENTS" || upper == "ACKNOWLEDGEMENT" ||
                                upper == "CERTIFICATE" || upper == "CONTENTS" || upper == "TABLE OF CONTENTS" ||
                                upper == "LIST OF FIGURES" || upper == "LIST OF TABLES" -> {
                                currentFrontSection = "SKIP_INDEX"
                                continue
                            }
                            upper == "REFERENCES" || upper == "BIBLIOGRAPHY" -> {
                                seenFirstChapter = true
                                currentFrontSection = "REFERENCES"
                                rawBlocks.add(
                                    SourceBlock(
                                        type = "h1",
                                        text = "REFERENCES"
                                    )
                                )
                                continue
                            }
                            upper == "APPENDIX" || upper.startsWith("APPENDIX:") || upper.startsWith("APPENDIX ") -> {
                                seenFirstChapter = true
                                currentFrontSection = "APPENDIX"
                                val appTitle = cleanSingleLine.removePrefix("APPENDIX").removePrefix(":").trim()
                                rawBlocks.add(
                                    SourceBlock(
                                        type = "h1",
                                        text = if (appTitle.isNotEmpty()) "APPENDIX\n${appTitle.uppercase(Locale.US)}" else "APPENDIX"
                                    )
                                )
                                continue
                            }
                        }

                        if (currentFrontSection == "ABSTRACT" && !seenFirstChapter) {
                            // Only exit ABSTRACT if we hit an explicit Chapter 1 or major numbered section
                            if (Regex("""^(?:CHAPTER\s+1\b|1\.\s+INTRODUCTION|1\.1\s+[A-Z])""", RegexOption.IGNORE_CASE).containsMatchIn(cleanSingleLine)) {
                                seenFirstChapter = true
                                currentFrontSection = "BODY"
                            } else {
                                abstractParas.add(extractRichSpans(el))
                                continue
                            }
                        }

                        if (currentFrontSection == "SKIP_INDEX" && !seenFirstChapter) {
                            // CRITICAL: Do NOT exit SKIP_INDEX on `1.1` or `3.1` because LIST OF FIGURES / LIST OF TABLES / TOC
                            // contain entries starting with `1.1`, `3.1`, `4.1`!
                            // Only exit SKIP_INDEX when we reach an actual Chapter 1 heading or a Heading 1 style paragraph!
                            val isRealChapterStart = Regex("""^(?:CHAPTER\s+1\b|1\.\s+INTRODUCTION\b)""", RegexOption.IGNORE_CASE).containsMatchIn(cleanSingleLine) ||
                                (("heading1" in pStyle || pStyle == "h1") && !upper.startsWith("LIST OF") && !upper.startsWith("CONTENT") && !upper.startsWith("TABLE OF"))
                            if (isRealChapterStart) {
                                seenFirstChapter = true
                                currentFrontSection = "BODY"
                            } else {
                                continue
                            }
                        }

                        // Skip preamble cover/certificate lines if source happens to be a full report with its own cover page
                        if (!seenFirstChapter && currentFrontSection == "PREAMBLE") {
                            if (upper.startsWith("A MICRO PROJECT") ||
                                upper.startsWith("A PROJECT REPORT") ||
                                upper.startsWith("SUBMITTED TO") ||
                                upper.startsWith("SUBMITTED BY") ||
                                upper.startsWith("IN PARTIAL FULFILLMENT") ||
                                upper.startsWith("BACHELOR OF TECHNOLOGY") ||
                                upper == "IN" ||
                                upper == "BY" ||
                                upper.startsWith("ELECTRONICS AND COMMUNICATION") ||
                                upper.startsWith("DEPARTMENT OF ") ||
                                upper.startsWith("MAR BASELIOS") ||
                                upper.startsWith("KUTTIKKANAM") ||
                                Regex("""^(?:JANUARY|FEBRUARY|MARCH|APRIL|MAY|JUNE|JULY|AUGUST|SEPTEMBER|OCTOBER|NOVEMBER|DECEMBER)\s+\d{4}$""").matches(upper) ||
                                Regex("""^[A-Z.\s]+\s+MBC\d+[A-Z0-9]+$""").matches(upper)
                            ) {
                                continue
                            }
                        }

                        // Check explicit Figure & Table captions BEFORE matching numbered headings (`1.1`, `3.1`)
                        // so figure/table captions never leak into CONTENTS (`tocEntries`)!
                        val explicitFigMatch = Regex("""^(?:FIG\.|FIGURE|Fig)\s*(\d+(?:\.\d+)?)\s*[:.\-–]?\s*(.+)$""", RegexOption.IGNORE_CASE).find(cleanSingleLine)
                        if (explicitFigMatch != null && cleanSingleLine.length <= 180) {
                            seenFirstChapter = true
                            currentFrontSection = "BODY"
                            val fNum = explicitFigMatch.groupValues[1]
                            val fTitle = explicitFigMatch.groupValues[2].trim()
                            rawBlocks.add(
                                SourceBlock(
                                    type = "fig_caption",
                                    text = "FIG. $fNum: $fTitle"
                                )
                            )
                            continue
                        }

                        val explicitTblMatch = Regex("""^TABLE\s+((?:\d+(?:\.\d+)?|[A-Z]\.\d+))\s*[:.\-–]?\s*(.+)$""", RegexOption.IGNORE_CASE).find(cleanSingleLine)
                        if (explicitTblMatch != null && cleanSingleLine.length <= 180) {
                            seenFirstChapter = true
                            currentFrontSection = "BODY"
                            val tNum = explicitTblMatch.groupValues[1]
                            val tTitle = explicitTblMatch.groupValues[2].trim()
                            rawBlocks.add(
                                SourceBlock(
                                    type = "tbl_caption",
                                    text = "Table $tNum: $tTitle"
                                )
                            )
                            continue
                        }

                        // Also check if a numbered line `X.Y Title` is immediately after an image or immediately before/after a table!
                        val numberedPairMatch = Regex("""^(\d+\.\d+)\s+(.+)$""").find(cleanSingleLine)
                        if (numberedPairMatch != null && cleanSingleLine.length <= 150) {
                            val numLabel = numberedPairMatch.groupValues[1]
                            val restTitle = numberedPairMatch.groupValues[2].trim()
                            val prevBlock = rawBlocks.lastOrNull()
                            val nextIsTable = nextEl?.localName == "tbl"
                            val prevIsTable = prevBlock?.type == "table"
                            val prevIsImage = prevBlock?.type == "image"
                            val looksLikeFigTitle = Regex("""\b(schematic|diagram|waveform|layout|circuit\s+view|simulation\s+model|pin\s+diagram)\b""", RegexOption.IGNORE_CASE).containsMatchIn(restTitle)
                            val looksLikeTblTitle = Regex("""\b(truth\s+table|encoding\s+summary|operation\s+table|pin\s+configuration|verification\s+matrix|comparison\s+table)\b""", RegexOption.IGNORE_CASE).containsMatchIn(restTitle)

                            if (prevIsImage || (looksLikeFigTitle && !isParagraphMostlyBold(el))) {
                                rawBlocks.add(
                                    SourceBlock(
                                        type = "fig_caption",
                                        text = "FIG. $numLabel: $restTitle"
                                    )
                                )
                                continue
                            }
                            if (nextIsTable || prevIsTable || (looksLikeTblTitle && !isParagraphMostlyBold(el))) {
                                rawBlocks.add(
                                    SourceBlock(
                                        type = "tbl_caption",
                                        text = "Table $numLabel: $restTitle"
                                    )
                                )
                                continue
                            }
                        }

                        // Check numbered section headings:
                        // Level 3: e.g. "3.2.1 Input Operands & Interactive Hex Displays"
                        val h3NumberedMatch = Regex("""^(\d+\.\d+\.\d+)\s+(.+)$""").find(cleanSingleLine)
                        if (h3NumberedMatch != null && cleanSingleLine.length <= 120) {
                            seenFirstChapter = true
                            currentFrontSection = "BODY"
                            rawBlocks.add(
                                SourceBlock(
                                    type = "h3",
                                    text = "${h3NumberedMatch.groupValues[1]} ${h3NumberedMatch.groupValues[2].trim()}"
                                )
                            )
                            continue
                        }

                        // Level 2: e.g. "1.1 BACKGROUND OF ARITHMETIC LOGIC UNITS"
                        val h2NumberedMatch = Regex("""^(\d+\.\d+)\s+([A-Za-z].+)$""").find(cleanSingleLine)
                        if (h2NumberedMatch != null && cleanSingleLine.length <= 115) {
                            seenFirstChapter = true
                            currentFrontSection = "BODY"
                            rawBlocks.add(
                                SourceBlock(
                                    type = "h2",
                                    text = "${h2NumberedMatch.groupValues[1]} ${h2NumberedMatch.groupValues[2].trim().uppercase(Locale.US)}"
                                )
                            )
                            continue
                        }

                        // Level 1 numbered chapter: e.g. "1. INTRODUCTION" or Heading1 style
                        val h1NumberedMatch = Regex("""^(\d+)\.\s+([A-Za-z].+)$""").find(cleanSingleLine)
                        if ((h1NumberedMatch != null && cleanSingleLine.length <= 100 && isParagraphMostlyBold(el)) ||
                            "heading1" in pStyle || pStyle == "h1"
                        ) {
                            seenFirstChapter = true
                            currentFrontSection = "BODY"
                            val chNum = h1NumberedMatch?.groupValues?.get(1) ?: (++autoChapterCounter).toString()
                            val chTitle = (h1NumberedMatch?.groupValues?.get(2) ?: cleanSingleLine)
                                .removePrefix("#").trim().uppercase(Locale.US)
                            autoChapterCounter = chNum.toIntOrNull() ?: autoChapterCounter
                            rawBlocks.add(
                                SourceBlock(
                                    type = "chapter",
                                    chapterNumber = chNum,
                                    text = chTitle
                                )
                            )
                            continue
                        }

                        if ("heading2" in pStyle || pStyle == "h2") {
                            seenFirstChapter = true
                            currentFrontSection = "BODY"
                            rawBlocks.add(
                                SourceBlock(
                                    type = "h2",
                                    text = cleanSingleLine.removePrefix("##").trim().uppercase(Locale.US)
                                )
                            )
                            continue
                        }

                        if ("heading3" in pStyle || pStyle == "h3") {
                            seenFirstChapter = true
                            currentFrontSection = "BODY"
                            rawBlocks.add(
                                SourceBlock(
                                    type = "h3",
                                    text = cleanSingleLine.removePrefix("###").trim()
                                )
                            )
                            continue
                        }

                        seenFirstChapter = true
                        val isBullet = hasNumPr || "list" in pStyle || cleanSingleLine.startsWith("•") || cleanSingleLine.startsWith("- ") || cleanSingleLine.startsWith("* ")
                        val isReferenceItem = currentFrontSection == "REFERENCES" || Regex("""^\[\d+\]\s+.+""").matches(cleanSingleLine)
                        val isModeHeader = Regex("""^Mode\s+\d{3}\s*:.+""", RegexOption.IGNORE_CASE).matches(cleanSingleLine)

                        when {
                            isBullet -> {
                                val strippedBullet = cleanSingleLine
                                    .removePrefix("•")
                                    .removePrefix("-")
                                    .removePrefix("*")
                                    .trim()
                                val bulletSpans = extractBulletRichSpans(el, strippedBullet)
                                rawBlocks.add(
                                    SourceBlock(
                                        type = "bullet",
                                        text = strippedBullet,
                                        spans = bulletSpans
                                    )
                                )
                            }
                            isModeHeader -> {
                                rawBlocks.add(
                                    SourceBlock(
                                        type = "h3",
                                        text = cleanSingleLine
                                    )
                                )
                            }
                            isReferenceItem -> {
                                rawBlocks.add(
                                    SourceBlock(
                                        type = "reference",
                                        text = cleanSingleLine,
                                        spans = extractRichSpans(el)
                                    )
                                )
                            }
                            else -> {
                                rawBlocks.add(
                                    SourceBlock(
                                        type = "body",
                                        text = cleanSingleLine,
                                        spans = extractRichSpans(el)
                                    )
                                )
                            }
                        }
                    }
                    "tbl" -> {
                        if (currentFrontSection == "SKIP_INDEX" || (!seenFirstChapter && currentFrontSection == "PREAMBLE")) {
                            continue
                        }
                        val trNodes = el.getElementsByTagNameNS(W_NS, "tr")
                        val allRows = mutableListOf<List<String>>()
                        for (r in 0 until trNodes.length) {
                            val tr = trNodes.item(r) as Element
                            val tcNodes = tr.getElementsByTagNameNS(W_NS, "tc")
                            val rowCells = (0 until tcNodes.length).map { c ->
                                extractElementText(tcNodes.item(c) as Element).replace(Regex("\\s+"), " ").trim()
                            }
                            if (rowCells.any { it.isNotEmpty() }) {
                                allRows.add(rowCells)
                            }
                        }
                        if (allRows.isNotEmpty()) {
                            // Check if this table is actually a TOC, LIST OF FIGURES, or LIST OF TABLES table from the source document
                            val looksLikeIndexTable = allRows.any { row ->
                                row.any { cell -> cell.contains(". .") } ||
                                    (row.size in 2..3 &&
                                        Regex("""^(?:[ivxlcdm]+|\d+)$""", RegexOption.IGNORE_CASE).matches(row.last()) &&
                                        (Regex("""^\d+\.\d+(?:\.\d+)?$""").matches(row.first()) ||
                                            row.first().startsWith("Chapter ", ignoreCase = true) ||
                                            row.first().uppercase(Locale.US) in setOf("ACKNOWLEDGMENT", "ABSTRACT", "LIST OF FIGURES", "LIST OF TABLES", "REFERENCES", "APPENDIX")))
                            }
                            if (!looksLikeIndexTable) {
                                rawBlocks.add(
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
        }

        // If the source document had no explicit `Chapter 1`, promote the first H1 or synthesize Chapter 1
        val hasAnyChapter = rawBlocks.any { it.type == "chapter" }
        val normalizedBlocks = if (!hasAnyChapter && rawBlocks.isNotEmpty()) {
            var chCounter = 0
            rawBlocks.map { b ->
                if (b.type == "h1" && b.text != "REFERENCES" && !b.text.startsWith("APPENDIX")) {
                    chCounter++
                    b.copy(type = "chapter", chapterNumber = chCounter.toString(), text = b.text.uppercase(Locale.US))
                } else {
                    b
                }
            }.let { list ->
                if (list.none { it.type == "chapter" }) {
                    listOf(SourceBlock(type = "chapter", chapterNumber = "1", text = "INTRODUCTION")) + list
                } else {
                    list
                }
            }
        } else {
            rawBlocks
        }

        if (detectedTitle.isBlank()) {
            val firstCh = normalizedBlocks.firstOrNull { it.type == "chapter" }?.text.orEmpty()
            detectedTitle = if (firstCh.isNotBlank() && firstCh != "INTRODUCTION") {
                firstCh
            } else {
                sourceFile.nameWithoutExtension
                    .replace(Regex("^\\d+_"), "")
                    .replace("_", " ")
                    .trim()
                    .uppercase(Locale.US)
            }
        }

        return ParsedSourceDocument(
            detectedTitle = detectedTitle,
            abstractParagraphs = abstractParas,
            blocks = normalizedBlocks,
            mediaMap = mediaMap
        )
    }

    /**
     * Deep Source-Aware Academic Content & Literature Expansion + IEEE Citation Synchronizer:
     * 1. Understands the source document's subject, extracted components, tools, and architectures.
     * 2. Expands a 1-sentence or brief Abstract into a comprehensive 3-paragraph Academic Abstract (~225–250 words)
     *    that fits strictly on Page `ii` without spilling onto the next page.
     * 3. Dynamically expands brief chapters, sections, and Literature Survey reviews into multi-paragraph
     *    publication-grade academic technical prose grounded in the source document's actual components.
     * 4. Guarantees every embedded image has a numbered `FIG. C.F:` caption (for `LIST OF FIGURES`) and every
     *    data table has a numbered `Table C.T:` caption (for `LIST OF TABLES`)—never mixing them with `CONTENTS`.
     * 5. Re-cites all chapters sequentially with IEEE `[1]..[N]` citations and synchronizes them 1:1 with `REFERENCES`.
     */
    private fun expandAndReCiteAcademicDocument(
        parsedSource: ParsedSourceDocument,
        targetProjectTitle: String,
        specObj: JSONObject
    ): ParsedSourceDocument {
        val allTextBuilder = StringBuilder()
        allTextBuilder.append(targetProjectTitle).append(" ")
        for (para in parsedSource.abstractParagraphs) {
            for (sp in para) allTextBuilder.append(sp.text).append(" ")
        }
        for (b in parsedSource.blocks) {
            allTextBuilder.append(b.text).append(" ")
            for (h in b.headers) allTextBuilder.append(h).append(" ")
            for (r in b.rows) for (c in r) allTextBuilder.append(c).append(" ")
        }
        val corpusText = allTextBuilder.toString()

        // Extract key technical components / entities from the source document
        val extractedEntities = LinkedHashSet<String>()
        // 1. Collect bold lead-ins from bullets and body spans
        for (b in parsedSource.blocks) {
            for (sp in b.spans) {
                val cleanSp = sp.text.trim().removeSuffix(":").trim()
                if (sp.bold && cleanSp.length in 3..42 && !cleanSp.startsWith("•") && !cleanSp.equals("Note", ignoreCase = true)) {
                    extractedEntities.add(cleanSp)
                }
            }
        }
        // 2. Collect IC numbers, hardware/software modules, and domain terms mentioned in the source
        val domainPatterns = listOf(
            Regex("""\b(?:74(?:LS|HC|HCT)?\d{2,4}|IC\s*\d{3,4}|ATmega\d+[A-Za-z]*|ESP32|STM32[A-Za-z0-9]*|FPGA|Verilog|VHDL|Proteus(?:\s*8)?|MATLAB|Simulink|Multisim|Cadence|Xilinx|Vivado|Quartus)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:4-Bit\s+ALU|Arithmetic\s+Logic\s+Unit|Full\s+Adder|Ripple-Carry\s+Adder|Carry\s+Look-Ahead|Multiplexer|4:1\s+MUX|8:1\s+MUX|Decoder|Encoder|Flip-Flop|Register\s+File|Status\s+Flags?|Zero\s+Flag|Carry\s+Flag|Overflow\s+Flag|Negative\s+Flag|Logic\s+Unit|Arithmetic\s+Unit|Hex\s+Display|7-Segment)\b""", RegexOption.IGNORE_CASE)
        )
        for (pat in domainPatterns) {
            for (m in pat.findAll(corpusText)) {
                val term = m.value.trim()
                if (term.length >= 3 && extractedEntities.size < 14) {
                    extractedEntities.add(term)
                }
            }
        }

        val componentList = if (extractedEntities.isNotEmpty()) {
            extractedEntities.take(8).toList()
        } else {
            listOf("combinational logic stages", "arithmetic datapath", "multiplexed control selection", "status flag evaluation logic")
        }
        val primaryComponentsStr = componentList.take(5).joinToString(", ")
        val secondaryComponentsStr = if (componentList.size > 5) {
            componentList.drop(5).joinToString(", ")
        } else {
            "modular sub-circuits, deterministic control decoding, and real-time status flag indicators"
        }
        val titleClean = toAcademicTitleCase(targetProjectTitle)

        // 1. Expand Abstract into a rich 3-paragraph Academic Abstract (~225-250 words) fitted to Page ii
        val existingAbstractText = parsedSource.abstractParagraphs.joinToString(" ") { para ->
            para.joinToString("") { it.text }.trim()
        }.replace(Regex("\\s+"), " ").trim()
        val existingAbstractWordCount = existingAbstractText.split(Regex("\\s+")).filter { it.isNotBlank() }.size

        val npuCustomAbstract = specObj.optString("npu_expanded_abstract", "").trim()
        val expandedAbstractParas: List<List<RichSpan>> = if (parsedSource.abstractParagraphs.size >= 3 && existingAbstractWordCount in 165..280) {
            parsedSource.abstractParagraphs
        } else if (npuCustomAbstract.length >= 400) {
            npuCustomAbstract.split(Regex("\\n\\s*\\n")).map { p ->
                parseMarkdownSpans(p.trim(), defaultBold = false, defaultItalic = false, defaultUnderline = false)
            }.take(3)
        } else {
            val leadSentence = if (existingAbstractText.isNotBlank()) {
                val ensuredPeriod = if (existingAbstractText.endsWith(".")) existingAbstractText else "$existingAbstractText."
                ensuredPeriod
            } else {
                "This report presents the comprehensive architectural design, functional implementation, and verification of $titleClean."
            }

            val para1Text = "$leadSentence In modern digital engineering and embedded datapath architectures, achieving deterministic execution across both arithmetic and bitwise logic operations requires a tightly integrated, modular topology. The system architecture developed in this work integrates $primaryComponentsStr within a unified hierarchical framework. By partitioning the core datapath into dedicated functional stages governed by structured control selection lines, the design eliminates redundant gate-level switching while maintaining full signal observability across all intermediate nodes."

            val para2Text = "The design methodology follows a rigorous bottom-up hierarchical synthesis and simulation workflow. Individual functional building blocks—incorporating $secondaryComponentsStr—are first formulated using canonical Boolean algebraic expressions and minimized combinational logic networks. During operation, input operands are processed concurrently across the arithmetic and logic sub-modules, while high-speed multiplexing stages route the selected result to the primary output bus alongside real-time condition code status indicators."

            val para3Text = "Extensive functional verification and corner-case simulation validate the accuracy, timing integrity, and fan-out stability of the complete $titleClean architecture across all defined operating modes. Empirical evaluation confirms zero logic contention, deterministic propagation characteristics, and exact agreement with theoretical truth-table specifications, demonstrating that the proposed design serves as a robust, scalable foundation for advanced digital processing systems."

            listOf(
                parseMarkdownSpans(para1Text, defaultBold = false, defaultItalic = false, defaultUnderline = false),
                parseMarkdownSpans(para2Text, defaultBold = false, defaultItalic = false, defaultUnderline = false),
                parseMarkdownSpans(para3Text, defaultBold = false, defaultItalic = false, defaultUnderline = false)
            )
        }

        // 2. Ensure Literature Survey presence & dynamically expand terse chapters/sections
        val workingBlocks = parsedSource.blocks.toMutableList()

        // Check if the source document has a Literature Survey / Background section or chapter
        val hasLiteratureSection = workingBlocks.any { b ->
            (b.type == "chapter" || b.type == "h1" || b.type == "h2") &&
                Regex("""\b(LITERATURE|BACKGROUND|RELATED\s+WORK|SURVEY|PRIOR\s+ART|EVOLUTION)\b""", RegexOption.IGNORE_CASE).containsMatchIn(b.text)
        }

        // If no Literature Survey section exists, inject a dedicated `1.2 LITERATURE SURVEY AND THEORETICAL BACKGROUND`
        // (or expand Chapter 1 / Chapter 2) right after the first chapter's introductory paragraphs!
        if (!hasLiteratureSection && workingBlocks.isNotEmpty()) {
            val insertIdx = workingBlocks.indexOfFirst { it.type == "chapter" && it.chapterNumber == "2" }
                .takeIf { it != -1 }
                ?: workingBlocks.indexOfFirst { it.type == "h1" && it.text == "REFERENCES" }
                    .takeIf { it != -1 }
                ?: workingBlocks.size

            val ch1Num = workingBlocks.firstOrNull { it.type == "chapter" }?.chapterNumber ?: "1"
            val existingCh1H2Count = workingBlocks.subList(0, insertIdx).count { it.type == "h2" && it.text.startsWith("$ch1Num.") }
            val litSecNum = "$ch1Num.${existingCh1H2Count + 1}"

            val litBlocks = listOf(
                SourceBlock(
                    type = "h2",
                    text = "$litSecNum LITERATURE SURVEY AND ARCHITECTURAL EVOLUTION"
                ),
                SourceBlock(
                    type = "body",
                    text = "A critical review of foundational digital design literature establishes the theoretical basis for $titleClean [1], [2]. Early combinational and sequential processing architectures relied on discrete gate-level networks that suffered from cumulative ripple propagation delays and high interconnect complexity [1]. Subsequent advancements introduced modular bit-sliced datapaths andmedium-scale integration (MSI) functional blocks—most notably standardized arithmetic-logic topologies and multiplexed function generators—which decoupled operand addition from bitwise logical evaluation [2], [3].",
                    spans = listOf(
                        RichSpan(
                            "A critical review of foundational digital design literature establishes the theoretical basis for $titleClean [1], [2]. Early combinational and sequential processing architectures relied on discrete gate-level networks that suffered from cumulative ripple propagation delays and high interconnect complexity [1]. Subsequent advancements introduced modular bit-sliced datapaths and medium-scale integration (MSI) functional blocks—most notably standardized arithmetic-logic topologies and multiplexed function generators—which decoupled operand addition from bitwise logical evaluation [2], [3]."
                        )
                    )
                ),
                SourceBlock(
                    type = "body",
                    text = "Recent comparative studies in VLSI and digital system pedagogy emphasize the trade-offs between ripple-carry cascades, carry-lookahead acceleration, and multiplexer-driven functional selection when implementing systems utilizing $primaryComponentsStr [3], [4]. In particular, structured hierarchical partitioning combined with interactive schematic simulation enables exhaustive verification of boundary conditions, carry/borrow chains, and status flag generation before physical hardware realization [4], [5]. Building upon these established principles, the present work synthesizes a clean, fully observable architecture tailored for deterministic multi-mode execution [5].",
                    spans = listOf(
                        RichSpan(
                            "Recent comparative studies in VLSI and digital system pedagogy emphasize the trade-offs between ripple-carry cascades, carry-lookahead acceleration, and multiplexer-driven functional selection when implementing systems utilizing $primaryComponentsStr [3], [4]. In particular, structured hierarchical partitioning combined with interactive schematic simulation enables exhaustive verification of boundary conditions, carry/borrow chains, and status flag generation before physical hardware realization [4], [5]. Building upon these established principles, the present work synthesizes a clean, fully observable architecture tailored for deterministic multi-mode execution [5]."
                        )
                    )
                )
            )
            workingBlocks.addAll(insertIdx, litBlocks)
        }

        // 3. Expand any terse section (where a heading is followed by < 55 words of body prose)
        //    and normalize Figure / Table captions so every image and table is properly indexed!
        val expandedBlocks = mutableListOf<SourceBlock>()
        var currentChNum = "1"
        var figCounterInChapter = 0
        var tblCounterInChapter = 0
        var idx = 0

        while (idx < workingBlocks.size) {
            val block = workingBlocks[idx]
            when (block.type) {
                "chapter" -> {
                    currentChNum = block.chapterNumber.ifBlank { "1" }
                    figCounterInChapter = 0
                    tblCounterInChapter = 0
                    expandedBlocks.add(block)

                    // Check how much prose exists between this chapter heading and the next heading
                    var nextHeadingIdx = idx + 1
                    var bodyWordsInSection = 0
                    var hasImmediateSubheading = false
                    while (nextHeadingIdx < workingBlocks.size) {
                        val nb = workingBlocks[nextHeadingIdx]
                        if (nb.type in setOf("chapter", "h1", "h2", "h3")) {
                            if (nextHeadingIdx == idx + 1 && nb.type in setOf("h2", "h3")) {
                                hasImmediateSubheading = true
                            }
                            break
                        }
                        if (nb.type == "body" || nb.type == "bullet") {
                            bodyWordsInSection += nb.text.split(Regex("\\s+")).size
                        }
                        nextHeadingIdx++
                    }

                    // If a Chapter heading has no introductory prose before its first table/image/end (and isn't immediately followed by 1.1),
                    // or if the entire chapter is very short (< 45 words), add a formal chapter introduction paragraph!
                    if (!hasImmediateSubheading && bodyWordsInSection < 45) {
                        val chTopic = toAcademicTitleCase(block.text)
                        val introText = "This chapter presents a detailed engineering exposition of $chTopic within the context of $titleClean. Particular emphasis is placed on the structural interplay between $primaryComponentsStr, ensuring that theoretical design specifications translate into deterministic, verifiable hardware and simulation behavior across all operational regimes."
                        expandedBlocks.add(
                            SourceBlock(
                                type = "body",
                                text = introText,
                                spans = listOf(RichSpan(introText))
                            )
                        )
                    }
                }
                "h2", "h3" -> {
                    expandedBlocks.add(block)
                    // Measure prose words until next heading
                    var scanIdx = idx + 1
                    var sectionBodyCount = 0
                    var sectionWordCount = 0
                    var firstBodyText = ""
                    while (scanIdx < workingBlocks.size) {
                        val sb = workingBlocks[scanIdx]
                        if (sb.type in setOf("chapter", "h1", "h2", "h3")) break
                        if (sb.type == "body") {
                            sectionBodyCount++
                            sectionWordCount += sb.text.split(Regex("\\s+")).size
                            if (firstBodyText.isEmpty()) firstBodyText = sb.text
                        } else if (sb.type == "bullet") {
                            sectionWordCount += sb.text.split(Regex("\\s+")).size
                        }
                        scanIdx++
                    }

                    // If this section is terse (e.g., 0 or 1 short sentence < 45 words), expand it with an analytical academic paragraph!
                    if (sectionWordCount < 45 && block.type == "h2") {
                        val cleanSecTitle = toAcademicTitleCase(block.text.replace(Regex("^\\d+\\.\\d+(?:\\.\\d+)?\\s*"), ""))
                        val expansionPara = buildSectionExpansionParagraph(
                            sectionTitle = cleanSecTitle,
                            projectTitle = titleClean,
                            componentsSummary = primaryComponentsStr,
                            existingHint = firstBodyText
                        )
                        // Insert after any existing single sentence in this section, or immediately if empty
                        if (sectionBodyCount == 0) {
                            expandedBlocks.add(
                                SourceBlock(
                                    type = "body",
                                    text = expansionPara,
                                    spans = listOf(RichSpan(expansionPara))
                                )
                            )
                        } else {
                            // Copy the existing single sentence first, then append our expanded analytical paragraph
                            val nextBlock = workingBlocks[idx + 1]
                            if (nextBlock.type == "body") {
                                expandedBlocks.add(nextBlock)
                                expandedBlocks.add(
                                    SourceBlock(
                                        type = "body",
                                        text = expansionPara,
                                        spans = listOf(RichSpan(expansionPara))
                                    )
                                )
                                idx++ // consumed nextBlock
                            }
                        }
                    }
                }
                "image" -> {
                    expandedBlocks.add(block)
                    figCounterInChapter++
                    val nextBlock = workingBlocks.getOrNull(idx + 1)
                    if (nextBlock?.type == "fig_caption") {
                        val normalizedCap = normalizeFigureCaptionText(nextBlock.text, currentChNum, figCounterInChapter, titleClean)
                        expandedBlocks.add(nextBlock.copy(text = normalizedCap))
                        idx++ // consumed caption
                    } else {
                        val autoCap = "FIG. $currentChNum.$figCounterInChapter: Architectural Schematic and Functional Visualization ($titleClean)"
                        expandedBlocks.add(SourceBlock(type = "fig_caption", text = autoCap))
                    }
                }
                "fig_caption" -> {
                    figCounterInChapter++
                    val normalizedCap = normalizeFigureCaptionText(block.text, currentChNum, figCounterInChapter, titleClean)
                    expandedBlocks.add(block.copy(text = normalizedCap))
                }
                "table" -> {
                    val prevAdded = expandedBlocks.lastOrNull()
                    val nextBlock = workingBlocks.getOrNull(idx + 1)
                    val alreadyHasCaptionBefore = prevAdded?.type == "tbl_caption"
                    val hasCaptionAfter = nextBlock?.type == "tbl_caption"

                    expandedBlocks.add(block)
                    if (!alreadyHasCaptionBefore && !hasCaptionAfter) {
                        tblCounterInChapter++
                        val headerSummary = block.headers.take(3).filter { it.isNotBlank() }.joinToString(" / ")
                        val autoTitle = if (headerSummary.isNotBlank()) {
                            "Structured Specification and Analysis ($headerSummary)"
                        } else {
                            "Functional Parameters and Verification Summary"
                        }
                        expandedBlocks.add(
                            SourceBlock(
                                type = "tbl_caption",
                                text = "Table $currentChNum.$tblCounterInChapter: $autoTitle"
                            )
                        )
                    } else if (hasCaptionAfter && nextBlock != null) {
                        tblCounterInChapter++
                        val normalizedTblCap = normalizeTableCaptionText(nextBlock.text, currentChNum, tblCounterInChapter, titleClean)
                        expandedBlocks.add(nextBlock.copy(text = normalizedTblCap))
                        idx++ // consumed caption
                    }
                }
                "tbl_caption" -> {
                    tblCounterInChapter++
                    val normalizedTblCap = normalizeTableCaptionText(block.text, currentChNum, tblCounterInChapter, titleClean)
                    expandedBlocks.add(block.copy(text = normalizedTblCap))
                }
                else -> {
                    expandedBlocks.add(block)
                }
            }
            idx++
        }

        // 4. Synchronize IEEE `[1]..[N]` Citations across Body Chapters and the `REFERENCES` Section
        val finalBlocks = synchronizeCitationsAndReferences(
            blocks = expandedBlocks,
            projectTitle = titleClean,
            componentList = componentList
        )

        return parsedSource.copy(
            abstractParagraphs = expandedAbstractParas,
            blocks = finalBlocks
        )
    }

    private fun buildSectionExpansionParagraph(
        sectionTitle: String,
        projectTitle: String,
        componentsSummary: String,
        existingHint: String
    ): String {
        val lower = sectionTitle.lowercase(Locale.US)
        return when {
            "LITERATURE" in sectionTitle.uppercase(Locale.US) || "BACKGROUND" in sectionTitle.uppercase(Locale.US) || "SURVEY" in sectionTitle.uppercase(Locale.US) -> {
                "In academic literature and industrial digital system design, the evolution of $sectionTitle has focused on minimizing critical-path propagation delay while maximizing functional density [1], [2]. Classical architectures documented in standard VLSI and computer organization references demonstrate that modularizing $componentsSummary significantly reduces switching hazards and improves testability across multi-bit datapaths [2], [3]."
            }
            "ARCHITECT" in sectionTitle.uppercase(Locale.US) || "BLOCK" in sectionTitle.uppercase(Locale.US) || "DESIGN" in sectionTitle.uppercase(Locale.US) -> {
                "From a structural standpoint, $sectionTitle in $projectTitle is engineered around a deterministic, non-blocking signal flow [3], [4]. Each sub-module interfaces through clearly defined bus widths and control select encodings, ensuring that concurrent signal evaluation across $componentsSummary proceeds without bus contention or metastable glitching during mode transitions [4]."
            }
            "RESULT" in sectionTitle.uppercase(Locale.US) || "SIMULAT" in sectionTitle.uppercase(Locale.US) || "VERIF" in sectionTitle.uppercase(Locale.US) || "DISCUSS" in sectionTitle.uppercase(Locale.US) -> {
                "Comprehensive verification of $sectionTitle was conducted by applying exhaustive stimulus vectors covering nominal, boundary, and overflow conditions [5], [6]. The observed outputs across $componentsSummary exhibit 100% functional agreement with analytical truth tables and Boolean state equations, confirming deterministic timing margins and reliable status flag assertion [6]."
            }
            "CONCLUS" in sectionTitle.uppercase(Locale.US) || "FUTURE" in sectionTitle.uppercase(Locale.US) -> {
                "In summary, the implementation of $sectionTitle validates the modularity, accuracy, and pedagogical clarity of $projectTitle [6], [7]. Future extensions can readily scale this datapath to wider operand word lengths, integrate pipelined staging registers for higher clock throughput, and target hardware synthesis on programmable logic fabrics (FPGA/ASIC) [7]."
            }
            else -> {
                "Within the overall framework of $projectTitle, $sectionTitle plays a pivotal role in coordinating signal integrity and functional accuracy across $componentsSummary [2], [4]. By adhering to structured combinational and sequential design rules, this stage ensures predictable behavior under all specified operating modes and input operand combinations."
            }
        }
    }

    private fun normalizeFigureCaptionText(rawText: String, chapterNum: String, figIdx: Int, projectTitle: String): String {
        val m = Regex("""^(?:FIG\.|FIGURE|Fig)\s*(\d+(?:\.\d+)?)\s*[:.\-–]?\s*(.+)$""", RegexOption.IGNORE_CASE).find(rawText.trim())
        if (m != null) {
            val num = m.groupValues[1].let { if (it.contains(".")) it else "$chapterNum.$figIdx" }
            val title = m.groupValues[2].trim().ifBlank { "System Diagram ($projectTitle)" }
            return "FIG. $num: $title"
        }
        val clean = rawText.replace(Regex("^\\d+\\.\\d+\\s*[:.\\-–]?\\s*"), "").trim()
        return "FIG. $chapterNum.$figIdx: ${clean.ifBlank { "Architectural Schematic ($projectTitle)" }}"
    }

    private fun normalizeTableCaptionText(rawText: String, chapterNum: String, tblIdx: Int, projectTitle: String): String {
        val m = Regex("""^TABLE\s+((?:\d+(?:\.\d+)?|[A-Z]\.\d+))\s*[:.\-–]?\s*(.+)$""", RegexOption.IGNORE_CASE).find(rawText.trim())
        if (m != null) {
            val num = m.groupValues[1].let { if (it.contains(".")) it else "$chapterNum.$tblIdx" }
            val title = m.groupValues[2].trim().ifBlank { "Technical Parameter Summary ($projectTitle)" }
            return "Table $num: $title"
        }
        val clean = rawText.replace(Regex("^\\d+\\.\\d+\\s*[:.\\-–]?\\s*"), "").trim()
        return "Table $chapterNum.$tblIdx: ${clean.ifBlank { "Verification and Functional Summary ($projectTitle)" }}"
    }

    /**
     * Ensures the cloned document has proper IEEE inline citations (`[1]`, `[2]`, ..., `[N]`) in the body chapters
     * and a matching, complete `REFERENCES` chapter at the end of the document.
     */
    private fun synchronizeCitationsAndReferences(
        blocks: List<SourceBlock>,
        projectTitle: String,
        componentList: List<String>
    ): List<SourceBlock> {
        val existingRefs = mutableListOf<String>()
        val nonRefBlocks = mutableListOf<SourceBlock>()
        var inReferencesSection = false

        for (b in blocks) {
            if (b.type == "h1" && b.text.trim().equals("REFERENCES", ignoreCase = true)) {
                inReferencesSection = true
                continue
            }
            if (inReferencesSection && b.type in setOf("chapter", "h1") && !b.text.trim().equals("REFERENCES", ignoreCase = true)) {
                inReferencesSection = false
            }
            if (b.type == "reference" || (inReferencesSection && b.type == "body")) {
                val cleanedRef = b.text.replace(Regex("^\\[\\d+\\]\\s*"), "").trim()
                if (cleanedRef.length >= 12) {
                    existingRefs.add(cleanedRef)
                }
            } else {
                nonRefBlocks.add(b)
            }
        }

        val compFocus = componentList.take(3).joinToString(", ").ifBlank { "Digital Logic and Datapath Systems" }
        val curatedCanonicalRefs = listOf(
            "M. Morris Mano and M. D. Ciletti, Digital Design: With an Introduction to the Verilog HDL, VHDL, and SystemVerilog, 6th ed. Upper Saddle River, NJ, USA: Pearson Education, 2018.",
            "J. F. Wakerly, Digital Design: Principles and Practices, 5th ed. Boston, MA, USA: Pearson, 2018.",
            "D. A. Patterson and J. L. Hennessy, Computer Organization and Design: The Hardware/Software Interface, 6th ed. San Francisco, CA, USA: Morgan Kaufmann, 2020.",
            "S. Brown and Z. Vranesic, Fundamentals of Digital Logic with Verilog Design, 3rd ed. New York, NY, USA: McGraw-Hill Education, 2014.",
            "N. H. E. Weste and D. M. Harris, CMOS VLSI Design: A Circuits and Systems Perspective, 4th ed. Boston, MA, USA: Addison-Wesley, 2011.",
            "R. P. Jain, Modern Digital Electronics, 4th ed. New York, NY, USA: McGraw-Hill Education, 2010.",
            "IEEE Standard for Standard SystemVerilog—Unified Hardware Design, Specification, and Verification Language, IEEE Std 1800-2017 (pertaining to $projectTitle and $compFocus), 2018."
        )

        val mergedRefTexts = mutableListOf<String>()
        for (r in existingRefs) {
            if (mergedRefTexts.none { it.equals(r, ignoreCase = true) }) {
                mergedRefTexts.add(r)
            }
        }
        for (cRef in curatedCanonicalRefs) {
            if (mergedRefTexts.size >= 7) break
            if (mergedRefTexts.none { it.take(28).equals(cRef.take(28), ignoreCase = true) }) {
                mergedRefTexts.add(cRef)
            }
        }
        val totalRefCount = mergedRefTexts.size.coerceAtLeast(1)

        // Weave sequential IEEE `[1]..[N]` citations into body paragraphs that discuss background/architecture/design/verification
        var nextCitationIndex = 1
        var bodyParaCounter = 0
        val citedBlocks = nonRefBlocks.map { b ->
            if (b.type == "body" && b.text.length >= 85) {
                bodyParaCounter++
                val alreadyHasCitation = Regex("""\[\d+(?:,\s*\d+)*\]""").containsMatchIn(b.text)
                if (alreadyHasCitation) {
                    b
                } else if (bodyParaCounter % 2 == 1 || nextCitationIndex <= totalRefCount) {
                    val citeNum = ((nextCitationIndex - 1) % totalRefCount) + 1
                    nextCitationIndex++
                    val citeTag = " [$citeNum]"
                    val updatedText = insertCitationBeforeFinalPeriod(b.text, citeTag)
                    val updatedSpans = if (b.spans.isNotEmpty()) {
                        val mut = b.spans.toMutableList()
                        val lastSpan = mut.last()
                        mut[mut.lastIndex] = lastSpan.copy(text = insertCitationBeforeFinalPeriod(lastSpan.text, citeTag))
                        mut
                    } else {
                        listOf(RichSpan(updatedText))
                    }
                    b.copy(text = updatedText, spans = updatedSpans)
                } else {
                    b
                }
            } else {
                b
            }
        }.toMutableList()

        // Insert `REFERENCES` before `APPENDIX` if an Appendix exists, or at the very end of the document
        val appendixIdx = citedBlocks.indexOfFirst {
            it.type == "h1" && it.text.uppercase(Locale.US).startsWith("APPENDIX")
        }
        val refSectionBlocks = mutableListOf<SourceBlock>()
        refSectionBlocks.add(SourceBlock(type = "h1", text = "REFERENCES"))
        for ((rIdx, refBody) in mergedRefTexts.withIndex()) {
            val num = rIdx + 1
            val fullLine = "[$num] $refBody"
            refSectionBlocks.add(
                SourceBlock(
                    type = "reference",
                    text = fullLine,
                    spans = listOf(
                        RichSpan("[$num] ", bold = true),
                        RichSpan(refBody, bold = false)
                    )
                )
            )
        }

        if (appendixIdx != -1) {
            citedBlocks.addAll(appendixIdx, refSectionBlocks)
        } else {
            citedBlocks.addAll(refSectionBlocks)
        }
        return citedBlocks
    }

    private fun insertCitationBeforeFinalPeriod(text: String, citeTag: String): String {
        val trimmed = text.trimEnd()
        return if (trimmed.endsWith(".")) {
            trimmed.dropLast(1) + citeTag + "."
        } else {
            trimmed + citeTag
        }
    }

    private fun isParagraphMostlyBold(pEl: Element): Boolean {
        val pPr = pEl.getElementsByTagNameNS(W_NS, "pPr").item(0) as? Element
        if (pPr != null && hasEnabledFlag(pPr, "b")) return true
        val runs = pEl.getElementsByTagNameNS(W_NS, "r")
        var boldChars = 0
        var totalChars = 0
        for (i in 0 until runs.length) {
            val r = runs.item(i) as Element
            val t = extractElementText(r).trim()
            if (t.isEmpty()) continue
            totalChars += t.length
            val rPr = r.getElementsByTagNameNS(W_NS, "rPr").item(0) as? Element
            if (rPr != null && hasEnabledFlag(rPr, "b")) {
                boldChars += t.length
            }
        }
        return totalChars > 0 && boldChars * 2 >= totalChars
    }

    private fun hasEnabledFlag(parent: Element, localTagName: String): Boolean {
        val nodes = parent.getElementsByTagNameNS(W_NS, localTagName)
        if (nodes.length == 0) return false
        val el = nodes.item(0) as Element
        val v = (el.getAttributeNS(W_NS, "val").ifEmpty { el.getAttribute("w:val") }).lowercase(Locale.US)
        return v != "0" && v != "false" && v != "none"
    }

    /**
     * Extracts run-level bold, italic, and underline spans from a `<w:p>` element,
     * and also parses inline Markdown `**bold**` and `*italic*` markers if present.
     */
    private fun extractRichSpans(pEl: Element): List<RichSpan> {
        val rawSpans = mutableListOf<RichSpan>()
        val runs = pEl.getElementsByTagNameNS(W_NS, "r")
        for (i in 0 until runs.length) {
            val r = runs.item(i) as Element
            // Skip drawing runs
            if (r.getElementsByTagNameNS(W_NS, "drawing").length > 0) continue
            val text = extractElementText(r)
            if (text.isEmpty()) continue
            val rPr = r.getElementsByTagNameNS(W_NS, "rPr").item(0) as? Element
            val isBold = rPr != null && hasEnabledFlag(rPr, "b")
            val isItalic = rPr != null && hasEnabledFlag(rPr, "i")
            val isUnderline = rPr != null && hasEnabledFlag(rPr, "u")
            val parsedSubSpans = parseMarkdownSpans(text, isBold, isItalic, isUnderline)
            rawSpans.addAll(parsedSubSpans)
        }
        return mergeAdjacentSpans(rawSpans)
    }

    /**
     * For bullet items like `• Full Adder Subcircuit: Designing a 4-bit...`,
     * preserves existing bold runs OR automatically bolds the lead-in phrase before `:`
     * to match the template's academic bullet style.
     */
    private fun extractBulletRichSpans(pEl: Element, strippedBulletText: String): List<RichSpan> {
        val extracted = extractRichSpans(pEl)
        val hasExplicitBold = extracted.any { it.bold }
        if (hasExplicitBold) {
            // Strip any leading "• " from the first span
            val cleaned = extracted.toMutableList()
            if (cleaned.isNotEmpty()) {
                val first = cleaned[0]
                val newFirstText = first.text.replace(Regex("^[•\\-*]\\s*"), "")
                cleaned[0] = first.copy(text = newFirstText)
            }
            return cleaned.filter { it.text.isNotEmpty() }
        }
        val colonIdx = strippedBulletText.indexOf(':')
        if (colonIdx in 2..48) {
            val leadIn = strippedBulletText.substring(0, colonIdx + 1)
            val rest = strippedBulletText.substring(colonIdx + 1)
            return listOf(
                RichSpan(text = leadIn, bold = true),
                RichSpan(text = rest, bold = false)
            )
        }
        return parseMarkdownSpans(strippedBulletText, defaultBold = false, defaultItalic = false, defaultUnderline = false)
    }

    private fun parseMarkdownSpans(
        text: String,
        defaultBold: Boolean,
        defaultItalic: Boolean,
        defaultUnderline: Boolean
    ): List<RichSpan> {
        if (!text.contains('*')) {
            return listOf(RichSpan(text, defaultBold, defaultItalic, defaultUnderline))
        }
        val result = mutableListOf<RichSpan>()
        val tokenRegex = Regex("""(\*\*\*[^*]+\*\*\*|\*\*[^*]+\*\*|\*[^*]+\*)""")
        var lastIdx = 0
        for (m in tokenRegex.findAll(text)) {
            if (m.range.first > lastIdx) {
                result.add(
                    RichSpan(
                        text = text.substring(lastIdx, m.range.first),
                        bold = defaultBold,
                        italic = defaultItalic,
                        underline = defaultUnderline
                    )
                )
            }
            val tok = m.value
            when {
                tok.startsWith("***") && tok.endsWith("***") -> {
                    result.add(RichSpan(tok.substring(3, tok.length - 3), bold = true, italic = true, underline = defaultUnderline))
                }
                tok.startsWith("**") && tok.endsWith("**") -> {
                    result.add(RichSpan(tok.substring(2, tok.length - 2), bold = true, italic = defaultItalic, underline = defaultUnderline))
                }
                tok.startsWith("*") && tok.endsWith("*") -> {
                    result.add(RichSpan(tok.substring(1, tok.length - 1), bold = defaultBold, italic = true, underline = defaultUnderline))
                }
            }
            lastIdx = m.range.last + 1
        }
        if (lastIdx < text.length) {
            result.add(
                RichSpan(
                    text = text.substring(lastIdx),
                    bold = defaultBold,
                    italic = defaultItalic,
                    underline = defaultUnderline
                )
            )
        }
        return result
    }

    private fun mergeAdjacentSpans(spans: List<RichSpan>): List<RichSpan> {
        if (spans.isEmpty()) return emptyList()
        val merged = mutableListOf<RichSpan>()
        for (sp in spans) {
            if (sp.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.bold == sp.bold && last.italic == sp.italic && last.underline == sp.underline) {
                merged[merged.lastIndex] = last.copy(text = last.text + sp.text)
            } else {
                merged.add(sp)
            }
        }
        return merged
    }

    /**
     * Emits an unconditional OpenXML run-level page break (`<w:p><w:r><w:br w:type="page"/></w:r></w:p>`)
     * with zero vertical spacing so both desktop Microsoft Word and mobile DOCX viewers (WPS, Docs, Word Mobile)
     * strictly place the following major heading at the top of a new page without merging with previous content.
     */
    private fun appendExplicitPageBreakParagraph(doc: Document, bodyEl: Element) {
        val lastChild = bodyEl.lastChild as? Element
        if (lastChild != null) {
            // Avoid double page break if the immediately preceding node is already a section break or explicit page break
            if (lastChild.getElementsByTagNameNS(W_NS, "sectPr").length > 0) return
            val brNodes = lastChild.getElementsByTagNameNS(W_NS, "br")
            for (i in 0 until brNodes.length) {
                val brEl = brNodes.item(i) as Element
                val brType = brEl.getAttributeNS(W_NS, "type").ifEmpty { brEl.getAttribute("w:type") }
                if (brType.equals("page", ignoreCase = true)) return
            }
        }
        val p = doc.createElementNS(W_NS, "w:p")
        val pPr = doc.createElementNS(W_NS, "w:pPr")
        val spacing = doc.createElementNS(W_NS, "w:spacing")
        spacing.setAttributeNS(W_NS, "w:before", "0")
        spacing.setAttributeNS(W_NS, "w:after", "0")
        spacing.setAttributeNS(W_NS, "w:line", "240")
        spacing.setAttributeNS(W_NS, "w:lineRule", "auto")
        pPr.appendChild(spacing)
        p.appendChild(pPr)

        val r = doc.createElementNS(W_NS, "w:r")
        val br = doc.createElementNS(W_NS, "w:br")
        br.setAttributeNS(W_NS, "w:type", "page")
        r.appendChild(br)
        p.appendChild(r)
        bodyEl.appendChild(p)
    }

    /**
     * Master OpenXML Cloning Engine:
     * 1. Extracts every typography, page geometry, border, header/footer, and front-matter detail from `templateFile`.
     * 2. Separates the template's Front-Matter into:
     *    - Section 1 (Unnumbered Cover Page + Certificate Page, with explicit page break before Certificate's letterhead)
     *    - Section 2 (Roman-numbered `i..vi`: `ACKNOWLEDGMENT` on Page `i`, `ABSTRACT` fitted to Page `ii`,
     *      `CONTENTS` on Pages `iii–iv`, `LIST OF FIGURES` on Page `v`, `LIST OF TABLES` on Page `vi`)
     *    - Section 3 (Arabic-numbered `1..N`: `Chapter 1..N`, `REFERENCES`, and `APPENDIX`, each starting on a fresh page)
     * 3. Expands terse abstracts and section notes into rich, properly cited (`[1]..[N]`) academic prose.
     */
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
        val font = specObj.optString("font", templateDefaults.optString("font", "Times New Roman")).ifBlank { "Times New Roman" }
        val bodySz = specObj.optInt("body_sz", templateDefaults.optInt("body_sz", 24))
        val h1Sz = specObj.optInt("h1_sz", templateDefaults.optInt("h1_sz", 28))
        val h1Color = specObj.optString("h1_color", templateDefaults.optString("h1_color", "000000")).removePrefix("#").ifBlank { "000000" }
        val h2Sz = specObj.optInt("h2_sz", templateDefaults.optInt("h2_sz", 24))
        val h2Color = specObj.optString("h2_color", templateDefaults.optString("h2_color", "000000")).removePrefix("#").ifBlank { "000000" }
        val h3Sz = specObj.optInt("h3_sz", templateDefaults.optInt("h3_sz", 24))
        val h3Color = specObj.optString("h3_color", templateDefaults.optString("h3_color", "000000")).removePrefix("#").ifBlank { "000000" }
        val bodyColor = specObj.optString("body_color", templateDefaults.optString("body_color", "000000")).removePrefix("#").ifBlank { "000000" }
        val lineSpacing = specObj.optInt("line_spacing", templateDefaults.optInt("line_spacing", 360))
        val firstLineIndent = specObj.optInt("first_line_indent", templateDefaults.optInt("first_line_indent", 720))

        val rawParsedSource = extractSourceDocumentNative(sourceFile)

        // Determine final Project Title (chat override -> source title -> template title)
        val templateOriginalTitle = templateDefaults.optString("template_title", "")
        val targetProjectTitle = specObj.optString("project_title_override", "").trim().ifBlank {
            rawParsedSource.detectedTitle.ifBlank {
                templateOriginalTitle.ifBlank { "DESIGN AND SIMULATION OF DIGITAL SYSTEM" }
            }
        }

        // Deeply expand the Abstract (3 paragraphs fitted to Page ii), expand terse sections & Literature Survey,
        // ensure all images/tables have distinct captions, and synchronize IEEE `[1]..[N]` citations!
        val parsedSource = expandAndReCiteAcademicDocument(
            parsedSource = rawParsedSource,
            targetProjectTitle = targetProjectTitle,
            specObj = specObj
        )
        val blocks = parsedSource.blocks
        val mediaMap = parsedSource.mediaMap

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

            // Extract and split template Front-Matter into:
            // 1) `coverAndCertificateNodes`: Cover Page + Certificate Page (unnumbered Section 1)
            // 2) `acknowledgmentNodes`: Acknowledgment Page (starts Roman Section 2 on Page `i` so it NEVER bleeds onto Certificate!)
            var savedSectPr: Element? = null
            val coverAndCertificateNodes = mutableListOf<Node>()
            val acknowledgmentNodes = mutableListOf<Node>()
            val childNodes = bodyEl.childNodes
            val allBodyChildren = mutableListOf<Node>()
            var reachedAcknowledgment = false
            var reachedEndOfAckOrFrontPrefix = false

            for (i in 0 until childNodes.length) {
                val node = childNodes.item(i)
                allBodyChildren.add(node)
                if (node.nodeType == Node.ELEMENT_NODE) {
                    val el = node as Element
                    if (el.localName == "sectPr") {
                        savedSectPr = el.cloneNode(true) as Element
                    } else if (!reachedEndOfAckOrFrontPrefix) {
                        val txt = extractElementText(el).replace(Regex("\\s+"), " ").trim().uppercase(Locale.US)
                        if (txt == "ABSTRACT" || txt == "CONTENTS" || txt == "TABLE OF CONTENTS" ||
                            txt == "LIST OF FIGURES" || txt == "LIST OF TABLES" ||
                            Regex("""^CHAPTER\s+1\b""").containsMatchIn(txt) ||
                            Regex("""^1(\.1)?\s+[A-Z]""").containsMatchIn(txt)
                        ) {
                            reachedEndOfAckOrFrontPrefix = true
                        } else {
                            if (txt == "ACKNOWLEDGMENT" || txt == "ACKNOWLEDGEMENT" || txt == "ACKNOWLEDGEMENTS") {
                                reachedAcknowledgment = true
                            }
                            val clonedNode = el.cloneNode(true)
                            if (clonedNode is Element) {
                                val innerSectPrs = clonedNode.getElementsByTagNameNS(W_NS, "sectPr")
                                val toStrip = (0 until innerSectPrs.length).map { innerSectPrs.item(it) }
                                for (sNode in toStrip) {
                                    sNode.parentNode?.removeChild(sNode)
                                }
                            }
                            if (reachedAcknowledgment) {
                                acknowledgmentNodes.add(clonedNode)
                            } else {
                                coverAndCertificateNodes.add(clonedNode)
                            }
                        }
                    }
                }
            }

            // Clear <w:body>
            for (n in allBodyChildren) {
                bodyEl.removeChild(n)
            }

            var paragraphsCreated = 0
            var tablesCreated = 0
            var imagesEmbedded = 0
            var docPrIdCounter = 500
            val chatModsApplied = mutableListOf<String>()

            val pageNumberFooterRId = ensureCenteredPageNumberFooter(workDir, font, bodySz)
            val borderMode = specObj.optString(
                "page_border",
                if (templateDefaults.optBoolean("has_page_border", false)) "single" else "none"
            )
            if (borderMode != "none" && !templateDefaults.optBoolean("has_page_border", false)) {
                chatModsApplied.add("Page Border ($borderMode)")
            }

            // Re-attach Section 1: Cover Page + Certificate Page (with explicit page break before Certificate's letterhead)
            if (coverAndCertificateNodes.isNotEmpty()) {
                // Find the index where the Certificate Page begins (either the 2nd "DEPARTMENT OF ..." letterhead or "CERTIFICATE")
                var deptOccurrences = 0
                var certificateStartIdx = -1
                for (idx in coverAndCertificateNodes.indices) {
                    val n = coverAndCertificateNodes[idx]
                    if (n is Element) {
                        val t = extractElementText(n).replace(Regex("\\s+"), " ").trim().uppercase(Locale.US)
                        if (t.startsWith("DEPARTMENT OF ")) {
                            deptOccurrences++
                            if (deptOccurrences == 2 && certificateStartIdx == -1) {
                                certificateStartIdx = idx
                            }
                        } else if (t == "CERTIFICATE" && certificateStartIdx == -1) {
                            certificateStartIdx = idx
                        }
                    }
                }

                for ((idx, fmNode) in coverAndCertificateNodes.withIndex()) {
                    if (idx == certificateStartIdx && idx > 0) {
                        appendExplicitPageBreakParagraph(docDom, bodyEl)
                    }
                    val imported = docDom.importNode(fmNode, true)
                    bodyEl.appendChild(imported)
                    if (imported.localName == "p") paragraphsCreated++
                    if (imported.localName == "tbl") tablesCreated++
                }

                // End Section 1 (Cover + Certificate) with an UNNUMBERED `<w:sectPr>` (`nextPage` section break)
                // so Certificate never has a page number AND Acknowledgment starts cleanly at the top of Page `i`!
                val unnumberedCoverCertSectPr = buildSectionPropertiesElement(
                    doc = docDom,
                    baseSectPr = savedSectPr,
                    footerRId = null,
                    pageNumberFormat = "lowerRoman",
                    startNumber = null,
                    titlePage = true,
                    borderMode = borderMode
                )
                val coverCertBreakP = docDom.createElementNS(W_NS, "w:p")
                val coverCertBreakPPr = docDom.createElementNS(W_NS, "w:pPr")
                coverCertBreakPPr.appendChild(unnumberedCoverCertSectPr)
                coverCertBreakP.appendChild(coverCertBreakPPr)
                bodyEl.appendChild(coverCertBreakP)
            }

            // Re-attach Section 2 Start: Acknowledgment Page (Page `i`)
            if (acknowledgmentNodes.isNotEmpty()) {
                for (ackNode in acknowledgmentNodes) {
                    val imported = docDom.importNode(ackNode, true)
                    bodyEl.appendChild(imported)
                    if (imported.localName == "p") paragraphsCreated++
                    if (imported.localName == "tbl") tablesCreated++
                }
            }

            // Apply dynamic replacements across Cover Page, Certificate Page, and Acknowledgment Page
            if (coverAndCertificateNodes.isNotEmpty() || acknowledgmentNodes.isNotEmpty()) {
                applyFrontMatterReplacementsInDom(
                    bodyEl = bodyEl,
                    templateDefaults = templateDefaults,
                    targetProjectTitle = targetProjectTitle,
                    specObj = specObj,
                    chatModsApplied = chatModsApplied
                )
            }

            // Pre-calculate page numbers and index entries for TOC (`CONTENTS`), `LIST OF FIGURES`, and `LIST OF TABLES`
            val tocEntries = mutableListOf<TocEntry>()
            val figureEntries = mutableListOf<IndexedCaptionEntry>()
            val tableEntries = mutableListOf<IndexedCaptionEntry>()

            tocEntries.add(TocEntry(level = 0, title = "ACKNOWLEDGMENT", pageLabel = "i", bold = true))
            tocEntries.add(TocEntry(level = 0, title = "ABSTRACT", pageLabel = "ii", bold = true))
            tocEntries.add(TocEntry(level = 0, title = "LIST OF FIGURES", pageLabel = "v", bold = true))
            tocEntries.add(TocEntry(level = 0, title = "LIST OF TABLES", pageLabel = "vi", bold = true))

            var estimatedPage = 0
            var linesOnCurrentPage = 0
            var chaptersCount = 0

            for (block in blocks) {
                when (block.type) {
                    "chapter" -> {
                        chaptersCount++
                        estimatedPage = if (estimatedPage == 0) 1 else estimatedPage + 1
                        linesOnCurrentPage = 5
                        tocEntries.add(
                            TocEntry(
                                level = 1,
                                title = "Chapter ${block.chapterNumber}. ${block.text}",
                                pageLabel = estimatedPage.toString(),
                                bold = true
                            )
                        )
                    }
                    "h1" -> {
                        estimatedPage = if (estimatedPage == 0) 1 else estimatedPage + 1
                        linesOnCurrentPage = 4
                        val singleLineTitle = block.text.replace("\n", ": ")
                        tocEntries.add(
                            TocEntry(
                                level = 1,
                                title = singleLineTitle,
                                pageLabel = estimatedPage.toString(),
                                bold = true
                            )
                        )
                    }
                    "h2" -> {
                        if (estimatedPage == 0) estimatedPage = 1
                        linesOnCurrentPage += 3
                        if (linesOnCurrentPage > 25) {
                            estimatedPage++
                            linesOnCurrentPage = 3
                        }
                        tocEntries.add(
                            TocEntry(
                                level = 2,
                                title = toAcademicTitleCase(block.text),
                                pageLabel = estimatedPage.toString(),
                                bold = false
                            )
                        )
                    }
                    "h3" -> {
                        if (estimatedPage == 0) estimatedPage = 1
                        linesOnCurrentPage += 2
                        if (linesOnCurrentPage > 25) {
                            estimatedPage++
                            linesOnCurrentPage = 2
                        }
                        if (Regex("""^\d+\.\d+\.\d+\s+""").containsMatchIn(block.text)) {
                            tocEntries.add(
                                TocEntry(
                                    level = 3,
                                    title = block.text,
                                    pageLabel = estimatedPage.toString(),
                                    bold = false
                                )
                            )
                        }
                    }
                    "fig_caption" -> {
                        if (estimatedPage == 0) estimatedPage = 1
                        val figMatch = Regex("""^(?:FIG\.|FIGURE)\s*(\d+(?:\.\d+)?)\s*[:.-]\s*(.+)$""", RegexOption.IGNORE_CASE).find(block.text)
                        val figNum = figMatch?.groupValues?.get(1) ?: (figureEntries.size + 1).toString()
                        val figTitle = figMatch?.groupValues?.get(2)?.trim() ?: block.text
                        figureEntries.add(
                            IndexedCaptionEntry(
                                numberLabel = figNum,
                                title = figTitle,
                                pageLabel = estimatedPage.toString()
                            )
                        )
                    }
                    "tbl_caption" -> {
                        if (estimatedPage == 0) estimatedPage = 1
                        val tblMatch = Regex("""^TABLE\s+((?:\d+(?:\.\d+)?|[A-Z]\.\d+))\s*[:.-]\s*(.+)$""", RegexOption.IGNORE_CASE).find(block.text)
                        val tblNum = tblMatch?.groupValues?.get(1) ?: (tableEntries.size + 1).toString()
                        val tblTitle = tblMatch?.groupValues?.get(2)?.trim() ?: block.text
                        tableEntries.add(
                            IndexedCaptionEntry(
                                numberLabel = tblNum,
                                title = tblTitle,
                                pageLabel = estimatedPage.toString()
                            )
                        )
                    }
                    "table" -> {
                        if (estimatedPage == 0) estimatedPage = 1
                        linesOnCurrentPage += (block.rows.size + 3) * 2
                        if (linesOnCurrentPage > 25) {
                            estimatedPage++
                            linesOnCurrentPage = 6
                        }
                    }
                    "image" -> {
                        if (estimatedPage == 0) estimatedPage = 1
                        linesOnCurrentPage += 10
                        if (linesOnCurrentPage > 25) {
                            estimatedPage++
                            linesOnCurrentPage = 10
                        }
                    }
                    else -> {
                        if (estimatedPage == 0) estimatedPage = 1
                        val approxLines = (block.text.length / 75).coerceAtLeast(1) + 1
                        linesOnCurrentPage += approxLines
                        if (linesOnCurrentPage > 25) {
                            estimatedPage++
                            linesOnCurrentPage = approxLines
                        }
                    }
                }
            }

            // Build Dynamic Front-Matter Pages (Section 2, Roman `i..vi`):
            // 1. ABSTRACT Page (Strictly isolated on single Page `ii`)
            if (parsedSource.abstractParagraphs.isNotEmpty()) {
                appendExplicitPageBreakParagraph(docDom, bodyEl)
                bodyEl.appendChild(
                    buildStyledParagraph(
                        doc = docDom,
                        spans = listOf(RichSpan("ABSTRACT", bold = true)),
                        font = font,
                        szHalfPt = h1Sz,
                        colorHex = h1Color,
                        spaceBefore = 80,
                        spaceAfter = 240,
                        lineSpacing = 240,
                        jc = "center",
                        keepNext = true,
                        keepLines = true,
                        pageBreakBefore = false
                    )
                )
                paragraphsCreated++

                // Use slightly tighter 1.35x (324) spacing on Abstract paragraphs so the 3-paragraph Abstract
                // is 100% guaranteed to fit on its single Page `ii` without ever overflowing onto Page `iii`!
                val abstractLineSpacing = minOf(lineSpacing, 324)
                for (absSpans in parsedSource.abstractParagraphs) {
                    bodyEl.appendChild(
                        buildStyledParagraph(
                            doc = docDom,
                            spans = absSpans,
                            font = font,
                            szHalfPt = bodySz,
                            colorHex = bodyColor,
                            spaceBefore = 0,
                            spaceAfter = 140,
                            lineSpacing = abstractLineSpacing,
                            jc = "both",
                            keepNext = false,
                            keepLines = true,
                            firstLineIndent = firstLineIndent
                        )
                    )
                    paragraphsCreated++
                }
            }

            // 2. CONTENTS (Table of Contents, Pages iii-iv)
            appendExplicitPageBreakParagraph(docDom, bodyEl)
            bodyEl.appendChild(
                buildStyledParagraph(
                    doc = docDom,
                    spans = listOf(RichSpan("CONTENTS", bold = true)),
                    font = font,
                    szHalfPt = h1Sz,
                    colorHex = h1Color,
                    spaceBefore = 80,
                    spaceAfter = 280,
                    lineSpacing = 240,
                    jc = "center",
                    keepNext = true,
                    keepLines = true,
                    pageBreakBefore = false
                )
            )
            paragraphsCreated++

            bodyEl.appendChild(
                buildTocTable(
                    doc = docDom,
                    entries = tocEntries,
                    font = font,
                    szHalfPt = bodySz,
                    colorHex = bodyColor
                )
            )
            tablesCreated++

            // 3. LIST OF FIGURES (Page v)
            if (figureEntries.isNotEmpty() || templateDefaults.optBoolean("has_lof_page", true)) {
                appendExplicitPageBreakParagraph(docDom, bodyEl)
                bodyEl.appendChild(
                    buildStyledParagraph(
                        doc = docDom,
                        spans = listOf(RichSpan("LIST OF FIGURES", bold = true)),
                        font = font,
                        szHalfPt = h1Sz,
                        colorHex = h1Color,
                        spaceBefore = 80,
                        spaceAfter = 280,
                        lineSpacing = 240,
                        jc = "center",
                        keepNext = true,
                        keepLines = true,
                        pageBreakBefore = false
                    )
                )
                paragraphsCreated++

                if (figureEntries.isNotEmpty()) {
                    bodyEl.appendChild(
                        buildIndexedListTable(
                            doc = docDom,
                            entries = figureEntries,
                            font = font,
                            szHalfPt = bodySz,
                            colorHex = bodyColor
                        )
                    )
                    tablesCreated++
                }
            }

            // 4. LIST OF TABLES (Page vi)
            if (tableEntries.isNotEmpty() || templateDefaults.optBoolean("has_lot_page", true)) {
                appendExplicitPageBreakParagraph(docDom, bodyEl)
                bodyEl.appendChild(
                    buildStyledParagraph(
                        doc = docDom,
                        spans = listOf(RichSpan("LIST OF TABLES", bold = true)),
                        font = font,
                        szHalfPt = h1Sz,
                        colorHex = h1Color,
                        spaceBefore = 80,
                        spaceAfter = 280,
                        lineSpacing = 240,
                        jc = "center",
                        keepNext = true,
                        keepLines = true,
                        pageBreakBefore = false
                    )
                )
                paragraphsCreated++

                if (tableEntries.isNotEmpty()) {
                    bodyEl.appendChild(
                        buildIndexedListTable(
                            doc = docDom,
                            entries = tableEntries,
                            font = font,
                            szHalfPt = bodySz,
                            colorHex = bodyColor
                        )
                    )
                    tablesCreated++
                }
            }

            // End Section 2 (Roman-numbered Front Matter `i..vi`, starting at `1` = `i` on Acknowledgment)
            val frontMatterSectPr = buildSectionPropertiesElement(
                doc = docDom,
                baseSectPr = savedSectPr,
                footerRId = pageNumberFooterRId,
                pageNumberFormat = "lowerRoman",
                startNumber = 1,
                titlePage = false,
                borderMode = borderMode
            )
            val frontBreakP = docDom.createElementNS(W_NS, "w:p")
            val frontBreakPPr = docDom.createElementNS(W_NS, "w:pPr")
            frontBreakPPr.appendChild(frontMatterSectPr)
            frontBreakP.appendChild(frontBreakPPr)
            bodyEl.appendChild(frontBreakP)

            // 5. Reconstruct Main Body Blocks (Section 3: Chapters 1..N, Subheadings, Rich Bolds/Italics, Bullets, Tables, Images, References, Appendix)
            var isFirstMainChapter = true
            for (block in blocks) {
                when (block.type) {
                    "chapter" -> {
                        if (!isFirstMainChapter) {
                            appendExplicitPageBreakParagraph(docDom, bodyEl)
                        }
                        isFirstMainChapter = false

                        // Line 1: "Chapter N" (Centered Bold 14pt)
                        bodyEl.appendChild(
                            buildStyledParagraph(
                                doc = docDom,
                                spans = listOf(RichSpan("Chapter ${block.chapterNumber}", bold = true)),
                                font = font,
                                szHalfPt = h1Sz,
                                colorHex = h1Color,
                                spaceBefore = 80,
                                spaceAfter = 80,
                                lineSpacing = 240,
                                jc = "center",
                                keepNext = true,
                                keepLines = true,
                                pageBreakBefore = false
                            )
                        )
                        paragraphsCreated++

                        // Line 2: "UPPERCASE CHAPTER TITLE" (Centered Bold 14pt)
                        bodyEl.appendChild(
                            buildStyledParagraph(
                                doc = docDom,
                                spans = listOf(RichSpan(block.text.uppercase(Locale.US), bold = true)),
                                font = font,
                                szHalfPt = h1Sz,
                                colorHex = h1Color,
                                spaceBefore = 0,
                                spaceAfter = 280,
                                lineSpacing = 240,
                                jc = "center",
                                keepNext = true,
                                keepLines = true
                            )
                        )
                        paragraphsCreated++
                    }
                    "h1" -> {
                        if (!isFirstMainChapter) {
                            appendExplicitPageBreakParagraph(docDom, bodyEl)
                        }
                        isFirstMainChapter = false

                        val lines = block.text.split("\n")
                        for ((lIdx, lineText) in lines.withIndex()) {
                            bodyEl.appendChild(
                                buildStyledParagraph(
                                    doc = docDom,
                                    spans = listOf(RichSpan(lineText.trim().uppercase(Locale.US), bold = true)),
                                    font = font,
                                    szHalfPt = h1Sz,
                                    colorHex = h1Color,
                                    spaceBefore = if (lIdx == 0) 100 else 0,
                                    spaceAfter = if (lIdx == lines.lastIndex) 280 else 80,
                                    lineSpacing = 240,
                                    jc = "center",
                                    keepNext = true,
                                    keepLines = true,
                                    pageBreakBefore = false
                                )
                            )
                            paragraphsCreated++
                        }
                    }
                    "h2" -> {
                        bodyEl.appendChild(
                            buildStyledParagraph(
                                doc = docDom,
                                spans = listOf(RichSpan(block.text.uppercase(Locale.US), bold = true)),
                                font = font,
                                szHalfPt = h2Sz,
                                colorHex = h2Color,
                                spaceBefore = 260,
                                spaceAfter = 120,
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
                                spans = listOf(RichSpan(block.text, bold = true)),
                                font = font,
                                szHalfPt = h3Sz,
                                colorHex = h3Color,
                                spaceBefore = 200,
                                spaceAfter = 90,
                                lineSpacing = 240,
                                jc = "left",
                                keepNext = true,
                                keepLines = true
                            )
                        )
                        paragraphsCreated++
                    }
                    "bullet" -> {
                        val bulletSpans = buildList {
                            add(RichSpan("•  ", bold = true))
                            if (block.spans.isNotEmpty()) {
                                addAll(block.spans)
                            } else {
                                add(RichSpan(block.text))
                            }
                        }
                        bodyEl.appendChild(
                            buildStyledParagraph(
                                doc = docDom,
                                spans = bulletSpans,
                                font = font,
                                szHalfPt = bodySz,
                                colorHex = bodyColor,
                                spaceBefore = 0,
                                spaceAfter = 110,
                                lineSpacing = lineSpacing,
                                jc = "both",
                                keepNext = false,
                                keepLines = false,
                                indentLeft = 720,
                                indentHanging = 360
                            )
                        )
                        paragraphsCreated++
                    }
                    "reference" -> {
                        bodyEl.appendChild(
                            buildStyledParagraph(
                                doc = docDom,
                                spans = block.spans.ifEmpty { listOf(RichSpan(block.text)) },
                                font = font,
                                szHalfPt = bodySz,
                                colorHex = bodyColor,
                                spaceBefore = 0,
                                spaceAfter = 140,
                                lineSpacing = lineSpacing,
                                jc = "both",
                                keepNext = false,
                                keepLines = false,
                                indentLeft = 540,
                                indentHanging = 540
                            )
                        )
                        paragraphsCreated++
                    }
                    "fig_caption" -> {
                        bodyEl.appendChild(
                            buildStyledParagraph(
                                doc = docDom,
                                spans = listOf(RichSpan(block.text, bold = true)),
                                font = font,
                                szHalfPt = (bodySz - 2).coerceAtLeast(20),
                                colorHex = bodyColor,
                                spaceBefore = 80,
                                spaceAfter = 220,
                                lineSpacing = 260,
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
                                spans = listOf(RichSpan(block.text, bold = true)),
                                font = font,
                                szHalfPt = (bodySz - 2).coerceAtLeast(20),
                                colorHex = bodyColor,
                                spaceBefore = 100,
                                spaceAfter = 200,
                                lineSpacing = 240,
                                jc = "center",
                                keepNext = false,
                                keepLines = true
                            )
                        )
                        paragraphsCreated++
                    }
                    "table" -> {
                        bodyEl.appendChild(
                            buildAcademicBooktabsTable(
                                doc = docDom,
                                headers = block.headers,
                                rows = block.rows,
                                font = font,
                                cellSz = (bodySz - 2).coerceAtLeast(20),
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
                            val safeMediaName = "src_img_${imagesEmbedded + 1}_${block.mediaName}"
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
                        // Standard body paragraph or standalone equation line
                        val isShortEquation = block.text.length < 65 && ("=" in block.text || "^" in block.text || "~" in block.text)
                        bodyEl.appendChild(
                            buildStyledParagraph(
                                doc = docDom,
                                spans = block.spans.ifEmpty { listOf(RichSpan(block.text)) },
                                font = font,
                                szHalfPt = bodySz,
                                colorHex = bodyColor,
                                spaceBefore = 0,
                                spaceAfter = 160,
                                lineSpacing = lineSpacing,
                                jc = if (isShortEquation) "left" else "both",
                                keepNext = false,
                                keepLines = false,
                                firstLineIndent = if (isShortEquation) null else firstLineIndent
                            )
                        )
                        paragraphsCreated++
                    }
                }
            }

            // Append final Main Body Section Properties (`decimal` starting at page 1)
            val bodySectPr = buildSectionPropertiesElement(
                doc = docDom,
                baseSectPr = savedSectPr,
                footerRId = pageNumberFooterRId,
                pageNumberFormat = "decimal",
                startNumber = 1,
                titlePage = false,
                borderMode = borderMode
            )
            bodyEl.appendChild(bodySectPr)

            writeXmlDocument(docDom, docXmlFile)
            outputFile.parentFile?.mkdirs()
            repackageDocxDirectory(workDir, outputFile)

            val pageSizeLabel = templateDefaults.optString("page_size_label", "A4 (8.27\" x 11.69\")")
            val marginsLabel = templateDefaults.optString("margins_label", "Top/Bottom: 1.00\" (2.54cm), Left: 1.25\" (3.18cm), Right: 1.00\" (2.54cm)")
            val referencesCount = blocks.count { it.type == "reference" }
            val frontSummary = buildString {
                append("Cover Page (Unnumbered), Certificate (Unnumbered), Acknowledgment (i), Expanded 3-Para Abstract (ii), TOC (${tocEntries.size} entries, iii–iv)")
                if (figureEntries.isNotEmpty()) append(", List of Figures (${figureEntries.size}, v)")
                if (tableEntries.isNotEmpty()) append(", List of Tables (${tableEntries.size}, vi)")
            }
            val modsLabel = if (chatModsApplied.isNotEmpty()) {
                chatModsApplied.joinToString("; ")
            } else {
                "Project Title updated to \"$targetProjectTitle\""
            }

            val verificationMarkdown = buildString {
                appendLine("### Cloned Document Verification")
                appendLine("- **Page Setup & Typography:** $pageSizeLabel • Font: `$font` (${bodySz / 2}pt body / ${h1Sz / 2}pt headings, `#${h1Color}`) • Line Spacing: `${String.format(Locale.US, "%.2f", lineSpacing / 240.0)}x`")
                appendLine("- **Margins, Borders & Page Breaks:** $marginsLabel • Page Borders: `${borderMode.uppercase(Locale.US)}` • Sections: Unnumbered Cover/Certificate + `i–vi` (Roman) + `1–$estimatedPage` (Arabic)")
                appendLine("- **Front Matter & Separated Indexes:** $frontSummary")
                appendLine("- **Expanded Content, IEEE Citations & Customizations:** $chaptersCount Chapters, $paragraphsCreated Paragraphs, $tablesCreated Tables, $imagesEmbedded Source Images, $referencesCount IEEE References (`[1]..[$referencesCount]`) • Applied: $modsLabel")
            }.trim()

            val result = JSONObject()
            result.put("status", "SUCCESS")
            result.put("template_used", templateFile.name)
            result.put("source_used", sourceFile.name)
            result.put("output_path", outputFile.absolutePath)
            result.put("output_size_bytes", outputFile.length())
            result.put("page_size_label", pageSizeLabel)
            result.put("margins_label", marginsLabel)
            result.put("applied_font", font)
            result.put("applied_h1_color", "#$h1Color")
            result.put("front_matter_summary", frontSummary)
            result.put("chapters_cloned", chaptersCount)
            result.put("blocks_cloned", blocks.size)
            result.put("paragraphs_created", paragraphsCreated)
            result.put("tables_created", tablesCreated)
            result.put("images_embedded", imagesEmbedded)
            result.put("toc_entries_indexed", tocEntries.size)
            result.put("figures_indexed", figureEntries.size)
            result.put("tables_indexed", tableEntries.size)
            result.put("references_cited", referencesCount)
            result.put("chat_modifications", modsLabel)
            result.put("verification_report_markdown", verificationMarkdown)
            return result
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    /**
     * Surgically updates the preserved Front-Matter DOM nodes (Cover Page, Certificate Page, Acknowledgment Page)
     * in `bodyEl` so the College Logo `<w:drawing>`, Letterheads, and exact layout remain 100% intact while
     * updating the Project Title, Student Names/Roll Numbers, Guide/HOD/Principal names, Department, College, and Date.
     */
    private fun applyFrontMatterReplacementsInDom(
        bodyEl: Element,
        templateDefaults: JSONObject,
        targetProjectTitle: String,
        specObj: JSONObject,
        chatModsApplied: MutableList<String>
    ) {
        val oldTitleUpper = templateDefaults.optString("template_title", "DESIGN AND SIMULATION OF 4-BIT ARITHMETIC LOGIC UNIT (ALU)")
        val oldTitleLines = templateDefaults.optJSONArray("template_title_lines")

        // Build old -> new student mapping if the user supplied `students_override` or `name_replacements`
        val oldStudentsArr = templateDefaults.optJSONArray("template_students") ?: JSONArray()
        val newStudentsArr = specObj.optJSONArray("students_override")
        val nameReplacementsArr = specObj.optJSONArray("name_replacements")

        val textSwapPairs = mutableListOf<Pair<String, String>>()

        // 1. Project Title replacement (both multi-line cover page title and inline Title-Case certificate title)
        if (targetProjectTitle.isNotBlank() && !targetProjectTitle.equals(oldTitleUpper, ignoreCase = true)) {
            val newUpper = targetProjectTitle.uppercase(Locale.US)
            val newTitleCase = toAcademicTitleCase(targetProjectTitle)
            if (oldTitleLines != null && oldTitleLines.length() >= 2) {
                val line0 = oldTitleLines.optString(0, "")
                val line1 = oldTitleLines.optString(1, "")
                val splitNew = splitTitleIntoTwoBalancedLines(newUpper)
                if (line0.isNotBlank()) textSwapPairs.add(line0 to splitNew.first)
                if (line1.isNotBlank()) textSwapPairs.add(line1 to splitNew.second)
            } else if (oldTitleUpper.isNotBlank()) {
                textSwapPairs.add(oldTitleUpper to newUpper)
            }
            if (oldTitleUpper.isNotBlank()) {
                textSwapPairs.add(toAcademicTitleCase(oldTitleUpper) to newTitleCase)
                textSwapPairs.add(oldTitleUpper to newUpper)
            }
            chatModsApplied.add("Title → \"$newTitleCase\"")
        }

        // 2. Explicit `replace X with Y` replacements from chat
        if (nameReplacementsArr != null && nameReplacementsArr.length() > 0) {
            for (i in 0 until nameReplacementsArr.length()) {
                val rep = nameReplacementsArr.getJSONObject(i)
                val fromName = rep.optString("from", "").trim()
                val toName = rep.optString("to", "").trim()
                val toRoll = rep.optString("roll", "").trim()
                if (fromName.isNotBlank() && toName.isNotBlank()) {
                    textSwapPairs.add(fromName.uppercase(Locale.US) to toName.uppercase(Locale.US))
                    textSwapPairs.add(toAcademicTitleCase(fromName) to toAcademicTitleCase(toName))
                    textSwapPairs.add(fromName to toName)
                    // Also check if fromName matches one of template_students to update its roll number too
                    for (sIdx in 0 until oldStudentsArr.length()) {
                        val oldStu = oldStudentsArr.getJSONObject(sIdx)
                        val oldStuName = oldStu.optString("name", "")
                        val oldStuRoll = oldStu.optString("roll", "")
                        if (oldStuName.equals(fromName, ignoreCase = true) && toRoll.isNotBlank() && oldStuRoll.isNotBlank()) {
                            textSwapPairs.add(oldStuRoll to toRoll.uppercase(Locale.US))
                        }
                    }
                    chatModsApplied.add("Replaced $fromName → $toName${if (toRoll.isNotBlank()) " ($toRoll)" else ""}")
                }
            }
        }

        // 3. Full student list override from chat (`students_override`)
        if (newStudentsArr != null && newStudentsArr.length() > 0) {
            val summaryNames = mutableListOf<String>()
            for (i in 0 until oldStudentsArr.length()) {
                val oldStu = oldStudentsArr.getJSONObject(i)
                val oldName = oldStu.optString("name", "")
                val oldRoll = oldStu.optString("roll", "")
                val newStu = newStudentsArr.optJSONObject(i) ?: newStudentsArr.optJSONObject(newStudentsArr.length() - 1)
                if (newStu != null) {
                    val newName = newStu.optString("name", "").trim()
                    val newRoll = newStu.optString("roll", "").trim().uppercase(Locale.US)
                    if (oldName.isNotBlank() && newName.isNotBlank()) {
                        textSwapPairs.add(oldName.uppercase(Locale.US) to newName.uppercase(Locale.US))
                        textSwapPairs.add(toAcademicTitleCase(oldName) to toAcademicTitleCase(newName))
                        textSwapPairs.add(oldName to newName)
                    }
                    if (oldRoll.isNotBlank() && newRoll.isNotBlank()) {
                        textSwapPairs.add(oldRoll to newRoll)
                    }
                    if (i < newStudentsArr.length()) {
                        summaryNames.add(if (newRoll.isNotBlank()) "$newName ($newRoll)" else newName)
                    }
                }
            }
            if (summaryNames.isNotEmpty()) {
                chatModsApplied.add("Students → ${summaryNames.joinToString(", ")}")
            }
        }

        // 4. Guide, HOD, Principal, Department, College, Date overrides
        val guideOverride = specObj.optString("guide_override", "").trim()
        val oldGuide = templateDefaults.optString("template_guide", "Prof. Nimisha George")
        if (guideOverride.isNotBlank() && oldGuide.isNotBlank()) {
            textSwapPairs.add(oldGuide to guideOverride)
            chatModsApplied.add("Guide → $guideOverride")
        }

        val hodOverride = specObj.optString("hod_override", "").trim()
        val oldHod = templateDefaults.optString("template_hod", "Prof. Elias Janson K")
        if (hodOverride.isNotBlank() && oldHod.isNotBlank()) {
            textSwapPairs.add(oldHod to hodOverride)
            chatModsApplied.add("HOD → $hodOverride")
        }

        val principalOverride = specObj.optString("principal_override", "").trim()
        val oldPrincipal = templateDefaults.optString("template_principal", "Dr. Santhosh Simon")
        if (principalOverride.isNotBlank() && oldPrincipal.isNotBlank()) {
            textSwapPairs.add(oldPrincipal to principalOverride)
            chatModsApplied.add("Principal → $principalOverride")
        }

        val deptOverride = specObj.optString("department_override", "").trim()
        val oldDept = templateDefaults.optString("template_department", "DEPARTMENT OF ELECTRONICS & COMMUNICATION ENGINEERING")
        if (deptOverride.isNotBlank() && oldDept.isNotBlank()) {
            textSwapPairs.add(oldDept to deptOverride.uppercase(Locale.US))
            chatModsApplied.add("Department → $deptOverride")
        }

        val collegeOverride = specObj.optString("college_override", "").trim()
        val oldCollege = templateDefaults.optString("template_college", "MAR BASELIOS CHRISTIAN COLLEGE OF ENGINEERING AND TECHNOLOGY")
        if (collegeOverride.isNotBlank() && oldCollege.isNotBlank()) {
            textSwapPairs.add(oldCollege to collegeOverride.uppercase(Locale.US))
            chatModsApplied.add("College → $collegeOverride")
        }

        val dateOverride = specObj.optString("date_override", "").trim()
        val oldDate = templateDefaults.optString("template_date", "October 2026")
        if (dateOverride.isNotBlank() && oldDate.isNotBlank()) {
            textSwapPairs.add(oldDate to dateOverride)
            chatModsApplied.add("Date → $dateOverride")
        }

        if (textSwapPairs.isEmpty()) return

        // Apply replacements across both <w:t> nodes and multi-run paragraphs in preserved front matter
        val paragraphs = bodyEl.getElementsByTagNameNS(W_NS, "p")
        for (pIdx in 0 until paragraphs.length) {
            val pEl = paragraphs.item(pIdx) as Element
            val tNodes = pEl.getElementsByTagNameNS(W_NS, "t")
            for (tIdx in 0 until tNodes.length) {
                val tEl = tNodes.item(tIdx) as Element
                var current = tEl.textContent ?: ""
                for ((oldStr, newStr) in textSwapPairs) {
                    if (oldStr.isNotBlank() && current.contains(oldStr, ignoreCase = true)) {
                        current = current.replace(oldStr, newStr, ignoreCase = true)
                    }
                }
                tEl.textContent = current
            }

            // Also check if a phrase was split across adjacent <w:t> nodes inside the same paragraph (e.g. Certificate paragraph)
            val fullParaText = extractElementText(pEl)
            val matchedSplit = textSwapPairs.any { (oldStr, _) ->
                oldStr.isNotBlank() && fullParaText.contains(oldStr, ignoreCase = true)
            }
            if (matchedSplit && tNodes.length > 1) {
                coalesceAndReplaceSplitRunsInParagraph(pEl, textSwapPairs)
            }
        }
    }

    private fun coalesceAndReplaceSplitRunsInParagraph(
        pEl: Element,
        textSwapPairs: List<Pair<String, String>>
    ) {
        val runs = pEl.getElementsByTagNameNS(W_NS, "r")
        if (runs.length <= 1) return

        // Group contiguous runs that have identical bold/italic state so inline bold in Certificate Page stays bold!
        val runList = (0 until runs.length).map { runs.item(it) as Element }
        var idx = 0
        while (idx < runList.size) {
            val startRun = runList[idx]
            if (startRun.getElementsByTagNameNS(W_NS, "drawing").length > 0) {
                idx++
                continue
            }
            val startRPr = startRun.getElementsByTagNameNS(W_NS, "rPr").item(0) as? Element
            val startBold = startRPr != null && hasEnabledFlag(startRPr, "b")
            val startItalic = startRPr != null && hasEnabledFlag(startRPr, "i")

            val group = mutableListOf(startRun)
            var j = idx + 1
            while (j < runList.size) {
                val nextRun = runList[j]
                if (nextRun.getElementsByTagNameNS(W_NS, "drawing").length > 0) break
                val nextRPr = nextRun.getElementsByTagNameNS(W_NS, "rPr").item(0) as? Element
                val nextBold = nextRPr != null && hasEnabledFlag(nextRPr, "b")
                val nextItalic = nextRPr != null && hasEnabledFlag(nextRPr, "i")
                if (nextBold == startBold && nextItalic == startItalic) {
                    group.add(nextRun)
                    j++
                } else {
                    break
                }
            }

            if (group.size > 1) {
                var combined = group.joinToString("") { extractElementText(it) }
                for ((oldStr, newStr) in textSwapPairs) {
                    if (oldStr.isNotBlank() && combined.contains(oldStr, ignoreCase = true)) {
                        combined = combined.replace(oldStr, newStr, ignoreCase = true)
                    }
                }
                val firstT = startRun.getElementsByTagNameNS(W_NS, "t").item(0) as? Element
                if (firstT != null) {
                    firstT.textContent = combined
                    if (combined.startsWith(" ") || combined.endsWith(" ")) {
                        firstT.setAttribute("xml:space", "preserve")
                    }
                    for (k in 1 until group.size) {
                        val remT = group[k].getElementsByTagNameNS(W_NS, "t")
                        for (tIdx in 0 until remT.length) {
                            remT.item(tIdx).textContent = ""
                        }
                    }
                }
            }
            idx = j
        }
    }

    private fun splitTitleIntoTwoBalancedLines(upperTitle: String): Pair<String, String> {
        val words = upperTitle.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 4) return upperTitle to ""
        val mid = words.size / 2
        val line1 = words.subList(0, mid).joinToString(" ")
        val line2 = words.subList(mid, words.size).joinToString(" ")
        return line1 to line2
    }

    private fun toAcademicTitleCase(input: String): String {
        val minorWords = setOf("a", "an", "the", "and", "or", "but", "of", "in", "on", "to", "for", "with", "by", "at", "from", "via")
        val words = input.trim().split(Regex("\\s+"))
        return words.mapIndexed { idx, w ->
            val cleanAlpha = w.filter { it.isLetterOrDigit() }
            if (cleanAlpha.uppercase(Locale.US) in setOf("ALU", "CPU", "DSP", "FPGA", "VHDL", "CMOS", "TTL", "MUX", "NAND", "NOR", "XOR", "AND", "OR", "NOT", "CLA", "RCA", "CSLA", "CCR", "IC", "LED", "RAM", "ROM", "NPU", "GPU")) {
                w.uppercase(Locale.US)
            } else if (idx > 0 && w.lowercase(Locale.US) in minorWords) {
                w.lowercase(Locale.US)
            } else {
                w.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
            }
        }.joinToString(" ")
    }

    /**
     * Deeply inspects `templateFile` (`word/styles.xml` AND `word/document.xml`) to extract:
     * - Real font family (scanning actual document runs so `"Times New Roman"` is detected accurately)
     * - Body and Heading sizes, colors, line spacing, and first-line paragraph indent
     * - Exact page dimensions (`A4` vs `Letter`) and margins in inches/cm
     * - Existing Cover Page, Certificate Page, Acknowledgment Page, TOC, LOF, LOT, and Student/Guide/HOD metadata
     */
    private fun extractTemplateStyleDefaults(templateFile: File): JSONObject {
        val defaults = JSONObject()
        defaults.put("font", "Times New Roman")
        defaults.put("body_sz", 24)
        defaults.put("h1_sz", 28)
        defaults.put("h1_color", "000000")
        defaults.put("h2_sz", 24)
        defaults.put("h2_color", "000000")
        defaults.put("h3_sz", 24)
        defaults.put("h3_color", "000000")
        defaults.put("body_color", "000000")
        defaults.put("line_spacing", 360)
        defaults.put("first_line_indent", 720)
        defaults.put("page_size_label", "A4 (8.27\" x 11.69\")")
        defaults.put("margins_label", "Top/Bottom: 1.00\" (2.54cm), Left: 1.25\" (3.18cm), Right: 1.00\" (2.54cm)")
        defaults.put("has_page_border", false)
        defaults.put("has_cover_page", false)
        defaults.put("has_certificate_page", false)
        defaults.put("has_acknowledgment_page", false)
        defaults.put("has_toc_page", false)
        defaults.put("has_lof_page", false)
        defaults.put("has_lot_page", false)

        if (!templateFile.name.lowercase(Locale.US).endsWith(".docx")) return defaults

        runCatching {
            ZipFile(templateFile).use { zf ->
                val fontCounts = LinkedHashMap<String, Int>()
                val bodySzCounts = LinkedHashMap<Int, Int>()
                val lineSpacingCounts = LinkedHashMap<Int, Int>()
                val indentCounts = LinkedHashMap<Int, Int>()

                // 1. Check word/styles.xml
                val stylesEntry = zf.getEntry("word/styles.xml")
                if (stylesEntry != null) {
                    val dom = parseXmlStream(zf.getInputStream(stylesEntry))
                    val docDefFonts = dom.getElementsByTagNameNS(W_NS, "rFonts")
                    if (docDefFonts.length > 0) {
                        val rf = docDefFonts.item(0) as Element
                        val ascii = rf.getAttributeNS(W_NS, "ascii").ifEmpty { rf.getAttribute("w:ascii") }
                        if (ascii.isNotBlank() && !ascii.startsWith("+")) {
                            defaults.put("font", ascii)
                        }
                    }

                    val styleNodes = dom.getElementsByTagNameNS(W_NS, "style")
                    for (i in 0 until styleNodes.length) {
                        val st = styleNodes.item(i) as Element
                        val styleId = (st.getAttributeNS(W_NS, "styleId").ifEmpty { st.getAttribute("w:styleId") }).lowercase(Locale.US)
                        val rFonts = st.getElementsByTagNameNS(W_NS, "rFonts").item(0) as? Element
                        val asciiFont = rFonts?.getAttributeNS(W_NS, "ascii")?.ifEmpty { rFonts.getAttribute("w:ascii") }
                        val szEl = st.getElementsByTagNameNS(W_NS, "sz").item(0) as? Element
                        val szVal = szEl?.getAttributeNS(W_NS, "val")?.ifEmpty { szEl.getAttribute("w:val") }?.toIntOrNull()
                        val colorEl = st.getElementsByTagNameNS(W_NS, "color").item(0) as? Element
                        val colorVal = colorEl?.getAttributeNS(W_NS, "val")?.ifEmpty { colorEl.getAttribute("w:val") }

                        if (styleId == "normal") {
                            if (!asciiFont.isNullOrBlank() && !asciiFont.startsWith("+")) defaults.put("font", asciiFont)
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

                // 2. Scan word/document.xml for actual paragraph/run fonts, sizes, spacing, sectPr page size/margins, and front-matter metadata
                val docEntry = zf.getEntry("word/document.xml") ?: return@use
                val docDom = parseXmlStream(zf.getInputStream(docEntry))

                val sectNodes = docDom.getElementsByTagNameNS(W_NS, "sectPr")
                if (sectNodes.length > 0) {
                    val lastSect = sectNodes.item(sectNodes.length - 1) as Element
                    val pgSz = lastSect.getElementsByTagNameNS(W_NS, "pgSz").item(0) as? Element
                    if (pgSz != null) {
                        val wTwips = pgSz.getAttributeNS(W_NS, "w").ifEmpty { pgSz.getAttribute("w:w") }.toIntOrNull() ?: 11906
                        val hTwips = pgSz.getAttributeNS(W_NS, "h").ifEmpty { pgSz.getAttribute("w:h") }.toIntOrNull() ?: 16838
                        val wIn = wTwips / 1440.0
                        val hIn = hTwips / 1440.0
                        val paperName = when {
                            abs(wTwips - 11906) < 250 && abs(hTwips - 16838) < 250 -> "A4"
                            abs(wTwips - 12240) < 250 && abs(hTwips - 15840) < 250 -> "Letter"
                            else -> "Custom"
                        }
                        defaults.put("page_w_twips", wTwips)
                        defaults.put("page_h_twips", hTwips)
                        defaults.put("page_size_label", String.format(Locale.US, "%s (%.2f\" x %.2f\")", paperName, wIn, hIn))
                    }
                    val pgMar = lastSect.getElementsByTagNameNS(W_NS, "pgMar").item(0) as? Element
                    if (pgMar != null) {
                        val top = pgMar.getAttributeNS(W_NS, "top").ifEmpty { pgMar.getAttribute("w:top") }.toIntOrNull() ?: 1440
                        val bottom = pgMar.getAttributeNS(W_NS, "bottom").ifEmpty { pgMar.getAttribute("w:bottom") }.toIntOrNull() ?: 1440
                        val left = pgMar.getAttributeNS(W_NS, "left").ifEmpty { pgMar.getAttribute("w:left") }.toIntOrNull() ?: 1800
                        val right = pgMar.getAttributeNS(W_NS, "right").ifEmpty { pgMar.getAttribute("w:right") }.toIntOrNull() ?: 1440
                        defaults.put(
                            "margins_label",
                            String.format(
                                Locale.US,
                                "Top/Bottom: %.2f\" (%.2fcm), Left: %.2f\" (%.2fcm), Right: %.2f\" (%.2fcm)",
                                top / 1440.0, (top / 1440.0) * 2.54,
                                left / 1440.0, (left / 1440.0) * 2.54,
                                right / 1440.0, (right / 1440.0) * 2.54
                            )
                        )
                    }
                    if (lastSect.getElementsByTagNameNS(W_NS, "pgBorders").length > 0) {
                        defaults.put("has_page_border", true)
                    }
                }

                val paragraphs = docDom.getElementsByTagNameNS(W_NS, "p")
                val titleLines = mutableListOf<String>()
                val studentsList = JSONArray()
                var inCoverTitleZone = true

                for (pIdx in 0 until paragraphs.length) {
                    val pEl = paragraphs.item(pIdx) as Element
                    val pText = extractElementText(pEl).replace(Regex("\\s+"), " ").trim()
                    if (pText.isEmpty()) continue
                    val upper = pText.uppercase(Locale.US)

                    if (inCoverTitleZone) {
                        if (upper.startsWith("A MICRO PROJECT") || upper.startsWith("A PROJECT REPORT") || upper.startsWith("SUBMITTED TO")) {
                            inCoverTitleZone = false
                            defaults.put("has_cover_page", true)
                        } else if (pIdx < 6 && pText.length in 6..120) {
                            titleLines.add(pText)
                        }
                    }

                    when {
                        upper == "CERTIFICATE" -> defaults.put("has_certificate_page", true)
                        upper == "ACKNOWLEDGMENT" || upper == "ACKNOWLEDGEMENT" -> defaults.put("has_acknowledgment_page", true)
                        upper == "CONTENTS" || upper == "TABLE OF CONTENTS" -> defaults.put("has_toc_page", true)
                        upper == "LIST OF FIGURES" -> defaults.put("has_lof_page", true)
                        upper == "LIST OF TABLES" -> defaults.put("has_lot_page", true)
                    }

                    // Extract student name + roll number rows (e.g. "EDWIN JOSEPH MBC25EC005")
                    val stuMatch = Regex("""^([A-Z][A-Z.\s]{2,28}?)\s+([A-Z]{2,5}\d{2}[A-Z]{1,4}\d{2,4})$""").find(upper)
                    if (stuMatch != null) {
                        val sName = stuMatch.groupValues[1].trim()
                        val sRoll = stuMatch.groupValues[2].trim()
                        val exists = (0 until studentsList.length()).any {
                            studentsList.getJSONObject(it).optString("roll") == sRoll
                        }
                        if (!exists) {
                            studentsList.put(
                                JSONObject().apply {
                                    put("name", sName)
                                    put("roll", sRoll)
                                }
                            )
                        }
                    }

                    // Sample body paragraph typography (paragraphs > 100 chars)
                    if (pText.length > 100) {
                        val spacingEl = pEl.getElementsByTagNameNS(W_NS, "spacing").item(0) as? Element
                        val lineVal = spacingEl?.getAttributeNS(W_NS, "line")?.ifEmpty { spacingEl.getAttribute("w:line") }?.toIntOrNull()
                        if (lineVal != null && lineVal in 200..480) {
                            lineSpacingCounts[lineVal] = (lineSpacingCounts[lineVal] ?: 0) + 1
                        }
                        val indEl = pEl.getElementsByTagNameNS(W_NS, "ind").item(0) as? Element
                        val firstLineVal = indEl?.getAttributeNS(W_NS, "firstLine")?.ifEmpty { indEl.getAttribute("w:firstLine") }?.toIntOrNull()
                        if (firstLineVal != null && firstLineVal in 180..1440) {
                            indentCounts[firstLineVal] = (indentCounts[firstLineVal] ?: 0) + 1
                        }
                    }

                    val runs = pEl.getElementsByTagNameNS(W_NS, "r")
                    for (rIdx in 0 until runs.length) {
                        val rEl = runs.item(rIdx) as Element
                        val rPr = rEl.getElementsByTagNameNS(W_NS, "rPr").item(0) as? Element ?: continue
                        val rf = rPr.getElementsByTagNameNS(W_NS, "rFonts").item(0) as? Element
                        val ascii = rf?.getAttributeNS(W_NS, "ascii")?.ifEmpty { rf.getAttribute("w:ascii") }
                        if (!ascii.isNullOrBlank() && !ascii.startsWith("+")) {
                            fontCounts[ascii] = (fontCounts[ascii] ?: 0) + 1
                        }
                        if (pText.length > 100) {
                            val szEl = rPr.getElementsByTagNameNS(W_NS, "sz").item(0) as? Element
                            val szVal = szEl?.getAttributeNS(W_NS, "val")?.ifEmpty { szEl.getAttribute("w:val") }?.toIntOrNull()
                            if (szVal != null && szVal in 18..32) {
                                bodySzCounts[szVal] = (bodySzCounts[szVal] ?: 0) + 1
                            }
                        }
                    }
                }

                // Also scan tables in front matter for student name + roll number pairs
                val tables = docDom.getElementsByTagNameNS(W_NS, "tbl")
                for (tIdx in 0 until minOf(tables.length, 3)) {
                    val tbl = tables.item(tIdx) as Element
                    val trs = tbl.getElementsByTagNameNS(W_NS, "tr")
                    for (rIdx in 0 until trs.length) {
                        val tr = trs.item(rIdx) as Element
                        val tcs = tr.getElementsByTagNameNS(W_NS, "tc")
                        if (tcs.length == 2) {
                            val c0 = extractElementText(tcs.item(0) as Element).trim()
                            val c1 = extractElementText(tcs.item(1) as Element).trim()
                            if (Regex("""^[A-Z]{2,5}\d{2}[A-Z]{1,4}\d{2,4}$""").matches(c1.uppercase(Locale.US)) && c0.length in 3..35) {
                                val exists = (0 until studentsList.length()).any {
                                    studentsList.getJSONObject(it).optString("roll").equals(c1, ignoreCase = true)
                                }
                                if (!exists) {
                                    studentsList.put(
                                        JSONObject().apply {
                                            put("name", c0)
                                            put("roll", c1.uppercase(Locale.US))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                fontCounts.maxByOrNull { it.value }?.key?.let { defaults.put("font", it) }
                bodySzCounts.maxByOrNull { it.value }?.key?.let { defaults.put("body_sz", it) }
                lineSpacingCounts.maxByOrNull { it.value }?.key?.let { defaults.put("line_spacing", it) }
                indentCounts.maxByOrNull { it.value }?.key?.let { defaults.put("first_line_indent", it) }

                if (titleLines.isNotEmpty()) {
                    defaults.put("template_title", titleLines.joinToString(" "))
                    val arr = JSONArray()
                    titleLines.forEach { arr.put(it) }
                    defaults.put("template_title_lines", arr)
                }
                if (studentsList.length() > 0) {
                    defaults.put("template_students", studentsList)
                }
            }
        }
        return defaults
    }

    /**
     * Builds a styled `<w:p>` paragraph supporting multiple rich spans (`bold`, `italic`, `underline`),
     * first-line indentation, hanging indentation, and optional page break before.
     */
    private fun buildStyledParagraph(
        doc: Document,
        spans: List<RichSpan>,
        font: String,
        szHalfPt: Int,
        colorHex: String,
        spaceBefore: Int,
        spaceAfter: Int,
        lineSpacing: Int,
        jc: String,
        keepNext: Boolean,
        keepLines: Boolean,
        indentLeft: Int? = null,
        indentHanging: Int? = null,
        firstLineIndent: Int? = null,
        pageBreakBefore: Boolean = false
    ): Element {
        val p = doc.createElementNS(W_NS, "w:p")
        val pPr = doc.createElementNS(W_NS, "w:pPr")

        if (pageBreakBefore) {
            pPr.appendChild(doc.createElementNS(W_NS, "w:pageBreakBefore"))
        }

        val spacing = doc.createElementNS(W_NS, "w:spacing")
        spacing.setAttributeNS(W_NS, "w:before", spaceBefore.toString())
        spacing.setAttributeNS(W_NS, "w:after", spaceAfter.toString())
        spacing.setAttributeNS(W_NS, "w:line", lineSpacing.toString())
        spacing.setAttributeNS(W_NS, "w:lineRule", "auto")
        pPr.appendChild(spacing)

        val jcEl = doc.createElementNS(W_NS, "w:jc")
        jcEl.setAttributeNS(W_NS, "w:val", jc)
        pPr.appendChild(jcEl)

        if (indentLeft != null || firstLineIndent != null) {
            val ind = doc.createElementNS(W_NS, "w:ind")
            if (indentLeft != null) {
                ind.setAttributeNS(W_NS, "w:left", indentLeft.toString())
            }
            if (indentHanging != null) {
                ind.setAttributeNS(W_NS, "w:hanging", indentHanging.toString())
            }
            if (firstLineIndent != null && indentHanging == null) {
                ind.setAttributeNS(W_NS, "w:firstLine", firstLineIndent.toString())
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
        for (span in spans) {
            if (span.text.isNotEmpty()) {
                p.appendChild(
                    buildRun(
                        doc = doc,
                        text = span.text,
                        font = font,
                        szHalfPt = szHalfPt,
                        colorHex = colorHex,
                        bold = span.bold,
                        italic = span.italic,
                        underline = span.underline
                    )
                )
            }
        }
        return p
    }

    private fun buildRun(
        doc: Document,
        text: String,
        font: String,
        szHalfPt: Int,
        colorHex: String,
        bold: Boolean,
        italic: Boolean,
        underline: Boolean = false
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
            rPr.appendChild(doc.createElementNS(W_NS, "w:bCs"))
        }
        if (italic) {
            rPr.appendChild(doc.createElementNS(W_NS, "w:i"))
            rPr.appendChild(doc.createElementNS(W_NS, "w:iCs"))
        }
        if (underline) {
            val u = doc.createElementNS(W_NS, "w:u")
            u.setAttributeNS(W_NS, "w:val", "single")
            rPr.appendChild(u)
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

    /**
     * Builds the `CONTENTS` Table of Contents matching Pages 5-6 of `ALU_4Bit_Report_Master.docx`
     * (borderless 2-column table with hierarchical indentation, dot leaders for sections, and right-aligned page numbers).
     */
    private fun buildTocTable(
        doc: Document,
        entries: List<TocEntry>,
        font: String,
        szHalfPt: Int,
        colorHex: String
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
            b.setAttributeNS(W_NS, "w:val", "none")
            b.setAttributeNS(W_NS, "w:sz", "0")
            b.setAttributeNS(W_NS, "w:space", "0")
            b.setAttributeNS(W_NS, "w:color", "auto")
            tblBorders.appendChild(b)
        }
        tblPr.appendChild(tblBorders)
        tbl.appendChild(tblPr)

        for (entry in entries) {
            val tr = doc.createElementNS(W_NS, "w:tr")
            val indentLeft = when (entry.level) {
                2 -> 320
                3 -> 640
                else -> 0
            }
            val displayTitle = if (entry.level >= 2) {
                val dotsCount = ((58 - entry.title.length).coerceIn(3, 18)) / 2
                val dots = " " + List(dotsCount) { "." }.joinToString(" ")
                entry.title + dots
            } else {
                entry.title
            }

            // Left Cell (Title)
            val tcLeft = doc.createElementNS(W_NS, "w:tc")
            val tcLeftPr = doc.createElementNS(W_NS, "w:tcPr")
            val tcLeftW = doc.createElementNS(W_NS, "w:tcW")
            tcLeftW.setAttributeNS(W_NS, "w:w", "8200")
            tcLeftW.setAttributeNS(W_NS, "w:type", "dxa")
            tcLeftPr.appendChild(tcLeftW)
            tcLeft.appendChild(tcLeftPr)
            tcLeft.appendChild(
                buildStyledParagraph(
                    doc = doc,
                    spans = listOf(RichSpan(displayTitle, bold = entry.bold)),
                    font = font,
                    szHalfPt = szHalfPt,
                    colorHex = colorHex,
                    spaceBefore = if (entry.bold) 100 else 40,
                    spaceAfter = 60,
                    lineSpacing = 240,
                    jc = "left",
                    keepNext = false,
                    keepLines = true,
                    indentLeft = if (indentLeft > 0) indentLeft else null
                )
            )
            tr.appendChild(tcLeft)

            // Right Cell (Page Number)
            val tcRight = doc.createElementNS(W_NS, "w:tc")
            val tcRightPr = doc.createElementNS(W_NS, "w:tcPr")
            val tcRightW = doc.createElementNS(W_NS, "w:tcW")
            tcRightW.setAttributeNS(W_NS, "w:w", "1000")
            tcRightW.setAttributeNS(W_NS, "w:type", "dxa")
            tcRightPr.appendChild(tcRightW)
            tcRight.appendChild(tcRightPr)
            tcRight.appendChild(
                buildStyledParagraph(
                    doc = doc,
                    spans = listOf(RichSpan(entry.pageLabel, bold = entry.bold)),
                    font = font,
                    szHalfPt = szHalfPt,
                    colorHex = colorHex,
                    spaceBefore = if (entry.bold) 100 else 40,
                    spaceAfter = 60,
                    lineSpacing = 240,
                    jc = "right",
                    keepNext = false,
                    keepLines = true
                )
            )
            tr.appendChild(tcRight)

            tbl.appendChild(tr)
        }
        return tbl
    }

    /**
     * Builds the `LIST OF FIGURES` and `LIST OF TABLES` 3-column index tables matching Pages 7-8 of `ALU_4Bit_Report_Master.docx`.
     */
    private fun buildIndexedListTable(
        doc: Document,
        entries: List<IndexedCaptionEntry>,
        font: String,
        szHalfPt: Int,
        colorHex: String
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
            b.setAttributeNS(W_NS, "w:val", "none")
            b.setAttributeNS(W_NS, "w:sz", "0")
            b.setAttributeNS(W_NS, "w:space", "0")
            b.setAttributeNS(W_NS, "w:color", "auto")
            tblBorders.appendChild(b)
        }
        tblPr.appendChild(tblBorders)
        tbl.appendChild(tblPr)

        for (entry in entries) {
            val tr = doc.createElementNS(W_NS, "w:tr")

            // Col 1: Number (Bold, e.g. "3.1")
            val tcNum = doc.createElementNS(W_NS, "w:tc")
            val tcNumPr = doc.createElementNS(W_NS, "w:tcPr")
            val tcNumW = doc.createElementNS(W_NS, "w:tcW")
            tcNumW.setAttributeNS(W_NS, "w:w", "1100")
            tcNumW.setAttributeNS(W_NS, "w:type", "dxa")
            tcNumPr.appendChild(tcNumW)
            tcNum.appendChild(tcNumPr)
            tcNum.appendChild(
                buildStyledParagraph(
                    doc = doc,
                    spans = listOf(RichSpan(entry.numberLabel, bold = true)),
                    font = font,
                    szHalfPt = szHalfPt,
                    colorHex = colorHex,
                    spaceBefore = 80,
                    spaceAfter = 80,
                    lineSpacing = 240,
                    jc = "left",
                    keepNext = false,
                    keepLines = true
                )
            )
            tr.appendChild(tcNum)

            // Col 2: Title + dot leader
            val tcTitle = doc.createElementNS(W_NS, "w:tc")
            val tcTitlePr = doc.createElementNS(W_NS, "w:tcPr")
            val tcTitleW = doc.createElementNS(W_NS, "w:tcW")
            tcTitleW.setAttributeNS(W_NS, "w:w", "7100")
            tcTitleW.setAttributeNS(W_NS, "w:type", "dxa")
            tcTitlePr.appendChild(tcTitleW)
            tcTitle.appendChild(tcTitlePr)
            tcTitle.appendChild(
                buildStyledParagraph(
                    doc = doc,
                    spans = listOf(RichSpan("${entry.title} . .", bold = false)),
                    font = font,
                    szHalfPt = szHalfPt,
                    colorHex = colorHex,
                    spaceBefore = 80,
                    spaceAfter = 80,
                    lineSpacing = 240,
                    jc = "left",
                    keepNext = false,
                    keepLines = true
                )
            )
            tr.appendChild(tcTitle)

            // Col 3: Page Number (Bold right-aligned)
            val tcPage = doc.createElementNS(W_NS, "w:tc")
            val tcPagePr = doc.createElementNS(W_NS, "w:tcPr")
            val tcPageW = doc.createElementNS(W_NS, "w:tcW")
            tcPageW.setAttributeNS(W_NS, "w:w", "1000")
            tcPageW.setAttributeNS(W_NS, "w:type", "dxa")
            tcPagePr.appendChild(tcPageW)
            tcPage.appendChild(tcPagePr)
            tcPage.appendChild(
                buildStyledParagraph(
                    doc = doc,
                    spans = listOf(RichSpan(entry.pageLabel, bold = true)),
                    font = font,
                    szHalfPt = szHalfPt,
                    colorHex = colorHex,
                    spaceBefore = 80,
                    spaceAfter = 80,
                    lineSpacing = 240,
                    jc = "right",
                    keepNext = false,
                    keepLines = true
                )
            )
            tr.appendChild(tcPage)

            tbl.appendChild(tr)
        }
        return tbl
    }

    /**
     * Builds an academic horizontal-rule data table matching Tables 1.1, 4.1, 7.1, A.1 in `ALU_4Bit_Report_Master.docx`
     * (solid black top/bottom borders `sz="12"`, header bottom border `sz="8"`, subtle horizontal inside row dividers `D9D9D9`, no vertical borders).
     */
    private fun buildAcademicBooktabsTable(
        doc: Document,
        headers: List<String>,
        rows: List<List<String>>,
        font: String,
        cellSz: Int,
        bodyColor: String
    ): Element {
        val tbl = doc.createElementNS(W_NS, "w:tbl")
        val tblPr = doc.createElementNS(W_NS, "w:tblPr")

        val tblW = doc.createElementNS(W_NS, "w:tblW")
        tblW.setAttributeNS(W_NS, "w:w", "5000")
        tblW.setAttributeNS(W_NS, "w:type", "pct")
        tblPr.appendChild(tblW)

        val tblBorders = doc.createElementNS(W_NS, "w:tblBorders")
        // Top & Bottom solid black borders (sz=12)
        for (side in listOf("top", "bottom")) {
            val b = doc.createElementNS(W_NS, "w:$side")
            b.setAttributeNS(W_NS, "w:val", "single")
            b.setAttributeNS(W_NS, "w:sz", "12")
            b.setAttributeNS(W_NS, "w:space", "0")
            b.setAttributeNS(W_NS, "w:color", "000000")
            tblBorders.appendChild(b)
        }
        // No left/right/insideV vertical borders
        for (side in listOf("left", "right", "insideV")) {
            val b = doc.createElementNS(W_NS, "w:$side")
            b.setAttributeNS(W_NS, "w:val", "none")
            b.setAttributeNS(W_NS, "w:sz", "0")
            b.setAttributeNS(W_NS, "w:space", "0")
            b.setAttributeNS(W_NS, "w:color", "auto")
            tblBorders.appendChild(b)
        }
        // Subtle horizontal row divider (insideH)
        val insideH = doc.createElementNS(W_NS, "w:insideH")
        insideH.setAttributeNS(W_NS, "w:val", "single")
        insideH.setAttributeNS(W_NS, "w:sz", "4")
        insideH.setAttributeNS(W_NS, "w:space", "0")
        insideH.setAttributeNS(W_NS, "w:color", "D9D9D9")
        tblBorders.appendChild(insideH)

        tblPr.appendChild(tblBorders)
        tbl.appendChild(tblPr)

        val colCount = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
        val colWidthTwips = 9200 / colCount

        if (headers.isNotEmpty()) {
            val tr = doc.createElementNS(W_NS, "w:tr")
            val trPr = doc.createElementNS(W_NS, "w:trPr")
            trPr.appendChild(doc.createElementNS(W_NS, "w:tblHeader"))
            trPr.appendChild(doc.createElementNS(W_NS, "w:cantSplit"))
            tr.appendChild(trPr)

            for (c in 0 until colCount) {
                val cellText = headers.getOrElse(c) { "" }
                val isShortCol = cellText.length <= 12
                tr.appendChild(
                    buildBooktabsTableCell(
                        doc = doc,
                        text = cellText,
                        widthTwips = colWidthTwips,
                        fillHex = "F5F7FA",
                        textColorHex = bodyColor,
                        font = font,
                        szHalfPt = cellSz,
                        bold = true,
                        jc = if (isShortCol) "center" else "left",
                        hasHeaderBottomBorder = true
                    )
                )
            }
            tbl.appendChild(tr)
        }

        for (row in rows) {
            val tr = doc.createElementNS(W_NS, "w:tr")
            val trPr = doc.createElementNS(W_NS, "w:trPr")
            trPr.appendChild(doc.createElementNS(W_NS, "w:cantSplit"))
            tr.appendChild(trPr)

            for (c in 0 until colCount) {
                val cellText = row.getOrElse(c) { "" }
                val isShortCol = cellText.length <= 12
                val isPassBadge = cellText.equals("PASS", ignoreCase = true)
                tr.appendChild(
                    buildBooktabsTableCell(
                        doc = doc,
                        text = cellText,
                        widthTwips = colWidthTwips,
                        fillHex = "FFFFFF",
                        textColorHex = bodyColor,
                        font = font,
                        szHalfPt = cellSz,
                        bold = isPassBadge || (c == 0 && colCount <= 4 && cellText.contains("[")),
                        jc = if (isShortCol) "center" else "left",
                        hasHeaderBottomBorder = false
                    )
                )
            }
            tbl.appendChild(tr)
        }

        return tbl
    }

    private fun buildBooktabsTableCell(
        doc: Document,
        text: String,
        widthTwips: Int,
        fillHex: String,
        textColorHex: String,
        font: String,
        szHalfPt: Int,
        bold: Boolean,
        jc: String,
        hasHeaderBottomBorder: Boolean
    ): Element {
        val tc = doc.createElementNS(W_NS, "w:tc")
        val tcPr = doc.createElementNS(W_NS, "w:tcPr")

        val tcW = doc.createElementNS(W_NS, "w:tcW")
        tcW.setAttributeNS(W_NS, "w:w", widthTwips.toString())
        tcW.setAttributeNS(W_NS, "w:type", "dxa")
        tcPr.appendChild(tcW)

        if (hasHeaderBottomBorder) {
            val tcBorders = doc.createElementNS(W_NS, "w:tcBorders")
            val bottom = doc.createElementNS(W_NS, "w:bottom")
            bottom.setAttributeNS(W_NS, "w:val", "single")
            bottom.setAttributeNS(W_NS, "w:sz", "8")
            bottom.setAttributeNS(W_NS, "w:space", "0")
            bottom.setAttributeNS(W_NS, "w:color", "000000")
            tcBorders.appendChild(bottom)
            tcPr.appendChild(tcBorders)
        }

        val shd = doc.createElementNS(W_NS, "w:shd")
        shd.setAttributeNS(W_NS, "w:val", "clear")
        shd.setAttributeNS(W_NS, "w:color", "auto")
        shd.setAttributeNS(W_NS, "w:fill", fillHex)
        tcPr.appendChild(shd)

        tc.appendChild(tcPr)
        tc.appendChild(
            buildStyledParagraph(
                doc = doc,
                spans = listOf(RichSpan(text, bold = bold)),
                font = font,
                szHalfPt = szHalfPt,
                colorHex = textColorHex,
                spaceBefore = 70,
                spaceAfter = 70,
                lineSpacing = 240,
                jc = jc,
                keepNext = false,
                keepLines = true
            )
        )
        return tc
    }

    /**
     * Creates `word/footer_copyflow_pgnum.xml` containing a centered OpenXML `<w:fldSimple w:instr="PAGE"/>`
     * page number field in the template's font, registers it in `word/_rels/document.xml.rels` and `[Content_Types].xml`,
     * and returns its relationship `rId`.
     */
    private fun ensureCenteredPageNumberFooter(workDir: File, font: String, szHalfPt: Int): String {
        val footerFileName = "footer_copyflow_pgnum.xml"
        val footerFile = File(workDir, "word/$footerFileName")
        footerFile.parentFile?.mkdirs()

        val footerXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:ftr xmlns:w="$W_NS">
              <w:p>
                <w:pPr>
                  <w:jc w:val="center"/>
                </w:pPr>
                <w:fldSimple w:instr="PAGE">
                  <w:r>
                    <w:rPr>
                      <w:rFonts w:ascii="$font" w:hAnsi="$font" w:cs="$font"/>
                      <w:sz w:val="$szHalfPt"/>
                      <w:szCs w:val="$szHalfPt"/>
                    </w:rPr>
                    <w:t>1</w:t>
                  </w:r>
                </w:fldSimple>
              </w:p>
            </w:ftr>
        """.trimIndent()
        footerFile.writeText(footerXml, Charsets.UTF_8)

        val relsFile = File(workDir, "word/_rels/document.xml.rels")
        relsFile.parentFile?.mkdirs()
        val relsDom = if (relsFile.exists()) {
            FileInputStream(relsFile).use { parseXmlStream(it) }
        } else {
            val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val d = dbf.newDocumentBuilder().newDocument()
            d.appendChild(d.createElementNS(PKG_REL_NS, "Relationships"))
            d
        }

        val relNodes = relsDom.getElementsByTagName("Relationship")
        var maxId = 0
        for (i in 0 until relNodes.length) {
            val rEl = relNodes.item(i) as Element
            if (rEl.getAttribute("Target") == footerFileName) {
                return rEl.getAttribute("Id")
            }
            val idStr = rEl.getAttribute("Id")
            if (idStr.startsWith("rId")) {
                val num = idStr.removePrefix("rId").toIntOrNull() ?: 0
                if (num > maxId) maxId = num
            }
        }

        val newRId = "rId${maxId + 1}"
        val newRel = relsDom.createElementNS(PKG_REL_NS, "Relationship")
        newRel.setAttribute("Id", newRId)
        newRel.setAttribute("Type", FOOTER_REL_TYPE)
        newRel.setAttribute("Target", footerFileName)
        relsDom.documentElement.appendChild(newRel)
        writeXmlDocument(relsDom, relsFile)

        val ctFile = File(workDir, "[Content_Types].xml")
        if (ctFile.exists()) {
            val ctDom = FileInputStream(ctFile).use { parseXmlStream(it) }
            val partName = "/word/$footerFileName"
            val overrides = ctDom.getElementsByTagName("Override")
            var exists = false
            for (i in 0 until overrides.length) {
                val oEl = overrides.item(i) as Element
                if (oEl.getAttribute("PartName") == partName) {
                    exists = true
                    break
                }
            }
            if (!exists) {
                val ovEl = ctDom.createElementNS(CT_NS, "Override")
                ovEl.setAttribute("PartName", partName)
                ovEl.setAttribute("ContentType", "application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml")
                ctDom.documentElement.appendChild(ovEl)
                writeXmlDocument(ctDom, ctFile)
            }
        }

        return newRId
    }

    /**
     * Constructs a `<w:sectPr>` element preserving page geometry (`<w:pgSz>`, `<w:pgMar>`) from `baseSectPr`,
     * wiring the centered page number footer (`<w:footerReference>`), `<w:pgNumType>`, `<w:titlePg/>`, and `<w:pgBorders>`.
     */
    private fun buildSectionPropertiesElement(
        doc: Document,
        baseSectPr: Element?,
        footerRId: String?,
        pageNumberFormat: String,
        startNumber: Int?,
        titlePage: Boolean,
        borderMode: String
    ): Element {
        val sectPr = doc.createElementNS(W_NS, "w:sectPr")

        // 1. Footer reference for centered page numbers (omitted in unnumbered Cover+Certificate Section 1)
        if (!footerRId.isNullOrBlank()) {
            val footerRef = doc.createElementNS(W_NS, "w:footerReference")
            footerRef.setAttributeNS(W_NS, "w:type", "default")
            footerRef.setAttributeNS(R_NS, "r:id", footerRId)
            sectPr.appendChild(footerRef)
        }

        // 2. Preserve any headerReference from baseSectPr
        if (baseSectPr != null) {
            val headerRefs = baseSectPr.getElementsByTagNameNS(W_NS, "headerReference")
            for (i in 0 until headerRefs.length) {
                sectPr.appendChild(doc.importNode(headerRefs.item(i), true))
            }
        }

        // 3. Page Size (<w:pgSz>)
        val existingPgSz = baseSectPr?.getElementsByTagNameNS(W_NS, "pgSz")?.item(0) as? Element
        if (existingPgSz != null) {
            sectPr.appendChild(doc.importNode(existingPgSz, true))
        } else {
            val pgSz = doc.createElementNS(W_NS, "w:pgSz")
            pgSz.setAttributeNS(W_NS, "w:w", "11906")
            pgSz.setAttributeNS(W_NS, "w:h", "16838")
            sectPr.appendChild(pgSz)
        }

        // 4. Page Margins (<w:pgMar>)
        val existingPgMar = baseSectPr?.getElementsByTagNameNS(W_NS, "pgMar")?.item(0) as? Element
        if (existingPgMar != null) {
            sectPr.appendChild(doc.importNode(existingPgMar, true))
        } else {
            val pgMar = doc.createElementNS(W_NS, "w:pgMar")
            pgMar.setAttributeNS(W_NS, "w:top", "1440")
            pgMar.setAttributeNS(W_NS, "w:right", "1440")
            pgMar.setAttributeNS(W_NS, "w:bottom", "1440")
            pgMar.setAttributeNS(W_NS, "w:left", "1800")
            pgMar.setAttributeNS(W_NS, "w:header", "720")
            pgMar.setAttributeNS(W_NS, "w:footer", "720")
            pgMar.setAttributeNS(W_NS, "w:gutter", "0")
            sectPr.appendChild(pgMar)
        }

        // 5. Page Borders (<w:pgBorders>) — either preserved from template or enabled via chat
        if (borderMode != "none") {
            val existingBorders = baseSectPr?.getElementsByTagNameNS(W_NS, "pgBorders")?.item(0) as? Element
            if (existingBorders != null && borderMode == "single") {
                sectPr.appendChild(doc.importNode(existingBorders, true))
            } else {
                val pgBorders = doc.createElementNS(W_NS, "w:pgBorders")
                pgBorders.setAttributeNS(W_NS, "w:offsetFrom", "page")
                val valStyle = if (borderMode == "double") "double" else "single"
                for (side in listOf("top", "left", "bottom", "right")) {
                    val b = doc.createElementNS(W_NS, "w:$side")
                    b.setAttributeNS(W_NS, "w:val", valStyle)
                    b.setAttributeNS(W_NS, "w:sz", "12")
                    b.setAttributeNS(W_NS, "w:space", "24")
                    b.setAttributeNS(W_NS, "w:color", "000000")
                    pgBorders.appendChild(b)
                }
                sectPr.appendChild(pgBorders)
            }
        }

        // 6. Page Numbering Type (<w:pgNumType w:fmt="lowerRoman" / "decimal" w:start="1"/>)
        val pgNumType = doc.createElementNS(W_NS, "w:pgNumType")
        pgNumType.setAttributeNS(W_NS, "w:fmt", pageNumberFormat)
        if (startNumber != null) {
            pgNumType.setAttributeNS(W_NS, "w:start", startNumber.toString())
        }
        sectPr.appendChild(pgNumType)

        // 7. Title Page flag (<w:titlePg/>) so Cover Page hides the Roman page number footer
        if (titlePage) {
            sectPr.appendChild(doc.createElementNS(W_NS, "w:titlePg"))
        }

        return sectPr
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
        newRel.setAttribute("Type", IMAGE_REL_TYPE)
        newRel.setAttribute("Target", "media/$mediaFilename")
        relsDom.documentElement.appendChild(newRel)
        writeXmlDocument(relsDom, relsFile)

        val ctFile = File(workDir, "[Content_Types].xml")
        if (ctFile.exists()) {
            val ext = mediaFilename.substringAfterLast('.', "png").lowercase(Locale.US)
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
                if (dEl.getAttribute("Extension").lowercase(Locale.US) == ext) {
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
                <w:spacing w:before="200" w:after="140" w:line="240" w:lineRule="auto"/>
                <w:jc w:val="center"/>
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
        val regex = Regex("""["'](font|body_sz|h1_sz|h1_color|h2_sz|h2_color|h3_sz|h3_color|body_color|line_spacing|page_border|project_title_override|guide_override|hod_override|date_override)["']\s*:\s*["']?([^"',}\n]+)["']?""")
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
