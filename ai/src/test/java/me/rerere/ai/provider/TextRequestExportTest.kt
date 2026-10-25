package me.rerere.ai.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.grok.GrokProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRequestExportTest {
    private val client = OkHttpClient.Builder().addInterceptor { error("Export must never make HTTP requests") }.build()
    private val credentials = AccountCredentialProvider { _, _ -> error("Export must never refresh credentials") }
    private val params = TextGenerationParams(
        model = Model(modelId = "test-model", abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING)),
        temperature = 0.4f,
        maxTokens = 500,
        reasoningLevel = ReasoningLevel.LOW,
        sessionId = "export-session",
    )
    private val messages = listOf(
        UIMessage.system("system prompt"),
        UIMessage.user("question"),
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("call_1", "lookup", "{\"q\":\"test\"}", listOf(UIMessagePart.Text("result"))),
            UIMessagePart.Text("final answer"),
        )),
    )

    @Test
    fun `openai chat export contains entire ordered transcript and parameters`() {
        val body = OpenAIProvider(
            client
        ).buildTextRequestBody(ProviderSetting.OpenAI(apiKey = "private-key"), messages, params, true)
        assertEquals(JsonPrimitive(true), body["stream"])
        assertEquals(JsonPrimitive(500), body["max_tokens"])
        val wireMessages = body.getValue("messages").jsonArray
        assertEquals(
            listOf("system", "user", "assistant", "tool", "assistant"),
            wireMessages.map { it.jsonObject["role"].toString().trim('"') }
        )
        assertTrue(body.toString().contains("final answer"))
        assertFalse(body.toString().contains("private-key"))
    }

    @Test
    fun `responses and account exports use their actual capability profiles without auth`() {
        val bodies = listOf(
            OpenAIProvider(
                client
            ).buildTextRequestBody(ProviderSetting.OpenAI(useResponseApi = true), messages, params, false),
            ChatGPTProvider(
                client,
                credentials
            ).buildTextRequestBody(ProviderSetting.ChatGPT(), messages, params, false),
            GrokProvider(client, credentials).buildTextRequestBody(ProviderSetting.Grok(), messages, params, false),
        )
        for (body in bodies) {
            assertEquals(JsonPrimitive(false), body["store"])
            assertEquals(JsonPrimitive("export-session"), body["prompt_cache_key"])
            assertTrue(body.getValue("input").jsonArray.any {
                it.jsonObject["type"] ==
                    JsonPrimitive("function_call_output")
            })
            assertTrue(body.toString().contains("final answer"))
        }
        assertEquals(JsonPrimitive("system prompt"), bodies[0]["instructions"])
        assertEquals(JsonPrimitive("developer"), bodies[1].getValue("input").jsonArray.first().jsonObject["role"])
        assertEquals(JsonPrimitive(true), bodies[1]["stream"])
        assertFalse(bodies[1].containsKey("temperature"))
        assertEquals(JsonPrimitive("system"), bodies[2].getValue("input").jsonArray.first().jsonObject["role"])
    }

    @Test
    fun `google export respects interactions and vertex settings`() {
        val provider = GoogleProvider(client)
        val content = provider.buildTextRequestBody(ProviderSetting.Google(), messages, params, true)
        assertTrue(content["systemInstruction"] is JsonObject)
        assertTrue(content.containsKey("contents"))
        assertTrue(content.toString().contains("final answer"))
        val interactions = provider.buildTextRequestBody(
            ProviderSetting.Google(useInteractionsApi = true),
            messages,
            params,
            true
        )
        assertEquals(JsonPrimitive("system prompt"), interactions["system_instruction"])
        assertEquals(JsonPrimitive(true), interactions["stream"])
        assertTrue(interactions.containsKey("input"))
        val vertex = provider.buildTextRequestBody(
            ProviderSetting.Google(useInteractionsApi = true, vertexAI = true),
            messages,
            params,
            true
        )
        assertEquals(content, vertex)
    }

    @Test
    fun `claude export retains system tool results and final answer`() {
        val body = ClaudeProvider(
            client
        ).buildTextRequestBody(ProviderSetting.Claude(apiKey = "secret"), messages, params, true)
        assertEquals(JsonPrimitive(500), body["max_tokens"])
        assertTrue(body.containsKey("system"))
        assertTrue(body.toString().contains("tool_result"))
        assertTrue(body.toString().contains("final answer"))
        assertFalse(body.toString().contains("secret"))
    }
}
