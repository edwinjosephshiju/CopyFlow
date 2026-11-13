package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.ArgonBorderSubtle
import com.example.ui.theme.ArgonCoral
import com.example.ui.theme.ArgonCyan
import com.example.ui.theme.ArgonIndigoSurface
import com.example.ui.theme.ArgonVioletGlow
import com.example.ui.theme.GeminiAuroraDeepBlue
import com.example.ui.theme.GeminiAuroraViolet
import com.example.ui.theme.GeminiBlack
import com.example.ui.theme.GeminiInputBarBg
import com.example.ui.theme.GeminiInputBarStroke
import com.example.ui.theme.GeminiTextMuted
import com.example.ui.theme.GeminiTextSecondary
import com.example.ui.theme.GeminiTextWhite
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.launch
import org.json.JSONArray

@Composable
fun TaskModeScreen(
    modifier: Modifier = Modifier,
    viewModel: TaskModeViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // Legacy storage permission launcher for Android 12 and below (API <= 32)
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // SAF Launchers for Model Folder, Single Model File, Granted Downloads Folder, Template Doc, Source Doc, and Reference Image
    val modelDirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.sideloadModelDirectory(treeUri)
        }
    }

    val modelFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { fileUri ->
        if (fileUri != null) {
            viewModel.sideloadSingleModelFile(fileUri)
        }
    }

    val downloadsFolderTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.grantDirectoryAccess(treeUri)
        }
    }

    val templateDocPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.attachSafDocument(uri, AttachmentRole.TEMPLATE)
        }
    }

    val sourceDocPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.attachSafDocument(uri, AttachmentRole.SOURCE)
        }
    }

    val referenceImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.attachSafDocument(uri, AttachmentRole.REFERENCE_IMAGE)
        }
    }

    val requestStoragePermissionIfLegacy = {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    val docMimeTypes = arrayOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/pdf",
        "*/*"
    )
    val imageMimeTypes = arrayOf("image/png", "image/jpeg", "image/*")

    // Auto-scroll chat list when new messages or streaming tokens arrive
    LaunchedEffect(uiState.messages.size, uiState.streamingAssistantText, uiState.activeToolBanner) {
        val totalItems = uiState.messages.size +
            (if (uiState.streamingAssistantText.isNotEmpty() || uiState.activeToolBanner != null) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            CopyFlowHistoryDrawer(
                sessions = uiState.sessions,
                activeSessionId = uiState.activeSessionId,
                searchQuery = uiState.historySearchQuery,
                onSearchQueryChange = viewModel::updateHistorySearchQuery,
                onSelectSession = viewModel::selectChatSession,
                onDeleteSession = viewModel::deleteChatSession,
                onNewChat = viewModel::startNewChat,
                onOpenSettings = { viewModel.toggleSettingsModal(true) },
                onCloseDrawer = { scope.launch { drawerState.close() } }
            )
        },
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            GeminiBlack,
                            GeminiBlack,
                            GeminiAuroraDeepBlue.copy(alpha = 0.45f),
                            GeminiAuroraViolet.copy(alpha = 0.35f)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
            ) {
                // 1. Clean Top Bar: [Menu] CopyFlow [New Chat]
                CopyFlowTopBar(
                    modelState = uiState.modelState,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onNewChat = viewModel::startNewChat,
                    onOpenSettings = { viewModel.toggleSettingsModal(true) }
                )

                // 1b. Live Hexagon v73 HTP NPU Utilization & Thermal Throttling HUD Bar
                HexagonNpuTelemetryHudBar(
                    telemetry = uiState.telemetrySnapshot,
                    recordedRunsCount = uiState.telemetryRuns.size,
                    onSyncGitHub = viewModel::syncTelemetryToGitHubNow,
                    onExportJson = viewModel::exportAndShareTelemetryJson,
                    onOpenSettings = { viewModel.toggleSettingsModal(true) }
                )

                HorizontalDivider(color = GeminiInputBarStroke.copy(alpha = 0.55f))

                // 2. Scrollable Middle Area (Hero Workspace or Active Chat Thread)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (uiState.messages.isEmpty() && !uiState.isGenerating) {
                        item(key = "hero_workspace") {
                            CopyFlowEmptyWorkspace(
                                uiState = uiState,
                                onDownloadHuggingFace = viewModel::downloadModelFromHuggingFace,
                                onCancelDownload = viewModel::cancelModelDownload,
                                onPickLocalModelFolder = { modelDirPickerLauncher.launch(null) },
                                onOpenSettings = { viewModel.toggleSettingsModal(true) },
                                onPickTemplateDoc = {
                                    requestStoragePermissionIfLegacy()
                                    templateDocPickerLauncher.launch(docMimeTypes)
                                },
                                onPickSourceDoc = {
                                    requestStoragePermissionIfLegacy()
                                    sourceDocPickerLauncher.launch(docMimeTypes)
                                },
                                onRunQuickPrompt = { prompt -> viewModel.sendUserMessage(prompt) }
                            )
                        }
                    } else {
                        items(uiState.messages, key = { it.id }) { msg ->
                            ChatMessageRow(
                                message = msg,
                                onShareDocx = viewModel::shareGeneratedDocx,
                                onOpenDocx = viewModel::openGeneratedDocx
                            )
                        }

                        if (uiState.isGenerating || uiState.streamingAssistantText.isNotEmpty() || uiState.streamingThoughtText.isNotEmpty() || uiState.activeToolBanner != null) {
                            item(key = "streaming_turn") {
                                StreamingAssistantBubble(
                                    streamingText = uiState.streamingAssistantText,
                                    streamingThoughtText = uiState.streamingThoughtText,
                                    streamingThoughtSummaryLine = uiState.streamingThoughtSummaryLine,
                                    isThinkingPhase = uiState.isThinkingPhase,
                                    thoughtDurationMs = uiState.streamingThoughtDurationMs,
                                    activeToolBanner = uiState.activeToolBanner,
                                    tokensPerSec = uiState.lastTokensPerSec
                                )
                            }
                        }
                    }
                }

                // 3. Bottom Composer Capsule
                CopyFlowComposerBar(
                    composerText = uiState.composerText,
                    onComposerTextChange = viewModel::updateComposerText,
                    pendingAttachments = uiState.pendingAttachments,
                    activeTemplateName = uiState.activeTemplateName,
                    activeSourceName = uiState.activeSourceName,
                    isModelReady = uiState.isModelReady,
                    isGenerating = uiState.isGenerating,
                    canSend = uiState.canSend,
                    onOpenAttachmentSheet = {
                        requestStoragePermissionIfLegacy()
                        viewModel.toggleAttachmentSheet(true)
                    },
                    onCycleAttachmentRole = viewModel::cycleAttachmentRole,
                    onRemoveAttachment = viewModel::removePendingAttachment,
                    onSendMessage = { viewModel.sendUserMessage() },
                    onStopGeneration = viewModel::stopGeneration
                )
            }
        }
    }

    if (uiState.showAttachmentSheet) {
        CopyFlowAttachmentSheet(
            grantedFolderName = uiState.grantedFolderName,
            onDismiss = { viewModel.toggleAttachmentSheet(false) },
            onPickTemplateDoc = {
                viewModel.toggleAttachmentSheet(false)
                templateDocPickerLauncher.launch(docMimeTypes)
            },
            onPickSourceDoc = {
                viewModel.toggleAttachmentSheet(false)
                sourceDocPickerLauncher.launch(docMimeTypes)
            },
            onPickReferenceImage = {
                viewModel.toggleAttachmentSheet(false)
                referenceImagePickerLauncher.launch(imageMimeTypes)
            },
            onGrantFolderAccess = {
                viewModel.toggleAttachmentSheet(false)
                downloadsFolderTreeLauncher.launch(null)
            }
        )
    }

    if (uiState.showSettingsModal) {
        CopyFlowSettingsModal(
            uiState = uiState,
            onDismiss = { viewModel.toggleSettingsModal(false) },
            onDownloadHuggingFace = viewModel::downloadModelFromHuggingFace,
            onCancelDownload = viewModel::cancelModelDownload,
            onPickModelDirectory = { modelDirPickerLauncher.launch(null) },
            onPickSingleModelFile = { modelFilePickerLauncher.launch(arrayOf("*/*")) },
            onSelectExecutionProvider = viewModel::setExecutionProvider,
            onToggleVisionEncoder = viewModel::setIncludeVisionEncoder,
            onToggleAutoSyncGitHub = viewModel::setAutoSyncTelemetryGitHub,
            onUpdateGitHubRepoSlug = viewModel::updateGitHubRepoSlug,
            onSyncTelemetryGitHubNow = viewModel::syncTelemetryToGitHubNow,
            onExportTelemetryJson = viewModel::exportAndShareTelemetryJson,
            onCheckGitHubReleases = viewModel::checkForGitHubReleaseUpdates,
            onDownloadReleaseApk = viewModel::downloadGitHubReleaseApk,
            onInstallReleaseApk = viewModel::installDownloadedReleaseApk,
            onClearChatHistory = viewModel::clearAllChatHistory
        )
    }
}

@Composable
private fun CopyFlowEmptyWorkspace(
    uiState: CopyFlowUiState,
    onDownloadHuggingFace: () -> Unit,
    onCancelDownload: () -> Unit,
    onPickLocalModelFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickTemplateDoc: () -> Unit,
    onPickSourceDoc: () -> Unit,
    onRunQuickPrompt: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = ArgonCyan,
            modifier = Modifier.size(38.dp)
        )

        Text(
            text = "On-Device Document Layout Cloner",
            style = MaterialTheme.typography.headlineMedium.copy(
                color = GeminiTextWhite,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp
            )
        )

        Text(
            text = "Powered by Qwen3.5-0.8B-ONNX (Multi-SoC NPU & GPU) + Chat Sandbox OpenXML Reconstruction",
            style = MaterialTheme.typography.bodyMedium.copy(color = GeminiTextSecondary)
        )

        ModelActionRequiredCard(
            modelState = uiState.modelState,
            onDownloadHuggingFace = onDownloadHuggingFace,
            onCancelDownload = onCancelDownload,
            onPickLocalModelFolder = onPickLocalModelFolder,
            onOpenSettings = onOpenSettings
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DocumentSlotCard(
                title = "Reference Template",
                subtitle = uiState.activeTemplateName ?: "Select .docx / .pdf layout",
                isLoaded = !uiState.activeTemplatePath.isNullOrBlank(),
                accentColor = ArgonVioletGlow,
                icon = Icons.Outlined.Description,
                testTag = "pick_template_slot",
                onClick = onPickTemplateDoc,
                modifier = Modifier.weight(1f)
            )

            DocumentSlotCard(
                title = "Source Content",
                subtitle = uiState.activeSourceName ?: "Select .docx / .pdf content",
                isLoaded = !uiState.activeSourcePath.isNullOrBlank(),
                accentColor = ArgonCyan,
                icon = Icons.Outlined.InsertDriveFile,
                testTag = "pick_source_slot",
                onClick = onPickSourceDoc,
                modifier = Modifier.weight(1f)
            )
        }

        val hasBothDocs = !uiState.activeTemplatePath.isNullOrBlank() && !uiState.activeSourcePath.isNullOrBlank()
        val hasAnyDoc = !uiState.activeTemplatePath.isNullOrBlank() || !uiState.activeSourcePath.isNullOrBlank()

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = ArgonIndigoSurface,
            border = BorderStroke(1.dp, ArgonBorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "CHAT SANDBOX SKILL ACTIONS",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = GeminiTextSecondary
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onRunQuickPrompt(
                                "Call inspect_template on template.docx and source.docx in the Chat Sandbox and summarize the exact OpenXML margins, fonts, heading colors, and table structures."
                            )
                        },
                        enabled = uiState.isModelReady && hasAnyDoc,
                        border = BorderStroke(1.dp, GeminiInputBarStroke),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_inspect_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = if (uiState.isModelReady && hasAnyDoc) ArgonCyan else GeminiTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Inspect Layout",
                            color = if (uiState.isModelReady && hasAnyDoc) GeminiTextWhite else GeminiTextMuted,
                            fontSize = 13.sp
                        )
                    }

                    Button(
                        onClick = {
                            onRunQuickPrompt(
                                "Clone source.docx into template.docx's exact OpenXML layout using clone_document, preserving page setup, headers, footers, and typography."
                            )
                        },
                        enabled = uiState.isModelReady && hasBothDocs,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ArgonVioletGlow,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_clone_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Clone on NPU",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentSlotCard(
    title: String,
    subtitle: String,
    isLoaded: Boolean,
    accentColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ArgonIndigoSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isLoaded) accentColor.copy(alpha = 0.65f) else ArgonBorderSubtle
        ),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isLoaded) accentColor else GeminiTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = GeminiTextWhite,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    color = if (isLoaded) accentColor else GeminiTextMuted
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChatMessageRow(
    message: ChatMessageEntity,
    onShareDocx: (String) -> Unit,
    onOpenDocx: (String) -> Unit
) {
    when (message.role) {
        "user" -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val attachments = rememberAttachmentBadges(message.attachmentsJson)
                if (attachments.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for ((name, role) in attachments) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = ArgonIndigoSurface,
                                border = BorderStroke(1.dp, ArgonBorderSubtle)
                            ) {
                                Text(
                                    text = "$role: $name",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = ArgonCyan
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                    color = GeminiInputBarBg,
                    border = BorderStroke(1.dp, GeminiInputBarStroke),
                    modifier = Modifier.widthIn(max = 330.dp)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        MarkdownRichText(
                            markdown = message.content,
                            textColor = GeminiTextWhite
                        )
                    }
                }
            }
        }

        "tool" -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!message.thoughtText.isNullOrBlank()) {
                    val split = remember(message.thoughtText) {
                        ThoughtChainParser.splitThoughtAndContent("", message.thoughtText)
                    }
                    MinimalThoughtDropdownBar(
                        thoughtText = split.thoughtText,
                        summaryLine = split.summaryLine,
                        isActivelyThinking = false,
                        durationMs = message.thoughtDurationMs
                    )
                }
                PythonToolExecutionCard(
                    toolName = message.toolName ?: "openxml_skill",
                    toolArgsJson = message.toolArgsJson,
                    toolOutputText = message.toolOutputText,
                    outputDocxPath = message.outputDocxPath,
                    onShareDocx = onShareDocx,
                    onOpenDocx = onOpenDocx
                )
            }
        }

        "error" -> {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = ArgonCoral.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, ArgonCoral.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = ArgonCoral,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = ArgonCoral
                        )
                    )
                }
            }
        }

        else -> {
            // Rich Markdown Assistant Chat Bubble with Minimal Thought Dropdown
            val split = remember(message.content, message.thoughtText) {
                ThoughtChainParser.splitThoughtAndContent(message.content, message.thoughtText)
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = ArgonCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Qwen3.5-0.8B NPU",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = ArgonCyan
                        )
                    )
                    if (message.tokensPerSec > 0f) {
                        Text(
                            text = String.format("• %.1f tok/s", message.tokensPerSec),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = GeminiTextMuted
                            )
                        )
                    }
                }

                if (split.thoughtText.isNotBlank()) {
                    MinimalThoughtDropdownBar(
                        thoughtText = split.thoughtText,
                        summaryLine = split.summaryLine,
                        isActivelyThinking = false,
                        durationMs = message.thoughtDurationMs
                    )
                }

                if (split.visibleContent.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp),
                        color = ArgonIndigoSurface,
                        border = BorderStroke(1.dp, ArgonBorderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            MarkdownRichText(
                                markdown = split.visibleContent,
                                textColor = GeminiTextWhite
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingAssistantBubble(
    streamingText: String,
    streamingThoughtText: String,
    streamingThoughtSummaryLine: String,
    isThinkingPhase: Boolean,
    thoughtDurationMs: Long,
    activeToolBanner: String?,
    tokensPerSec: Float
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = ArgonCyan
            )
            Text(
                text = if (tokensPerSec > 0f) {
                    String.format("Qwen3.5-0.8B NPU • %.1f tok/s", tokensPerSec)
                } else {
                    "Qwen3.5-0.8B NPU • GPU Batch Tokenizer + NPU Prefill..."
                },
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    color = ArgonCyan
                )
            )
        }

        // Minimal 1-line verbose thought bar with tap-to-expand dropdown during inference
        MinimalThoughtDropdownBar(
            thoughtText = streamingThoughtText,
            summaryLine = streamingThoughtSummaryLine,
            isActivelyThinking = isThinkingPhase,
            durationMs = thoughtDurationMs
        )

        if (activeToolBanner != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ArgonIndigoSurface,
                border = BorderStroke(1.dp, ArgonCyan.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Code,
                        contentDescription = null,
                        tint = ArgonCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = activeToolBanner,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = GeminiTextWhite
                        )
                    )
                }
            }
        }

        if (streamingText.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp),
                color = ArgonIndigoSurface,
                border = BorderStroke(1.dp, ArgonBorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    MarkdownRichText(
                        markdown = streamingText,
                        textColor = GeminiTextWhite
                    )
                }
            }
        }
    }
}

private fun rememberAttachmentBadges(attachmentsJson: String): List<Pair<String, String>> {
    if (attachmentsJson.isBlank() || attachmentsJson == "[]") return emptyList()
    return runCatching {
        val arr = JSONArray(attachmentsJson)
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(obj.optString("name", "file") to obj.optString("role", "DOC"))
        }
        list
    }.getOrDefault(emptyList())
}
