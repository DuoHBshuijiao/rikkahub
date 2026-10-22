package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.ConversationCleanupOptions
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Composable
internal fun CleanupConversationDialog(
    blockedReason: String?,
    onConfirm: suspend (ConversationCleanupOptions) -> Boolean,
    onComplete: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var reasoning by rememberSaveable { mutableStateOf(false) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("清理会话内容") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("将永久删除当前会话所有消息版本中的所选内容，包含未选中的重新生成版本。操作不可撤销。")
                CardGroup {
                    item(
                        modifier = Modifier.toggleable(
                            value = reasoning,
                            enabled = !busy,
                            role = Role.Checkbox,
                            onValueChange = { reasoning = it },
                        ),
                        trailingContent = { Checkbox(reasoning, null, enabled = !busy) },
                    ) { Text("删除全部思考内容") }
                    item(
                        modifier = Modifier.toggleable(
                            value = tools,
                            enabled = !busy,
                            role = Role.Checkbox,
                            onValueChange = { tools = it },
                        ),
                        trailingContent = { Checkbox(tools, null, enabled = !busy) },
                        supportingContent = { Text("包含工具名称、参数和结果；不会撤销已执行的文件操作或其他外部操作。") },
                    ) { Text("删除全部工具调用及结果") }
                }
                (blockedReason ?: error)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (reasoning || tools) && blockedReason == null,
                onClick = {
                    if (busy) return@TextButton
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val changed = onConfirm(ConversationCleanupOptions(reasoning, tools))
                            onComplete(changed)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.localizedMessage ?: "清理失败，请重试"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "正在删除…" else "删除") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        },
    )
}
