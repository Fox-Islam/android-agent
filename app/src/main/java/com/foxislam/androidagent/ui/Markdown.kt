package com.foxislam.androidagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlin.math.min

/**
 * Minimal Markdown for a chat bubble. A full CommonMark dependency would be several hundred KB to draw
 * a handful of spans, so this covers the subset that turns up in agent output and leaves
 * anything stranger as the literal text the model wrote
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val blocks = remember(text) { parseBlocks(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = LocalContentColor.current.copy(alpha = 0.12f)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Paragraph -> Text(block.text.inline(linkColor, codeBackground), style = style)

                is Block.Heading -> Text(
                    text = block.text.inline(linkColor, codeBackground),
                    style = style.copy(fontWeight = FontWeight.Bold).let {
                        if (block.level <= 2 && it.fontSize.isSpecified) {
                            it.copy(fontSize = it.fontSize * 1.15f)
                        } else {
                            it
                        }
                    },
                )

                is Block.Item -> Row(
                    modifier = Modifier.padding(start = (block.depth * 14).dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(block.bullet, style = style)
                    Text(block.text.inline(linkColor, codeBackground), style = style)
                }

                is Block.Code -> Surface(
                    color = codeBackground,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = block.text,
                        style = style.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }

                is Block.Quote -> Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(LocalContentColor.current.copy(alpha = 0.4f)),
                    )
                    Text(block.text.inline(linkColor, codeBackground), style = style)
                }

                Block.Rule -> HorizontalDivider()
            }
        }
    }
}

internal sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Item(val bullet: String, val text: String, val depth: Int) : Block
    data class Code(val text: String) : Block
    data class Quote(val text: String) : Block
    data object Rule : Block
}

private const val MAX_NESTING = 8

private val FENCE = Regex("^\\s*(```|~~~)")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
private val QUOTE = Regex("^\\s*>\\s?(.*)$")
private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

/**
 * Blank lines separate blocks; single newlines inside one are kept. Markdown would fold them
 * into spaces, but a model that broke a line meant to break it
 */
internal fun parseBlocks(source: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = StringBuilder()
    val quote = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trim())
        paragraph.setLength(0)
    }

    fun flushQuote() {
        if (quote.isNotBlank()) blocks += Block.Quote(quote.toString().trim())
        quote.setLength(0)
    }

    fun flush() {
        flushParagraph()
        flushQuote()
    }

    val lines = source.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = FENCE.find(line)

        if (fence != null) {
            flush()
            val marker = fence.groupValues[1]
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(marker)) {
                body.append(lines[i]).append('\n')
                i++
            }
            // An unterminated fence runs to the end of the message, which is what a model
            // truncated mid-block produces - render what there is instead of nothing
            i++
            blocks += Block.Code(body.toString().trimEnd('\n'))
            continue
        }

        val heading = HEADING.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        val quoted = QUOTE.matchEntire(line)

        when {
            line.isBlank() -> flush()

            RULE.matches(line) -> { flush(); blocks += Block.Rule }

            heading != null -> {
                flush()
                blocks += Block.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }

            numbered != null -> {
                flush()
                blocks += Block.Item(
                    bullet = numbered.groupValues[2] + ".",
                    text = numbered.groupValues[3],
                    depth = numbered.groupValues[1].length / 2,
                )
            }

            bullet != null -> {
                flush()
                blocks += Block.Item(
                    bullet = "•",
                    text = bullet.groupValues[2],
                    depth = bullet.groupValues[1].length / 2,
                )
            }

            quoted != null -> {
                flushParagraph()
                quote.append(quoted.groupValues[1]).append('\n')
            }

            else -> {
                flushQuote()
                paragraph.append(line.trim()).append('\n')
            }
        }
        i++
    }

    flush()
    return blocks
}

internal fun String.inline(linkColor: Color, codeBackground: Color): AnnotatedString =
    buildAnnotatedString { appendInline(this@inline, linkColor, codeBackground) }

/**
 * [depth] stops a wall of asterisks from recursing once per pair all the way down the stack.
 * Nesting this deep is not meaningful markup, so past the limit the rest is literal text
 */
private fun AnnotatedString.Builder.appendInline(
    src: String,
    link: Color,
    code: Color,
    depth: Int = 0,
) {
    if (depth > MAX_NESTING) {
        append(src)
        return
    }
    var i = 0
    while (i < src.length) {
        val c = src[i]
        val next = when {
            c == '\\' && i + 1 < src.length && !src[i + 1].isLetterOrDigit() -> {
                append(src[i + 1]); i + 2
            }
            c == '`' -> appendCode(src, i, code)
            c == '[' -> appendLink(src, i, link, code, depth)
            c == '*' || c == '_' || c == '~' -> appendEmphasis(src, i, link, code, depth)
            c == 'h' -> appendAutolink(src, i, link)
            else -> null
        }
        if (next == null) {
            append(c)
            i++
        } else {
            i = next
        }
    }
}

private fun AnnotatedString.Builder.appendCode(src: String, at: Int, background: Color): Int? {
    val ticks = src.runLengthAt(at, '`')
    val fence = "`".repeat(ticks)
    val close = src.indexOf(fence, at + ticks)
    if (close < 0) return null
    val inner = src.substring(at + ticks, close)
    if (inner.isEmpty()) return null
    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = background)) {
        append(inner.trim())
    }
    return close + ticks
}

private fun AnnotatedString.Builder.appendLink(
    src: String,
    at: Int,
    link: Color,
    code: Color,
    depth: Int,
): Int? {
    val label = src.indexOfClosing(at, '[', ']') ?: return null
    if (label + 1 >= src.length || src[label + 1] != '(') return null
    val target = src.indexOfClosing(label + 1, '(', ')') ?: return null
    // [label](url "title") - the title is not rendered anywhere, so drop it
    val url = src.substring(label + 2, target).trim().substringBefore(' ')
    if (url.isEmpty()) return null

    val start = length
    appendInline(src.substring(at + 1, label), link, code, depth + 1)
    addLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link))), start, length)
    return target + 1
}

private fun AnnotatedString.Builder.appendEmphasis(
    src: String,
    at: Int,
    link: Color,
    code: Color,
    depth: Int,
): Int? {
    val char = src[at]
    val run = src.runLengthAt(at, char)

    // A delimiter run opens a span only when non-whitespace follows it, and closes one only
    // when non-whitespace precedes it. Without that, "2 * 3 * 4" comes out italic
    if (at + run >= src.length || src[at + run].isWhitespace()) return null

    if (char == '~') return appendStrikethrough(src, at, run, link, code, depth)

    // snake_case is not emphasis: an underscore only opens a span at a word boundary
    if (char == '_' && at > 0 && src[at - 1].isLetterOrDigit()) return null

    val width = min(run, 3)
    val marker = char.toString().repeat(width)
    val close = src.closingMarker(marker, at + width, char == '_') ?: return null
    val inner = src.substring(at + width, close)
    if (inner.isBlank()) return null
    withStyle(emphasisStyle(width)) { appendInline(inner, link, code, depth + 1) }
    return close + width
}

private fun AnnotatedString.Builder.appendStrikethrough(
    src: String,
    at: Int,
    run: Int,
    link: Color,
    code: Color,
    depth: Int,
): Int? {
    if (run < 2) return null
    val close = src.indexOf("~~", at + 2)
    if (close < 0 || close == at + 2 || src[close - 1].isWhitespace()) return null
    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
        appendInline(src.substring(at + 2, close), link, code, depth + 1)
    }
    return close + 2
}

/**
 * Where [marker] closes the span opened at [from], skipping runs that cannot close one: a
 * marker with a space in front of it, and for [underscore] a marker inside a word
 */
private fun String.closingMarker(marker: String, from: Int, underscore: Boolean): Int? {
    var at = from
    while (true) {
        val close = indexOf(marker, at)
        if (close < 0) return null
        val spaceBefore = this[close - 1].isWhitespace()
        val insideWord = underscore &&
            close + marker.length < length && this[close + marker.length].isLetterOrDigit()
        if (!spaceBefore && !insideWord) return close
        at = close + marker.length
    }
}

private fun emphasisStyle(width: Int): SpanStyle = when (width) {
    1 -> SpanStyle(fontStyle = FontStyle.Italic)
    2 -> SpanStyle(fontWeight = FontWeight.Bold)
    else -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
}

private val AUTOLINK = Regex("https?://[^\\s<>\\[\\]()]+")

/** A bare URL is still a link the user wants to tap, and models rarely bracket them */
private fun AnnotatedString.Builder.appendAutolink(src: String, at: Int, link: Color): Int? {
    if (at > 0 && src[at - 1].isLetterOrDigit()) return null
    val match = AUTOLINK.matchAt(src, at) ?: return null
    // A URL that ends a sentence should not swallow the full stop
    val url = match.value.trimEnd('.', ',', ';', ':', '!', '?')
    if (url.isEmpty()) return null

    val start = length
    append(url)
    addLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link))), start, length)
    return at + url.length
}

private fun String.runLengthAt(index: Int, char: Char): Int {
    var run = 0
    while (index + run < length && this[index + run] == char) run++
    return run
}

private fun String.indexOfClosing(open: Int, opener: Char, closer: Char): Int? {
    var depth = 0
    for (i in open until length) {
        when (this[i]) {
            opener -> depth++
            closer -> if (--depth == 0) return i
        }
    }
    return null
}
