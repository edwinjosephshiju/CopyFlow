package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class AttachmentRole(val label: String) {
    TEMPLATE("Template"),
    SOURCE("Source"),
    REFERENCE_IMAGE("Image")
}

data class AttachedDocumentFile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val posixPath: String,
    val sizeBytes: Long,
    val mimeType: String,
    val role: AttachmentRole
)

data class CopyFlowUiState(
    val modelState: ModelState = ModelState.NotLoaded(),
    val sessionMetadata: SessionGraphMetadata? = null,
    val sessions: List<ChatSessionEntity> = emptyList(),
    val activeSessionId: String? = null,
    val draftSandboxId: String = UUID.randomUUID().toString(),
    val messages: List<ChatMessageEntity> = emptyList(),
    val composerText: String = "",
    val pendingAttachments: List<AttachedDocumentFile> = emptyList(),
    val activeTemplatePath: String? = null,
    val activeTemplateName: String? = null,
    val activeSourcePath: String? = null,
    val activeSourceName: String? = null,
    val grantedFolderName: String? = null,
    val isGenerating: Boolean = false,
    val streamingAssistantText: String = "",
    val streamingThoughtText: String = "",
    val streamingThoughtSummaryLine: String = "",
    val isThinkingPhase: Boolean = false,
    val streamingThoughtDurationMs: Long = 0L,
    val activeToolBanner: String? = null,
    val lastTokensPerSec: Float = 0f,
    val executionProvider: ExecutionProviderMode = ExecutionProviderMode.AUTO_NPU_GPU,
    val includeVisionEncoder: Boolean = false,
    val maxNewTokens: Int = 512,
    val artifactStatuses: List<Pair<HfModelArtifact, Long>> = emptyList(),
    val showSettingsModal: Boolean = false,
    val showAttachmentSheet: Boolean = false,
    val historySearchQuery: String = "",
    val telemetrySnapshot: HardwareTelemetrySnapshot = HardwareTelemetrySnapshot(),
    val telemetryRuns: List<TelemetryRunEntity> = emptyList(),
    val cloudTelemetryRuns: List<CloudTelemetryRun> = emptyList(),
    val signedInUserId: String = "",
    val signedInUserEmail: String? = null,
    val githubRepoSlug: String = "edwinjosephshiju/CopyFlow",
    val hasGitHubToken: Boolean = false,
    val autoSyncTelemetryGitHub: Boolean = true,
    val releaseState: GitHubReleaseState = GitHubReleaseState.Idle
) {
    val isModelReady: Boolean
        get() = modelState is ModelState.Ready

    val canSend: Boolean
        get() = isModelReady && !isGenerating && (composerText.isNotBlank() || pendingAttachments.isNotEmpty())

    val currentSandboxSessionId: String
        get() = activeSessionId ?: draftSandboxId
}

class TaskModeViewModel(
    private val appContext: Context,
    private val firestoreTelemetryRepo: FirestoreTelemetrySinkRepository,
    private val currentUserId: String,
    private val currentUserEmail: String?
) : ViewModel() {

    private val dao = TaskHistoryDatabase.getInstance(appContext).dao()
    private val modelManager = QwenModelManager(appContext)
    private val qwenEngine = QwenOnnxNpuEngine()
    private val pythonBridge = ChaquopyPythonBridge(appContext)
    private val telemetryMonitor = HardwareTelemetryMonitor(appContext)
    private val releaseUpdater = GitHubReleaseUpdater(appContext)

    private val _uiState = MutableStateFlow(
        CopyFlowUiState(
            grantedFolderName = pythonBridge.getGrantedDirectoryName(),
            signedInUserId = currentUserId,
            signedInUserEmail = currentUserEmail,
            githubRepoSlug = telemetryMonitor.getConfiguredGitHubRepo(),
            hasGitHubToken = telemetryMonitor.hasConfiguredGitHubToken()
        )
    )
    val uiState: StateFlow<CopyFlowUiState> = _uiState.asStateFlow()

    private var activeMessagesJob: Job? = null
    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            modelManager.modelState.collectLatest { mState ->
                _uiState.update {
                    it.copy(
                        modelState = mState,
                        artifactStatuses = modelManager.getArtifactStatusList()
                    )
                }
                if (mState is ModelState.Ready) {
                    runCatching {
                        val meta = qwenEngine.initializeIfNeeded(mState)
                        telemetryMonitor.onSessionGraphInspected(meta)
                        _uiState.update { it.copy(sessionMetadata = meta) }
                    }.onFailure { err ->
                        _uiState.update {
                            it.copy(
                                modelState = ModelState.Error(
                                    message = "Failed to initialize ONNX Runtime session: ${err.message}",
                                    stackTrace = err.stackTraceToString()
                                )
                            )
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            telemetryMonitor.snapshot.collectLatest { snap ->
                _uiState.update {
                    it.copy(
                        telemetrySnapshot = snap,
                        hasGitHubToken = telemetryMonitor.hasConfiguredGitHubToken()
                    )
                }
            }
        }

        viewModelScope.launch {
            dao.observeRecentTelemetryRuns().collectLatest { runs ->
                _uiState.update { it.copy(telemetryRuns = runs) }
            }
        }

        // Observe real-time Cloud Firestore Telemetry Sink (/users/{uid}/telemetry_runs)
        viewModelScope.launch {
            firestoreTelemetryRepo.observeTelemetryRuns()
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val rootError = firestoreError ?: error
                    telemetryMonitor.setCloudSyncStatus(
                        "Firestore Sink Error: ${rootError.message ?: "Unable to observe cloud runs"}"
                    )
                }
                .collectLatest { cloudRuns ->
                    _uiState.update { it.copy(cloudTelemetryRuns = cloudRuns) }
                    telemetryMonitor.setCloudSyncStatus(
                        "Cloud Firestore Sink Active (${cloudRuns.size} cloud runs synced)"
                    )
                }
        }

        viewModelScope.launch {
            releaseUpdater.releaseState.collectLatest { relState ->
                _uiState.update { it.copy(releaseState = relState) }
            }
        }

        viewModelScope.launch {
            dao.observeAllSessions().collectLatest { sessionList ->
                _uiState.update { it.copy(sessions = sessionList) }
            }
        }

        viewModelScope.launch {
            val savedConfig = dao.getModelConfig() ?: ModelConfigEntity()
            val provider = runCatching {
                ExecutionProviderMode.valueOf(savedConfig.executionProvider)
            }.getOrDefault(ExecutionProviderMode.AUTO_NPU_GPU)

            _uiState.update {
                it.copy(
                    executionProvider = provider,
                    includeVisionEncoder = savedConfig.includeVisionEncoder,
                    maxNewTokens = savedConfig.maxNewTokens,
                    autoSyncTelemetryGitHub = savedConfig.autoSyncTelemetryGitHub,
                    artifactStatuses = modelManager.getArtifactStatusList(),
                    grantedFolderName = pythonBridge.getGrantedDirectoryName(),
                    githubRepoSlug = telemetryMonitor.getConfiguredGitHubRepo(),
                    hasGitHubToken = telemetryMonitor.hasConfiguredGitHubToken()
                )
            }
            modelManager.inspectDiskAndRefreshState(
                provider = provider,
                includeVision = savedConfig.includeVisionEncoder
            )
        }
    }

    fun updateComposerText(text: String) {
        _uiState.update { it.copy(composerText = text) }
    }

    fun updateHistorySearchQuery(query: String) {
        _uiState.update { it.copy(historySearchQuery = query) }
    }

    fun toggleSettingsModal(show: Boolean) {
        _uiState.update {
            it.copy(
                showSettingsModal = show,
                artifactStatuses = modelManager.getArtifactStatusList(),
                grantedFolderName = pythonBridge.getGrantedDirectoryName()
            )
        }
    }

    fun toggleAttachmentSheet(show: Boolean) {
        _uiState.update {
            it.copy(
                showAttachmentSheet = show,
                grantedFolderName = pythonBridge.getGrantedDirectoryName()
            )
        }
    }

    fun grantDirectoryAccess(treeUri: Uri) {
        val folderName = pythonBridge.saveGrantedDirectoryTree(treeUri)
        _uiState.update { it.copy(grantedFolderName = folderName) }
        Toast.makeText(
            appContext,
            "Granted directory access to '$folderName' for Chat Sandbox tools",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun setExecutionProvider(provider: ExecutionProviderMode) {
        viewModelScope.launch {
            val current = _uiState.value
            dao.saveModelConfig(
                ModelConfigEntity(
                    id = 1,
                    executionProvider = provider.name,
                    includeVisionEncoder = current.includeVisionEncoder,
                    maxNewTokens = current.maxNewTokens,
                    autoSyncTelemetryGitHub = current.autoSyncTelemetryGitHub
                )
            )
            _uiState.update { it.copy(executionProvider = provider) }
            modelManager.inspectDiskAndRefreshState(
                provider = provider,
                includeVision = current.includeVisionEncoder
            )
        }
    }

    fun setIncludeVisionEncoder(include: Boolean) {
        viewModelScope.launch {
            val current = _uiState.value
            dao.saveModelConfig(
                ModelConfigEntity(
                    id = 1,
                    executionProvider = current.executionProvider.name,
                    includeVisionEncoder = include,
                    maxNewTokens = current.maxNewTokens,
                    autoSyncTelemetryGitHub = current.autoSyncTelemetryGitHub
                )
            )
            _uiState.update { it.copy(includeVisionEncoder = include) }
            modelManager.inspectDiskAndRefreshState(
                provider = current.executionProvider,
                includeVision = include
            )
        }
    }

    fun setAutoSyncTelemetryGitHub(enabled: Boolean) {
        viewModelScope.launch {
            val current = _uiState.value
            dao.saveModelConfig(
                ModelConfigEntity(
                    id = 1,
                    executionProvider = current.executionProvider.name,
                    includeVisionEncoder = current.includeVisionEncoder,
                    maxNewTokens = current.maxNewTokens,
                    autoSyncTelemetryGitHub = enabled
                )
            )
            _uiState.update { it.copy(autoSyncTelemetryGitHub = enabled) }
        }
    }

    fun updateGitHubRepoSlug(repoSlug: String) {
        telemetryMonitor.setConfiguredGitHubRepo(repoSlug)
        _uiState.update { it.copy(githubRepoSlug = repoSlug.trim()) }
    }

    fun syncTelemetryToGitHubNow() {
        viewModelScope.launch {
            telemetryMonitor.setCloudSyncStatus("Syncing runs to Cloud Firestore Telemetry Sink...")
            // Always capture a live hardware snapshot run when the user taps "Sync to Firestore"
            val snap = telemetryMonitor.snapshot.value
            val livePingRun = TelemetryRunEntity(
                id = UUID.randomUUID().toString(),
                sessionId = _uiState.value.currentSandboxSessionId,
                timestamp = System.currentTimeMillis(),
                socBadge = snap.socBadge,
                executionProvider = _uiState.value.executionProvider.name,
                promptTokens = snap.promptTokenCount,
                generatedTokens = snap.generatedTokenCount,
                batchTokenizeMs = snap.batchTokenizeLatencyMs,
                gpuPrefetchMs = snap.gpuEmbedPrefetchLatencyMs,
                prefillTokPerSec = snap.prefillTokensPerSec,
                decodeTokPerSec = snap.decodeTokensPerSec,
                avgNpuStepMs = snap.avgNpuStepLatencyMs,
                peakNpuUtilPercent = snap.peakNpuUtilizationPercent,
                thermalStatusLabel = snap.thermalStatusLabel,
                isThermalThrottling = snap.isThermalThrottling,
                nativeHeapMb = snap.nativeHeapAllocatedMb,
                availRamMb = snap.systemAvailRamMb,
                inspectDurationMs = snap.openXmlInspectDurationMs,
                cloneDurationMs = snap.openXmlCloneDurationMs,
                compactedTurns = snap.compactedTurnsArchived,
                status = "MANUAL_SYNC_SNAPSHOT"
            )
            dao.insertTelemetryRun(livePingRun)

            val runs = dao.getRecentTelemetryRunsOnce()
            val result = firestoreTelemetryRepo.syncAllLocalRunsToFirestore(runs)
            result.onSuccess { count ->
                val okMsg = "Synced $count telemetry runs to Cloud Firestore (/users/$currentUserId/telemetry_runs)"
                telemetryMonitor.setCloudSyncStatus(okMsg)
                Toast.makeText(appContext, okMsg, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                val errMsg = "Firestore Sync failed: ${err.message}"
                telemetryMonitor.setCloudSyncStatus(errMsg)
                Toast.makeText(appContext, errMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun exportAndShareTelemetryJson() {
        viewModelScope.launch {
            try {
                val runs = dao.getRecentTelemetryRunsOnce()
                val jsonFile = telemetryMonitor.writeTelemetryFlightLogFile(runs)
                val uri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    jsonFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "CopyFlow Hexagon v73 HTP NPU & Thermal Telemetry")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "Export NPU Telemetry Flight Log").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(appContext, "Telemetry export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun checkForGitHubReleaseUpdates() {
        val repoSlug = _uiState.value.githubRepoSlug
        viewModelScope.launch {
            releaseUpdater.checkForUpdates(repoSlug)
        }
    }

    fun downloadGitHubReleaseApk(updateInfo: GitHubReleaseState.UpdateAvailable) {
        viewModelScope.launch {
            releaseUpdater.downloadAndVerifyReleaseApk(updateInfo)
        }
    }

    fun installDownloadedReleaseApk(apkFilePath: String) {
        releaseUpdater.triggerPackageInstall(apkFilePath)
    }

    fun downloadModelFromHuggingFace() {
        val current = _uiState.value
        viewModelScope.launch {
            modelManager.downloadFromHuggingFace(
                provider = current.executionProvider,
                includeVision = current.includeVisionEncoder
            )
        }
    }

    fun cancelModelDownload() {
        modelManager.cancelDownload()
    }

    fun sideloadModelDirectory(treeUri: Uri) {
        val current = _uiState.value
        viewModelScope.launch {
            modelManager.sideloadFromSafDirectory(
                treeUri = treeUri,
                provider = current.executionProvider,
                includeVision = current.includeVisionEncoder
            )
        }
    }

    fun sideloadSingleModelFile(fileUri: Uri) {
        val current = _uiState.value
        viewModelScope.launch {
            modelManager.sideloadSingleFile(
                fileUri = fileUri,
                provider = current.executionProvider,
                includeVision = current.includeVisionEncoder
            )
        }
    }

    /**
     * Copies the selected SAF document directly into the active Chat Sandbox
     * (`filesDir/chat_sandboxes/<sessionId>/`) with both its original filename and
     * a canonical role alias (`template.docx` or `source.docx`).
     */
    fun attachSafDocument(uri: Uri, preferredRole: AttachmentRole? = null) {
        viewModelScope.launch {
            try {
                val currentState = _uiState.value
                val sandboxId = currentState.currentSandboxSessionId

                val guessedRole = preferredRole ?: when {
                    currentState.activeTemplatePath == null &&
                        currentState.pendingAttachments.none { it.role == AttachmentRole.TEMPLATE } ->
                        AttachmentRole.TEMPLATE
                    else -> AttachmentRole.SOURCE
                }

                val sandboxFile = pythonBridge.copySafUriToChatSandbox(
                    uri = uri,
                    sessionId = sandboxId,
                    role = guessedRole
                )
                val lower = sandboxFile.name.lowercase()
                val finalRole = if (preferredRole == null && (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
                    AttachmentRole.REFERENCE_IMAGE
                } else {
                    guessedRole
                }

                if (finalRole != guessedRole) {
                    pythonBridge.createRoleAliasInSandbox(
                        sandboxFile,
                        pythonBridge.getChatSandboxDir(sandboxId),
                        finalRole
                    )
                }

                val mime = appContext.contentResolver.getType(uri) ?: when {
                    lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    lower.endsWith(".pdf") -> "application/pdf"
                    lower.endsWith(".png") -> "image/png"
                    else -> "image/jpeg"
                }

                val attached = AttachedDocumentFile(
                    name = sandboxFile.name,
                    posixPath = sandboxFile.absolutePath,
                    sizeBytes = sandboxFile.length(),
                    mimeType = mime,
                    role = finalRole
                )

                _uiState.update { state ->
                    val updatedAttachments = state.pendingAttachments + attached
                    val newTemplatePath = if (finalRole == AttachmentRole.TEMPLATE) sandboxFile.absolutePath else state.activeTemplatePath
                    val newTemplateName = if (finalRole == AttachmentRole.TEMPLATE) sandboxFile.name else state.activeTemplateName
                    val newSourcePath = if (finalRole == AttachmentRole.SOURCE) sandboxFile.absolutePath else state.activeSourcePath
                    val newSourceName = if (finalRole == AttachmentRole.SOURCE) sandboxFile.name else state.activeSourceName

                    state.copy(
                        pendingAttachments = updatedAttachments,
                        activeTemplatePath = newTemplatePath,
                        activeTemplateName = newTemplateName,
                        activeSourcePath = newSourcePath,
                        activeSourceName = newSourceName,
                        showAttachmentSheet = false
                    )
                }

                Toast.makeText(
                    appContext,
                    "Copied ${sandboxFile.name} to Chat Sandbox (${finalRole.label})",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    appContext,
                    "Sandbox attachment copy failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun cycleAttachmentRole(attachmentId: String) {
        _uiState.update { state ->
            val sandboxDir = pythonBridge.getChatSandboxDir(state.currentSandboxSessionId)
            val updated = state.pendingAttachments.map { item ->
                if (item.id == attachmentId) {
                    val nextRole = when (item.role) {
                        AttachmentRole.TEMPLATE -> AttachmentRole.SOURCE
                        AttachmentRole.SOURCE -> AttachmentRole.TEMPLATE
                        AttachmentRole.REFERENCE_IMAGE -> AttachmentRole.REFERENCE_IMAGE
                    }
                    val f = File(item.posixPath)
                    if (f.exists()) {
                        pythonBridge.createRoleAliasInSandbox(f, sandboxDir, nextRole)
                    }
                    item.copy(role = nextRole)
                } else {
                    item
                }
            }
            val tpl = updated.lastOrNull { it.role == AttachmentRole.TEMPLATE }
            val src = updated.lastOrNull { it.role == AttachmentRole.SOURCE }
            state.copy(
                pendingAttachments = updated,
                activeTemplatePath = tpl?.posixPath ?: state.activeTemplatePath,
                activeTemplateName = tpl?.name ?: state.activeTemplateName,
                activeSourcePath = src?.posixPath ?: state.activeSourcePath,
                activeSourceName = src?.name ?: state.activeSourceName
            )
        }
    }

    fun removePendingAttachment(attachmentId: String) {
        _uiState.update { state ->
            state.copy(
                pendingAttachments = state.pendingAttachments.filterNot { it.id == attachmentId }
            )
        }
    }

    fun startNewChat() {
        generationJob?.cancel()
        activeMessagesJob?.cancel()
        _uiState.update {
            it.copy(
                activeSessionId = null,
                draftSandboxId = UUID.randomUUID().toString(),
                messages = emptyList(),
                composerText = "",
                pendingAttachments = emptyList(),
                activeTemplatePath = null,
                activeTemplateName = null,
                activeSourcePath = null,
                activeSourceName = null,
                isGenerating = false,
                streamingAssistantText = "",
                activeToolBanner = null
            )
        }
    }

    fun selectChatSession(sessionId: String) {
        generationJob?.cancel()
        activeMessagesJob?.cancel()
        viewModelScope.launch {
            val session = dao.getSessionById(sessionId) ?: return@launch
            val sandboxDir = pythonBridge.getChatSandboxDir(sessionId)

            // Ensure session's bound template and source files are mirrored into its Chat Sandbox
            val resolvedTpl = session.templatePath?.let { path ->
                pythonBridge.resolveSandboxFile(path, sessionId, AttachmentRole.TEMPLATE, path)
            }
            val resolvedSrc = session.sourcePath?.let { path ->
                pythonBridge.resolveSandboxFile(path, sessionId, AttachmentRole.SOURCE, path)
            }

            _uiState.update {
                it.copy(
                    activeSessionId = session.id,
                    activeTemplatePath = resolvedTpl?.absolutePath ?: session.templatePath,
                    activeTemplateName = resolvedTpl?.name ?: session.templateName,
                    activeSourcePath = resolvedSrc?.absolutePath ?: session.sourcePath,
                    activeSourceName = resolvedSrc?.name ?: session.sourceName,
                    pendingAttachments = emptyList(),
                    isGenerating = false,
                    streamingAssistantText = "",
                    activeToolBanner = null
                )
            }
            activeMessagesJob = viewModelScope.launch {
                dao.observeMessagesForSession(sessionId).collectLatest { msgs ->
                    _uiState.update { it.copy(messages = msgs) }
                }
            }
        }
    }

    fun deleteChatSession(sessionId: String) {
        viewModelScope.launch {
            dao.deleteMessagesForSession(sessionId)
            dao.deleteSessionById(sessionId)
            runCatching {
                pythonBridge.getChatSandboxDir(sessionId).deleteRecursively()
            }
            if (_uiState.value.activeSessionId == sessionId) {
                startNewChat()
            }
        }
    }

    fun clearAllChatHistory() {
        viewModelScope.launch {
            dao.deleteAllMessages()
            dao.deleteAllSessions()
            runCatching {
                File(appContext.filesDir, "chat_sandboxes").deleteRecursively()
            }
            startNewChat()
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        _uiState.update {
            it.copy(
                isGenerating = false,
                streamingAssistantText = "",
                activeToolBanner = null
            )
        }
    }

    fun sendUserMessage(overridePrompt: String? = null) {
        val state = _uiState.value
        val readyModel = state.modelState as? ModelState.Ready ?: return
        if (state.isGenerating) return

        val rawPrompt = (overridePrompt ?: state.composerText).trim()
        val attachments = state.pendingAttachments
        if (rawPrompt.isEmpty() && attachments.isEmpty()) return

        generationJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            // Use the draftSandboxId as the new sessionId so files already copied into the draft sandbox stay in place!
            val sessionId = state.activeSessionId ?: state.draftSandboxId

            val tplAttachment = attachments.lastOrNull { it.role == AttachmentRole.TEMPLATE }
            val srcAttachment = attachments.lastOrNull { it.role == AttachmentRole.SOURCE }

            val resolvedTemplatePath = tplAttachment?.posixPath ?: state.activeTemplatePath
            val resolvedTemplateName = tplAttachment?.name ?: state.activeTemplateName
            val resolvedSourcePath = srcAttachment?.posixPath ?: state.activeSourcePath
            val resolvedSourceName = srcAttachment?.name ?: state.activeSourceName

            // Ensure both files are copied and aliased inside this session's Chat Sandbox
            val sandboxDir = pythonBridge.getChatSandboxDir(sessionId)
            resolvedTemplatePath?.let { path ->
                val f = File(path)
                if (f.exists()) pythonBridge.createRoleAliasInSandbox(f, sandboxDir, AttachmentRole.TEMPLATE)
            }
            resolvedSourcePath?.let { path ->
                val f = File(path)
                if (f.exists()) pythonBridge.createRoleAliasInSandbox(f, sandboxDir, AttachmentRole.SOURCE)
            }

            val sessionTitle = if (state.activeSessionId == null) {
                when {
                    rawPrompt.isNotEmpty() -> rawPrompt.take(46)
                    resolvedTemplateName != null && resolvedSourceName != null ->
                        "Clone $resolvedSourceName → $resolvedTemplateName"
                    else -> attachments.first().name
                }
            } else {
                dao.getSessionById(sessionId)?.title ?: "CopyFlow Task"
            }

            val sessionEntity = ChatSessionEntity(
                id = sessionId,
                title = sessionTitle,
                createdAt = dao.getSessionById(sessionId)?.createdAt ?: now,
                updatedAt = now,
                templatePath = resolvedTemplatePath,
                templateName = resolvedTemplateName,
                sourcePath = resolvedSourcePath,
                sourceName = resolvedSourceName,
                latestOutputDocxPath = dao.getSessionById(sessionId)?.latestOutputDocxPath
            )
            dao.upsertSession(sessionEntity)

            if (state.activeSessionId == null) {
                _uiState.update { it.copy(activeSessionId = sessionId) }
                activeMessagesJob?.cancel()
                activeMessagesJob = viewModelScope.launch {
                    dao.observeMessagesForSession(sessionId).collectLatest { msgs ->
                        _uiState.update { it.copy(messages = msgs) }
                    }
                }
            }

            val attachmentsJsonArr = JSONArray()
            for (att in attachments) {
                val obj = JSONObject()
                obj.put("name", att.name)
                obj.put("posixPath", att.posixPath)
                obj.put("sizeBytes", att.sizeBytes)
                obj.put("role", att.role.name)
                attachmentsJsonArr.put(obj)
            }

            val effectiveUserText = if (rawPrompt.isNotEmpty()) {
                rawPrompt
            } else {
                "Inspect the attached documents and clone the source content into the reference template layout."
            }

            val userMsg = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "user",
                content = effectiveUserText,
                timestamp = now,
                attachmentsJson = attachmentsJsonArr.toString()
            )
            dao.upsertMessage(userMsg)

            _uiState.update {
                it.copy(
                    composerText = "",
                    pendingAttachments = emptyList(),
                    activeTemplatePath = resolvedTemplatePath,
                    activeTemplateName = resolvedTemplateName,
                    activeSourcePath = resolvedSourcePath,
                    activeSourceName = resolvedSourceName,
                    isGenerating = true,
                    streamingAssistantText = "",
                    streamingThoughtText = "",
                    streamingThoughtSummaryLine = "Batch tokenizing prompt on GPU...",
                    isThinkingPhase = true,
                    streamingThoughtDurationMs = 0L,
                    activeToolBanner = null
                )
            }

            runAgentConversationLoop(
                sessionId = sessionId,
                readyModel = readyModel,
                templatePath = resolvedTemplatePath,
                sourcePath = resolvedSourcePath
            )
        }
    }

    private suspend fun runAgentConversationLoop(
        sessionId: String,
        readyModel: ModelState.Ready,
        templatePath: String?,
        sourcePath: String?
    ) {
        telemetryMonitor.onTaskRunStarted()
        var toolIterations = 0
        val maxToolIterations = 4
        val hasBothDocs = !templatePath.isNullOrBlank() && !sourcePath.isNullOrBlank()
        val hasAnyDoc = !templatePath.isNullOrBlank() || !sourcePath.isNullOrBlank()
        var inspectedInThisRun = hasBothDocs && pythonBridge.hasCachedSandboxInspection(sessionId)
        var clonedInThisRun = false
        var inspectMsInRun = 0L
        var cloneMsInRun = 0L
        var lastCloneVerificationMarkdown = ""

        while (toolIterations <= maxToolIterations) {
            val allMessages = dao.getMessagesForSessionOnce(sessionId)
            val combinedUserInstructions = allMessages
                .filter { it.role == "user" }
                .joinToString("\n") { it.content }
            val turns = buildCompactedConversationTurns(
                allMessages = allMessages,
                sessionId = sessionId,
                templatePath = templatePath,
                sourcePath = sourcePath
            )

            // Guided `<think>` chain seed to keep internal reasoning fast, structured, and non-verbose:
            val guidedAssistantPrefix = when {
                !inspectedInThisRun && !clonedInThisRun && hasAnyDoc -> {
                    val tplLabel = templatePath?.let { File(it).name } ?: "none"
                    val srcLabel = sourcePath?.let { File(it).name } ?: "none"
                    "<think>\n1. Sandbox files ready: template=$tplLabel, source=$srcLabel.\n2. Action: execute inspect_template to cache OpenXML styles and source AST.\n</think>\n"
                }
                inspectedInThisRun && !clonedInThisRun && hasBothDocs -> {
                    "<think>\n1. Sandbox inspection_state.json verified (READY_TO_CLONE).\n2. Skipping prose summary; executing clone_document immediately.\n</think>\n"
                }
                clonedInThisRun -> {
                    "<think>\n1. OpenXML .docx reconstruction complete in Chat Sandbox.\n2. Writing concise 4-bullet verification summary.\n</think>\n"
                }
                else -> "<think>\n"
            }

            val chatMlPrompt = qwenEngine.buildChatMlPrompt(
                systemPrompt = pythonBridge.getSkillSystemPromptAddendum(),
                conversationTurns = turns,
                assistantPrefillPrefix = guidedAssistantPrefix
            )

            // Reduce maxNewTokens on final report turns so the model stays crisp and non-verbose
            val stepMaxTokens = when {
                clonedInThisRun -> minOf(_uiState.value.maxNewTokens, 180)
                inspectedInThisRun && !clonedInThisRun -> minOf(_uiState.value.maxNewTokens, 160)
                else -> _uiState.value.maxNewTokens
            }

            var detectedToolCall: ParsedToolCall? = null
            var completedEvent: GenerationEvent.Completed? = null
            var errorEvent: GenerationEvent.Error? = null
            var finalTps = 0f

            qwenEngine.streamGeneration(
                readyState = readyModel,
                promptText = chatMlPrompt,
                maxNewTokens = stepMaxTokens,
                initialAssistantPrefix = guidedAssistantPrefix,
                breakOnProseWhenToolExpected = (inspectedInThisRun && !clonedInThisRun && hasBothDocs),
                telemetryMonitor = telemetryMonitor
            ).collect { event ->
                when (event) {
                    is GenerationEvent.SessionInspected -> {
                        _uiState.update { it.copy(sessionMetadata = event.metadata) }
                    }
                    is GenerationEvent.TokenDelta -> {
                        finalTps = event.tokensPerSec
                        _uiState.update {
                            it.copy(
                                streamingAssistantText = event.visibleTextSoFar,
                                streamingThoughtText = event.thoughtTextSoFar,
                                streamingThoughtSummaryLine = event.thoughtSummaryLine,
                                isThinkingPhase = event.isThinkingPhase,
                                streamingThoughtDurationMs = event.thoughtDurationMs,
                                lastTokensPerSec = event.tokensPerSec
                            )
                        }
                    }
                    is GenerationEvent.ToolCallDetected -> {
                        detectedToolCall = event.toolCall
                    }
                    is GenerationEvent.Completed -> {
                        completedEvent = event
                        finalTps = event.tokensPerSec
                    }
                    is GenerationEvent.Error -> {
                        errorEvent = event
                    }
                }
            }

            if (errorEvent != null) {
                telemetryMonitor.onStageChanged(HardwareExecutionStage.IDLE)
                recordTelemetryRunToDatabase(
                    sessionId = sessionId,
                    inspectDurationMs = inspectMsInRun,
                    cloneDurationMs = cloneMsInRun,
                    status = "ERROR: ${errorEvent!!.message.take(80)}"
                )
                dao.upsertMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        role = "error",
                        content = "${errorEvent!!.message}\n\n${errorEvent!!.stackTrace.take(800)}",
                        timestamp = System.currentTimeMillis()
                    )
                )
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingAssistantText = "",
                        streamingThoughtText = "",
                        streamingThoughtSummaryLine = "",
                        isThinkingPhase = false,
                        activeToolBanner = null
                    )
                }
                return
            }

            val effectiveToolCall: ParsedToolCall? = if (detectedToolCall != null) {
                detectedToolCall
            } else if (inspectedInThisRun && !clonedInThisRun && hasBothDocs) {
                val fallbackThought = buildString {
                    val compThought = completedEvent?.thoughtText.orEmpty().trim()
                    if (compThought.isNotEmpty()) append(compThought)
                    val extraNote = completedEvent?.visibleText.orEmpty().trim()
                    if (extraNote.isNotEmpty()) {
                        if (isNotEmpty()) append("\n")
                        append("Auto-compacted pre-clone note: ").append(extraNote.take(140))
                    }
                }
                val autoArgs = JSONObject().apply {
                    put("template_path", "template.docx")
                    put("source_path", "source.docx")
                    put("spec_json", JSONObject())
                }
                ParsedToolCall(
                    name = "clone_document",
                    argumentsJson = autoArgs,
                    rawBlock = """<tool_call>{"name": "clone_document", "arguments": {"template_path": "template.docx", "source_path": "source.docx", "spec_json": {}}}</tool_call>""",
                    thoughtText = fallbackThought,
                    thoughtDurationMs = completedEvent?.thoughtDurationMs ?: 0L
                )
            } else {
                null
            }

            if (effectiveToolCall != null) {
                toolIterations++
                _uiState.update {
                    it.copy(
                        streamingAssistantText = "",
                        isThinkingPhase = false,
                        activeToolBanner = "Executing Chat Sandbox tool: ${effectiveToolCall.name}(...)"
                    )
                }

                try {
                    val reqTpl = effectiveToolCall.argumentsJson.optString("template_path", templatePath ?: "template.docx")
                    val reqSrc = effectiveToolCall.argumentsJson.optString("source_path", sourcePath ?: "source.docx")
                    val sandboxDir = pythonBridge.getChatSandboxDir(sessionId)

                    when (effectiveToolCall.name) {
                        "inspect_template" -> {
                            telemetryMonitor.onStageChanged(HardwareExecutionStage.PYTHON_SANDBOX_INSPECT)
                            val t0 = System.currentTimeMillis()
                            val report = pythonBridge.inspectTemplate(
                                templatePath = reqTpl,
                                sourcePath = reqSrc,
                                sessionId = sessionId,
                                fallbackTemplatePath = templatePath,
                                fallbackSourcePath = sourcePath,
                                maxChars = 10000
                            )
                            inspectMsInRun = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                            telemetryMonitor.recordOpenXmlToolDuration("inspect_template", inspectMsInRun)
                            inspectedInThisRun = true
                            dao.upsertMessage(
                                ChatMessageEntity(
                                    id = UUID.randomUUID().toString(),
                                    sessionId = sessionId,
                                    role = "tool",
                                    content = "Executed `inspect_template.py` inside Chat Sandbox.",
                                    timestamp = System.currentTimeMillis(),
                                    thoughtText = effectiveToolCall.thoughtText.ifBlank { null },
                                    thoughtDurationMs = effectiveToolCall.thoughtDurationMs,
                                    toolName = "inspect_template",
                                    toolArgsJson = effectiveToolCall.argumentsJson.toString(),
                                    toolOutputText = report,
                                    tokensPerSec = finalTps
                                )
                            )
                        }
                        "clone_document" -> {
                            telemetryMonitor.onStageChanged(HardwareExecutionStage.PYTHON_SANDBOX_CLONE)
                            val t0 = System.currentTimeMillis()
                            val outFile = File(sandboxDir, "CopyFlow_Cloned_${System.currentTimeMillis()}.docx")
                            val specJsonObj = effectiveToolCall.argumentsJson.optJSONObject("spec_json")
                                ?: runCatching { JSONObject(effectiveToolCall.argumentsJson.optString("spec_json", "{}")) }.getOrDefault(JSONObject())

                            val cloneRes = pythonBridge.cloneDocument(
                                templatePath = reqTpl,
                                sourcePath = reqSrc,
                                outputPath = outFile.absolutePath,
                                specJson = specJsonObj.toString(),
                                sessionId = sessionId,
                                fallbackTemplatePath = templatePath,
                                fallbackSourcePath = sourcePath,
                                userChatInstructions = combinedUserInstructions
                            )
                            cloneMsInRun = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                            telemetryMonitor.recordOpenXmlToolDuration("clone_document", cloneMsInRun)
                            clonedInThisRun = true
                            lastCloneVerificationMarkdown = cloneRes.optString("verification_report_markdown", "")
                            dao.upsertMessage(
                                ChatMessageEntity(
                                    id = UUID.randomUUID().toString(),
                                    sessionId = sessionId,
                                    role = "tool",
                                    content = "Reconstructed OpenXML `.docx` in Chat Sandbox.",
                                    timestamp = System.currentTimeMillis(),
                                    thoughtText = effectiveToolCall.thoughtText.ifBlank { null },
                                    thoughtDurationMs = effectiveToolCall.thoughtDurationMs,
                                    toolName = "clone_document",
                                    toolArgsJson = effectiveToolCall.argumentsJson.toString(),
                                    toolOutputText = cloneRes.toString(2),
                                    outputDocxPath = outFile.absolutePath,
                                    tokensPerSec = finalTps
                                )
                            )
                        }
                        "run_custom_openxml_python" -> {
                            telemetryMonitor.onStageChanged(HardwareExecutionStage.PYTHON_SANDBOX_CLONE)
                            val t0 = System.currentTimeMillis()
                            val outFile = File(sandboxDir, "CopyFlow_Custom_${System.currentTimeMillis()}.docx")
                            val scriptCode = effectiveToolCall.argumentsJson.optString("script_code", "")
                            val customRes = pythonBridge.runCustomOpenXmlPython(
                                scriptCode = scriptCode,
                                templatePath = reqTpl,
                                sourcePath = reqSrc,
                                outputPath = outFile.absolutePath,
                                sessionId = sessionId,
                                fallbackTemplatePath = templatePath,
                                fallbackSourcePath = sourcePath,
                                userChatInstructions = combinedUserInstructions
                            )
                            cloneMsInRun = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                            telemetryMonitor.recordOpenXmlToolDuration("run_custom_openxml_python", cloneMsInRun)
                            clonedInThisRun = true
                            lastCloneVerificationMarkdown = customRes.optString("verification_report_markdown", "")
                            dao.upsertMessage(
                                ChatMessageEntity(
                                    id = UUID.randomUUID().toString(),
                                    sessionId = sessionId,
                                    role = "tool",
                                    content = "Executed custom OpenXML script in Chat Sandbox.",
                                    timestamp = System.currentTimeMillis(),
                                    thoughtText = effectiveToolCall.thoughtText.ifBlank { null },
                                    thoughtDurationMs = effectiveToolCall.thoughtDurationMs,
                                    toolName = "run_custom_openxml_python",
                                    toolArgsJson = effectiveToolCall.argumentsJson.toString(),
                                    toolOutputText = customRes.toString(2),
                                    outputDocxPath = outFile.absolutePath,
                                    tokensPerSec = finalTps
                                )
                            )
                        }
                        else -> {
                            throw IllegalArgumentException("Unsupported tool name emitted by model: ${effectiveToolCall.name}")
                        }
                    }
                } catch (toolErr: Exception) {
                    telemetryMonitor.onStageChanged(HardwareExecutionStage.IDLE)
                    recordTelemetryRunToDatabase(
                        sessionId = sessionId,
                        inspectDurationMs = inspectMsInRun,
                        cloneDurationMs = cloneMsInRun,
                        status = "TOOL_ERR: ${toolErr.message?.take(60)}"
                    )
                    dao.upsertMessage(
                        ChatMessageEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            role = "error",
                            content = "Tool execution failed (${effectiveToolCall.name}): ${toolErr.message}",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            streamingAssistantText = "",
                            streamingThoughtText = "",
                            streamingThoughtSummaryLine = "",
                            isThinkingPhase = false,
                            activeToolBanner = null
                        )
                    }
                    return
                }

                _uiState.update { it.copy(activeToolBanner = null) }
                if (clonedInThisRun && lastCloneVerificationMarkdown.isNotBlank()) {
                    dao.upsertMessage(
                        ChatMessageEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            role = "assistant",
                            content = lastCloneVerificationMarkdown,
                            timestamp = System.currentTimeMillis(),
                            thoughtText = effectiveToolCall.thoughtText.ifBlank {
                                "Verified reconstructed OpenXML package, multi-section pagination, dynamic literature expansion, and IEEE citation synchronization."
                            },
                            thoughtDurationMs = effectiveToolCall.thoughtDurationMs,
                            tokensPerSec = finalTps
                        )
                    )
                    break
                }
                continue
            }

            val assistantReply = if (clonedInThisRun && lastCloneVerificationMarkdown.isNotBlank()) {
                lastCloneVerificationMarkdown
            } else {
                completedEvent?.visibleText?.trim().orEmpty()
            }
            val assistantThought = completedEvent?.thoughtText?.trim().orEmpty()
            val assistantThoughtMs = completedEvent?.thoughtDurationMs ?: 0L
            if (assistantReply.isNotEmpty() || assistantThought.isNotEmpty()) {
                dao.upsertMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        role = "assistant",
                        content = assistantReply.ifEmpty { "Task step completed." },
                        timestamp = System.currentTimeMillis(),
                        thoughtText = assistantThought.ifEmpty { null },
                        thoughtDurationMs = assistantThoughtMs,
                        tokensPerSec = finalTps
                    )
                )
            }
            break
        }

        telemetryMonitor.onStageChanged(HardwareExecutionStage.IDLE)
        recordTelemetryRunToDatabase(
            sessionId = sessionId,
            inspectDurationMs = inspectMsInRun,
            cloneDurationMs = cloneMsInRun,
            status = if (clonedInThisRun) "CLONED_DOCX_OK" else "COMPLETED"
        )

        _uiState.update {
            it.copy(
                isGenerating = false,
                streamingAssistantText = "",
                streamingThoughtText = "",
                streamingThoughtSummaryLine = "",
                isThinkingPhase = false,
                activeToolBanner = null
            )
        }
    }

    private suspend fun recordTelemetryRunToDatabase(
        sessionId: String,
        inspectDurationMs: Long,
        cloneDurationMs: Long,
        status: String
    ) {
        val snap = telemetryMonitor.snapshot.value
        val runEntity = TelemetryRunEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            timestamp = System.currentTimeMillis(),
            socBadge = snap.socBadge,
            executionProvider = _uiState.value.executionProvider.name,
            promptTokens = snap.promptTokenCount,
            generatedTokens = snap.generatedTokenCount,
            batchTokenizeMs = snap.batchTokenizeLatencyMs,
            gpuPrefetchMs = snap.gpuEmbedPrefetchLatencyMs,
            prefillTokPerSec = snap.prefillTokensPerSec,
            decodeTokPerSec = snap.decodeTokensPerSec,
            avgNpuStepMs = snap.avgNpuStepLatencyMs,
            peakNpuUtilPercent = snap.peakNpuUtilizationPercent,
            thermalStatusLabel = snap.thermalStatusLabel,
            isThermalThrottling = snap.isThermalThrottling,
            nativeHeapMb = snap.nativeHeapAllocatedMb,
            availRamMb = snap.systemAvailRamMb,
            inspectDurationMs = inspectDurationMs,
            cloneDurationMs = cloneDurationMs,
            compactedTurns = snap.compactedTurnsArchived,
            status = status
        )
        dao.insertTelemetryRun(runEntity)

        if (_uiState.value.autoSyncTelemetryGitHub) {
            val cloudRes = firestoreTelemetryRepo.recordTelemetryRun(runEntity)
            cloudRes.onSuccess { docId ->
                telemetryMonitor.setCloudSyncStatus(
                    "Auto-synced run $docId to Cloud Firestore Telemetry Sink"
                )
            }.onFailure { err ->
                telemetryMonitor.setCloudSyncStatus(
                    "Cloud Firestore auto-sync warning: ${err.message}"
                )
            }
        }
    }

    /**
     * Highly Optimized Internal Thought & History Context Compactor for Qwen3.5-0.8B:
     * 1. Strips 100% of `<think>...</think>` internal reasoning chains from prior turns (`compactThoughtAndProseForPrompt`).
     * 2. Keeps only the last 5 messages in active detail and archives older messages to a 1-line state tag.
     * 3. Replaces raw 10,000-character XML `inspect_template` dumps with a ~140-token JSON digest
     *    from `inspection_state.json` so the 0.8B model's attention stays locked on tool execution.
     */
    private fun buildCompactedConversationTurns(
        allMessages: List<ChatMessageEntity>,
        sessionId: String,
        templatePath: String?,
        sourcePath: String?
    ): List<Pair<String, String>> {
        val validMessages = allMessages.filter { it.role != "error" }
        val recentWindowSize = 5
        val olderMessages = if (validMessages.size > recentWindowSize) {
            validMessages.dropLast(recentWindowSize)
        } else {
            emptyList()
        }
        val recentMessages = if (validMessages.size > recentWindowSize) {
            validMessages.takeLast(recentWindowSize)
        } else {
            validMessages
        }

        val lastCloneIndex = recentMessages.indexOfLast {
            it.role == "tool" && (it.toolName == "clone_document" || it.toolName == "run_custom_openxml_python")
        }

        val turns = mutableListOf<Pair<String, String>>()
        telemetryMonitor.recordCompactedTurns(olderMessages.size)

        if (olderMessages.isNotEmpty()) {
            val olderTools = olderMessages.mapNotNull { it.toolName }.distinct().joinToString(", ")
            val summaryNote = buildString {
                append("[COMPACTED CONTEXT]: ${olderMessages.size} prior turns archived.")
                if (olderTools.isNotBlank()) {
                    append(" Tools run: $olderTools.")
                }
            }
            turns.add("user" to summaryNote)
        }

        for ((idx, msg) in recentMessages.withIndex()) {
            when (msg.role) {
                "user" -> {
                    val isLastUserMsg = (idx == recentMessages.indexOfLast { it.role == "user" })
                    val userText = buildString {
                        append(msg.content)
                        if (isLastUserMsg) {
                            if (!templatePath.isNullOrBlank()) {
                                val tplName = File(templatePath).name
                                append("\n[SANDBOX_TEMPLATE_FILE]: template.docx (original: $tplName)")
                            }
                            if (!sourcePath.isNullOrBlank()) {
                                val srcName = File(sourcePath).name
                                append("\n[SANDBOX_SOURCE_FILE]: source.docx (original: $srcName)")
                            }
                        }
                    }
                    turns.add("user" to userText)
                }
                "tool" -> {
                    val tName = msg.toolName ?: "inspect_template"
                    val tArgs = msg.toolArgsJson?.takeIf { it.isNotBlank() }
                        ?: """{"template_path":"template.docx","source_path":"source.docx"}"""
                    // Emit the matching assistant <tool_call> turn with compacted 1-line <think>
                    turns.add(
                        "assistant" to """<think>[Dispatched $tName]</think><tool_call>{"name": "$tName", "arguments": $tArgs}</tool_call>"""
                    )

                    val isAlreadyClonedLater = (lastCloneIndex > idx)
                    val compactDigest = pythonBridge.buildCompactToolDigestForModel(
                        toolName = msg.toolName,
                        sessionId = sessionId,
                        templatePath = templatePath,
                        sourcePath = sourcePath,
                        rawToolOutput = msg.toolOutputText ?: msg.content,
                        isAlreadyClonedLaterInTurn = isAlreadyClonedLater
                    )
                    turns.add("user" to "<tool_response>\n$compactDigest\n</tool_response>")
                }
                else -> {
                    // Strip all <think>...</think> blocks and compact prior assistant prose
                    val compactedAssistant = ThoughtChainParser.compactThoughtAndProseForPrompt(
                        rawAssistantContent = msg.content,
                        maxChars = 220
                    )
                    turns.add("assistant" to compactedAssistant)
                }
            }
        }

        return turns
    }

    fun shareGeneratedDocx(docxPath: String) {
        try {
            val file = File(docxPath)
            if (!file.exists()) {
                Toast.makeText(appContext, "File not found: $docxPath", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Export Cloned DOCX").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(appContext, "Share error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun openGeneratedDocx(docxPath: String) {
        try {
            val file = File(docxPath)
            if (!file.exists()) {
                Toast.makeText(appContext, "File not found: $docxPath", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(viewIntent)
        } catch (e: Exception) {
            Toast.makeText(
                appContext,
                "No DOCX viewer installed; use Export / Share instead.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCleared() {
        super.onCleared()
        telemetryMonitor.close()
        qwenEngine.closeSessions()
    }
}
