package me.rerere.rikkahub.ui.pages.chat

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.encodeBase64
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.data.ai.ChatRequestFactory
import me.rerere.rikkahub.data.export.ChatExportFormat
import me.rerere.rikkahub.data.export.ExportOptions
import me.rerere.rikkahub.data.export.chatExportFileName
import me.rerere.rikkahub.data.export.exportChatMarkdown
import me.rerere.rikkahub.data.export.filterExportMessages
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun ChatExportSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    conversation: Conversation,
    selectedMessages: List<UIMessage>,
    allowImageExport: Boolean = true,
    onExportComplete: () -> Unit = onDismissRequest,
) {
    if (!visible) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val factory = koinInject<ChatRequestFactory>()
    val scope = rememberCoroutineScope()
    // Freeze once when opened, including an in-progress answer. Export never follows later deltas.
    val snapshot = remember(conversation.id) { conversation }
    val messages = remember(conversation.id) { selectedMessages.toList() }
    var format by rememberSaveable { mutableStateOf(ChatExportFormat.MARKDOWN) }
    var includeReasoning by rememberSaveable { mutableStateOf(true) }
    var includeTools by rememberSaveable { mutableStateOf(true) }
    var assistantOnly by rememberSaveable { mutableStateOf(false) }
    var expandReasoning by rememberSaveable { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var pendingFile by rememberSaveable { mutableStateOf<String?>(null) }
    var exportError by rememberSaveable { mutableStateOf<String?>(null) }
    val options = ExportOptions(includeReasoning, expandReasoning, includeTools, assistantOnly)
    val filtered = remember(messages, options) { filterExportMessages(messages, options) }
    val busy = preparing || pendingFile != null

    fun saveResult(uri: Uri?) {
        val path = pendingFile ?: return
        if (uri == null) {
            pendingFile = null
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("无法打开目标文件")
                    output.use { target -> File(path).inputStream().use { it.copyTo(target) } }
                }
                pendingFile = null
                toaster.show("聊天记录已保存", type = ToastType.Success)
                onExportComplete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                exportError = "保存失败：${e.message}"
                pendingFile = null
            }
        }
    }
    val markdownSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown"), ::saveResult)
    val jsonSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), ::saveResult)

    fun export(save: Boolean) {
        if (preparing || pendingFile != null) return
        preparing = true
        scope.launch {
            exportError = null
            try {
                val file = withContext(Dispatchers.IO) {
                    require(filtered.isNotEmpty()) { "筛选后没有可导出的消息" }
                    val text = when (format) {
                        ChatExportFormat.MARKDOWN -> exportChatMarkdown(snapshot.title, messages, options) { image ->
                            image.encodeBase64().getOrThrow().base64
                        }
                        ChatExportFormat.JSON -> JsonInstantPretty.encodeToString(
                            JsonElement.serializer(),
                            factory.buildExportRequestBody(settings, snapshot, messages, options),
                        )
                    }
                    val directory = context.appTempFolder.resolve("chat-exports/${Uuid.random()}")
                    check(directory.mkdirs()) { "无法创建导出目录" }
                    directory.resolve(chatExportFileName(snapshot.title, format)).also { it.writeText(text, Charsets.UTF_8) }
                }
                if (save) {
                    pendingFile = file.absolutePath
                    when (format) {
                        ChatExportFormat.MARKDOWN -> markdownSave.launch(file.name)
                        ChatExportFormat.JSON -> jsonSave.launch(file.name)
                    }
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = format.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享聊天记录"))
                    onExportComplete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingFile = null
                exportError = "导出失败：${e.message}"
            } finally {
                preparing = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismissRequest() },
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("导出聊天记录", style = MaterialTheme.typography.titleLargeEmphasized)
            Text(snapshot.title.ifBlank { "未命名会话" }, style = MaterialTheme.typography.bodyMedium)
            CardGroup {
                formItem(
                    headlineContent = { Text("文件格式") },
                ) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ChatExportFormat.entries.forEachIndexed { index, value ->
                            SegmentedButton(
                                selected = format == value,
                                onClick = { format = value; exportError = null },
                                enabled = !busy,
                                shape = SegmentedButtonDefaults.itemShape(index, ChatExportFormat.entries.size),
                            ) {
                                Text(if (value == ChatExportFormat.MARKDOWN) "Markdown" else "JSON")
                            }
                        }
                    }
                }
                switchItem(checked = includeTools, onCheckedChange = { includeTools = it; exportError = null }, enabled = !busy,
                    supportingContent = { Text("包含调用参数、返回结果和服务端工具记录") }, headlineContent = { Text("包含工具调用记录") })
                switchItem(checked = includeReasoning, onCheckedChange = { includeReasoning = it; exportError = null }, enabled = !busy,
                    headlineContent = { Text("包含思考内容") })
                switchItem(checked = assistantOnly, onCheckedChange = { assistantOnly = it; exportError = null }, enabled = !busy,
                    headlineContent = { Text("仅助手消息") })
            }
            Text(
                if (format == ChatExportFormat.JSON) "按当前配置重建请求格式。思考内容遵循接口的发送规则，与历史实际请求可能不同。"
                else "导出当前选中的回答版本，共 ${filtered.size} 条消息。以打开面板时的记录为准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (filtered.isEmpty()) Text("筛选后没有可导出的消息", color = MaterialTheme.colorScheme.error)
            exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) CircularProgressIndicator()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { export(false) }, enabled = !busy && filtered.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text("分享")
                }
                Button(onClick = { export(true) }, enabled = !busy && filtered.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text("保存文件")
                }
            }
            if (allowImageExport) {
                CardGroup {
                    switchItem(checked = expandReasoning, onCheckedChange = { expandReasoning = it }, enabled = !busy && includeReasoning,
                        headlineContent = { Text("图片中展开思考") })
                    item(
                        headlineContent = { Text("导出图片") },
                        onClick = if (busy || filtered.isEmpty()) null else ({
                            scope.launch {
                                preparing = true
                                exportError = null
                                try {
                                    exportChatImage(context, scope, density, snapshot, filtered, settings, options)
                                    onExportComplete()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    exportError = "导出图片失败：${e.message}"
                                } finally {
                                    preparing = false
                                }
                            }
                        }),
                    )
                }
            }
        }
    }
}
