package com.example

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ArgonBorderSubtle
import com.example.ui.theme.ArgonCyan
import com.example.ui.theme.ArgonIndigoSurface
import com.example.ui.theme.ArgonVioletGlow
import com.example.ui.theme.GeminiInputBarBg
import com.example.ui.theme.GeminiInputBarStroke
import com.example.ui.theme.GeminiTextMuted
import com.example.ui.theme.GeminiTextSecondary
import com.example.ui.theme.GeminiTextWhite
import com.example.ui.theme.JetBrainsMonoFontFamily

data class ThoughtSplitResult(
    val thoughtText: String,
    val summaryLine: String,
    val visibleContent: String,
    val isCurrentlyThinking: Boolean
)

/**
 * Deterministic parser and context compactor for `<think>...</think>` internal reasoning chains
 * and pre-`<tool_call>` routing notes.
 */
object ThoughtChainParser {

    fun splitThoughtAndContent(
        rawText: String,
        explicitThought: String? = null
    ): ThoughtSplitResult {
        val thoughtParts = mutableListOf<String>()
        if (!explicitThought.isNullOrBlank()) {
            thoughtParts.add(explicitThought.trim())
        }

        var working = rawText
        var currentlyThinking = false

        // 1. Extract all closed <think>...</think> blocks
        while (true) {
            val start = working.indexOf("<think>")
            if (start == -1) break
            val end = working.indexOf("</think>", start)
            if (end != -1) {
                val inner = working.substring(start + "<think>".length, end).trim()
                if (inner.isNotEmpty()) {
                    thoughtParts.add(inner)
                }
                working = (working.substring(0, start) + working.substring(end + "</think>".length)).trim()
            } else {
                // Unclosed <think> block (actively streaming thought tokens)
                currentlyThinking = true
                val inner = working.substring(start + "<think>".length).trim()
                if (inner.isNotEmpty()) {
                    thoughtParts.add(inner)
                }
                working = working.substring(0, start).trim()
                break
            }
        }

        // 2. If there is a <tool_call> block, move any pre-tool reasoning prose into thoughtParts
        //    and strip the raw <tool_call> JSON from visible chat prose.
        val toolStart = working.indexOf("<tool_call>")
        if (toolStart != -1) {
            val preToolText = working.substring(0, toolStart).trim()
            if (preToolText.isNotEmpty()) {
                thoughtParts.add(preToolText)
            }
            val toolEnd = working.indexOf("</tool_call>", toolStart)
            working = if (toolEnd != -1) {
                working.substring(toolEnd + "</tool_call>".length).trim()
            } else {
                val partialJson = working.substring(toolStart + "<tool_call>".length).trim()
                if (partialJson.isNotEmpty()) {
                    thoughtParts.add("Dispatching tool call: ${partialJson.take(90)}")
                }
                ""
            }
        }

        val combinedThought = thoughtParts
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .trim()

        val summaryLine = extractMinimalSummaryLine(combinedThought, currentlyThinking)

        return ThoughtSplitResult(
            thoughtText = combinedThought,
            summaryLine = summaryLine,
            visibleContent = working.trim(),
            isCurrentlyThinking = currentlyThinking
        )
    }

    private fun extractMinimalSummaryLine(thought: String, isThinking: Boolean): String {
        if (thought.isBlank()) {
            return if (isThinking) "Analyzing sandbox AST & layout rules..." else ""
        }
        val lastActiveLine = thought
            .lineSequence()
            .map { line ->
                line.trim()
                    .removePrefix("-")
                    .removePrefix("*")
                    .removePrefix("•")
                    .replace(Regex("^\\d+\\.\\s*"), "")
                    .replace("**", "")
                    .replace("`", "")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .lastOrNull() ?: "Routing document layout cloner..."

        return if (lastActiveLine.length > 72) {
            lastActiveLine.take(72) + "…"
        } else {
            lastActiveLine
        }
    }

    /**
     * Highly optimized Internal Thought Context Compactor:
     * Strips all `<think>...</think>` chains and verbose reasoning from prior turns before
     * re-feeding conversation history into the NPU context window.
     */
    fun compactThoughtAndProseForPrompt(rawAssistantContent: String, maxChars: Int = 220): String {
        val split = splitThoughtAndContent(rawAssistantContent)
        val clean = split.visibleContent
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (clean.isEmpty()) {
            return "<think>[AST verified]</think>"
        }
        return if (clean.length > maxChars) {
            clean.take(maxChars) + "..."
        } else {
            clean
        }
    }
}

/**
 * Minimal single-line internal thought bar with a tap-to-expand dropdown to inspect
 * the full `<think>` chain during or after inference.
 */
@Composable
fun MinimalThoughtDropdownBar(
    thoughtText: String,
    summaryLine: String,
    isActivelyThinking: Boolean,
    durationMs: Long = 0L,
    modifier: Modifier = Modifier
) {
    if (thoughtText.isBlank() && !isActivelyThinking) return

    var expanded by remember { mutableStateOf(false) }
    val displaySummary = summaryLine.ifBlank {
        if (isActivelyThinking) "Thinking on NPU..." else "Internal thought chain"
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0E131D),
        border = BorderStroke(
            width = 1.dp,
            color = if (isActivelyThinking) ArgonCyan.copy(alpha = 0.45f) else ArgonBorderSubtle
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .testTag("thought_dropdown_toggle")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = "Internal Thought Chain",
                    tint = if (isActivelyThinking) ArgonCyan else ArgonVioletGlow,
                    modifier = Modifier.size(15.dp)
                )

                Text(
                    text = if (isActivelyThinking) "Thinking:" else "Thought:",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = if (isActivelyThinking) ArgonCyan else ArgonVioletGlow,
                        fontWeight = FontWeight.SemiBold
                    )
                )

                Text(
                    text = displaySummary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = GeminiTextSecondary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (durationMs > 0L) {
                    Text(
                        text = String.format("%.1fs", durationMs / 1000.0f),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = GeminiTextMuted,
                            fontSize = 10.sp
                        )
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse thought chain" else "Expand thought chain",
                    tint = GeminiTextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded && thoughtText.isNotBlank(),
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF090C12))
                ) {
                    HorizontalDivider(color = GeminiInputBarStroke.copy(alpha = 0.6f))
                    SelectionContainer {
                        Text(
                            text = thoughtText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = GeminiTextSecondary,
                                lineHeight = 17.sp
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }
            }
        }
    }
}

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class BulletItem(val indentLevel: Int, val bulletMarker: String, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class TableBlock(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

/**
 * Rich Markdown renderer for model outputs and chat bubbles.
 * Supports headings (#..####), bold (**), italic (*), inline code (`), fenced code blocks (```),
 * bulleted and numbered lists, blockquotes (>), horizontal rules (---), and markdown tables.
 */
@Composable
fun MarkdownRichText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = GeminiTextWhite
) {
    val cleanedMarkdown = remember(markdown) {
        ThoughtChainParser.splitThoughtAndContent(markdown).visibleContent
    }

    val blocks = remember(cleanedMarkdown) {
        parseMarkdownBlocks(cleanedMarkdown)
    }

    SelectionContainer(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (block in blocks) {
                when (block) {
                    is MarkdownBlock.Heading -> {
                        val (fontSize, lineHeight, color) = when (block.level) {
                            1 -> Triple(20.sp, 26.sp, GeminiTextWhite)
                            2 -> Triple(18.sp, 24.sp, GeminiTextWhite)
                            3 -> Triple(16.sp, 22.sp, ArgonCyan)
                            else -> Triple(15.sp, 20.sp, GeminiTextSecondary)
                        }
                        Text(
                            text = parseInlineMarkdown(block.text, color),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                fontWeight = FontWeight.SemiBold,
                                color = color
                            ),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    is MarkdownBlock.Paragraph -> {
                        Text(
                            text = parseInlineMarkdown(block.text, textColor),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = textColor,
                                lineHeight = 23.sp
                            )
                        )
                    }

                    is MarkdownBlock.BulletItem -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (block.indentLevel * 14).dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = block.bulletMarker,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = ArgonCyan,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 23.sp
                                ),
                                modifier = Modifier.width(24.dp)
                            )
                            Text(
                                text = parseInlineMarkdown(block.text, textColor),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = textColor,
                                    lineHeight = 23.sp
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is MarkdownBlock.Quote -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(ArgonIndigoSurface.copy(alpha = 0.65f))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(ArgonCyan)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = parseInlineMarkdown(block.text, GeminiTextSecondary),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = GeminiTextSecondary,
                                    fontStyle = FontStyle.Italic,
                                    lineHeight = 21.sp
                                )
                            )
                        }
                    }

                    is MarkdownBlock.CodeBlock -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF0A0D14),
                            border = BorderStroke(1.dp, GeminiInputBarStroke),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (block.language.isNotBlank()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(ArgonIndigoSurface)
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = block.language.uppercase(),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = JetBrainsMonoFontFamily,
                                                color = ArgonCyan,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        )
                                    }
                                    HorizontalDivider(color = GeminiInputBarStroke)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = block.code,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = JetBrainsMonoFontFamily,
                                            color = GeminiTextWhite,
                                            lineHeight = 18.sp
                                        )
                                    )
                                }
                            }
                        }
                    }

                    is MarkdownBlock.TableBlock -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ArgonIndigoSurface,
                            border = BorderStroke(1.dp, ArgonBorderSubtle),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Column {
                                if (block.headers.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .background(GeminiInputBarBg)
                                            .padding(vertical = 8.dp, horizontal = 10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        for (h in block.headers) {
                                            Text(
                                                text = parseInlineMarkdown(h, ArgonCyan),
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    color = ArgonCyan,
                                                    fontWeight = FontWeight.SemiBold
                                                ),
                                                modifier = Modifier.width(120.dp)
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = GeminiInputBarStroke)
                                }
                                for ((rIdx, row) in block.rows.withIndex()) {
                                    Row(
                                        modifier = Modifier
                                            .background(
                                                if (rIdx % 2 == 1) Color(0xFF101520) else Color.Transparent
                                            )
                                            .padding(vertical = 7.dp, horizontal = 10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        for (cell in row) {
                                            Text(
                                                text = parseInlineMarkdown(cell, GeminiTextWhite),
                                                style = MaterialTheme.typography.bodySmall.copy(color = GeminiTextWhite),
                                                modifier = Modifier.width(120.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    is MarkdownBlock.Divider -> {
                        HorizontalDivider(
                            color = GeminiInputBarStroke,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    if (markdown.isBlank()) return emptyList()
    val lines = markdown.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0

    val numberedRegex = Regex("""^(\s*)(\d+\.)\s+(.*)$""")
    val bulletRegex = Regex("""^(\s*)([-*•])\s+(.*)$""")
    val headingRegex = Regex("""^(#{1,4})\s+(.*)$""")

    while (i < lines.size) {
        val rawLine = lines[i]
        val trimmed = rawLine.trim()

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // Fenced code block ```
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val codeBuilder = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                if (codeBuilder.isNotEmpty()) codeBuilder.append('\n')
                codeBuilder.append(lines[i])
                i++
            }
            if (i < lines.size) i++ // consume closing ```
            blocks.add(MarkdownBlock.CodeBlock(language = lang, code = codeBuilder.toString()))
            continue
        }

        // Horizontal rule
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            blocks.add(MarkdownBlock.Divider)
            i++
            continue
        }

        // Heading
        val headingMatch = headingRegex.matchEntire(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2].trim()
            blocks.add(MarkdownBlock.Heading(level = level, text = text))
            i++
            continue
        }

        // Markdown Table
        if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.count { it == '|' } >= 2) {
            val tableLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().let { it.startsWith("|") && it.endsWith("|") }) {
                tableLines.add(lines[i].trim())
                i++
            }
            val parsedRows = tableLines
                .filterNot { row -> row.replace("|", "").replace("-", "").replace(":", "").isBlank() }
                .map { row ->
                    row.trim('|').split('|').map { it.trim() }
                }
            if (parsedRows.isNotEmpty()) {
                blocks.add(
                    MarkdownBlock.TableBlock(
                        headers = parsedRows.first(),
                        rows = if (parsedRows.size > 1) parsedRows.drop(1) else emptyList()
                    )
                )
            }
            continue
        }

        // Blockquote
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines.add(lines[i].trim().removePrefix(">").trim())
                i++
            }
            blocks.add(MarkdownBlock.Quote(quoteLines.joinToString("\n")))
            continue
        }

        // Bullet list item
        val bulletMatch = bulletRegex.matchEntire(rawLine)
        if (bulletMatch != null) {
            val indent = (bulletMatch.groupValues[1].length / 2).coerceIn(0, 4)
            val content = bulletMatch.groupValues[3]
            blocks.add(MarkdownBlock.BulletItem(indentLevel = indent, bulletMarker = "•", text = content))
            i++
            continue
        }

        // Numbered list item
        val numMatch = numberedRegex.matchEntire(rawLine)
        if (numMatch != null) {
            val indent = (numMatch.groupValues[1].length / 2).coerceIn(0, 4)
            val marker = numMatch.groupValues[2]
            val content = numMatch.groupValues[3]
            blocks.add(MarkdownBlock.BulletItem(indentLevel = indent, bulletMarker = marker, text = content))
            i++
            continue
        }

        // Paragraph (accumulate consecutive plain lines)
        val paraBuilder = StringBuilder(trimmed)
        i++
        while (i < lines.size) {
            val nextRaw = lines[i]
            val nextTrim = nextRaw.trim()
            if (nextTrim.isEmpty() ||
                nextTrim.startsWith("```") ||
                nextTrim.startsWith("#") ||
                nextTrim.startsWith(">") ||
                (nextTrim.startsWith("|") && nextTrim.endsWith("|")) ||
                nextTrim == "---" ||
                bulletRegex.matches(nextRaw) ||
                numberedRegex.matches(nextRaw)
            ) {
                break
            }
            paraBuilder.append(' ').append(nextTrim)
            i++
        }
        blocks.add(MarkdownBlock.Paragraph(paraBuilder.toString()))
    }

    return blocks
}

/**
 * Parses inline markdown tokens:
 * - `code` (inline monospace pill)
 * - ***bold italic***
 * - **bold**
 * - *italic*
 * - ~~strikethrough~~
 */
private fun parseInlineMarkdown(text: String, baseColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        val len = text.length

        while (cursor < len) {
            // 1. Inline code `...`
            if (text[cursor] == '`') {
                val endTick = text.indexOf('`', cursor + 1)
                if (endTick != -1) {
                    val codeContent = text.substring(cursor + 1, endTick)
                    withStyle(
                        SpanStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = ArgonCyan,
                            background = Color(0xFF161C28),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(" $codeContent ")
                    }
                    cursor = endTick + 1
                    continue
                }
            }

            // 2. Bold + Italic ***...***
            if (text.startsWith("***", cursor)) {
                val endIdx = text.indexOf("***", cursor + 3)
                if (endIdx != -1) {
                    val inner = text.substring(cursor + 3, endIdx)
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic,
                            color = GeminiTextWhite
                        )
                    ) {
                        append(parseInlineMarkdown(inner, GeminiTextWhite))
                    }
                    cursor = endIdx + 3
                    continue
                }
            }

            // 3. Bold **...**
            if (text.startsWith("**", cursor)) {
                val endIdx = text.indexOf("**", cursor + 2)
                if (endIdx != -1) {
                    val inner = text.substring(cursor + 2, endIdx)
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.SemiBold,
                            color = GeminiTextWhite
                        )
                    ) {
                        append(parseInlineMarkdown(inner, GeminiTextWhite))
                    }
                    cursor = endIdx + 2
                    continue
                }
            }

            // 4. Strikethrough ~~...~~
            if (text.startsWith("~~", cursor)) {
                val endIdx = text.indexOf("~~", cursor + 2)
                if (endIdx != -1) {
                    val inner = text.substring(cursor + 2, endIdx)
                    withStyle(
                        SpanStyle(
                            textDecoration = TextDecoration.LineThrough,
                            color = GeminiTextMuted
                        )
                    ) {
                        append(inner)
                    }
                    cursor = endIdx + 2
                    continue
                }
            }

            // 5. Italic *...*
            if (text[cursor] == '*' && (cursor + 1 < len && text[cursor + 1] != ' ')) {
                val endIdx = text.indexOf('*', cursor + 1)
                if (endIdx != -1) {
                    val inner = text.substring(cursor + 1, endIdx)
                    withStyle(
                        SpanStyle(
                            fontStyle = FontStyle.Italic,
                            color = baseColor
                        )
                    ) {
                        append(parseInlineMarkdown(inner, baseColor))
                    }
                    cursor = endIdx + 1
                    continue
                }
            }

            append(text[cursor])
            cursor++
        }
    }
}
