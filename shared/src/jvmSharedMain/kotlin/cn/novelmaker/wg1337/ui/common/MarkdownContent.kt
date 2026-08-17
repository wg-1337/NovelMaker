package cn.novelmaker.wg1337.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染组件（无第三方库，仅供展示）。
 *
 * 支持：标题（# ~ ###）、粗体（**text**）、斜体（*text*）、行内代码（`code`）、
 * 代码块（``` 围栏）、无序/有序列表、引用（>）、分隔线（---）、链接（[text](url)）。
 *
 * 注意：仅用于输出展示；编辑场景仍使用纯文本（Markdown 格式不在编辑中生效）。
 */
@Composable
fun MarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val colors = MaterialTheme.colorScheme
    val codeBg = colors.surface
    val linkColor = colors.primary
    val blocks = remember(markdown) { parseMarkdown(markdown) }

    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inline(block.text, codeBg, linkColor),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
                is MdBlock.Paragraph -> Text(
                    text = inline(block.text, codeBg, linkColor),
                    style = textStyle,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                is MdBlock.ListItem -> Row(Modifier.padding(vertical = 1.dp)) {
                    Text(block.marker, style = textStyle, color = colors.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(inline(block.text, codeBg, linkColor), style = textStyle)
                }
                is MdBlock.CodeBlock -> Surface(
                    color = codeBg,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        block.code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                is MdBlock.Quote -> Surface(
                    color = codeBg.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Text(
                        inline(block.text, codeBg, linkColor),
                        style = textStyle.copy(fontStyle = FontStyle.Italic),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                MdBlock.Divider -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

// ───────────────────── 解析 ─────────────────────

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class ListItem(val ordered: Boolean, val marker: String, val text: String) : MdBlock()
    data class CodeBlock(val code: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    object Divider : MdBlock()
}

/** 逐行解析 Markdown 为块列表；连续普通行合并为一个段落（保留换行） */
private fun parseMarkdown(md: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val pendingText = mutableListOf<String>()
    var inCodeBlock = false
    val codeLines = mutableListOf<String>()

    fun flushText() {
        if (pendingText.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(pendingText.joinToString("\n")))
            pendingText.clear()
        }
    }

    for (line in md.lines()) {
        if (inCodeBlock) {
            if (line.trimStart().startsWith("```")) {
                blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n")))
                codeLines.clear()
                inCodeBlock = false
            } else {
                codeLines.add(line)
            }
            continue
        }
        if (line.trimStart().startsWith("```")) {
            flushText()
            inCodeBlock = true
            continue
        }
        val t = line.trim()
        when {
            t.isEmpty() -> flushText()
            t.startsWith("###") -> { flushText(); blocks.add(MdBlock.Heading(3, t.removePrefix("###").trim())) }
            t.startsWith("##") -> { flushText(); blocks.add(MdBlock.Heading(2, t.removePrefix("##").trim())) }
            t.startsWith("#") -> { flushText(); blocks.add(MdBlock.Heading(1, t.removePrefix("#").trim())) }
            t.matches(Regex("^[-*_]{3,}\\s*$")) -> { flushText(); blocks.add(MdBlock.Divider) }
            t.startsWith(">") -> { flushText(); blocks.add(MdBlock.Quote(t.removePrefix(">").trim())) }
            t.startsWith("- ") || t.startsWith("* ") -> { flushText(); blocks.add(MdBlock.ListItem(false, "•", t.drop(2).trim())) }
            t.matches(Regex("^\\d+\\.\\s+.*")) -> {
                flushText()
                blocks.add(MdBlock.ListItem(true, "${t.substringBefore(".")}.", t.replace(Regex("^\\d+\\.\\s+"), "").trim()))
            }
            else -> pendingText.add(t)
        }
    }
    if (inCodeBlock) blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n")))
    flushText()
    return blocks
}

// ───────────────────── 行内样式 ─────────────────────

private data class InlineRule(val regex: Regex, val style: SpanStyle)

/** 渲染行内样式：粗体 → 行内代码 → 斜体 → 链接（支持嵌套） */
private fun inline(text: String, codeBg: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    appendStyled(
        text,
        listOf(
            InlineRule(Regex("\\*\\*(.+?)\\*\\*"), SpanStyle(fontWeight = FontWeight.Bold)),
            InlineRule(Regex("`([^`]+)`"), SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)),
            InlineRule(Regex("\\*(.+?)\\*"), SpanStyle(fontStyle = FontStyle.Italic)),
            InlineRule(
                Regex("\\[(.+?)\\]\\(([^)]+)\\)"),
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
            )
        ),
        0
    )
}

private fun AnnotatedString.Builder.appendStyled(text: String, rules: List<InlineRule>, index: Int) {
    if (index >= rules.size) {
        append(text)
        return
    }
    val (regex, style) = rules[index]
    var last = 0
    for (m in regex.findAll(text)) {
        append(text.substring(last, m.range.first))
        pushStyle(style)
        appendStyled(m.groupValues[1], rules, index + 1)
        pop()
        last = m.range.last + 1
    }
    append(text.substring(last))
}
