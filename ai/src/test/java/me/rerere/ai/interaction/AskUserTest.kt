package me.rerere.ai.interaction

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserTest {
    private val single = UserQuestion("q", "怎么实现？", QuestionSelection.SINGLE, listOf("A", "A"))
    private val multi = single.copy(selection = QuestionSelection.MULTI)

    @Test
    fun `shared fixtures normalize legacy input and report malformed questions without losing the form`() {
        val cases = AskUserProtocol.json.parseToJsonElement(
            javaClass.getResource("/ask-user-cases.json")!!.readText()
        ).jsonObject
        cases.getValue("valid").jsonArray.forEach { fixture ->
            val obj = fixture.jsonObject
            val parsed = AskUserProtocol.parse(obj.getValue("input").toString())
            assertEquals(
                obj.getValue("expected"),
                AskUserProtocol.json.parseToJsonElement(AskUserProtocol.json.encodeToString(parsed))
            )
        }
        cases.getValue("invalid").jsonArray.forEach { fixture ->
            val obj = fixture.jsonObject
            val error = AskUserProtocol.inputError(obj.getValue("input").toString())
            assertNotNull(error)
            assertTrue(error!!, error.startsWith(obj.getValue("path").jsonPrimitive.content))
        }
        assertEquals("$: invalid JSON", AskUserProtocol.inputError("{unfinished"))
    }

    @Test
    fun `editing selects by identity and never merges duplicate labels`() {
        val start = AskUserProtocol.newDraft(listOf(single)).questions.single()
        val selected = start.select(single, "q:s0")
        val edited = selected.edit(single, "q:s1", "changed")
        assertEquals(setOf("q:s1"), edited.selectedIds)
        assertEquals("A", edited.options[0].text)
        assertEquals("changed", edited.options[1].text)
        assertEquals("A", edited.options[1].originalText)
        assertTrue(edited.isComplete(single))
        val blank = edited.edit(single, "q:s1", " ")
        assertEquals(edited.selectedIds, blank.selectedIds)
        assertFalse(blank.isComplete(single))
    }

    @Test
    fun `multi answers combine editable suggestions and several custom options`() {
        val start = AskUserProtocol.newDraft(listOf(multi)).questions.single()
        val edited = start.edit(multi, "q:s0", "A, B")
            .edit(multi, "q:c0", "Windows, Linux\n第二行 😀")
            .addOption().edit(multi, "q:c1", "第三项")
        assertEquals(setOf("q:s0", "q:c0", "q:c1"), edited.selectedIds)
        val encoded = AskUserProtocol.encodeAnswer(listOf(multi), AskUserDraft(listOf(edited)))
        assertEquals(listOf("A, B", "Windows, Linux\n第二行 😀", "第三项"), AskUserProtocol.answerTexts(encoded)["q"])
        val removed = edited.removeOption("q:c0")
        assertEquals(setOf("q:s0", "q:c1"), removed.selectedIds)
        assertEquals("第三项", removed.options.last().text)
        assertEquals(setOf("q:c1"), removed.select(multi, "q:s0").selectedIds)
    }

    @Test
    fun `custom input always exists and adding or restoring does not select anything`() {
        val start = AskUserProtocol.newDraft(listOf(single)).questions.single()
        assertTrue(start.addOption().selectedIds.isEmpty())
        val afterRemoval = start.removeOption("q:c0")
        assertTrue(afterRemoval.options.any { it.id == "q:c1" && it.isCustom })
        assertEquals("q:c2", afterRemoval.addOption().options.last().id)
        val edited = start.edit(single, "q:s0", "edited").select(single, "q:s1")
        val restored = edited.restoreOption("q:s0")
        assertEquals("A", restored.options[0].text)
        assertEquals(setOf("q:s1"), restored.selectedIds)
        assertFalse(AskUserDraft(emptyList()).isComplete(emptyList()))
    }

    @Test
    fun `answers reject nonexistent options wrong cardinality and missing questions`() {
        val draft = AskUserProtocol.newDraft(listOf(single))
            .update("q") { it.edit(single, "q:s0", "edited") }
        val valid = AskUserProtocol.encodeAnswer(listOf(single), draft)
        fun reject(value: String) {
            assertThrows(
                AskUserValidationException::class.java
            ) { AskUserProtocol.validateAnswer(listOf(single), value) }
        }
        reject(valid.replace("q:s0", "q:s9"))
        reject(valid.replace("suggested", "custom"))
        reject(valid.replace("\"text\":\"edited\"", "\"text\":\"\""))
        reject("""{"version":2,"answers":{"q":[]}}""")
        reject("""{"version":2,"answers":{}}""")
        val two = AskUserProtocol.encodeAnswer(
            listOf(multi),
            AskUserProtocol.newDraft(listOf(multi)).update("q") { it.select(multi, "q:s0").select(multi, "q:s1") },
        )
        reject(two)
    }

    @Test
    fun `historical strings remain intact and draft serialization preserves identity`() {
        val legacy = """{"answers":{"q":"A, B, 自定义"}}"""
        AskUserProtocol.validateAnswer(listOf(single), legacy)
        assertEquals(listOf("A, B, 自定义"), AskUserProtocol.answerTexts(legacy)["q"])
        val draft = AskUserProtocol.newDraft(listOf(multi)).update("q") { it.edit(multi, "q:c0", "答案").addOption() }
        assertEquals(
            draft,
            AskUserProtocol.json.decodeFromString<AskUserDraft>(AskUserProtocol.json.encodeToString(draft))
        )
    }
}
