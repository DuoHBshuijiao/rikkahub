package me.rerere.ai.provider.providers.chatgpt

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.UsageWindow
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.supportsBuiltInSearch
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class ChatGPTProviderTest {
    private val api = ResponseAPI(OkHttpClient())

    private fun codexBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        sessionId: String? = "session-1",
    ) = api.buildRequestBody(CODEX_CAPABILITIES, messages, params.copy(sessionId = sessionId), stream = true)

    @Test
    fun `system prompt becomes leading developer message instead of instructions`() {
        val body = codexBody(
            messages = listOf(UIMessage.system("Be brief."), UIMessage.user("hello")),
            params = TextGenerationParams(model = Model(modelId = "gpt-5.5")),
        )

        assertFalse("instructions" in body)
        val input = body["input"]!!.jsonArray
        val developer = input[0].jsonObject
        assertEquals("developer", developer["role"]!!.jsonPrimitive.content)
        assertEquals("Be brief.", developer["content"]!!.jsonPrimitive.content)
        assertEquals("user", input[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no developer message without system prompt`() {
        val body = codexBody(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(model = Model(modelId = "gpt-5.5")),
        )

        val input = body["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("user", input[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streams without storage and drops unsupported parameters`() {
        val body = codexBody(
            messages = listOf(UIMessage.user("hello")),
            // 非推理模型名，ResponseAPI 会带上 temperature/top_p
            params = TextGenerationParams(
                model = Model(modelId = "custom-model"),
                temperature = 0.7f,
                topP = 0.9f,
                maxTokens = 1024,
            ),
        )

        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, body["store"]!!.jsonPrimitive.content.toBoolean())
        assertFalse("max_output_tokens" in body)
        assertFalse("temperature" in body)
        assertFalse("top_p" in body)
        // 路由字段保持在最前面
        assertEquals(listOf("model", "stream", "store"), body.keys.take(3))
    }

    @Test
    fun `always includes encrypted reasoning and prompt cache key`() {
        val body = codexBody(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(model = Model(modelId = "gpt-5.5")),
            sessionId = "conversation-42",
        )

        assertEquals(
            listOf("reasoning.encrypted_content"),
            body["include"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("conversation-42", body["prompt_cache_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `keeps include from reasoning models and omits missing cache key`() {
        val body = codexBody(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(modelId = "gpt-5.5", abilities = listOf(ModelAbility.REASONING)),
            ),
            sessionId = null,
        )

        assertEquals(1, body["include"]!!.jsonArray.size)
        assertTrue("reasoning" in body)
        assertNull(body["prompt_cache_key"])
    }

    @Test
    fun `switching from Gemini drops foreign reasoning and preserves conversation text`() {
        val gemini = Model(modelId = "gemini-3.1-pro")
        val gpt = Model(modelId = "gpt-6.1-sol")
        val reasoning = UIMessagePart.Reasoning(
            reasoning = "Gemini private reasoning",
            metadata = GoogleThoughtMetadata(thoughtSignature = "gemini-signature").toMetadata(),
        )
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            modelId = gemini.id,
            parts = listOf(reasoning, UIMessagePart.Text("The answer is 42.")),
        )
        val originalMessage = json.encodeToString(assistant)
        val body = codexBody(
            messages = listOf(UIMessage.user("Find the answer."), assistant, UIMessage.user("Explain it.")),
            params = TextGenerationParams(model = gpt),
        )

        val input = body["input"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user"), input.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("The answer is 42.", input[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(body.toString().contains("Gemini private reasoning"))
        assertFalse(body.toString().contains("gemini-signature"))
        assertEquals(originalMessage, json.encodeToString(assistant))
    }

    @Test
    fun `drops unencrypted reasoning including summaries and reasoning only messages`() {
        val model = Model(modelId = "gpt-6.1-sol")
        val parts = listOf(
            UIMessagePart.Reasoning("unsigned reasoning"),
            UIMessagePart.Reasoning(
                reasoning = "unsigned summary",
                reasoningType = ReasoningType.SUMMARY_TEXT,
            ),
            UIMessagePart.Reasoning(
                reasoning = "id without encrypted content",
                metadata = OpenAIReasoningMetadata(reasoningId = "rs_unsigned").toMetadata(),
            ),
            UIMessagePart.Reasoning(
                reasoning = "blank encrypted content",
                metadata = OpenAIReasoningMetadata(encryptedContent = " ").toMetadata(),
            ),
        )
        val body = codexBody(
            messages = listOf(
                UIMessage.user("hello"),
                UIMessage(role = MessageRole.ASSISTANT, modelId = model.id, parts = parts),
                UIMessage.user("continue"),
            ),
            params = TextGenerationParams(model = model),
        )

        val input = body["input"]!!.jsonArray
        assertEquals(2, input.size)
        assertTrue(input.all { it.jsonObject["role"]!!.jsonPrimitive.content == "user" })
        assertFalse(body.toString().contains("rs_unsigned"))
    }

    @Test
    fun `preserves current model encrypted reasoning and legacy messages without model id`() {
        val model = Model(modelId = "gpt-6.1-sol")
        for (modelId in listOf(model.id, null)) {
            val body = codexBody(
                messages = listOf(UIMessage(
                    role = MessageRole.ASSISTANT,
                    modelId = modelId,
                    parts = listOf(
                        UIMessagePart.Reasoning(
                            reasoning = "native summary",
                            metadata = OpenAIReasoningMetadata(
                                reasoningId = "rs_native",
                                encryptedContent = "native-encrypted-content",
                            ).toMetadata(),
                            reasoningType = ReasoningType.SUMMARY_TEXT,
                        ),
                        UIMessagePart.Text("Answer"),
                    ),
                )),
                params = TextGenerationParams(model = model),
            )

            val input = body["input"]!!.jsonArray
            val reasoning = input[0].jsonObject
            assertEquals("reasoning", reasoning["type"]!!.jsonPrimitive.content)
            assertEquals("rs_native", reasoning["id"]!!.jsonPrimitive.content)
            assertEquals("native-encrypted-content", reasoning["encrypted_content"]!!.jsonPrimitive.content)
            assertEquals(
                "native summary",
                reasoning["summary"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
            )
            assertFalse("content" in reasoning)
            assertEquals("assistant", input[1].jsonObject["role"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `preserves metadata only encrypted reasoning without creating plaintext content`() {
        val model = Model(modelId = "gpt-6.1-sol")
        val body = codexBody(
            messages = listOf(UIMessage(
                role = MessageRole.ASSISTANT,
                modelId = model.id,
                parts = listOf(UIMessagePart.Reasoning(
                    reasoning = "",
                    metadata = OpenAIReasoningMetadata(
                        reasoningId = "rs_metadata",
                        encryptedContent = "encrypted-state",
                    ).toMetadata(),
                )),
            )),
            params = TextGenerationParams(model = model),
        )

        val reasoning = body["input"]!!.jsonArray.single().jsonObject
        assertEquals("rs_metadata", reasoning["id"]!!.jsonPrimitive.content)
        assertEquals("encrypted-state", reasoning["encrypted_content"]!!.jsonPrimitive.content)
        assertTrue(reasoning["summary"]!!.jsonArray.isEmpty())
        assertFalse("content" in reasoning)
    }

    @Test
    fun `drops encrypted reasoning from another model but preserves tool calls and outputs`() {
        val previousModel = Model(modelId = "gpt-5.5")
        val body = codexBody(
            messages = listOf(UIMessage(
                role = MessageRole.ASSISTANT,
                modelId = previousModel.id,
                parts = listOf(
                    UIMessagePart.Text("Looking it up."),
                    UIMessagePart.Reasoning(
                        reasoning = "foreign reasoning",
                        metadata = OpenAIReasoningMetadata(
                            reasoningId = "rs_foreign",
                            encryptedContent = "foreign-encrypted-content",
                        ).toMetadata(),
                    ),
                    UIMessagePart.Tool(
                        toolCallId = "call_1",
                        toolName = "lookup",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("42")),
                    ),
                    UIMessagePart.Text("The answer is 42."),
                ),
            )),
            params = TextGenerationParams(model = Model(modelId = "gpt-6.1-sol")),
        )

        val input = body["input"]!!.jsonArray
        assertEquals(4, input.size)
        assertEquals("assistant", input[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("function_call", input[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", input[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", input[1].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("call_1", input[2].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("42", input[2].jsonObject["output"]!!.jsonPrimitive.content)
        assertEquals("assistant", input[3].jsonObject["role"]!!.jsonPrimitive.content)
        assertFalse(body.toString().contains("rs_foreign"))
        assertFalse(body.toString().contains("foreign-encrypted-content"))
    }

    @Test
    fun `parses visible codex models`() {
        val models = parseCodexModels(
            """
            {
              "models": [
                {
                  "slug": "gpt-5.5",
                  "display_name": "GPT-5.5",
                  "visibility": "list",
                  "supported_reasoning_levels": [{"effort": "low", "description": "fast"}],
                  "input_modalities": ["text", "image"],
                  "context_window": 272000
                },
                {
                  "slug": "gpt-5.5-mini",
                  "display_name": "GPT-5.5 mini",
                  "visibility": "list",
                  "supported_reasoning_levels": [],
                  "input_modalities": ["text"]
                },
                {
                  "slug": "internal-model",
                  "display_name": "Internal",
                  "visibility": "hide",
                  "supported_reasoning_levels": []
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("gpt-5.5", "gpt-5.5-mini"), models.map { it.modelId })
        val full = models[0]
        assertEquals("GPT-5.5", full.displayName)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), full.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), full.abilities)
        assertEquals(272000, full.contextLength)
        val mini = models[1]
        assertEquals(listOf(Modality.TEXT), mini.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL), mini.abilities)
    }

    @Test
    fun `falls back to all models when none are listed`() {
        val models = parseCodexModels(
            """{"models": [{"slug": "a", "display_name": "A", "visibility": "hide"}]}"""
        )

        assertEquals(listOf("a"), models.map { it.modelId })
        // 没给 input_modalities 时按 Codex 的默认值：文本 + 图片
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), models.single().inputModalities)
    }

    @Test
    fun `chatgpt provider round trips through provider list serialization`() {
        val provider = ProviderSetting.ChatGPT(
            credential = OAuthCredential(accessToken = "access", refreshToken = "refresh", accountId = "acc"),
        )

        val encoded = json.encodeToString<List<ProviderSetting>>(listOf(provider))
        assertTrue(encoded.contains("\"type\":\"chatgpt\""))
        val decoded = json.decodeFromString<List<ProviderSetting>>(encoded).single()

        assertTrue(decoded is ProviderSetting.ChatGPT)
        assertEquals(provider.credential, (decoded as ProviderSetting.ChatGPT).credential)
    }

    @Test
    fun `credential is masked in toString`() {
        val credential = OAuthCredential(accessToken = "access-token-secret", refreshToken = "refresh-token-secret")

        assertFalse(credential.toString().contains("access-token-secret"))
        assertFalse(credential.toString().contains("refresh-token-secret"))
    }

    @Test
    fun `built in search support by provider type`() {
        assertTrue(ProviderSetting.ChatGPT().supportsBuiltInSearch)
        assertTrue(ProviderSetting.Google().supportsBuiltInSearch)
        assertTrue(ProviderSetting.Claude().supportsBuiltInSearch)
        assertTrue(ProviderSetting.OpenAI(useResponseApi = true).supportsBuiltInSearch)
        assertFalse(ProviderSetting.OpenAI(useResponseApi = false).supportsBuiltInSearch)
    }

    @Test
    fun `usage response maps both rate limit windows`() {
        val usage = parseCodexUsage(
            """{"plan_type":"plus","rate_limit":{"allowed":true,"limit_reached":false,
                "primary_window":{"used_percent":42,"limit_window_seconds":18000,"reset_after_seconds":600,"reset_at":1760000000},
                "secondary_window":{"used_percent":7,"limit_window_seconds":604800,"reset_after_seconds":9000,"reset_at":1760500000}},
                "credits":{"has_credits":false,"unlimited":false,"balance":null}}"""
        )

        assertEquals(
            ProviderQuota.UsageLimits(
                windows = listOf(
                    UsageWindow(
                        usedPercent = 42.0,
                        windowMinutes = 300,
                        resetsAt = Instant.fromEpochSeconds(1760000000)
                    ),
                    UsageWindow(
                        usedPercent = 7.0,
                        windowMinutes = 10080,
                        resetsAt = Instant.fromEpochSeconds(1760500000)
                    ),
                ),
                limitReached = false,
            ),
            usage,
        )
    }

    @Test
    fun `usage response without rate limit has no windows`() {
        val usage = parseCodexUsage("""{"plan_type":"free","rate_limit":null}""")

        assertTrue(usage.windows.isEmpty())
        assertFalse(usage.limitReached)
    }

    @Test
    fun `usage response reports reached limit and skips unknown window fields`() {
        val usage = parseCodexUsage(
            """{"rate_limit":{"allowed":false,"limit_reached":true,
                "primary_window":{"used_percent":100,"limit_window_seconds":0,"reset_at":0},"secondary_window":null}}"""
        )

        assertTrue(usage.limitReached)
        assertEquals(listOf(UsageWindow(usedPercent = 100.0, windowMinutes = null, resetsAt = null)), usage.windows)
    }
}
