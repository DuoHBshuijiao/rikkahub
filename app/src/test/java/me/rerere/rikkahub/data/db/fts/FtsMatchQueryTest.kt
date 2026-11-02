package me.rerere.rikkahub.data.db.fts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsMatchQueryTest {
    private fun build(keyword: String) = buildFtsMatchQuery(keyword) { "<$it>" }

    @Test
    fun `requires chinese characters to form an ordered contiguous phrase`() {
        assertEquals("\"你好世界\"", build("你好世界"))
        assertEquals("\"老手艺\"", build("老手艺"))
        assertEquals("\"好\"", build("好"))
    }

    @Test
    fun `sends ascii runs to simple_query and joins segments with AND`() {
        assertEquals("<GPT4> AND \"模型\"", build("GPT4模型"))
        assertEquals("\"周杰伦\" AND <jay>", build("周杰伦 jay"))
        assertEquals("<laoshouyi>", build("laoshouyi"))
    }

    @Test
    fun `escapes quotes and keeps surrogate pairs whole`() {
        assertEquals("\"引\"\"\"", build("引\""))
        assertEquals("\"😀好\"", build("😀好"))
    }

    @Test
    fun `returns null for blank keywords`() {
        assertNull(build("   "))
        assertNull(buildFtsMatchQuery("abc") { "" })
    }
}
