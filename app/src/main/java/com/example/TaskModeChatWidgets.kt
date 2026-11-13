package com.example

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ArgonBorderSubtle
import com.example.ui.theme.ArgonCoral
import com.example.ui.theme.ArgonCyan
import com.example.ui.theme.ArgonEmerald
import com.example.ui.theme.ArgonIndigoSurface
import com.example.ui.theme.ArgonVioletGlow
import com.example.ui.theme.GeminiBlack
import com.example.ui.theme.GeminiInputBarBg
import com.example.ui.theme.GeminiInputBarStroke
import com.example.ui.theme.GeminiPillActiveBg
import com.example.ui.theme.GeminiTextMuted
import com.example.ui.theme.GeminiTextSecondary
import com.example.ui.theme.GeminiTextWhite
import com.example.ui.theme.JetBrainsMonoFontFamily

/**
 * Navigation Drawer showing ONLY real user-created chat history from SQLite and
 * keeping ONLY the Settings Gear Icon in the bottom footer (zero reference-image profile rows).
 */
@Composable
fun CopyFlowHistoryDrawer(
    sessions: List<ChatSessionEntity>,
    activeSessionId: String?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSearchField by remember { mutableStateOf(false) }

    val filteredSessions = remember(sessions, searchQuery) {
        if (searchQuery.isBlank()) {
            sessions
        } else {
            val q = searchQuery.trim().lowercase()
            sessions.filter {
                it.title.lowercase().contains(q) ||
                    (it.templateName?.lowercase()?.contains(q) == true) ||
                    (it.sourceName?.lowercase()?.contains(q) == true)
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(325.dp),
        color = GeminiBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "CopyFlow",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = GeminiTextWhite,
                        fontWeight = FontWeight.SemiBold
                    )
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showSearchField = !showSearchField },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("drawer_search_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = "Search chat history",
                            tint = GeminiTextSecondary
                        )
                    }
                    IconButton(
                        onClick = onCloseDrawer,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("drawer_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close history drawer",
                            tint = GeminiTextSecondary
                        )
                    }
                }
            }

            if (showSearchField) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Filter conversations...", color = GeminiTextMuted) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("drawer_search_input")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    onNewChat()
                    onCloseDrawer()
                },
                border = BorderStroke(1.dp, GeminiInputBarStroke),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("drawer_new_chat_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    tint = GeminiTextWhite,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("New Document Task", color = GeminiTextWhite)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Recent Tasks",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = GeminiTextMuted,
                    fontFamily = JetBrainsMonoFontFamily
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Real chat history list or explicit empty state
            if (filteredSessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            tint = GeminiTextMuted,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = if (searchQuery.isBlank()) {
                                "No chat history yet"
                            } else {
                                "No matching sessions"
                            },
                            style = MaterialTheme.typography.titleSmall.copy(color = GeminiTextSecondary)
                        )
                        Text(
                            text = "Every document layout cloning conversation you run is automatically saved here.",
                            style = MaterialTheme.typography.bodySmall.copy(color = GeminiTextMuted)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredSessions, key = { it.id }) { session ->
                        val isSelected = session.id == activeSessionId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) GeminiPillActiveBg else Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onSelectSession(session.id)
                                    onCloseDrawer()
                                }
                                .testTag("history_session_${session.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = session.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = GeminiTextWhite,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (!session.templateName.isNullOrBlank() || !session.sourceName.isNullOrBlank()) {
                                        Text(
                                            text = buildString {
                                                session.templateName?.let { append("Tpl: $it ") }
                                                session.sourceName?.let { append("• Src: $it") }
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = JetBrainsMonoFontFamily,
                                                color = GeminiTextMuted
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { onDeleteSession(session.id) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("delete_session_${session.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "Delete conversation",
                                        tint = GeminiTextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = GeminiInputBarStroke)
            Spacer(modifier = Modifier.height(8.dp))

            // Drawer Footer: ONLY the Settings Gear Icon (no profile name, no EJ avatar)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = {
                        onCloseDrawer()
                        onOpenSettings()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("drawer_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Open App & Model Settings",
                        tint = GeminiTextSecondary
                    )
                }
            }
        }
    }
}

/**
 * Keyboard-safe Bottom Composer Bar:
 * Displays active Template & Source document badges, pending attachment chips,
 * and the rounded composer capsule (`+` attach button, multi-line text field, Send/Stop button).
 */
@Composable
fun CopyFlowComposerBar(
    composerText: String,
    onComposerTextChange: (String) -> Unit,
    pendingAttachments: List<AttachedDocumentFile>,
    activeTemplateName: String?,
    activeSourceName: String?,
    isModelReady: Boolean,
    isGenerating: Boolean,
    canSend: Boolean,
    onOpenAttachmentSheet: () -> Unit,
    onCycleAttachmentRole: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopGeneration: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Active session document context badges (Template & Source)
        if (!activeTemplateName.isNullOrBlank() || !activeSourceName.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                activeTemplateName?.let { tpl ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ArgonVioletGlow.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, ArgonVioletGlow.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Description,
                                contentDescription = null,
                                tint = ArgonVioletGlow,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Template: $tpl",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = GeminiTextWhite
                                )
                            )
                        }
                    }
                }

                activeSourceName?.let { src ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ArgonCyan.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, ArgonCyan.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.InsertDriveFile,
                                contentDescription = null,
                                tint = ArgonCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Source: $src",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = GeminiTextWhite
                                )
                            )
                        }
                    }
                }
            }
        }

        // Pending attachment chips
        if (pendingAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (att in pendingAttachments) {
                    val roleColor = when (att.role) {
                        AttachmentRole.TEMPLATE -> ArgonVioletGlow
                        AttachmentRole.SOURCE -> ArgonCyan
                        AttachmentRole.REFERENCE_IMAGE -> ArgonEmerald
                    }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ArgonIndigoSurface,
                        border = BorderStroke(1.dp, roleColor.copy(alpha = 0.55f))
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = roleColor.copy(alpha = 0.2f),
                                modifier = Modifier.clickable { onCycleAttachmentRole(att.id) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = att.role.label,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = JetBrainsMonoFontFamily,
                                            color = roleColor,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                    if (att.role != AttachmentRole.REFERENCE_IMAGE) {
                                        Icon(
                                            imageVector = Icons.Outlined.SwapHoriz,
                                            contentDescription = "Switch Template/Source role",
                                            tint = roleColor,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }

                            Text(
                                text = att.name,
                                style = MaterialTheme.typography.bodySmall.copy(color = GeminiTextWhite),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(120.dp)
                            )

                            IconButton(
                                onClick = { onRemoveAttachment(att.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Remove attachment",
                                    tint = GeminiTextSecondary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Main capsule input bar
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = GeminiInputBarBg,
            border = BorderStroke(1.dp, GeminiInputBarStroke),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onOpenAttachmentSheet,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("attach_files_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Attach DOCX, PDF, or Image",
                        tint = GeminiTextWhite
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (composerText.isEmpty()) {
                        Text(
                            text = if (isModelReady) {
                                "Describe layout changes or clone task..."
                            } else {
                                "Load Qwen3.5-0.8B-ONNX model to start..."
                            },
                            style = MaterialTheme.typography.bodyLarge.copy(color = GeminiTextMuted)
                        )
                    }
                    BasicTextField(
                        value = composerText,
                        onValueChange = onComposerTextChange,
                        enabled = isModelReady && !isGenerating,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = GeminiTextWhite),
                        cursorBrush = SolidColor(ArgonCyan),
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_input_field")
                    )
                }

                if (isGenerating) {
                    IconButton(
                        onClick = onStopGeneration,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(ArgonCoral.copy(alpha = 0.2f))
                            .testTag("stop_generation_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Stop,
                            contentDescription = "Stop generation",
                            tint = ArgonCoral
                        )
                    }
                } else {
                    IconButton(
                        onClick = onSendMessage,
                        enabled = canSend,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (canSend) ArgonVioletGlow else GeminiPillActiveBg
                            )
                            .testTag("send_message_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = "Send task message",
                            tint = if (canSend) Color.Black else GeminiTextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopyFlowAttachmentSheet(
    grantedFolderName: String?,
    onDismiss: () -> Unit,
    onPickTemplateDoc: () -> Unit,
    onPickSourceDoc: () -> Unit,
    onPickReferenceImage: () -> Unit,
    onGrantFolderAccess: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ArgonIndigoSurface,
        contentColor = GeminiTextWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Attach Files to Chat Sandbox",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = GeminiTextWhite,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Text(
                text = "Selected files are copied directly into this conversation's Chat Sandbox (as template.docx / source.docx and original filenames) so tools always have full read/write access.",
                style = MaterialTheme.typography.bodySmall.copy(color = GeminiTextSecondary)
            )

            AttachmentOptionCard(
                title = "Reference Template (.docx / .pdf)",
                subtitle = "Copied to Chat Sandbox as template.docx + original name",
                accentColor = ArgonVioletGlow,
                icon = Icons.Outlined.Description,
                testTag = "attach_template_option",
                onClick = onPickTemplateDoc
            )

            AttachmentOptionCard(
                title = "Source Content Document (.docx / .pdf)",
                subtitle = "Copied to Chat Sandbox as source.docx + original name",
                accentColor = ArgonCyan,
                icon = Icons.Outlined.InsertDriveFile,
                testTag = "attach_source_option",
                onClick = onPickSourceDoc
            )

            AttachmentOptionCard(
                title = "Reference Layout Image (.png / .jpg)",
                subtitle = "Visual page screenshot or figure to inspect or embed",
                accentColor = ArgonEmerald,
                icon = Icons.Outlined.Image,
                testTag = "attach_image_option",
                onClick = onPickReferenceImage
            )

            AttachmentOptionCard(
                title = if (grantedFolderName != null) {
                    "Directory Access Granted: $grantedFolderName"
                } else {
                    "Grant Downloads / Folder Access"
                },
                subtitle = "Allow tools to automatically find & copy files from your Downloads or Documents folder into the Chat Sandbox",
                accentColor = ArgonCyan,
                icon = Icons.Outlined.FolderOpen,
                testTag = "grant_folder_access_option",
                onClick = onGrantFolderAccess
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AttachmentOptionCard(
    title: String,
    subtitle: String,
    accentColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = GeminiInputBarBg,
        border = BorderStroke(1.dp, GeminiInputBarStroke),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = GeminiTextWhite,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(color = GeminiTextSecondary)
                )
            }
        }
    }
}

/**
 * Real-Time Hexagon v73 HTP NPU Utilization & Thermal Throttling HUD Bar.
 * Shows a compact 1-line live strip during normal operation and expands on tap
 * to reveal full GPU Batch Tokenizer -> NPU Prefill/Decode pipeline metrics,
 * Thermal Headroom, Native ONNX Heap, and 1-Tap Option #2 GitHub Telemetry Sync.
 */
@Composable
fun HexagonNpuTelemetryHudBar(
    telemetry: HardwareTelemetrySnapshot,
    recordedRunsCount: Int,
    onSyncGitHub: () -> Unit,
    onExportJson: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val npuColor = when {
        telemetry.isThermalThrottling -> ArgonCoral
        telemetry.npuUtilizationPercent >= 75 -> ArgonVioletGlow
        telemetry.npuUtilizationPercent > 0 -> ArgonCyan
        else -> ArgonEmerald
    }
    val thermalColor = if (telemetry.isThermalThrottling) ArgonCoral else ArgonEmerald

    Surface(
        color = GeminiInputBarBg.copy(alpha = 0.92f),
        border = BorderStroke(
            width = 1.dp,
            color = if (telemetry.isThermalThrottling) ArgonCoral.copy(alpha = 0.6f) else GeminiInputBarStroke
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .testTag("hexagon_npu_telemetry_hud")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Compact 1-Line Live Telemetry Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Memory,
                        contentDescription = null,
                        tint = npuColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = buildString {
                            append("HTP NPU: ${telemetry.npuUtilizationPercent}%")
                            if (telemetry.peakNpuUtilizationPercent > 0) {
                                append(" (Pk ${telemetry.peakNpuUtilizationPercent}%)")
                            }
                            append(" • ")
                            append(telemetry.thermalStatusLabel)
                            telemetry.socTempCelsius?.let { c ->
                                append(String.format(" %.0f°C", c))
                            }
                            append(" • ${telemetry.nativeHeapAllocatedMb}MB")
                            if (telemetry.decodeTokensPerSec > 0f) {
                                append(String.format(" • %.1f tok/s", telemetry.decodeTokensPerSec))
                            }
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = GeminiTextWhite,
                            fontSize = 11.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = npuColor.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = telemetry.activeStage.badgeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = npuColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "Collapse telemetry" else "Expand telemetry",
                        tint = GeminiTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Live NPU Duty Cycle Progress Bar
            LinearProgressIndicator(
                progress = { (telemetry.npuUtilizationPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = npuColor,
                trackColor = ArgonIndigoSurface
            )

            // Expanded Telemetry Details & 1-Tap GitHub Sync
            if (expanded) {
                HorizontalDivider(color = GeminiInputBarStroke)

                Text(
                    text = "SoC: ${telemetry.socBadge} • Chain: ${telemetry.activeProviderChain}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = ArgonCyan
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TelemetryMetricTile(
                        label = "GPU Batch Tokenize",
                        value = String.format("%.1f ms (%d tok)", telemetry.batchTokenizeLatencyMs, telemetry.promptTokenCount),
                        accent = ArgonCyan,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryMetricTile(
                        label = "GPU Embed Prefetch",
                        value = String.format("%.1f ms (32-tok)", telemetry.gpuEmbedPrefetchLatencyMs),
                        accent = ArgonVioletGlow,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TelemetryMetricTile(
                        label = "NPU Prefill / Decode",
                        value = String.format("%.1f / %.1f tok/s", telemetry.prefillTokensPerSec, telemetry.decodeTokensPerSec),
                        accent = npuColor,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryMetricTile(
                        label = "NPU Step Latency",
                        value = String.format("%.1f ms (avg %.1f)", telemetry.lastNpuStepLatencyMs, telemetry.avgNpuStepLatencyMs),
                        accent = npuColor,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TelemetryMetricTile(
                        label = "Thermal State",
                        value = buildString {
                            append(telemetry.thermalStatusLabel)
                            telemetry.socTempCelsius?.let { append(String.format(" (%.1f°C)", it)) }
                            if (telemetry.thermalHeadroom > 0f) {
                                append(String.format(" • HR %.2f", telemetry.thermalHeadroom))
                            }
                        },
                        accent = thermalColor,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryMetricTile(
                        label = "ONNX Native / Avail RAM",
                        value = "${telemetry.nativeHeapAllocatedMb} MB / ${telemetry.systemAvailRamMb} MB free",
                        accent = if (telemetry.isLowMemory) ArgonCoral else GeminiTextWhite,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TelemetryMetricTile(
                        label = "OpenXML Inspect / Clone",
                        value = "${telemetry.openXmlInspectDurationMs} ms / ${telemetry.openXmlCloneDurationMs} ms",
                        accent = ArgonEmerald,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryMetricTile(
                        label = "Compacted Turns",
                        value = "${telemetry.compactedTurnsArchived} archived (${recordedRunsCount} runs)",
                        accent = GeminiTextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = telemetry.githubSyncStatus,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = GeminiTextSecondary
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onSyncGitHub,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ArgonCyan,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("hud_sync_github_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync to Firestore", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = onExportJson,
                        border = BorderStroke(1.dp, GeminiInputBarStroke),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("hud_export_json_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = null,
                            tint = GeminiTextWhite,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export JSON", color = GeminiTextWhite, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ArgonIndigoSurface,
        border = BorderStroke(1.dp, ArgonBorderSubtle),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = GeminiTextMuted,
                    fontSize = 10.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Full Settings Modal triggered from the Settings Gear Icon in the Chat History Drawer:
 * - Hugging Face `onnx-community/Qwen3.5-0.8B-ONNX` multi-file downloader + live byte progress
 * - Local SAF directory picker & single file picker
 * - Snapdragon 8 Gen 2 Execution Provider selector (`QNN_HTP`, `NNAPI`, `XNNPACK`)
 * - Vision Encoder sub-graph toggle
 * - Hexagon v73 HTP Telemetry Flight Recorder & Option #2 GitHub Sync
 * - GitHub Releases In-App Self-Updater
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopyFlowSettingsModal(
    uiState: CopyFlowUiState,
    onDismiss: () -> Unit,
    onDownloadHuggingFace: () -> Unit,
    onCancelDownload: () -> Unit,
    onPickModelDirectory: () -> Unit,
    onPickSingleModelFile: () -> Unit,
    onSelectExecutionProvider: (ExecutionProviderMode) -> Unit,
    onToggleVisionEncoder: (Boolean) -> Unit,
    onToggleAutoSyncGitHub: (Boolean) -> Unit,
    onUpdateGitHubRepoSlug: (String) -> Unit,
    onSyncTelemetryGitHubNow: () -> Unit,
    onExportTelemetryJson: () -> Unit,
    onCheckGitHubReleases: () -> Unit,
    onDownloadReleaseApk: (GitHubReleaseState.UpdateAvailable) -> Unit,
    onInstallReleaseApk: (String) -> Unit,
    onClearChatHistory: () -> Unit,
    onSignOut: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ArgonIndigoSurface,
        contentColor = GeminiTextWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "CopyFlow Settings • Qwen3.5-0.8B-ONNX",
                style = MaterialTheme.typography.titleLarge.copy(
                    color = GeminiTextWhite,
                    fontWeight = FontWeight.SemiBold
                )
            )

            // Section 1: Model Weights & Hugging Face Downloader
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = GeminiInputBarBg,
                border = BorderStroke(1.dp, GeminiInputBarStroke),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "ONNX Model Weights (onnx-community/Qwen3.5-0.8B-ONNX)",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = GeminiTextWhite,
                            fontWeight = FontWeight.SemiBold
                        )
                    )

                    val mState = uiState.modelState
                    if (mState is ModelState.Downloading) {
                        val frac = if (mState.fileTotalBytes > 0L) {
                            (mState.fileBytesRead.toFloat() / mState.fileTotalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        Text(
                            text = "Downloading (${mState.fileIndex}/${mState.totalFiles}): ${mState.currentFileName}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = ArgonCyan
                            )
                        )
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = ArgonCyan,
                            trackColor = ArgonIndigoSurface
                        )
                        OutlinedButton(
                            onClick = onCancelDownload,
                            border = BorderStroke(1.dp, ArgonCoral)
                        ) {
                            Text("Cancel Download", color = ArgonCoral)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onDownloadHuggingFace,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ArgonVioletGlow,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_download_hf_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download HF", fontWeight = FontWeight.SemiBold)
                            }

                            OutlinedButton(
                                onClick = onPickModelDirectory,
                                border = BorderStroke(1.dp, GeminiInputBarStroke),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_pick_folder_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    tint = GeminiTextWhite,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pick Folder", color = GeminiTextWhite)
                            }
                        }

                        OutlinedButton(
                            onClick = onPickSingleModelFile,
                            border = BorderStroke(1.dp, GeminiInputBarStroke),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_pick_file_button")
                        ) {
                            Text("Sideload Single .onnx / .onnx_data / .json File", color = GeminiTextSecondary)
                        }
                    }

                    // Per-file verification table
                    HorizontalDivider(color = GeminiInputBarStroke)
                    Text(
                        text = "Required Sub-Graphs & External Weights Status:",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = GeminiTextSecondary
                        )
                    )
                    for ((artifact, actualBytes) in uiState.artifactStatuses) {
                        if (!artifact.requiredForText && !uiState.includeVisionEncoder) continue
                        val isVerified = actualBytes >= artifact.minBytes
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isVerified) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isVerified) ArgonEmerald else GeminiTextMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = artifact.relativePath,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = if (isVerified) GeminiTextWhite else GeminiTextMuted
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = if (actualBytes > 0L) {
                                    String.format("%.1f MB", actualBytes / (1024.0 * 1024.0))
                                } else {
                                    "Missing"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = if (isVerified) ArgonEmerald else GeminiTextMuted
                                )
                            )
                        }
                    }
                }
            }

            // Section 2: Multi-SoC NPU (Snapdragon / MediaTek / Exynos) & Mobile GPU Fallback
            val detectedHardware = remember { SocHardwareDetector.detect() }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = GeminiInputBarBg,
                border = BorderStroke(1.dp, GeminiInputBarStroke),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Multi-SoC NPU & Mobile GPU Fallback",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = GeminiTextWhite,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        text = "Detected: ${detectedHardware.summaryBadge}\n" +
                            "NPU: ${detectedHardware.vendor.npuName} • GPU: ${detectedHardware.vendor.gpuName}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = ArgonCyan
                        )
                    )
                    for (mode in ExecutionProviderMode.entries) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectExecutionProvider(mode) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.executionProvider == mode,
                                onClick = { onSelectExecutionProvider(mode) },
                                colors = RadioButtonDefaults.colors(selectedColor = ArgonCyan)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = GeminiTextWhite,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                                Text(
                                    text = mode.description,
                                    style = MaterialTheme.typography.bodySmall.copy(color = GeminiTextSecondary)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = GeminiInputBarStroke)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable Vision Encoder Sub-Graph",
                                style = MaterialTheme.typography.bodyMedium.copy(color = GeminiTextWhite)
                            )
                            Text(
                                text = "Loads onnx/vision_encoder_q4f16.onnx (+62 MB)",
                                style = MaterialTheme.typography.bodySmall.copy(color = GeminiTextSecondary)
                            )
                        }
                        Switch(
                            checked = uiState.includeVisionEncoder,
                            onCheckedChange = onToggleVisionEncoder
                        )
                    }
                }
            }

            // Section 3: Discovered ONNX Graph Metadata (when loaded)
            uiState.sessionMetadata?.let { meta ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = GeminiInputBarBg,
                    border = BorderStroke(1.dp, GeminiInputBarStroke),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Inspected ONNX Session Graph",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = ArgonCyan,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Text(
                            text = "SoC: ${meta.detectedSocBadge}\n" +
                                "NPU Decoder Chain: ${meta.activeProviderLabel}\n" +
                                "GPU Batch Tokenizer & Embed: ${meta.tokenizerPipelineLabel}\n" +
                                "Hybrid Layers: ${meta.numKvLayers} Full-Attn KV + ${meta.numLinearLayers} Linear-Attn GDN (${if (meta.kvIsFloat16) "FP16" else "FP32"})\n" +
                                "M-RoPE position_ids rank: ${meta.positionIdsRank}D ([3, batch, seq_len])",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = GeminiTextSecondary
                            )
                        )
                    }
                }
            }

            // Section 4: Built-In AI Studio Cloud Firestore Telemetry Sink
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = GeminiInputBarBg,
                border = BorderStroke(1.dp, GeminiInputBarStroke),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Thermostat,
                            contentDescription = null,
                            tint = ArgonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Built-In AI Studio Cloud Firestore Telemetry Sink",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = GeminiTextWhite,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }

                    Text(
                        text = buildString {
                            append("Authenticated Google Account: ")
                            append(uiState.signedInUserEmail ?: uiState.signedInUserId)
                            append("\nCollection Path: /users/${uiState.signedInUserId}/telemetry_runs")
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = ArgonEmerald
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Sync Telemetry to Cloud Firestore",
                                style = MaterialTheme.typography.bodyMedium.copy(color = GeminiTextWhite)
                            )
                            Text(
                                text = "Automatically streams Hexagon v73 HTP NPU duty cycle, thermal state, and OpenXML clone timings to Cloud Firestore",
                                style = MaterialTheme.typography.bodySmall.copy(color = GeminiTextSecondary)
                            )
                        }
                        Switch(
                            checked = uiState.autoSyncTelemetryGitHub,
                            onCheckedChange = onToggleAutoSyncGitHub
                        )
                    }

                    Text(
                        text = "Status: ${uiState.telemetrySnapshot.githubSyncStatus}\n" +
                            "Local SQLite Runs: ${uiState.telemetryRuns.size} • Cloud Firestore Runs: ${uiState.cloudTelemetryRuns.size}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = ArgonCyan
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSyncTelemetryGitHubNow,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ArgonCyan,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_sync_github_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync Firestore", fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = onExportTelemetryJson,
                            border = BorderStroke(1.dp, GeminiInputBarStroke),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_export_telemetry_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = null,
                                tint = GeminiTextWhite,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export JSON", color = GeminiTextWhite)
                        }
                    }
                }
            }

            // Section 5: In-App Self-Updater from GitHub Releases
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = GeminiInputBarBg,
                border = BorderStroke(1.dp, GeminiInputBarStroke),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdateAlt,
                            contentDescription = null,
                            tint = ArgonVioletGlow,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "In-App Self-Update (GitHub Releases)",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = GeminiTextWhite,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }

                    OutlinedTextField(
                        value = uiState.githubRepoSlug,
                        onValueChange = onUpdateGitHubRepoSlug,
                        label = { Text("GitHub Releases Repo (owner/repo)", color = GeminiTextSecondary) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_github_repo_input")
                    )

                    Text(
                        text = "Installed Version: v${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE}) • Repo: ${uiState.githubRepoSlug}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = GeminiTextSecondary
                        )
                    )

                    when (val rel = uiState.releaseState) {
                        is GitHubReleaseState.Idle -> {
                            OutlinedButton(
                                onClick = onCheckGitHubReleases,
                                border = BorderStroke(1.dp, ArgonVioletGlow.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("check_github_releases_button")
                            ) {
                                Text("Check GitHub Releases for New APK", color = GeminiTextWhite)
                            }
                        }

                        is GitHubReleaseState.Checking -> {
                            Text(
                                text = "Querying https://api.github.com/repos/${rel.repoSlug}/releases/latest...",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = ArgonCyan
                                )
                            )
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = ArgonCyan,
                                trackColor = ArgonIndigoSurface
                            )
                        }

                        is GitHubReleaseState.UpToDate -> {
                            Text(
                                text = "Up to date! Installed v${rel.currentVersion} matches latest release (${rel.latestTag}) on ${rel.repoSlug}.",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = ArgonEmerald
                                )
                            )
                            OutlinedButton(
                                onClick = onCheckGitHubReleases,
                                border = BorderStroke(1.dp, GeminiInputBarStroke),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Re-check GitHub Releases", color = GeminiTextSecondary)
                            }
                        }

                        is GitHubReleaseState.UpdateAvailable -> {
                            Text(
                                text = "New Release Available: ${rel.latestTag} (${rel.releaseTitle})\n" +
                                    "Asset: ${rel.apkAssetName} (${String.format("%.1f MB", rel.apkSizeBytes / (1024.0 * 1024.0))})",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = ArgonEmerald
                                )
                            )
                            Text(
                                text = rel.releaseNotes,
                                style = MaterialTheme.typography.bodySmall.copy(color = GeminiTextSecondary),
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                            Button(
                                onClick = { onDownloadReleaseApk(rel) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ArgonVioletGlow,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("download_release_apk_button")
                            ) {
                                Text("Download & Verify ${rel.apkAssetName}", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        is GitHubReleaseState.DownloadingApk -> {
                            val frac = if (rel.totalBytes > 0L) {
                                (rel.bytesRead.toFloat() / rel.totalBytes.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            Text(
                                text = "Downloading ${rel.apkAssetName}: ${String.format("%.1f / %.1f MB", rel.bytesRead / (1024.0 * 1024.0), rel.totalBytes / (1024.0 * 1024.0))}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = ArgonCyan
                                )
                            )
                            LinearProgressIndicator(
                                progress = { frac },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = ArgonCyan,
                                trackColor = ArgonIndigoSurface
                            )
                        }

                        is GitHubReleaseState.ReadyToInstall -> {
                            Text(
                                text = "Downloaded ${rel.latestTag} • SHA-256: ${rel.sha256Hex.take(16)}...",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = ArgonEmerald
                                )
                            )
                            Button(
                                onClick = { onInstallReleaseApk(rel.apkFilePath) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ArgonEmerald,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("install_release_apk_button")
                            ) {
                                Text("Install Update APK Now", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        is GitHubReleaseState.Error -> {
                            Text(
                                text = rel.message,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = ArgonCoral
                                )
                            )
                            OutlinedButton(
                                onClick = onCheckGitHubReleases,
                                border = BorderStroke(1.dp, GeminiInputBarStroke),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Retry Release Check", color = GeminiTextWhite)
                            }
                        }
                    }
                }
            }

            // Section 6: Clear Chat History & Sign Out
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onClearChatHistory,
                    border = BorderStroke(1.dp, ArgonCoral.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("clear_all_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = ArgonCoral,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear History", color = ArgonCoral)
                }

                OutlinedButton(
                    onClick = onSignOut,
                    border = BorderStroke(1.dp, GeminiInputBarStroke),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_sign_out_button")
                ) {
                    Text("Sign Out", color = GeminiTextWhite)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
