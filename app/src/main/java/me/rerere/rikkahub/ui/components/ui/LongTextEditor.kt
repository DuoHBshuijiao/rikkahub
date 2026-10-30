package me.rerere.rikkahub.ui.components.ui

import android.os.Build
import android.os.Trace
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Search01

/** A single scroll owner and a decoration-local overlay keep highlight coordinates independent of padding. */
@OptIn(FlowPreview::class)
@Composable
internal fun EditorTextField(
    state: TextFieldState,
    viewport: EditorViewport,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    outlined: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    search: LongTextEditorState? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val colors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
    )
    val hint: (@Composable () -> Unit)? = if (placeholder.isEmpty()) null else ({ Text(placeholder) })
    val decorator = if (outlined) {
        OutlinedTextFieldDefaults.decorator(
            state = state,
            enabled = enabled,
            lineLimits = lineLimits,
            outputTransformation = null,
            interactionSource = interactions,
            placeholder = hint,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
        )
    } else {
        TextFieldDefaults.decorator(
            state = state,
            enabled = enabled,
            lineLimits = lineLimits,
            outputTransformation = null,
            interactionSource = interactions,
            placeholder = hint,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            colors = colors,
            container = {
                TextFieldDefaults.Container(
                    enabled = enabled,
                    isError = false,
                    interactionSource = interactions,
                    colors = colors,
                    shape = MaterialTheme.shapes.largeIncreased,
                )
            },
        )
    }
    val matchColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
    val currentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    BasicTextField(
        state = state,
        modifier = modifier.onSizeChanged { viewport.measuredHeight = it.height }
            .pointerInput(viewport) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) {
                            // A user gesture takes precedence over any in-progress mode restoration.
                            viewport.pendingAnchor = null
                        }
                    }
                }
            },
        enabled = enabled,
        readOnly = readOnly,
        lineLimits = lineLimits,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        interactionSource = interactions,
        scrollState = viewport.scroll,
        onTextLayout = { getResult -> viewport.layout = getResult() },
        decorator = { innerTextField ->
            decorator.Decoration {
                Box(
                    Modifier.clipToBounds().editorHighlights(viewport, search, matchColor, currentColor),
                ) {
                    innerTextField()
                }
            }
        },
    )
    LaunchedEffect(viewport, viewport.pendingAnchor) {
        val anchor = viewport.pendingAnchor ?: return@LaunchedEffect
        coroutineScope {
            val edits = launch {
                snapshotFlow { state.text to state.selection }.drop(1).first()
                viewport.pendingAnchor = null
            }
            try {
                snapshotFlow { viewport.layout to viewport.scroll.viewportSize }
                    .filter { (layout, height) -> layout != null && height > 0 }
                    .onEach {
                        // IME animation also changes the viewport. Follow the reading anchor until it settles.
                        withFrameNanos { }
                        if (viewport.pendingAnchor == anchor) {
                            viewport.scrollOffset(anchor)?.let { viewport.scroll.scrollTo(it) }
                        }
                    }
                    .debounce(160)
                    .first()
                viewport.pendingAnchor = null
            } finally {
                edits.cancel()
            }
        }
    }
}

@Composable
internal fun EditorPlaceholder(viewport: EditorViewport) {
    val height = with(LocalDensity.current) { viewport.measuredHeight.toDp() }.coerceAtLeast(56.dp)
    Spacer(Modifier.fillMaxWidth().height(height))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FullscreenTextEditor(
    editor: LongTextEditorState,
    label: String,
    placeholder: String = "",
    readOnly: Boolean = false,
    testTag: String = "long-text-editor",
    receiveContentListener: ReceiveContentListener? = null,
    onDismiss: () -> Unit = editor::close,
) {
    val scope = rememberCoroutineScope()
    val editorFocus = remember { FocusRequester() }
    val queryFocus = remember { FocusRequester() }
    var menuExpanded by remember { mutableStateOf(false) }
    EditorSearchEffect(editor)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        LaunchedEffect(editor, editor.searchVisible, editor.focusEditorRequest) {
            if (editor.searchVisible) {
                queryFocus.requestFocus()
            } else if (!readOnly) {
                editorFocus.requestFocus()
            }
        }
        LaunchedEffect(editor, editor.navigation) {
            val target = editor.navigation ?: return@LaunchedEffect
            val viewport = editor.fullscreen
            viewport.pendingAnchor = null
            val layout = snapshotFlow { viewport.layout }.filterNotNull().first()
            val line = layout.getLineForOffset(target.start.coerceIn(0, layout.layoutInput.text.length))
            // Keep context above and below the match. No focus change and no long animated scroll.
            viewport.scroll.scrollTo((layout.getLineTop(line) - viewport.scroll.viewportSize / 3).toInt())
            editor.navigation = null
        }
        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label.ifBlank { "编辑文本" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!editor.searchVisible) {
                        IconButton(onClick = editor::showSearch) { Icon(HugeIcons.Search01, "查找") }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) { Icon(HugeIcons.MoreVertical, "更多编辑操作") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("到开头") },
                                onClick = {
                                    menuExpanded = false
                                    editor.fullscreen.pendingAnchor = null
                                    scope.launch { editor.fullscreen.scroll.scrollTo(0) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("到结尾") },
                                onClick = {
                                    menuExpanded = false
                                    editor.fullscreen.pendingAnchor = null
                                    scope.launch { editor.fullscreen.scroll.scrollTo(editor.fullscreen.scroll.maxValue) }
                                },
                            )
                        }
                    }
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
                if (editor.searchVisible) EditorSearchBar(editor, queryFocus, readOnly)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    EditorTextField(
                        state = editor.text,
                        viewport = editor.fullscreen,
                        modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(editorFocus)
                            .testTag(testTag).then(
                                if (receiveContentListener == null) {
                                    Modifier
                                } else {
                                    Modifier.contentReceiver(receiveContentListener)
                                },
                            ),
                        placeholder = placeholder,
                        readOnly = readOnly,
                        search = editor,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    EditorScrollbar(editor.fullscreen)
                }
            }
        }
    }
}

@Composable
private fun EditorSearchBar(editor: LongTextEditorState, focus: FocusRequester, readOnly: Boolean) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        TextField(
            state = editor.query,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("editor-search-query"),
            placeholder = { Text("查找关键词") },
            lineLimits = TextFieldLineLimits.SingleLine,
            shape = MaterialTheme.shapes.largeIncreased,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onKeyboardAction = { editor.navigate(1) },
            leadingIcon = { Icon(HugeIcons.Search01, null) },
            trailingIcon = {
                IconButton(onClick = {
                    editor.closeSearch(readOnly)
                    if (readOnly) keyboard?.hide()
                }) { Icon(HugeIcons.Cancel01, "关闭查找") }
            },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val count = editor.result.matches.size
            Text(
                when {
                    editor.searchPending -> "查找中…"
                    editor.result.query.isEmpty() -> "输入关键词"
                    count == 0 -> "无匹配结果"
                    else -> "${editor.currentMatch + 1} / $count"
                },
                modifier = Modifier.weight(1f).padding(start = 12.dp).testTag("editor-search-count"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { editor.navigate(-1) }, enabled = count > 0 && !editor.searchPending) {
                Icon(HugeIcons.ArrowUp01, "上一处匹配")
            }
            IconButton(onClick = { editor.navigate(1) }, enabled = count > 0 && !editor.searchPending) {
                Icon(HugeIcons.ArrowDown01, "下一处匹配")
            }
        }
    }
}

@Composable
private fun EditorSearchEffect(editor: LongTextEditorState) {
    LaunchedEffect(editor, editor.searchVisible) {
        if (!editor.searchVisible) return@LaunchedEffect
        observeEditorSearch(editor) { searching ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cookie = System.identityHashCode(editor)
                if (searching) {
                    Trace.beginAsyncSection("editor_search_response", cookie)
                } else {
                    Trace.endAsyncSection("editor_search_response", cookie)
                }
            }
        }
    }
}

private fun Modifier.editorHighlights(
    viewport: EditorViewport,
    editor: LongTextEditorState?,
    color: Color,
    currentColor: Color,
): Modifier {
    if (editor == null) return this
    return drawWithCache {
        val layout = viewport.layout
        val result = editor.result
        // Compare the source once per layout/result change, never once per scroll frame.
        val valid = editor.searchVisible && !editor.searchPending && layout != null &&
            layout.layoutInput.text.text == result.text
        val highlights = Path()
        var currentPath: Path? = null
        var currentPathIndex = -1
        var firstMatch = -1
        var lastMatch = -1
        var firstOffset = -1
        var lastOffset = -1
        onDrawBehind {
            if (valid && result.matches.isNotEmpty()) {
                val scrollY = viewport.scroll.value.toFloat()
                val start = layout.getLineStart(layout.getLineForVerticalPosition(scrollY))
                val end = layout.getLineEnd(layout.getLineForVerticalPosition(scrollY + size.height))
                val first = result.matches.firstEditorMatchAt(start)
                var last = first
                while (last < result.matches.size && result.matches[last].start < end) last++
                if (first != firstMatch || last != lastMatch || start != firstOffset || end != lastOffset) {
                    highlights.rewind()
                    var index = first
                    while (index < last) {
                        val match = result.matches[index]
                        val rangeStart = maxOf(start, match.start)
                        var rangeEnd = minOf(end, match.end)
                        index++
                        // Dense one-character searches can be drawn as contiguous ranges.
                        while (index < last && result.matches[index].start <= rangeEnd) {
                            rangeEnd = minOf(end, result.matches[index].end)
                            index++
                        }
                        // A multiline match may span the entire document; only build its visible path.
                        highlights.addPath(layout.getPathForRange(rangeStart, rangeEnd))
                    }
                    currentPathIndex = -1
                    firstMatch = first
                    lastMatch = last
                    firstOffset = start
                    lastOffset = end
                }
                clipRect {
                    translate(top = -scrollY) {
                        drawPath(highlights, color)
                        val current = editor.currentMatch
                        if (current in first until last) {
                            if (currentPathIndex != current) {
                                val match = result.matches[current]
                                currentPath = layout.getPathForRange(maxOf(start, match.start), minOf(end, match.end))
                                currentPathIndex = current
                            }
                            currentPath?.let { drawPath(it, currentColor) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorScrollbar(viewport: EditorViewport) {
    val scroll = viewport.scroll
    val scope = rememberCoroutineScope()
    val color = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val minimumThumb = with(LocalDensity.current) { 48.dp.toPx() }
    Box(
        Modifier.width(48.dp).fillMaxHeight().testTag("editor-scrollbar")
            .semantics {
                contentDescription = "快速滚动"
                if (scroll.maxValue == 0) hideFromAccessibility()
                progressBarRangeInfo = ProgressBarRangeInfo(scroll.value.toFloat(), 0f..scroll.maxValue.toFloat())
                setProgress { target ->
                    scope.launch { scroll.scrollTo(target.toInt()) }
                    true
                }
            }
            .pointerInput(scroll, minimumThumb) {
                coroutineScope {
                    var grabOffset = 0f
                    fun thumbHeight(): Float = scrollbarThumbHeight(size.height.toFloat(), scroll.maxValue, minimumThumb)
                    fun move(y: Float) {
                        val track = size.height - thumbHeight()
                        if (track > 0 && scroll.maxValue > 0) {
                            val target = ((y - grabOffset) / track).coerceIn(0f, 1f) * scroll.maxValue
                            scroll.dispatchRawDelta(target - scroll.value)
                        }
                    }
                    detectVerticalDragGestures(
                        onDragStart = { position ->
                            viewport.pendingAnchor = null
                            launch(start = CoroutineStart.UNDISPATCHED) { scroll.stopScroll(MutatePriority.UserInput) }
                            val thumb = thumbHeight()
                            val top = if (scroll.maxValue > 0) (size.height - thumb) * scroll.value / scroll.maxValue else 0f
                            grabOffset = if (position.y in top..(top + thumb)) position.y - top else thumb / 2
                            move(position.y)
                        },
                        onVerticalDrag = { change, _ ->
                            if (scroll.maxValue > 0) {
                                change.consume()
                                move(change.position.y)
                            }
                        },
                    )
                }
            }
            .drawWithCache {
                val width = 4.dp.toPx()
                onDrawBehind {
                    if (scroll.maxValue > 0) {
                        val height = scrollbarThumbHeight(size.height, scroll.maxValue, minimumThumb)
                        val x = (size.width - width) / 2
                        drawRoundRect(trackColor, Offset(x, 0f), Size(width, size.height), CornerRadius(width))
                        val y = (size.height - height) * scroll.value / scroll.maxValue
                        drawRoundRect(color, Offset(x, y), Size(width, height), CornerRadius(width))
                    }
                }
            },
    )
}

private fun scrollbarThumbHeight(height: Float, maxScroll: Int, minimum: Float): Float =
    (height * height / (height + maxScroll).coerceAtLeast(1f)).coerceIn(minOf(minimum, height), height)
