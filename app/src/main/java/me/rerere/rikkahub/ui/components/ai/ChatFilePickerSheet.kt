package me.rerere.rikkahub.ui.components.ai

import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.utils.isAllowedFileType
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

private enum class ChatFilePickerPage { SOURCE, WORKSPACES, FILES }

private data class SelectedWorkspaceFile(val area: WorkspaceStorageArea, val entry: WorkspaceFileEntry)

@Composable
internal fun ChatFilePickerSheet(
    workspaceId: String?,
    onPickLocal: () -> Unit,
    onFilesAdded: (List<UIMessagePart.Document>) -> Unit,
    onDismiss: () -> Unit,
) {
    val repository: WorkspaceRepository = koinInject()
    val filesManager: FilesManager = koinInject()
    val workspaces by repository.listFlow().collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(ChatFilePickerPage.SOURCE) }
    var selectedWorkspaceId by remember { mutableStateOf(workspaceId) }
    val workspace = workspaces?.find { it.id == selectedWorkspaceId }
    var area by remember { mutableStateOf(WorkspaceStorageArea.FILES) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var layout by remember { mutableStateOf<WorkspaceLayout?>(null) }
    val fileListState = rememberLazyListState()
    var selected by remember { mutableStateOf<Map<String, SelectedWorkspaceFile>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var importing by remember { mutableStateOf(false) }

    fun goBack() {
        when {
            importing -> onDismiss()
            page == ChatFilePickerPage.FILES && path.isNotEmpty() ->
                path = path.substringBeforeLast('/', "")
            page == ChatFilePickerPage.FILES -> page = ChatFilePickerPage.WORKSPACES
            else -> page = ChatFilePickerPage.SOURCE
        }
    }

    LaunchedEffect(page, selectedWorkspaceId, area, path, retry) {
        if (page != ChatFilePickerPage.FILES) return@LaunchedEffect
        val id = selectedWorkspaceId ?: return@LaunchedEffect
        loading = true
        error = null
        entries = emptyList()
        try {
            layout = repository.layout(id) ?: error("工作区不存在，请重新选择")
            fileListState.scrollToItem(0)
            entries = repository.listFiles(id, area, path)
                .sortedWith(compareByDescending<WorkspaceFileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "无法读取文件列表：${e.localizedMessage ?: "请重试"}"
        } finally {
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        BackHandler(enabled = page != ChatFilePickerPage.SOURCE) { goBack() }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (page != ChatFilePickerPage.SOURCE) {
                    IconButton(onClick = { goBack() }, enabled = !importing) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                }
                Text(
                    text = when (page) {
                        ChatFilePickerPage.SOURCE -> "添加文件"
                        ChatFilePickerPage.WORKSPACES -> "选择工作区"
                        ChatFilePickerPage.FILES -> workspace?.name ?: "工作区文件"
                    },
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            when (page) {
                ChatFilePickerPage.SOURCE -> CardGroup {
                    item(
                        onClick = {
                            page = if (workspace != null) ChatFilePickerPage.FILES else ChatFilePickerPage.WORKSPACES
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Folder01) },
                        headlineContent = { Text("添加工作区内文件") },
                        supportingContent = { Text("选择已有文档，将完整内容作为附件加入上下文") },
                    )
                    item(
                        onClick = onPickLocal,
                        leadingContent = { CardGroupIcon(HugeIcons.Files02) },
                        headlineContent = { Text("从本地上传") },
                        supportingContent = { Text("使用系统文件选择器") },
                    )
                }

                ChatFilePickerPage.WORKSPACES -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(workspaces.orEmpty(), key = { it.id }) { item ->
                            Surface(
                                onClick = {
                                    if (item.id != selectedWorkspaceId) {
                                        selected = emptyMap()
                                        path = ""
                                        area = WorkspaceStorageArea.FILES
                                    }
                                    selectedWorkspaceId = item.id
                                    page = ChatFilePickerPage.FILES
                                },
                                shape = MaterialTheme.shapes.large,
                            ) {
                                ListItem(
                                    leadingContent = { Icon(HugeIcons.Folder01, contentDescription = null) },
                                ) { Text(item.name) }
                            }
                        }
                        if (workspaces == null) {
                            item { CircularProgressIndicator(modifier = Modifier.padding(16.dp)) }
                        } else if (workspaces.orEmpty().isEmpty()) {
                            item { Text("暂无工作区，请先在扩展中创建工作区", modifier = Modifier.padding(16.dp)) }
                        }
                    }
                }

                ChatFilePickerPage.FILES -> {
                    Text(
                        "文档将作为附件完整读取，仍受所用模型的上下文长度限制。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!importing) {
                        ConnectedChoiceButtons(
                            options = WorkspaceStorageArea.entries,
                            selected = area,
                            onSelect = {
                                area = it
                                path = ""
                            },
                        ) { option, isSelected ->
                            ConnectedChoiceLabel(
                                text = if (option == WorkspaceStorageArea.FILES) "工作目录" else "根目录",
                                selected = isSelected,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = layout?.absolutePath(area, path) ?: "/$path",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = { page = ChatFilePickerPage.WORKSPACES },
                            enabled = !importing,
                        ) { Text("切换工作区") }
                    }
                    LazyColumn(
                        state = fileListState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp).weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (loading) {
                            item { CircularProgressIndicator(modifier = Modifier.padding(16.dp)) }
                        }
                        items(entries, key = { it.path }) { entry ->
                            val key = "${area.name}:${entry.path}"
                            val checked = key in selected
                            val supported = entry.isDirectory || isAllowedFileType(entry.name, workspaceDocumentMime(entry.name))
                            val enabled = supported && !importing && !loading
                            val onClick = {
                                if (entry.isDirectory) {
                                    path = entry.path
                                } else {
                                    selected = if (checked) selected - key else selected + (key to SelectedWorkspaceFile(area, entry))
                                }
                            }
                            Surface(
                                modifier = if (entry.isDirectory) {
                                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                                } else {
                                    Modifier.toggleable(
                                        value = checked,
                                        enabled = enabled,
                                        role = Role.Checkbox,
                                        onValueChange = { onClick() },
                                    )
                                },
                                shape = MaterialTheme.shapes.large,
                                color = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                ListItem(
                                    supportingContent = if (!supported) { { Text("不支持的文件类型") } } else null,
                                    leadingContent = {
                                        Icon(
                                            if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.Files02,
                                            contentDescription = null,
                                        )
                                    },
                                    trailingContent = if (!entry.isDirectory) {
                                        { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) }
                                    } else null,
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                    ),
                                ) { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                        if (!loading && entries.isEmpty() && error == null) {
                            item { Text("此目录为空", modifier = Modifier.padding(16.dp)) }
                        }
                    }
                    error?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { retry++ }, enabled = !importing) { Text("重试") }
                    }
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selected.isNotEmpty() && !importing && workspace != null,
                        onClick = {
                            val id = selectedWorkspaceId ?: return@FilledTonalButton
                            val pending = selected.values.toList()
                            importing = true
                            error = null
                            scope.launch {
                                val copies = mutableListOf<ManagedFileEntity>()
                                var delivered = false
                                try {
                                    val documents = withContext(Dispatchers.IO) {
                                        pending.map { (storageArea, entry) ->
                                            val mime = workspaceDocumentMime(entry.name)
                                            val copy = filesManager.saveManagedFromWriter(FileFolders.UPLOAD, entry.name, mime) { output ->
                                                repository.exportFile(id, storageArea, entry.path, output)
                                            }
                                            copies.add(copy)
                                            UIMessagePart.Document(
                                                url = filesManager.getFile(copy).toUri().toString(),
                                                fileName = entry.name,
                                                mime = mime,
                                            )
                                        }
                                    }
                                    onFilesAdded(documents)
                                    delivered = true
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = "添加文件失败：${e.localizedMessage ?: "请重试"}"
                                } finally {
                                    if (!delivered) {
                                        withContext(NonCancellable) {
                                            copies.forEach { filesManager.delete(it.id) }
                                        }
                                    }
                                    importing = false
                                }
                            }
                        },
                    ) { Text(if (importing) "正在添加文件…" else "添加所选文件 (${selected.size})") }
                }
            }
        }
    }
}

private fun workspaceDocumentMime(fileName: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"
