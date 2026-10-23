package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.interaction.AskUserProtocol

const val ASK_USER_TOOL_NAME = "ask_user"

internal fun buildAskUserTool(): Tool = Tool(
    name = ASK_USER_TOOL_NAME,
    description = """
        Ask the user one or more questions when you need clarification, additional information, or confirmation.
        Use single to request one answer or multi to allow several answers. Supply suggested options as strings;
        use an empty options array when no suggestions are useful. Every option is editable and the user can
        always add custom answers, so do not add an Other option or any custom-input configuration.
        Answers contain version 2 and an answers object mapping question IDs to arrays of selected options.
        Each selected option's text is the user's final answer, which may differ from original_text.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put("description", "List of questions to ask the user")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("additionalProperties", false)
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Unique identifier for this question")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "The question text to display to the user")
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Suggested answers. Every option can be edited; custom input is always available. Use [] for free input."
                                )
                                put("items", buildJsonObject {
                                    put("type", "string")
                                })
                            })
                            put("selection_type", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add("single")
                                        add("multi")
                                    }
                                )
                                put(
                                    "description",
                                    "single: one answer; multi: one or more answers. All options are editable and custom answers are always supported."
                                )
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("question")
                            add("selection_type")
                            add("options")
                        })
                    })
                })
            },
            required = listOf("questions")
        )
    },
    needsApproval = { true },
    validateInput = AskUserProtocol::inputError,
    execute = {
        error("ask_user tool should be handled by HITL flow")
    }
)
