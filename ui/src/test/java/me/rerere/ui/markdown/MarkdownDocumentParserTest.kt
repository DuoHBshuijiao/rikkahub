package me.rerere.ui.markdown

import me.rerere.bettertext.markdown.MarkdownBlock
import me.rerere.bettertext.markdown.MarkdownInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MarkdownDocumentParserTest {
    @Test
    fun `completion resolves definitions appended after already stable blocks`() {
        val parser = MarkdownDocumentParser(true)
        val prefix = "[reference][id]\n\n" + "A stable paragraph.\n\n".repeat(12)
        parser.parse(prefix, streaming = true)
        val source = prefix + "[id]: https://example.com\n"
        parser.parse(source, streaming = true)
        val completed = parser.parse(source, streaming = false)
        assertEquals(parseMarkdown(source), completed)
        val paragraph = completed.blocks.first() as MarkdownBlock.Paragraph
        assertEquals("https://example.com", (paragraph.content.single() as MarkdownInline.Link).destination)
    }

    @Test
    fun `stopping an unfinished reply replaces temporary syntax completion`() {
        val parser = MarkdownDocumentParser(true)
        val temporary = parser.parse("**unfinished", streaming = true)
        val completed = parser.parse("**unfinished", streaming = false)
        assertNotEquals(temporary.blocks, completed.blocks)
        assertEquals(parseMarkdown("**unfinished"), completed)
    }

    @Test
    fun `replacement documents and repeated completed input stay consistent`() {
        val parser = MarkdownDocumentParser(false)
        parser.parse("old\n\ncontent", streaming = true)
        val replacement = parser.parse("new first\nnew second", streaming = false)
        assertEquals(parseMarkdown("new first\nnew second", false), replacement)
        assertSame(replacement, parser.parse("new first\nnew second", streaming = false))
    }
}
