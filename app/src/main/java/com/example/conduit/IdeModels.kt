package com.example.conduit

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.example.conduit.ui.theme.IdeAccent
import com.example.conduit.ui.theme.IdeSurfaceBrightDark
import com.example.conduit.ui.theme.SyntaxComment
import com.example.conduit.ui.theme.SyntaxKeyword
import com.example.conduit.ui.theme.SyntaxNumber
import com.example.conduit.ui.theme.SyntaxPlain
import com.example.conduit.ui.theme.SyntaxString
import com.example.conduit.ui.theme.SyntaxType

sealed class WorkspaceItem {
    abstract val id: String
    abstract val timestamp: String

    data class UserPrompt(
        override val id: String,
        override val timestamp: String,
        val text: String
    ) : WorkspaceItem()

    data class AssistantResponse(
        override val id: String,
        override val timestamp: String,
        val text: String
    ) : WorkspaceItem()

    data class ToolExecution(
        override val id: String,
        override val timestamp: String,
        val label: String,
        val command: String,
        val exitCode: Int? = null,
        val stdout: String = "",
        val stderr: String = "",
        val isRunning: Boolean = false
    ) : WorkspaceItem()

    data class SystemStatsItem(
        override val id: String,
        override val timestamp: String,
        val stats: SystemStats
    ) : WorkspaceItem()

    data class ScreenshotItem(
        override val id: String,
        override val timestamp: String,
        val bitmap: Bitmap
    ) : WorkspaceItem()

    data class ErrorNotice(
        override val id: String,
        override val timestamp: String,
        val title: String,
        val detail: String
    ) : WorkspaceItem()
}

enum class ToolActivityKind {
    COMMAND,
    EXPLORE,
    EDIT,
    THOUGHT
}

sealed class MarkdownSegment {
    data class Heading(val level: Int, val text: String) : MarkdownSegment()
    data class Paragraph(val text: String) : MarkdownSegment()
    data class BulletItem(val indent: Int, val marker: String, val text: String) : MarkdownSegment()
    data class CodeBlock(val language: String, val code: String) : MarkdownSegment()
    data class ToolActivity(
        val summary: String,
        val details: String,
        val kind: ToolActivityKind
    ) : MarkdownSegment()
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>
    ) : MarkdownSegment()
    data object Divider : MarkdownSegment()
}

private val TOOL_LINE_REGEX = Regex(
    """^(?:[●▸•]\s*(?:Ran|Explored|Edited|Created|Updated|Read|Searched|Thought(?:\s+for)?|Executed)\b.*|\[Ran\s+[^\]]+\].*)$"""
)
private val ORDERED_LIST_REGEX = Regex("""^(\d+\.)\s+(.*)$""")
private val HR_REGEX = Regex("""^([-*_])\1{2,}\s*$""")

private fun classifyToolKind(summary: String): ToolActivityKind {
    val lower = summary.lowercase()
    return when {
        lower.startsWith("thought") -> ToolActivityKind.THOUGHT
        lower.startsWith("edited") || lower.startsWith("created") || lower.startsWith("updated") -> ToolActivityKind.EDIT
        lower.startsWith("explored") || lower.startsWith("read") || lower.startsWith("searched") -> ToolActivityKind.EXPLORE
        else -> ToolActivityKind.COMMAND
    }
}

private fun extractParentheticalDetails(summary: String): String {
    val start = summary.indexOf('(')
    val end = summary.lastIndexOf(')')
    if (start != -1 && end > start + 1) {
        val inner = summary.substring(start + 1, end).trim()
        if (inner.contains("·")) {
            return inner.split("·").joinToString("\n") { "$ ${it.trim()}" }
        }
        return inner
    }
    return summary
}

private fun isTableRow(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("|") && t.endsWith("|") && t.count { it == '|' } >= 2
}

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (!isTableRow(t)) return false
    val cells = parseTableCells(t)
    return cells.isNotEmpty() && cells.all { cell ->
        val clean = cell.trim().removePrefix(":").removeSuffix(":")
        clean.isNotEmpty() && clean.all { it == '-' }
    }
}

private fun parseTableCells(row: String): List<String> {
    val trimmed = row.trim()
    val inside = trimmed.removePrefix("|").removeSuffix("|")
    return inside.split("|").map { it.trim() }
}

fun parseMarkdownSegments(raw: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val lines = raw.lines()
    val paraBuf = StringBuilder()
    val codeBuf = StringBuilder()
    var inCode = false
    var codeLang = ""

    fun flushParagraph() {
        val text = paraBuf.toString().trim()
        if (text.isNotEmpty()) {
            segments.add(MarkdownSegment.Paragraph(text))
        }
        paraBuf.clear()
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // Fenced code blocks
        if (trimmed.startsWith("```")) {
            if (!inCode) {
                flushParagraph()
                inCode = true
                codeLang = trimmed.removePrefix("```").trim()
                codeBuf.clear()
            } else {
                segments.add(MarkdownSegment.CodeBlock(codeLang, codeBuf.toString().trimEnd()))
                codeBuf.clear()
                inCode = false
                codeLang = ""
            }
            i++
            continue
        }

        if (inCode) {
            if (codeBuf.isNotEmpty()) codeBuf.append('\n')
            codeBuf.append(line)
            i++
            continue
        }

        // Blank line flushes current paragraph
        if (trimmed.isEmpty()) {
            flushParagraph()
            i++
            continue
        }

        // Tool activity lines (e.g. "● Ran `fastfetch`", "● Ran 2 commands ...", "● Edited ...", "● Thought for ...")
        if (TOOL_LINE_REGEX.matches(trimmed)) {
            flushParagraph()
            val cleanedSummary = trimmed
                .removePrefix("●")
                .removePrefix("▸")
                .removePrefix("•")
                .replace(Regex("""\s*\((?:tap|click|ctrl\+\w+)\s+to\s+expand\)\s*$""", RegexOption.IGNORE_CASE), "")
                .trim()

            // Check if the immediately following lines are a fenced code block containing the tool's output
            var j = i + 1
            var codeDetails: String? = null
            // Skip any empty line between the tool header and code block
            while (j < lines.size && lines[j].trim().isEmpty()) {
                j++
            }
            if (j < lines.size && lines[j].trim().startsWith("```")) {
                val blockLines = mutableListOf<String>()
                j++ // skip opening ```
                while (j < lines.size) {
                    val lineInside = lines[j]
                    if (lineInside.trim().startsWith("```")) {
                        j++ // skip closing ```
                        codeDetails = blockLines.joinToString("\n")
                        break
                    }
                    blockLines.add(lineInside)
                    j++
                }
            }

            if (codeDetails != null) {
                segments.add(
                    MarkdownSegment.ToolActivity(
                        summary = cleanedSummary,
                        details = codeDetails.trim(),
                        kind = classifyToolKind(cleanedSummary)
                    )
                )
                i = j
                continue
            }

            // Fallback: Collect optional indented/tree continuation lines under the tool line
            val detailLines = mutableListOf<String>()
            j = i + 1
            while (j < lines.size) {
                val nextLine = lines[j]
                val nextTrimmed = nextLine.trim()
                if (nextTrimmed.isEmpty()) break
                val isContinuation = nextLine.startsWith("  ") ||
                    nextTrimmed.startsWith("│") ||
                    nextTrimmed.startsWith("├") ||
                    nextTrimmed.startsWith("└") ||
                    nextTrimmed.startsWith("$ ")
                if (isContinuation && !TOOL_LINE_REGEX.matches(nextTrimmed)) {
                    detailLines.add(
                        nextTrimmed
                            .removePrefix("│")
                            .removePrefix("├")
                            .removePrefix("└")
                            .removePrefix("─")
                            .trim()
                    )
                    j++
                } else {
                    break
                }
            }

            val details = if (detailLines.isNotEmpty()) {
                detailLines.joinToString("\n")
            } else {
                extractParentheticalDetails(cleanedSummary)
            }

            segments.add(
                MarkdownSegment.ToolActivity(
                    summary = cleanedSummary,
                    details = details,
                    kind = classifyToolKind(cleanedSummary)
                )
            )
            i = j
            continue
        }

        // Horizontal rule
        if (HR_REGEX.matches(trimmed)) {
            flushParagraph()
            segments.add(MarkdownSegment.Divider)
            i++
            continue
        }

        // Headings (#, ##, ###)
        when {
            trimmed.startsWith("### ") -> {
                flushParagraph()
                segments.add(MarkdownSegment.Heading(3, trimmed.removePrefix("### ").trim()))
                i++
                continue
            }
            trimmed.startsWith("## ") -> {
                flushParagraph()
                segments.add(MarkdownSegment.Heading(2, trimmed.removePrefix("## ").trim()))
                i++
                continue
            }
            trimmed.startsWith("# ") -> {
                flushParagraph()
                segments.add(MarkdownSegment.Heading(1, trimmed.removePrefix("# ").trim()))
                i++
                continue
            }
        }

        // Unordered bullet items (- , * , • )
        val leadingSpaces = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val afterIndent = line.substring(leadingSpaces)
        if (afterIndent.startsWith("- ") || afterIndent.startsWith("* ") || afterIndent.startsWith("• ")) {
            flushParagraph()
            segments.add(
                MarkdownSegment.BulletItem(
                    indent = (leadingSpaces / 2).coerceAtMost(3),
                    marker = "•",
                    text = afterIndent.substring(2).trim()
                )
            )
            i++
            continue
        }

        // Ordered list items (1. , 2. )
        val orderedMatch = ORDERED_LIST_REGEX.matchEntire(afterIndent)
        if (orderedMatch != null) {
            flushParagraph()
            segments.add(
                MarkdownSegment.BulletItem(
                    indent = (leadingSpaces / 2).coerceAtMost(3),
                    marker = orderedMatch.groupValues[1],
                    text = orderedMatch.groupValues[2].trim()
                )
            )
            i++
            continue
        }

        // Table rows (| col 1 | col 2 |)
        if (isTableRow(trimmed) && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            flushParagraph()
            val headers = parseTableCells(trimmed)
            val rows = mutableListOf<List<String>>()
            var k = i + 2 // skip separator row
            while (k < lines.size) {
                val nextRow = lines[k].trim()
                if (!isTableRow(nextRow) || nextRow.isEmpty()) break
                rows.add(parseTableCells(nextRow))
                k++
            }
            segments.add(MarkdownSegment.Table(headers, rows))
            i = k
            continue
        }

        // Regular paragraph text
        if (paraBuf.isNotEmpty()) paraBuf.append('\n')
        paraBuf.append(trimmed)
        i++
    }

    if (inCode && codeBuf.isNotEmpty()) {
        segments.add(MarkdownSegment.CodeBlock(codeLang, codeBuf.toString().trimEnd()))
    } else {
        flushParagraph()
    }

    return segments
}

private val INLINE_MD_REGEX = Regex(
    """(`[^`\n]+`|\*\*\*[^*\n]+\*\*\*|\*\*[^*\n]+\*\*|(?<!\*)\*[^*\s][^*\n]*?\*(?!\*)|\[([^\]\n]+)\]\(([^)\n]+)\))"""
)

fun parseInlineMarkdown(
    text: String,
    inlineCodeBg: Color = IdeSurfaceBrightDark,
    inlineCodeColor: Color = Color(0xFFE2E8F0),
    linkColor: Color = IdeAccent
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in INLINE_MD_REGEX.findAll(text)) {
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        val token = match.value
        when {
            token.startsWith("`") && token.endsWith("`") -> {
                val code = token.substring(1, token.length - 1)
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = inlineCodeColor,
                        background = inlineCodeBg
                    )
                ) {
                    append("\u00A0$code\u00A0")
                }
            }
            token.startsWith("***") && token.endsWith("***") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                    append(token.substring(3, token.length - 3))
                }
            }
            token.startsWith("**") && token.endsWith("**") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(token.substring(2, token.length - 2))
                }
            }
            token.startsWith("*") && token.endsWith("*") -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(token.substring(1, token.length - 1))
                }
            }
            token.startsWith("[") && match.groupValues.size >= 3 -> {
                val label = match.groupValues[2]
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(label)
                }
            }
            else -> append(token)
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

private val CODE_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum",
    "if", "else", "when", "for", "while", "return", "try", "catch", "finally",
    "import", "package", "private", "public", "internal", "override", "suspend",
    "true", "false", "null", "this", "super", "def", "async", "await", "from",
    "const", "let", "function", "export", "default", "switch", "case", "break"
)

private val TOKEN_REGEX = Regex(
    """(//.*$|#.*$|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b\d+(?:\.\d+)?\b|\b[A-Z][A-Za-z0-9_]*\b|\b[a-z_][A-Za-z0-9_]*\b)""",
    RegexOption.MULTILINE
)

fun highlightCodeSyntax(code: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in TOKEN_REGEX.findAll(code)) {
        if (match.range.first > cursor) {
            withStyle(SpanStyle(color = SyntaxPlain, fontFamily = FontFamily.Monospace)) {
                append(code.substring(cursor, match.range.first))
            }
        }
        val token = match.value
        val color = when {
            token.startsWith("//") || token.startsWith("#") -> SyntaxComment
            token.startsWith("\"") || token.startsWith("'") -> SyntaxString
            token.first().isDigit() -> SyntaxNumber
            token in CODE_KEYWORDS -> SyntaxKeyword
            token.first().isUpperCase() -> SyntaxType
            else -> SyntaxPlain
        }
        val weight = if (token in CODE_KEYWORDS) FontWeight.SemiBold else FontWeight.Normal
        withStyle(SpanStyle(color = color, fontFamily = FontFamily.Monospace, fontWeight = weight)) {
            append(token)
        }
        cursor = match.range.last + 1
    }
    if (cursor < code.length) {
        withStyle(SpanStyle(color = SyntaxPlain, fontFamily = FontFamily.Monospace)) {
            append(code.substring(cursor))
        }
    }
}
