package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV6Migration
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.DEFAULT_USER_PERSONA_ID
import me.rerere.rikkahub.data.model.UserPersona
import me.rerere.rikkahub.data.model.getCurrentUserPersona
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreferenceStoreV6MigrationTest {
    private val display = DisplaySetting(userNickname = "Legacy", userAvatar = Avatar.Image("file:///old.png"))

    @Test
    fun `datastore migration preserves legacy profile and is repeatable`() = runBlocking {
        val migration = PreferenceStoreV6Migration()
        val preferences = mutablePreferencesOf(
            SettingsStore.VERSION to 5,
            SettingsStore.DISPLAY_SETTING to JsonInstant.encodeToString(display),
        )
        val migrated = migration.migrate(preferences)
        val persona = migrated.toSettings().getCurrentUserPersona()
        assertEquals(UserPersona(DEFAULT_USER_PERSONA_ID, display.userNickname, display.userAvatar), persona)
        assertFalse(migration.shouldMigrate(migrated))
        assertEquals(migrated, migration.migrate(migrated))
    }

    @Test
    fun `old backup migration preserves avatar nickname and selection`() {
        val backup = """{"displaySetting":${JsonInstant.encodeToString(display)}}"""
        val migrated = SettingsJsonMigrator.migrate(backup)
        val restored = JsonInstant.decodeFromString<Settings>(migrated)
        assertEquals(display.userAvatar, restored.getCurrentUserPersona().avatar)
        assertEquals(display.userNickname, restored.getCurrentUserPersona().nickname)
        assertEquals(migrated, SettingsJsonMigrator.migrate(migrated))
    }

    @Test
    fun `existing personas are not replaced by legacy fields during upgrade or restore`() = runBlocking {
        val persona = UserPersona(nickname = "Existing")
        val personas = listOf(UserPersona(id = DEFAULT_USER_PERSONA_ID), persona)
        val preferences = mutablePreferencesOf(
            SettingsStore.USER_PERSONAS to JsonInstant.encodeToString(personas),
            SettingsStore.SELECTED_USER_PERSONA to persona.id.toString(),
            SettingsStore.DISPLAY_SETTING to JsonInstant.encodeToString(display),
        )
        val migrated = PreferenceStoreV6Migration().migrate(preferences).toSettings()
        assertEquals(personas, migrated.userPersonas)
        assertEquals(persona.id, migrated.selectedUserPersonaId)
        val backup = Settings(userPersonas = personas, selectedUserPersonaId = persona.id, displaySetting = display)
        val restored = JsonInstant.decodeFromString<Settings>(SettingsJsonMigrator.migrate(JsonInstant.encodeToString(backup)))
        assertEquals(personas, restored.userPersonas)
        assertEquals(persona.id, restored.selectedUserPersonaId)
    }
}
