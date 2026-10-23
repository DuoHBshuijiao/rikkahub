package me.rerere.ai.interaction

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
enum class QuestionSelection {
    @SerialName("single")
    SINGLE,

    @SerialName("multi")
    MULTI,
}

@Serializable
data class UserQuestion(
    val id: String,
    val question: String,
    val selection: QuestionSelection,
    val options: List<String>,
)

@Serializable
data class AnswerOption(
    val id: String,
    val text: String,
    val originalText: String? = null,
) {
    val isCustom: Boolean get() = originalText == null
}

@Serializable
data class QuestionDraft(
    val questionId: String,
    val options: List<AnswerOption>,
    val selectedIds: Set<String> = emptySet(),
    val nextCustomIndex: Int = 1,
) {
    fun select(question: UserQuestion, optionId: String): QuestionDraft {
        require(options.any { it.id == optionId })
        return copy(
            selectedIds = when (question.selection) {
                QuestionSelection.SINGLE -> setOf(optionId)
                QuestionSelection.MULTI -> if (optionId in
                    selectedIds) {
                    selectedIds - optionId
                } else {
                    selectedIds + optionId
                }
            }
        )
    }

    fun edit(question: UserQuestion, optionId: String, text: String): QuestionDraft {
        require(options.any { it.id == optionId })
        val edited = copy(options = options.map { if (it.id == optionId) it.copy(text = text) else it })
        if (text.isBlank()) return edited
        return edited.copy(
            selectedIds = if (question.selection ==
                QuestionSelection.SINGLE) {
                setOf(optionId)
            } else {
                selectedIds + optionId
            }
        )
    }

    fun addOption(): QuestionDraft = copy(
        options = options + AnswerOption("$questionId:c$nextCustomIndex", ""),
        nextCustomIndex = nextCustomIndex + 1,
    )

    fun removeOption(optionId: String): QuestionDraft {
        require(options.any { it.id == optionId && it.isCustom })
        val removed = copy(options = options.filterNot { it.id == optionId }, selectedIds = selectedIds - optionId)
        return if (removed.options.none { it.isCustom }) removed.addOption() else removed
    }

    fun restoreOption(optionId: String): QuestionDraft = copy(
        options = options.map { if (it.id == optionId && !it.isCustom) it.copy(text = it.originalText!!) else it }
    )

    fun isComplete(question: UserQuestion): Boolean {
        val selected = options.filter { it.id in selectedIds }
        return questionId == question.id && selected.size == selectedIds.size &&
            selected.isNotEmpty() && selected.all { it.text.isNotBlank() } &&
            (question.selection == QuestionSelection.MULTI || selected.size == 1)
    }
}

@Serializable
data class AskUserDraft(val questions: List<QuestionDraft>) {
    fun update(questionId: String, transform: (QuestionDraft) -> QuestionDraft): AskUserDraft =
        copy(questions = questions.map { if (it.questionId == questionId) transform(it) else it })

    fun isComplete(specs: List<UserQuestion>): Boolean = specs.isNotEmpty() &&
        questions.map { it.questionId } == specs.map { it.id } &&
        specs.zip(questions).all { (spec, draft) -> draft.isComplete(spec) }
}

class AskUserValidationException(message: String) : IllegalArgumentException(message)

/** The same contract is used before approval, when displaying a question, and on answer submission. */
object AskUserProtocol {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun parse(input: String): List<UserQuestion> {
        val root = parseObject(input, "$")
        val rawQuestions = root["questions"] as? JsonArray ?: invalid("$.questions", "expected an array")
        if (rawQuestions.isEmpty()) invalid("$.questions", "at least one question is required")
        val questions = rawQuestions.mapIndexed { index, element ->
            val path = "$.questions[$index]"
            val obj = element as? JsonObject ?: invalid(path, "expected an object")
            val id = optionalString(obj["id"], "$path.id")?.takeIf { it.isNotBlank() } ?: "q_${index + 1}"
            val question = string(obj["question"], "$path.question")
            if (question.isBlank()) invalid("$path.question", "question must not be blank")
            val selection = when (optionalString(obj["selection_type"], "$path.selection_type")) {
                null, "text", "single" -> QuestionSelection.SINGLE
                "multi" -> QuestionSelection.MULTI
                else -> invalid("$path.selection_type", "expected single or multi")
            }
            val options = when (val raw = obj["options"]) {
                null, JsonNull -> emptyList()
                is JsonArray -> raw.mapIndexed { optionIndex, option ->
                    string(option, "$path.options[$optionIndex]").also {
                        if (it.isBlank()) invalid("$path.options[$optionIndex]", "option must not be blank")
                    }
                }
                else -> invalid("$path.options", "expected an array of strings")
            }
            UserQuestion(id, question, selection, options)
        }
        if (questions.map { it.id }.distinct().size !=
            questions.size) {
            invalid("$.questions", "question IDs must be unique")
        }
        return questions
    }

    fun inputError(input: String): String? = try {
        parse(input)
        null
    } catch (e: AskUserValidationException) {
        e.message
    }

    fun newDraft(questions: List<UserQuestion>): AskUserDraft = AskUserDraft(
        questions.map { question ->
            QuestionDraft(
                questionId = question.id,
                options =
                question.options.mapIndexed { index, text -> AnswerOption("${question.id}:s$index", text, text) } +
                    AnswerOption("${question.id}:c0", ""),
            )
        }
    )

    fun encodeAnswer(questions: List<UserQuestion>, draft: AskUserDraft): String {
        require(draft.isComplete(questions)) { "Answer every question and fill the selected options" }
        val result = buildJsonObject {
            put("version", 2)
            put("answers", buildJsonObject {
                draft.questions.forEach { question ->
                    put(question.questionId, JsonArray(question.options.filter {
                        it.id in question.selectedIds
                    }.map { option ->
                        buildJsonObject {
                            put("option_id", option.id)
                            put("source", if (option.isCustom) "custom" else "suggested")
                            put("text", option.text)
                            option.originalText?.let { put("original_text", it) }
                        }
                    }))
                }
            })
        }.toString()
        validateAnswer(questions, result)
        return result
    }

    fun validateAnswer(questions: List<UserQuestion>, input: String) {
        if (questions.isEmpty()) invalid("$.answers", "no questions to answer")
        val root = parseObject(input, "$")
        val answers = root["answers"] as? JsonObject ?: invalid("$.answers", "expected an object")
        if (answers.keys != questions.map { it.id }.toSet()) invalid("$.answers", "answer every question exactly once")
        val version = root["version"]
        if (version != null && version != JsonPrimitive(2)) invalid("$.version", "unsupported answer version")
        questions.forEach { question ->
            val path = "$.answers.${question.id}"
            val value = answers.getValue(question.id)
            // Older clients return one string, including flattened multi-select answers. Never split it on commas.
            if (version == null) {
                if (string(value, path).isBlank()) invalid(path, "answer must not be blank")
                return@forEach
            }
            val selected = value as? JsonArray ?: invalid(path, "expected an array")
            if (selected.isEmpty() || (question.selection == QuestionSelection.SINGLE && selected.size != 1)) {
                invalid(path, "single requires one answer; multi requires at least one")
            }
            val ids = mutableSetOf<String>()
            selected.forEachIndexed { index, element ->
                val optionPath = "$path[$index]"
                val option = element as? JsonObject ?: invalid(optionPath, "expected an answer option")
                val id = string(option["option_id"], "$optionPath.option_id")
                if (!ids.add(id)) invalid(optionPath, "duplicate option ID")
                if (string(
                        option["text"],
                        "$optionPath.text"
                    ).isBlank()) {
                    invalid(optionPath, "answer must not be blank")
                }
                when (string(option["source"], "$optionPath.source")) {
                    "suggested" -> {
                        val suggestedIndex = id.removePrefix("${question.id}:s").toIntOrNull()
                        if (suggestedIndex == null || id != "${question.id}:s$suggestedIndex" ||
                            question.options.getOrNull(suggestedIndex) !=
                            string(option["original_text"], "$optionPath.original_text")
                        ) {
                            invalid(optionPath, "suggested option does not exist in this question")
                        }
                    }
                    "custom" -> {
                        val customIndex = id.removePrefix("${question.id}:c").toIntOrNull()
                        if (customIndex == null || customIndex < 0 || id != "${question.id}:c$customIndex" ||
                            option["original_text"] != null
                        ) {
                            invalid(optionPath, "invalid custom option")
                        }
                    }
                    else -> invalid(optionPath, "expected suggested or custom source")
                }
            }
        }
    }

    /** Read-only compatibility for persisted v1 strings and v2 arrays. */
    fun answerTexts(input: String): Map<String, List<String>> {
        val answers = parseObject(input, "$")["answers"] as? JsonObject ?: invalid("$.answers", "expected an object")
        return answers.mapValues { (id, value) ->
            when (value) {
                is JsonArray -> value.mapIndexed { index, item ->
                    val option = item as? JsonObject ?: invalid("$.answers.$id[$index]", "expected an object")
                    string(option["text"], "$.answers.$id[$index].text")
                }
                else -> listOf(string(value, "$.answers.$id"))
            }
        }
    }

    private fun parseObject(input: String, path: String): JsonObject {
        val element = try {
            json.parseToJsonElement(input)
        } catch (_: IllegalArgumentException) {
            invalid(path, "invalid JSON")
        }
        return element as? JsonObject ?: invalid(path, "expected an object")
    }

    private fun optionalString(value: JsonElement?, path: String): String? =
        if (value == null || value == JsonNull) null else string(value, path)

    private fun string(value: JsonElement?, path: String): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid(path, "expected a string")

    private fun invalid(path: String, reason: String): Nothing = throw AskUserValidationException("$path: $reason")
}
