package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.fts.decodeHighlightedRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MessageSearchTest {
    private fun message(
        text: String
    ) = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    private fun hits(text: String, query: String, assistant: Assistant? = null, fallback: List<String> = emptyList()) =
        buildMessageSearchHits("conversation", "node", message(text), assistant, query, fallbackTerms = fallback)

    @Test
    fun `every occurrence has its own displayed offset and balanced context`() {
        val text = "甲".repeat(35) + "关键词" + "乙".repeat(35) + "关键词" + "丙".repeat(35)
        val results = hits(text, "关键词")
        assertEquals(2, results.size)
        assertEquals(listOf(35, 73), results.map { it.target.start })
        assertEquals("… " + "甲".repeat(20) + "关键词" + "乙".repeat(20) + " …", results.first().snippet.text)
        assertEquals("关键词", results.first().snippet.let { it.text.substring(it.highlight.start, it.highlight.end) })
        assertNotEquals(results.first().key, results.last().key)
    }

    @Test
    fun `long reply is searchable after ten thousand characters`() {
        val results = hits("正文".repeat(50_000) + "结尾关键词", "关键词")
        assertEquals(1, results.size)
        assertEquals(100_002, results.single().target.start)
        assertTrue(results.single().snippet.text.endsWith("结尾关键词"))
    }

    @Test
    fun `matching is case insensitive and adjacent occurrences remain separate`() {
        assertEquals(listOf(SearchTextRange(0, 3), SearchTextRange(3, 6)), findSearchRanges("ABCabc", "abc"))
        assertEquals(2, hits("哈哈哈哈", "哈哈").size)
        assertTrue(findSearchRanges("abc", " ").isEmpty())
    }

    @Test
    fun `source markup never shifts the displayed target`() {
        val results = hits("开头**关键**词和[关键词](https://example.com/关键词)", "关键词")
        assertEquals(listOf(2, 6), results.map { it.target.start })
        assertEquals(2, results.size)
        assertTrue(hits("[链接](https://hidden.example)", "hidden").isEmpty())
    }

    @Test
    fun `visual replacements are applied before calculating offsets and fingerprints`() {
        val assistant = Assistant(regexes = listOf(AssistantRegex(
            id = Uuid.random(),
            findRegex = "隐藏内容",
            replaceString = "",
            visualOnly = true,
            affectingScope = setOf(AssistantAffectScope.ASSISTANT),
        )))
        val result = hits("隐藏内容前文关键词", "关键词", assistant).single()
        assertEquals(2, result.target.start)
        assertEquals(searchContentFingerprint("前文关键词"), result.target.contentFingerprint)
        assertTrue(hits("隐藏内容", "隐藏内容", assistant).isEmpty())
    }

    @Test
    fun `part addresses skip reasoning without losing the original part index`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("第一部分关键词"),
            UIMessagePart.Image("https://example.com/image"),
            UIMessagePart.Text("第二部分关键词"),
        ))
        val results = buildMessageSearchHits("conversation", "node", message, null, "关键词")
        assertEquals(listOf(0, 2), results.map { it.target.partIndex })
    }

    @Test
    fun `pinyin and prefix matches use actual indexed text when the query is not literal`() {
        val results = hits("窗前明月，山中明月", "mingyue", fallback = listOf("明月"))
        assertEquals(2, results.size)
        assertTrue(results.all { it.target.matchedText == "明月" })
        assertFalse(results.any { it.snippet.text.contains("mingyue") })
    }

    @Test
    fun `snippet boundaries preserve surrogate pairs and original brackets`() {
        val snippet = buildSearchSnippet("😀[关键词]😀", SearchTextRange(3, 6), contextCharacters = 2)
        assertEquals("😀[关键词]😀", snippet.text)
        assertEquals(SearchTextRange(3, 6), snippet.highlight)
        val start = buildSearchSnippet("关键词后文", SearchTextRange(0, 3))
        assertEquals("关键词后文", start.text)
    }

    @Test
    fun `native byte positions convert chinese and emoji to utf16 ranges`() {
        assertEquals(listOf(SearchTextRange(1, 5)), decodeFtsMatchPositions("a你😀好z", "1,11;"))
        assertEquals(emptyList<SearchTextRange>(), decodeFtsMatchPositions("你", "1,2;invalid;"))
        assertEquals(listOf(SearchTextRange(0, 1), SearchTextRange(2, 3)), decodeFtsMatchPositions("你a好", "0,3;4,7;"))
    }

    @Test
    fun `legacy native highlight fallback does not interpret user brackets as markers`() {
        assertEquals(
            listOf(SearchTextRange(3, 6)),
            decodeHighlightedRanges("[前]关键词[后]", "[前]<open>关键词<close>[后]", "<open>", "<close>"),
        )
    }
}
