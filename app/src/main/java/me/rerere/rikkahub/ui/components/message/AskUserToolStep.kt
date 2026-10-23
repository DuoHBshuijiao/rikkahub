package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.ai.interaction.AnswerOption
import me.rerere.ai.interaction.AskUserDraft
import me.rerere.ai.interaction.AskUserProtocol
import me.rerere.ai.interaction.QuestionDraft
import me.rerere.ai.interaction.QuestionSelection
import me.rerere.ai.interaction.UserQuestion
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.ui.components.DotLoading

@Composable
internal fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((String, String) -> Unit)?,
    onToolApproval: ((String, Boolean, String) -> Unit)?,
) {
    val parsed = remember(tool.input) { runCatching { AskUserProtocol.parse(tool.input) } }
    val questions = parsed.getOrNull()
    val preparing = loading && !tool.isExecuted && tool.approvalState == ToolApprovalState.Auto
    var expanded by rememberSaveable(tool.toolCallId) { mutableStateOf(true) }
    val store = LocalAskUserDraftStore.current
    LaunchedEffect(tool.toolCallId, tool.approvalState, tool.isExecuted) {
        if (tool.isExecuted || tool.approvalState is ToolApprovalState.Answered || tool.approvalState is ToolApprovalState.Denied) {
            store?.remove(tool.toolCallId)
        }
    }
    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (preparing) DotLoading(size = 10.dp) else Icon(
                HugeIcons.BubbleChatQuestion, contentDescription = null, modifier = Modifier.size(16.dp)
            )
        },
        label = {
            Text(
                when {
                    preparing -> "正在准备问题…"
                    questions == null -> "提问参数有误"
                    questions.size == 1 -> questions.single().question
                    else -> "请回答 ${questions.size} 个问题"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                when {
                    preparing -> Text("问题准备完成后即可回答")
                    tool.approvalState is ToolApprovalState.Answered -> {
                        val raw = (tool.approvalState as ToolApprovalState.Answered).answer
                        val answers = remember(raw) { runCatching { AskUserProtocol.answerTexts(raw) }.getOrNull() }
                        if (answers == null || questions == null) {
                            Text(raw, color = MaterialTheme.colorScheme.primary)
                        } else {
                            questions.forEach { question ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(question.question, style = MaterialTheme.typography.titleSmall)
                                    answers[question.id]?.takeIf { values -> values.any { it.isNotBlank() } }
                                        ?.forEach { Text(it, color = MaterialTheme.colorScheme.primary) }
                                        ?: Text("这道题没有保存的答案", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    tool.approvalState is ToolApprovalState.Denied ->
                        Text((tool.approvalState as ToolApprovalState.Denied).reason.ifBlank { "已取消提问" })
                    questions == null -> {
                        Text("无法显示这次提问，请让模型修正参数后重试。", color = MaterialTheme.colorScheme.error)
                        Text(parsed.exceptionOrNull()?.message.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        if (tool.isPending && onToolApproval != null) {
                            TextButton(onClick = { onToolApproval(tool.toolCallId, false, "提问参数无效，请修正后重新提问") }) {
                                Text("取消这次提问")
                            }
                        }
                    }
                    tool.isPending && onToolAnswer != null -> AskUserForm(tool, questions, onToolAnswer)
                    else -> Text(if (tool.isExecuted) "这次提问已结束" else "等待问题准备完成")
                }
            }
        },
    )
}

@Composable
private fun AskUserForm(tool: UIMessagePart.Tool, questions: List<UserQuestion>, onAnswer: (String, String) -> Unit) {
    val store = LocalAskUserDraftStore.current
    var draft by remember(tool.toolCallId, tool.input, store) {
        mutableStateOf(store?.load(tool.toolCallId, tool.input, questions) ?: AskUserProtocol.newDraft(questions))
    }
    var submitting by remember { mutableStateOf(false) }
    // ChatService accepts answers asynchronously. A failed operation can be retried without losing the draft.
    LaunchedEffect(submitting) {
        if (submitting) { delay(1_000); submitting = false }
    }
    AskUserQuestionnaire(
        questions = questions,
        draft = draft,
        onDraftChange = { draft = it; store?.save(tool.toolCallId, tool.input, it) },
        enabled = !submitting,
    )
    FilledTonalButton(
        onClick = {
            if (!submitting && draft.isComplete(questions)) {
                submitting = true
                onAnswer(tool.toolCallId, AskUserProtocol.encodeAnswer(questions, draft))
            }
        },
        enabled = !submitting && draft.isComplete(questions),
        modifier = Modifier.testTag("ask-user-submit"),
    ) { Text(if (submitting) "正在提交…" else "提交答案") }
}

@Composable
internal fun AskUserQuestionnaire(
    questions: List<UserQuestion>,
    draft: AskUserDraft,
    onDraftChange: (AskUserDraft) -> Unit,
    enabled: Boolean = true,
) {
    questions.zip(draft.questions).forEach { (question, answer) ->
        key(question.id) {
            AskUserQuestionEditor(question, answer, enabled) { updated ->
                onDraftChange(draft.update(question.id) { updated })
            }
        }
    }
}

@Composable
private fun AskUserQuestionEditor(
    question: UserQuestion,
    draft: QuestionDraft,
    enabled: Boolean,
    onChange: (QuestionDraft) -> Unit,
) {
    var editingId by remember(question.id) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(question.question, style = MaterialTheme.typography.titleSmallEmphasized)
        Text(
            if (question.selection == QuestionSelection.SINGLE) "单选 · 点击选择，双击文字编辑" else "多选 · 点击选择，双击文字编辑",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CardGroup {
            draft.options.forEach { option ->
                formItem {
                    key(option.id) {
                        AskUserOptionRow(
                            option = option,
                            multi = question.selection == QuestionSelection.MULTI,
                            selected = option.id in draft.selectedIds,
                            editing = option.id == editingId,
                            enabled = enabled,
                            onSelect = { onChange(draft.select(question, option.id)) },
                            onEdit = { editingId = option.id },
                            onTextChange = { onChange(draft.edit(question, option.id, it)) },
                            onDone = { editingId = null },
                            onRemove = { onChange(draft.removeOption(option.id)); editingId = null },
                            onRestore = { onChange(draft.restoreOption(option.id)) },
                        )
                    }
                }
            }
        }
        TextButton(
            enabled = enabled,
            onClick = {
                val updated = draft.addOption()
                onChange(updated)
                editingId = updated.options.last().id
            },
            modifier = Modifier.testTag("ask-add-${question.id}"),
        ) { Text("添加选项") }
    }
}

@Composable
private fun AskUserOptionRow(
    option: AnswerOption,
    multi: Boolean,
    selected: Boolean,
    editing: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onTextChange: (String) -> Unit,
    onDone: () -> Unit,
    onRemove: () -> Unit,
    onRestore: () -> Unit,
) {
    val focus = remember(option.id) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(editing) { if (editing) focus.requestFocus() }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        val controlModifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .testTag("ask-select-${option.id}")
            .semantics { contentDescription = "选择 ${option.text.ifBlank { "自定义答案" }}" }
        if (multi) Checkbox(selected, { onSelect() }, modifier = controlModifier, enabled = enabled)
        else RadioButton(selected, onSelect, modifier = controlModifier, enabled = enabled)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (editing) {
                OutlinedTextField(
                    value = option.text,
                    onValueChange = onTextChange,
                    enabled = enabled,
                    label = { Text(if (option.isCustom) "自定义答案" else "修改选项") },
                    isError = selected && option.text.isBlank(),
                    minLines = 1,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("ask-input-${option.id}"),
                )
                Row {
                    TextButton(enabled = enabled, onClick = { focusManager.clearFocus(); onDone() }) { Text("完成") }
                    if (option.isCustom) TextButton(enabled = enabled, onClick = onRemove) { Text("移除") }
                    else if (option.text != option.originalText) TextButton(enabled = enabled, onClick = onRestore) { Text("还原") }
                }
            } else {
                Text(
                    text = option.text.ifBlank { "填写自定义答案" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (option.text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                        .testTag("ask-text-${option.id}")
                        .combinedClickable(
                            enabled = enabled,
                            role = Role.Button,
                            onClick = if (option.text.isBlank()) onEdit else onSelect,
                            onDoubleClick = onEdit,
                            onClickLabel = if (option.text.isBlank()) "填写答案" else "选择选项",
                        )
                        .onPreviewKeyEvent {
                            if (enabled && it.key == Key.F2 && it.type == KeyEventType.KeyUp) { onEdit(); true } else false
                        }
                        .semantics { customActions = listOf(CustomAccessibilityAction("编辑选项") { onEdit(); true }) }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                )
            }
            if (selected && option.text.isBlank()) {
                Text("请填写所选项的内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
