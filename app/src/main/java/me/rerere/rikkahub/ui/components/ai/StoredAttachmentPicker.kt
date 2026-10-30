package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Files02
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.Instant

internal class StoredAttachmentPickerState {
    val listState = LazyListState()
    var attachments by mutableStateOf<List<StoredAttachment>>(emptyList())
    var selected by mutableStateOf<Set<String>>(emptySet())
    var alreadyAdded by mutableStateOf<Set<String>>(emptySet())
    var loading by mutableStateOf(true)
    var adding by mutableStateOf(false)
    var loadError by mutableStateOf<String?>(null)
    var addError by mutableStateOf<String?>(null)
    var retry by mutableIntStateOf(0)

    val pendingSelection: Set<String> get() = selected - alreadyAdded
}

@Composable
internal fun StoredAttachmentPicker(
    state: StoredAttachmentPickerState,
    filesManager: FilesManager,
    existingAttachments: List<UIMessagePart>,
    onAttachmentsAdded: (List<UIMessagePart>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val currentExistingAttachments by rememberUpdatedState(existingAttachments)
    val currentOnAttachmentsAdded by rememberUpdatedState(onAttachmentsAdded)

    LaunchedEffect(state.retry, existingAttachments) {
        state.loading = true
        state.loadError = null
        try {
            state.alreadyAdded = withContext(Dispatchers.IO) { storedAttachmentKeys(existingAttachments) }
            filesManager.observe(FileFolders.UPLOAD).collectLatest { entities ->
                state.attachments = withContext(Dispatchers.IO) {
                    loadStoredAttachments(entities, filesManager::getFile)
                }
                state.loading = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.loading = false
            state.loadError = "无法读取内部附件：${e.localizedMessage ?: "请重试"}"
        }
    }

    StoredAttachmentPickerContent(
        state = state,
        onAdd = {
            state.adding = true
            state.addError = null
            scope.launch {
                try {
                    val attachments = state.attachments
                    val selected = state.pendingSelection
                    val existing = currentExistingAttachments
                    val parts = withContext(Dispatchers.IO) {
                        prepareStoredAttachments(attachments, selected, existing)
                    }
                    currentOnAttachmentsAdded(parts)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StoredAttachmentUnavailableException) {
                    state.selected -= e.keys
                    state.addError = e.message
                    state.retry++
                } catch (e: Exception) {
                    state.addError = "添加附件失败：${e.localizedMessage ?: "请重试"}"
                } finally {
                    state.adding = false
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
internal fun StoredAttachmentPickerContent(
    state: StoredAttachmentPickerState,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().heightIn(max = 480.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                state.loading -> CircularProgressIndicator()
                state.loadError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.loadError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                    TextButton(onClick = { state.retry++ }, enabled = !state.adding) { Text("重试") }
                }
                state.attachments.isEmpty() -> Text(
                    text = "暂无可复用的图片或文档",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn(
                    state = state.listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.attachments, key = { it.key }) { attachment ->
                        val alreadyAdded = attachment.key in state.alreadyAdded
                        val checked = alreadyAdded || attachment.key in state.selected
                        val enabled = !alreadyAdded && !state.adding
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = if (checked) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        ) {
                            ListItem(
                                modifier = Modifier.toggleable(
                                    value = checked,
                                    enabled = enabled,
                                    role = Role.Checkbox,
                                    onValueChange = { selected ->
                                        state.selected = if (selected) {
                                            state.selected + attachment.key
                                        } else {
                                            state.selected - attachment.key
                                        }
                                    },
                                ),
                                supportingContent = {
                                    Column {
                                        Text(
                                            "${attachment.entity.sizeBytes.fileSizeToString()} · " +
                                                Instant.ofEpochMilli(attachment.entity.createdAt).toLocalDateTime(),
                                        )
                                        if (alreadyAdded) Text("已添加")
                                    }
                                },
                                leadingContent = {
                                    Surface(
                                        modifier = Modifier.size(48.dp),
                                        shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ) {
                                        if (attachment.isImage) {
                                            AsyncImage(
                                                model = attachment.file,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(HugeIcons.Files02, contentDescription = null)
                                            }
                                        }
                                    }
                                },
                                trailingContent = {
                                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            ) {
                                Text(attachment.entity.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        state.addError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        FilledTonalButton(
            onClick = onAdd,
            enabled = state.pendingSelection.isNotEmpty() && !state.loading && !state.adding && state.loadError == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.adding) "正在添加附件…" else "添加所选附件（${state.pendingSelection.size}）")
        }
    }
}
