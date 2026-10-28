package me.rerere.ui.markdown

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import me.rerere.bettertext.markdown.LocalMarkdownBlockPath
import me.rerere.bettertext.markdown.MarkdownBlock
import me.rerere.bettertext.markdown.MarkdownDocument
import me.rerere.bettertext.markdown.MarkdownInline
import me.rerere.bettertext.text.BetterTextLayoutResult

/** A stable address in the displayed document, independent of font size and line wrapping. */
data class MarkdownSearchTarget(
    val segmentPath: String,
    val start: Int,
    val end: Int,
    val matchedText: String,
    val requestId: String,
)

data class MarkdownSearchSegment(val path: String, val text: String)

internal class MarkdownSearchContext(
    val target: MarkdownSearchTarget,
    val onPosition: (Rect) -> Unit,
)

internal val LocalMarkdownSearch = staticCompositionLocalOf<MarkdownSearchContext?> { null }

/**
 * Walks the same text slots as MarkdownBlocks. Markup, URLs, image alt text and rendered math
 * aren't searchable text. Keeping slot addresses separate from the AST preserves parser equality
 * and lets search and rendering share a document without injecting markers into its source.
 */
fun MarkdownDocument.searchSegments(): List<MarkdownSearchSegment> = buildList {
    fun inline(content: List<MarkdownInline>, path: String) {
        content.toSegments().forEachIndexed { index, segment ->
            if (segment is InlineSegment.Text) {
                add(MarkdownSearchSegment("$path/text/$index", segment.content.searchText()))
            }
        }
    }

    fun blocks(blocks: List<MarkdownBlock>, parent: String) {
        blocks.forEachIndexed { index, block ->
            val path = "$parent/$index"
            when (block) {
                is MarkdownBlock.Paragraph -> inline(block.content, path)
                is MarkdownBlock.Heading -> inline(block.content, path)
                is MarkdownBlock.CodeBlock -> add(MarkdownSearchSegment("$path/code", block.code))
                is MarkdownBlock.Quote -> blocks(block.children, path)
                is MarkdownBlock.Container -> blocks(block.children, path)
                is MarkdownBlock.ListBlock -> block.items.forEachIndexed { itemIndex, item ->
                    blocks(item.children, "$path/item/$itemIndex")
                }
                is MarkdownBlock.Details -> {
                    add(MarkdownSearchSegment("$path/summary", block.summary.searchText()))
                    blocks(block.children, path)
                }
                is MarkdownBlock.Table -> {
                    block.header.forEachIndexed { column, cell -> inline(cell.content, "$path/header/$column") }
                    block.rows.forEachIndexed { row, cells ->
                        cells.forEachIndexed { column, cell -> inline(cell.content, "$path/row/$row/$column") }
                    }
                }
                MarkdownBlock.Divider -> Unit
            }
        }
    }
    blocks(this@searchSegments.blocks, "")
}

internal fun List<MarkdownInline>.searchText(): String = buildString {
    fun appendNodes(nodes: List<MarkdownInline>) {
        nodes.forEach { node ->
            when (node) {
                is MarkdownInline.Text -> append(node.text)
                is MarkdownInline.Code -> append(node.code)
                is MarkdownInline.Styled -> appendNodes(node.children)
                is MarkdownInline.Link -> appendNodes(node.children)
                is MarkdownInline.Image -> append(" ".repeat(node.alt.orEmpty().length))
                is MarkdownInline.Math -> append(" ".repeat(node.formula.length))
            }
        }
    }
    appendNodes(this@searchText)
}

/** Allows application code slots to opt into the same search highlighting and relocation. */
@Composable
fun currentMarkdownCodeSearchTarget(): MarkdownSearchTarget? {
    val path = "${LocalMarkdownBlockPath.current}/code"
    return LocalMarkdownSearch.current?.target?.takeIf { it.segmentPath == path }
}

/** [textOffset] is used by code blocks which render each source line as a separate Text. */
@Composable
fun MarkdownSearchText(
    text: String,
    textOffset: Int = 0,
    path: String = "${LocalMarkdownBlockPath.current}/code",
    // Maps searchable text offsets to the AnnotatedString's offsets around inline widgets.
    renderedOffset: (Int) -> Int = { it },
    content: @Composable (Modifier, ((BetterTextLayoutResult) -> Unit)?) -> Unit,
) {
    val search = LocalMarkdownSearch.current
    val target = search?.target?.takeIf {
        it.segmentPath == path && it.start < textOffset + text.length && it.end > textOffset
    }
    if (target == null) {
        content(Modifier, null)
        return
    }
    val start = (target.start - textOffset).coerceAtLeast(0)
    val end = (target.end - textOffset).coerceAtMost(text.length)
    val expectedOffset = (textOffset - target.start).coerceAtLeast(0)
    if (start >= end || !text.regionMatches(start, target.matchedText, expectedOffset, end - start)) {
        content(Modifier, null)
        return
    }
    val anchor = target.start >= textOffset
    val geometry = remember(text, target.requestId) { SearchTextGeometry() }
    var layout by remember(text, target.requestId) { mutableStateOf<BetterTextLayoutResult?>(null) }
    var relocated by remember(target.requestId) { mutableStateOf(false) }
    var highlightReady by remember(target.requestId) { mutableStateOf(false) }
    val requester = remember { BringIntoViewRequester() }
    val currentOnPosition by rememberUpdatedState(search?.onPosition)
    val emphasis = remember(target.requestId) { Animatable(1f) }
    val color = MaterialTheme.colorScheme.tertiaryContainer
    val animationSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()

    fun reportPosition() {
        if (!geometry.ready || !anchor) return
        val coordinates = geometry.coordinates?.takeIf { it.isAttached } ?: return
        val rect = geometry.line ?: return
        currentOnPosition?.invoke(
            Rect(coordinates.localToRoot(rect.topLeft), coordinates.localToRoot(rect.bottomRight))
        )
    }

    LaunchedEffect(target.requestId, text, anchor) {
        if (anchor) {
            snapshotFlow { layout }.filterNotNull().first()
            if (!relocated) {
                geometry.line?.let { requester.bringIntoView(it) }
                relocated = true
            }
            geometry.ready = true
            reportPosition()
        }
        highlightReady = true
    }
    LaunchedEffect(target.requestId) {
        snapshotFlow { highlightReady }.first { it }
        emphasis.animateTo(0f, animationSpec)
    }

    content(
        Modifier
            .bringIntoViewRequester(requester)
            .onGloballyPositioned {
                geometry.coordinates = it
                reportPosition()
            }
            .drawWithContent {
                geometry.path?.let { drawPath(it, color = color, alpha = 0.65f + emphasis.value * 0.35f) }
                drawContent()
            },
        { result ->
            val renderedStart = renderedOffset(start).coerceIn(0, result.text.length)
            val renderedEnd = renderedOffset(end).coerceIn(renderedStart, result.text.length)
            if (renderedStart < renderedEnd) {
                geometry.path = result.getPathForRange(renderedStart, renderedEnd)
                val line = result.getLineForOffset(renderedStart)
                val bounds = geometry.path!!.getBounds()
                geometry.line = Rect(bounds.left, result.getLineTop(line), bounds.right, result.getLineBottom(line))
            }
            layout = result
            reportPosition()
        },
    )
}

private class SearchTextGeometry {
    var coordinates: LayoutCoordinates? = null
    var path: Path? = null
    var line: Rect? = null
    var ready = false
}
