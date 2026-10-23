package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UserPersonaTest {
    private val default = UserPersona(id = DEFAULT_USER_PERSONA_ID, nickname = "Default")
    private val alice = UserPersona(nickname = "Alice", avatar = Avatar.Emoji("A"))
    private val bob = UserPersona(nickname = "Bob", avatar = Avatar.Image("file:///avatar.png"))
    private val settings = Settings(userPersonas = listOf(default, alice, bob), selectedUserPersonaId = alice.id)
    private val conversation = Conversation.ofId(kotlin.uuid.Uuid.random())

    @Test
    fun `legacy profile becomes the default without overwriting existing personas`() {
        val legacy = Settings(displaySetting = DisplaySetting(userNickname = "Legacy", userAvatar = Avatar.Emoji("L")))
        val migrated = legacy.withUserPersonaDefaults()
        assertEquals("Legacy", migrated.getCurrentUserPersona().nickname)
        assertEquals(Avatar.Emoji("L"), migrated.getCurrentUserPersona().avatar)
        assertEquals(migrated, migrated.withUserPersonaDefaults())
        assertEquals(settings, settings.withUserPersonaDefaults())
    }

    @Test
    fun `draft follows current selection and first save binds the selected identity`() {
        val draft = conversation.copy(newConversation = true)
        assertEquals(alice, settings.getUserPersonaOf(draft))
        val switched = settings.copy(selectedUserPersonaId = bob.id)
        val bound = draft.bindConfig(switched)
        assertEquals(bob.id, bound.config!!.userPersonaId)
        assertEquals(bob, settings.getUserPersonaOf(bound))
        assertSame(bound, bound.bindConfig(settings))
    }

    @Test
    fun `old conversations use the default even when a different identity is selected`() {
        assertEquals(DEFAULT_USER_PERSONA_ID, conversation.bindConfig(settings).config!!.userPersonaId)
        val oldConfig = JsonInstant.decodeFromString<ConversationConfig>("{}")
        assertEquals(default, settings.getUserPersonaOf(conversation.copy(config = oldConfig)))
    }

    @Test
    fun `editing an identity updates all bound conversations without rewriting text`() {
        val first = conversation.copy(config = ConversationConfig(userPersonaId = alice.id))
            .updateCurrentMessages(listOf(UIMessage.user("Alice said hello")))
        val second = first.copy(id = kotlin.uuid.Uuid.random())
        val edited = alice.copy(nickname = "Alicia", avatar = Avatar.Emoji("B"))
        val changed = settings.copy(userPersonas = listOf(default, edited, bob))
        assertEquals(edited, changed.getUserPersonaOf(first))
        assertEquals(edited, changed.getUserPersonaOf(second))
        assertEquals("Alice said hello", first.currentMessages.single().toText())
        assertEquals(alice, settings.getUserPersonaOf(first))
    }

    @Test
    fun `deletion falls back to default and normalizes a binding on save`() {
        val bound = conversation.copy(config = ConversationConfig(userPersonaId = alice.id))
        val deleted = settings.withoutUserPersona(alice.id)
        assertEquals(default, deleted.getCurrentUserPersona())
        assertEquals(default, deleted.getUserPersonaOf(bound))
        assertEquals(DEFAULT_USER_PERSONA_ID, bound.bindConfig(deleted).config!!.userPersonaId)
        assertSame(settings, settings.withoutUserPersona(DEFAULT_USER_PERSONA_ID))
    }

    @Test
    fun `assistant edits and forks preserve identity`() {
        val bound = conversation.copy(config = ConversationConfig(userPersonaId = bob.id))
            .updateCurrentMessages(listOf(UIMessage.user("hello")))
        val changed = bound.withAssistantUpdate(settings.getAssistantOf(bound).copy(enableWebSearch = true), settings)
        assertEquals(bob.id, changed.config!!.userPersonaId)
        val fork = changed.fork(changed.currentMessages.single().id, emptySet()) { it }!!
        assertEquals(bob.id, fork.config!!.userPersonaId)
    }

    @Test
    fun `profiles and conversation bindings survive serialization`() {
        val restored = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(settings))
        assertEquals(settings.userPersonas, restored.userPersonas)
        assertEquals(alice, restored.getCurrentUserPersona())
        val bound = conversation.copy(config = ConversationConfig(userPersonaId = bob.id))
        assertEquals(bound, JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(bound)))
        assertEquals("user", alice.copy(nickname = "  ").placeholderName)
    }
}
