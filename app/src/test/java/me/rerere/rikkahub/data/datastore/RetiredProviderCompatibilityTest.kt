package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

class RetiredProviderCompatibilityTest {
    private val retiredIds = listOf(
        "1b1395ed-b702-4aeb-8bc1-b681c4456953",
        "2a05506f-3a59-450a-a493-33a82bc85a81",
        "56a94d29-c88b-41c5-8e09-38a7612d6cf8",
        "da93779f-3956-48cc-82ef-67bb482eaaf7",
        "aecf04fd-cb5c-4582-aed2-e8bf393923fd",
        "afbc54ad-807e-4455-9594-7d7a546356ad",
    ).map(Uuid::parse)
    private val retiredTtsId = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c")

    @Test
    fun `fresh settings do not include retired presets`() {
        val settings = Settings().normalized()

        assertFalse(settings.providers.any { it.id in retiredIds })
        assertFalse(settings.ttsProviders.any { it.id == retiredTtsId })
        assertEquals(listOf(DEFAULT_SYSTEM_TTS_ID), settings.ttsProviders.map { it.id })
    }

    @Test
    fun `old backups preserve configured and untouched retired presets as custom providers`() {
        val providers = retiredIds.mapIndexed { index, id ->
            ProviderSetting.OpenAI(
                id = id,
                name = "Saved provider $index",
                baseUrl = "https://saved-$index.example.com/v1",
                apiKey = if (index == 0) "" else "saved-key-$index",
                models = if (index == 0) emptyList() else listOf(Model(modelId = "saved-model-$index")),
                enabled = index % 2 == 0,
                customHeaders = listOf(CustomHeader("X-Custom", "saved-value")),
                builtIn = true,
            )
        }
        val modelIds = providers.flatMap { it.models }.map { it.id }
        val tts = TTSProviderSetting.OpenAI(
            id = retiredTtsId,
            name = "Saved voice",
            apiKey = "saved-voice-key",
            baseUrl = "https://saved-voice.example.com/v1",
            model = "saved-tts-model",
            voice = "saved-voice",
        )
        val legacy = Settings(
            providers = providers,
            chatModelId = modelIds.first(),
            favoriteModels = modelIds,
            assistants = DEFAULT_ASSISTANTS.map { it.copy(chatModelId = modelIds.first()) },
            ttsProviders = listOf(tts),
            selectedTTSProviderId = tts.id,
        )
        val json = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(legacy)).jsonObject
        val backup = JsonObject(json + ("sponsorAlertDismissedAt" to JsonPrimitive(150)))
        val restored = JsonInstant.decodeFromString<Settings>(
            SettingsJsonMigrator.migrate(backup.toString())
        ).normalized()

        providers.forEach { expected ->
            val actual = restored.providers.single { it.id == expected.id }
            assertFalse(actual.builtIn)
            assertEquals(
                JsonInstant.encodeToString<ProviderSetting>(expected),
                JsonInstant.encodeToString(actual),
            )
        }
        assertEquals(legacy.chatModelId, restored.chatModelId)
        assertEquals(modelIds, restored.favoriteModels)
        assertEquals(modelIds.first(), restored.assistants.first().chatModelId)
        assertEquals(tts, restored.ttsProviders.single { it.id == retiredTtsId })
        assertEquals(tts.id, restored.selectedTTSProviderId)
    }
}
