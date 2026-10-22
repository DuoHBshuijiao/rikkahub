package me.rerere.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSearchTest {
    @Test
    fun `search slots use displayed text instead of markdown syntax or link destinations`() {
        val segments = parseMarkdown("前文**关键**字[链接](https://hidden.example)后文").searchSegments()
        assertEquals(listOf(MarkdownSearchSegment("/0/text/0", "前文关键字链接后文")), segments)
    }

    @Test
    fun `nested lists quotes details and table cells have unambiguous paths`() {
        val document = parseMarkdown(
            """
            > 引用关键字

            - 列表关键字

            <details><summary>摘要</summary><p>折叠关键字</p></details>

            | 表头 |
            | ---- |
            | 表格关键字 |
            """.trimIndent()
        )
        val segments = document.searchSegments()
        assertEquals(segments.size, segments.map { it.path }.distinct().size)
        assertTrue(segments.any { it.path == "/0/0/text/0" && it.text == "引用关键字" })
        assertTrue(segments.any { it.path == "/1/item/0/0/text/0" && it.text == "列表关键字" })
        assertTrue(segments.any { it.path == "/2/summary" && it.text == "摘要" })
        assertTrue(segments.any { it.path == "/2/0/text/0" && it.text == "折叠关键字" })
        assertTrue(segments.any { it.path == "/3/row/0/0/text/0" && it.text == "表格关键字" })
    }

    @Test
    fun `code and repeated paragraphs retain distinct addresses`() {
        val segments = parseMarkdown("关键字\n\n关键字\n\n```text\n代码关键字\n```").searchSegments()
        assertEquals(listOf("/0/text/0", "/1/text/0", "/2/code"), segments.map { it.path })
        assertTrue(segments.last().text.contains("代码关键字"))
    }

    @Test
    fun `math placeholders preserve following character offsets without searching formulas`() {
        val segments = parseMarkdown("前面 ${'$'}formula${'$'} 后面关键字").searchSegments()
        val text = segments.single().text
        assertFalse(text.contains("formula"))
        assertEquals("前面 formula 后面关键字".indexOf("关键字"), text.indexOf("关键字"))
    }
}
