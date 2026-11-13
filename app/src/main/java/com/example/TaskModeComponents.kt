package com.example

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.example.ui.theme.GeminiInputBarBg
import com.example.ui.theme.GeminiInputBarStroke
import com.example.ui.theme.GeminiTextMuted
import com.example.ui.theme.GeminiTextSecondary
import com.example.ui.theme.GeminiTextWhite
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.io.File

/**
 * Clean CopyFlow Top Bar:
 * Shows ONLY the menu icon (left), CopyFlow title + live model status badge (center-left),
 * and the New Chat icon (right). Zero reference-image avatars or "Gemini Argon" labels.
 */
@Composable
fun CopyFlowTopBar(
    modelState: ModelState,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("menu_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Menu,
                    contentDescription = "Open chat history drawer",
                    tint = GeminiTextWhite
                )
            }

            Text(
                text = "CopyFlow",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = GeminiTextWhite
                )
            )

            val (badgeText, badgeColor) = when (modelState) {
                is ModelState.Ready -> "Qwen3.5 NPU" to ArgonEmerald
                is ModelState.Downloading -> "Downloading" to ArgonCyan
                is ModelState.Verifying -> "Verifying" to ArgonVioletGlow
                is ModelState.Error -> "Model Error" to ArgonCoral
                is ModelState.NotLoaded -> "Model Required" to GeminiTextSecondary
            }

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = badgeColor.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onOpenSettings() }
                    .testTag("model_status_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                    )
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = badgeColor
                        )
                    )
                }
            }
        }

        IconButton(
            onClick = onNewChat,
            modifier = Modifier
                .size(48.dp)
                .testTag("new_chat_button")
        ) {
            Icon(
                imageVector = Icons.Outlined.EditNote,
                contentDescription = "Start new chat",
                tint = GeminiTextWhite
            )
        }
    }
}

/**
 * Strict Gatekeeping Banner / Card shown when Qwen3.5-0.8B-ONNX is not yet loaded on disk,
 * currently downloading from Hugging Face, or in an error state.
 */
@Composable
fun ModelActionRequiredCard(
    modelState: ModelState,
    onDownloadHuggingFace: () -> Unit,
    onCancelDownload: () -> Unit,
    onPickLocalModelFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("model_gate_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ArgonIndigoSurface),
        border = BorderStroke(1.dp, ArgonBorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = when (modelState) {
                        is ModelState.Error -> Icons.Outlined.ErrorOutline
                        is ModelState.Ready -> Icons.Outlined.CheckCircle
                        else -> Icons.Outlined.Memory
                    },
                    contentDescription = "Model status icon",
                    tint = when (modelState) {
                        is ModelState.Error -> ArgonCoral
                        is ModelState.Ready -> ArgonEmerald
                        else -> ArgonCyan
                    },
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (modelState) {
                            is ModelState.NotLoaded -> "Action Required: Load Qwen3.5-0.8B-ONNX"
                            is ModelState.Downloading -> "Downloading Qwen3.5-0.8B-ONNX Sub-Graphs"
                            is ModelState.Verifying -> "Verifying ONNX Weights & SHA-256"
                            is ModelState.Error -> "Model Initialization Error"
                            is ModelState.Ready -> "Qwen3.5-0.8B-ONNX Ready"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = GeminiTextWhite,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        text = "onnx-community/Qwen3.5-0.8B-ONNX • Split Q4F16 Sub-Graphs",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = GeminiTextSecondary
                        )
                    )
                }
            }

            when (modelState) {
                is ModelState.NotLoaded -> {
                    Text(
                        text = modelState.reason,
                        style = MaterialTheme.typography.bodyMedium.copy(color = GeminiTextSecondary)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                                .testTag("download_hf_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CloudDownload,
                                contentDescription = "Download from Hugging Face",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download HF", fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = onPickLocalModelFolder,
                            border = BorderStroke(1.dp, GeminiInputBarStroke),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pick_model_dir_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = "Select local model folder",
                                tint = GeminiTextWhite,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Local Folder", color = GeminiTextWhite)
                        }
                    }
                }

                is ModelState.Downloading -> {
                    val progressFraction = if (modelState.fileTotalBytes > 0L) {
                        (modelState.fileBytesRead.toFloat() / modelState.fileTotalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Text(
                        text = "File ${modelState.fileIndex}/${modelState.totalFiles}: ${modelState.currentFileName}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = ArgonCyan
                        )
                    )
                    if (modelState.fileTotalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = ArgonCyan,
                            trackColor = GeminiInputBarBg
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = ArgonCyan,
                            trackColor = GeminiInputBarBg
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val readMb = modelState.fileBytesRead / (1024.0 * 1024.0)
                        val totalMb = if (modelState.fileTotalBytes > 0) modelState.fileTotalBytes / (1024.0 * 1024.0) else 0.0
                        Text(
                            text = if (totalMb > 0) {
                                String.format("%.1f MB / %.1f MB", readMb, totalMb)
                            } else {
                                String.format("%.1f MB transferred", readMb)
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = GeminiTextSecondary
                            )
                        )
                        OutlinedButton(
                            onClick = onCancelDownload,
                            border = BorderStroke(1.dp, ArgonCoral.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Cancel", color = ArgonCoral, fontSize = 12.sp)
                        }
                    }
                }

                is ModelState.Verifying -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = ArgonVioletGlow
                        )
                        Text(
                            text = modelState.stepDescription,
                            style = MaterialTheme.typography.bodyMedium.copy(color = GeminiTextSecondary)
                        )
                    }
                }

                is ModelState.Error -> {
                    Text(
                        text = modelState.message,
                        style = MaterialTheme.typography.bodyMedium.copy(color = ArgonCoral)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onDownloadHuggingFace,
                            colors = ButtonDefaults.buttonColors(containerColor = ArgonVioletGlow, contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Retry Download", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = onOpenSettings,
                            border = BorderStroke(1.dp, GeminiInputBarStroke),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Open Settings", color = GeminiTextWhite)
                        }
                    }
                }

                is ModelState.Ready -> {
                    val sizeMb = modelState.totalSizeBytes / (1024.0 * 1024.0)
                    Text(
                        text = String.format(
                            "Verified %.1f MB • %s • SHA256 %s",
                            sizeMb,
                            modelState.executionProvider.label,
                            modelState.decoderDataSha256Prefix
                        ),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = ArgonEmerald
                        )
                    )
                }
            }
        }
    }
}

/**
 * Renders an expandable card for a Python skill tool execution (`inspect_template`, `clone_document`,
 * or `run_custom_openxml_python`), plus Share / Open buttons when a `.docx` file was produced.
 */
@Composable
fun PythonToolExecutionCard(
    toolName: String,
    toolArgsJson: String?,
    toolOutputText: String?,
    outputDocxPath: String?,
    onShareDocx: (String) -> Unit,
    onOpenDocx: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("python_tool_card_$toolName"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ArgonIndigoSurface),
        border = BorderStroke(1.dp, ArgonBorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Code,
                        contentDescription = "Python tool execution",
                        tint = ArgonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Python Tool • $toolName",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = GeminiTextWhite,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        if (!toolArgsJson.isNullOrBlank()) {
                            Text(
                                text = toolArgsJson,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = GeminiTextMuted
                                )
                            )
                        }
                    }
                }

                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse tool output" else "Expand tool output",
                    tint = GeminiTextSecondary
                )
            }

            AnimatedVisibility(visible = expanded && !toolOutputText.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0A0D14),
                    border = BorderStroke(1.dp, GeminiInputBarStroke),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = (toolOutputText ?: "").take(2500),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = GeminiTextSecondary,
                            lineHeight = 15.sp
                        ),
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            if (!outputDocxPath.isNullOrBlank()) {
                val outFile = File(outputDocxPath)
                val sizeKb = if (outFile.exists()) outFile.length() / 1024 else 0L
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ArgonEmerald.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, ArgonEmerald.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Description,
                                contentDescription = "Reconstructed DOCX",
                                tint = ArgonEmerald,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    text = outFile.name,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = GeminiTextWhite,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${sizeKb} KB • OpenXML Verified",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = ArgonEmerald
                                    )
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = { onShareDocx(outputDocxPath) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("share_docx_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Share,
                                    contentDescription = "Share or Export DOCX",
                                    tint = GeminiTextWhite
                                )
                            }
                            IconButton(
                                onClick = { onOpenDocx(outputDocxPath) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("open_docx_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = "Open DOCX in external viewer",
                                    tint = ArgonCyan
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
