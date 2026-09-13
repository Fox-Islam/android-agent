package com.foxislam.androidagent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTest {

    private fun spans(source: String): List<Pair<String, SpanStyle>> =
        source.inline(Color.Blue, Color.Gray).let { annotated ->
            annotated.spanStyles.map { annotated.text.substring(it.start, it.end) to it.item }
        }

    private fun text(source: String): String = source.inline(Color.Blue, Color.Gray).text

    @Test
    fun `bold and italic, with asterisks or underscores`() {
        assertEquals(FontWeight.Bold, spans("a **b** c").single().second.fontWeight)
        assertEquals(FontWeight.Bold, spans("a __b__ c").single().second.fontWeight)
        assertEquals(FontStyle.Italic, spans("a *b* c").single().second.fontStyle)
        assertEquals(FontStyle.Italic, spans("a _b_ c").single().second.fontStyle)
        assertEquals("a b c", text("a **b** c"))

        val both = spans("***b***").single().second
        assertEquals(FontWeight.Bold, both.fontWeight)
        assertEquals(FontStyle.Italic, both.fontStyle)
    }

    @Test
    fun `snake_case is not emphasis`() {
        assertEquals("some_var_name", text("some_var_name"))
        assertTrue(spans("some_var_name").isEmpty())
    }

    @Test
    fun `unmatched markers stay literal`() {
        // A run that opens on whitespace is arithmetic, not emphasis
        assertEquals("2 * 3 * 4", text("2 * 3 * 4"))
        assertEquals("a * b * c", text("a * b * c"))
        assertEquals("open * close*", text("open * close*"))
        assertEquals("a ** b", text("a ** b"))
        assertEquals("a ` b", text("a ` b"))
        assertEquals("[not a link", text("[not a link"))
    }

    @Test
    fun `strikethrough and code spans`() {
        assertEquals(TextDecoration.LineThrough, spans("~~gone~~").single().second.textDecoration)
        assertEquals("gone", text("~~gone~~"))
        assertEquals("x = 1", text("`x = 1`"))
        assertEquals("**not bold**", text("`**not bold**`"))
    }

    @Test
    fun `links carry their url and drop the syntax`() {
        val annotated = "see [docs](https://example.com/a_b) now".inline(Color.Blue, Color.Gray)
        assertEquals("see docs now", annotated.text)
        val link = annotated.getLinkAnnotations(0, annotated.text.length).single()
        assertEquals("docs", annotated.text.substring(link.start, link.end))
    }

    @Test
    fun `bare urls become links without swallowing the full stop`() {
        val annotated = "go to https://example.com/x. ok".inline(Color.Blue, Color.Gray)
        assertEquals("go to https://example.com/x. ok", annotated.text)
        val link = annotated.getLinkAnnotations(0, annotated.text.length).single()
        assertEquals("https://example.com/x", annotated.text.substring(link.start, link.end))
    }

    @Test
    fun `escapes suppress markup`() {
        assertEquals("*literal*", text("\\*literal\\*"))
    }

    @Test
    fun `blocks are split by kind`() {
        val blocks = parseBlocks(
            """
            # Title

            Some **text**
            on two lines.

            - one
            - two
            1. first

            > quoted

            ```
            code *here*
            ```

            ---
            """.trimIndent(),
        )
        assertEquals(
            listOf("Heading", "Paragraph", "Item", "Item", "Item", "Quote", "Code", "Rule"),
            blocks.map { it::class.simpleName },
        )
        assertEquals("Some **text**\non two lines.", (blocks[1] as Block.Paragraph).text)
        assertEquals("•", (blocks[2] as Block.Item).bullet)
        assertEquals("1.", (blocks[4] as Block.Item).bullet)
        assertEquals("code *here*", (blocks[6] as Block.Code).text)
    }

    @Test
    fun `an unterminated fence still renders`() {
        val blocks = parseBlocks("before\n\n```\nstuck")
        assertEquals(listOf("Paragraph", "Code"), blocks.map { it::class.simpleName })
        assertEquals("stuck", (blocks[1] as Block.Code).text)
    }

    @Test
    fun `pathological input terminates`() {
        repeat(3) { n ->
            val source = "*".repeat(500 * (n + 1))
            assertTrue(text(source).isNotEmpty())
        }
        assertTrue(parseBlocks("`".repeat(2000)).isNotEmpty())
    }
}
