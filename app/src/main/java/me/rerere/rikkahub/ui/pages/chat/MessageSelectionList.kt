package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.toLocalString

@Composable
fun MessageSelectionTopBar(
    selection: MessageSelection,
    total: Int,
    onChange: (MessageSelection) -> Unit,
    onExit: () -> Unit,
) {
    TopAppBar(
        title = { Text("已选 ${selection.selectedIds.size} / 共 $total 条", style = MaterialTheme.typography.titleMediumEmphasized) },
        navigationIcon = {
            IconButton(onClick = onExit) { Icon(HugeIcons.Cancel01, "退出多选") }
        },
        actions = {
            FilterChip(
                selected = selection.onlySelected,
                onClick = { onChange(selection.copy(onlySelected = !selection.onlySelected)) },
                enabled = !selection.choosingRange,
                label = { Text("仅看已选") },
                modifier = Modifier.padding(end = 8.dp),
            )
        },
    )
}

@Composable
fun MessageSelectionToolbar(
    selection: MessageSelection,
    conversation: Conversation,
    onChange: (MessageSelection) -> Unit,
    onExport: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
            if (selection.choosingRange) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (selection.rangeStart == null) "点选范围起点" else "点选终点，将选中首尾之间的全部消息",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    TextButton(onClick = { onChange(selection.copy(choosingRange = false, rangeStart = null)) }) { Text("取消") }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onChange(selection.copy(selectedIds = conversation.messageNodes.map { it.id }.toSet(), choosingRange = false, rangeStart = null)) }) { Text("全选") }
                TextButton(onClick = { onChange(selection.copy(selectedIds = emptySet(), choosingRange = false, rangeStart = null)) }, enabled = selection.selectedIds.isNotEmpty()) { Text("清空") }
                TextButton(onClick = { onChange(selection.copy(choosingRange = true, rangeStart = null, onlySelected = false)) }) { Text("范围选择") }
                Button(onClick = onExport, enabled = selection.selectedIds.isNotEmpty() && !selection.choosingRange) { Text("导出") }
            }
        }
    }
}

@Composable
fun MessageSelectionList(
    conversation: Conversation,
    selection: MessageSelection,
    onSelectionChange: (MessageSelection) -> Unit,
    innerPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    val selectedListState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val latestSelection by rememberUpdatedState(selection)
    val onChange by rememberUpdatedState(onSelectionChange)
    val orderedIds = remember(conversation.messageNodes) { conversation.messageNodes.map { it.id } }
    val latestIds by rememberUpdatedState(orderedIds)
    val visibleNodes = if (selection.onlySelected) conversation.messageNodes.filter { it.id in selection.selectedIds } else conversation.messageNodes
    val originalIndices = remember(orderedIds) { orderedIds.withIndex().associate { it.value to it.index } }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    var located by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // A restored LazyListState already has a position; only locate on first entry.
        if (!located) {
            val index = orderedIds.indexOf(selection.initialNodeId)
            if (index >= 0) listState.scrollToItem(index)
            located = true
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        LazyColumn(
            state = if (selection.onlySelected) selectedListState else listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            if (visibleNodes.isEmpty()) {
                item {
                    Text("尚未选择消息", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(visibleNodes, key = { _, node -> node.id }, contentType = { _, _ -> "messageSelection" }) { index, node ->
                val message = node.currentMessage
                val selected = node.id in selection.selectedIds
                SegmentedListItem(
                    modifier = Modifier.testTag("message-selection-${node.id}"),
                    checked = selected,
                    onCheckedChange = {
                        val wasDeselected = selected && !selection.choosingRange
                        onSelectionChange(selection.choose(node.id, orderedIds))
                        if (wasDeselected) {
                            snackbar.currentSnackbarData?.dismiss()
                            scope.launch {
                                if (snackbar.showSnackbar("已取消选择第 ${(originalIndices[node.id] ?: 0) + 1} 条消息", actionLabel = "撤销") == SnackbarResult.ActionPerformed) {
                                    if (node.id in latestIds) onChange(latestSelection.copy(selectedIds = latestSelection.selectedIds + node.id))
                                }
                            }
                        }
                    },
                    shapes = ListItemDefaults.segmentedShapes(index, visibleNodes.size),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    ),
                    leadingContent = { Checkbox(checked = selected, onCheckedChange = null) },
                    overlineContent = {
                        Text(
                            "${(originalIndices[node.id] ?: 0) + 1} · ${message.role.selectionLabel()} · ${message.createdAt.toJavaLocalDateTime().toLocalString()}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = if (selection.rangeStart == node.id) ({ Text("范围起点", color = MaterialTheme.colorScheme.primary) }) else null,
                    trailingContent = {
                        IconButton(onClick = { previewId = node.id.toString() }) { Icon(HugeIcons.Fullscreen, "查看全文") }
                    },
                ) {
                    Text(
                        remember(message) { message.selectionSummary() },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }

    conversation.messageNodes.find { it.id.toString() == previewId }?.let { node ->
        Dialog(onDismissRequest = { previewId = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.safeDrawingPadding()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("消息全文", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        TextButton(onClick = { previewId = null }) { Text("完成") }
                    }
                    SelectionContainer(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text(node.currentMessage.selectionSummary(Int.MAX_VALUE))
                    }
                }
            }
        }
    }
}

private fun MessageRole.selectionLabel(): String = when (this) {
    MessageRole.USER -> "用户"
    MessageRole.ASSISTANT -> "助手"
    MessageRole.SYSTEM -> "系统"
    MessageRole.TOOL -> "工具"
}

internal fun UIMessage.selectionSummary(limit: Int = 300): String = buildString {
    // Keep the answer visible even when a long tool chain precedes it.
    val summaryParts = if (limit == Int.MAX_VALUE) parts else parts.filterIsInstance<UIMessagePart.Text>() + parts.filterNot { it is UIMessagePart.Text }
    for (part in summaryParts) {
        if (length >= limit) break
        val text = when (part) {
            is UIMessagePart.Text -> part.text.trim()
            is UIMessagePart.Image -> "[图片]"
            is UIMessagePart.Video -> "[视频]"
            is UIMessagePart.Audio -> "[音频]"
            is UIMessagePart.Document -> "[文件] ${part.fileName}"
            is UIMessagePart.Tool -> if (limit == Int.MAX_VALUE) {
                "[工具调用] ${part.toolName}\n${part.input}\n${copy(parts = part.output).selectionSummary(limit)}"
            } else {
                "[工具调用] ${part.toolName}"
            }
            is UIMessagePart.ServerTool -> "[服务端工具] ${part.toolName}"
            is UIMessagePart.Reasoning -> if (limit == Int.MAX_VALUE) "[思考] ${part.reasoning}" else "[思考]"
            else -> "[附加内容]"
        }
        if (text.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append(text.take((limit - length).coerceAtLeast(0)))
        }
    }
}.ifBlank { "[空消息]" }
