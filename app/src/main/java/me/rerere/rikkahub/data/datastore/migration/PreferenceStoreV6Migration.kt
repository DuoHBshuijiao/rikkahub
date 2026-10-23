package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.DEFAULT_USER_PERSONA_ID
import me.rerere.rikkahub.utils.JsonInstant

class PreferenceStoreV6Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[SettingsStore.VERSION] ?: 0) < 6

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val root = JsonObject(buildMap {
            prefs[SettingsStore.DISPLAY_SETTING]?.let { put("displaySetting", JsonInstant.parseToJsonElement(it)) }
            prefs[SettingsStore.USER_PERSONAS]?.let { put("userPersonas", JsonInstant.parseToJsonElement(it)) }
            prefs[SettingsStore.SELECTED_USER_PERSONA]?.let { put("selectedUserPersonaId", JsonPrimitive(it)) }
        })
        val migrated = migrateUserPersonas(root)
        prefs[SettingsStore.USER_PERSONAS] = migrated.getValue("userPersonas").toString()
        prefs[SettingsStore.SELECTED_USER_PERSONA] =
            (migrated.getValue("selectedUserPersonaId") as JsonPrimitive).content
        prefs[SettingsStore.VERSION] = 6
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/** Shared by DataStore upgrades and settings.json restore; existing identities are never overwritten. */
internal fun migrateUserPersonas(root: JsonObject): JsonObject {
    val fields = root.toMutableMap()
    if ("userPersonas" !in fields) {
        val display = root["displaySetting"] as? JsonObject
        val persona = JsonObject(buildMap {
            put("id", JsonPrimitive(DEFAULT_USER_PERSONA_ID.toString()))
            display?.get("userNickname")?.let { put("nickname", it) }
            display?.get("userAvatar")?.let { put("avatar", it) }
        })
        fields["userPersonas"] = JsonArray(listOf(persona))
    }
    fields.putIfAbsent("selectedUserPersonaId", JsonPrimitive(DEFAULT_USER_PERSONA_ID.toString()))
    return JsonObject(fields)
}
