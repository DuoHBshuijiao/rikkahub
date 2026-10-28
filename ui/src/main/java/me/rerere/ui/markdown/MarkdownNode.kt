package me.rerere.ui.markdown

import me.rerere.bettertext.markdown.MarkdownInline
import me.rerere.bettertext.markdown.plainText as betterPlainText

/** The application and the renderer share the same node model. */
fun List<MarkdownInline>.plainText(): String = betterPlainText()

// Keep this segmentation identical to BetterText's renderer: search paths address these slots.
internal sealed interface InlineSegment {
    data class Text(val content: List<MarkdownInline>) : InlineSegment
    data class Image(val image: MarkdownInline.Image, val link: String?) : InlineSegment
    data class Math(val formula: String) : InlineSegment
}

internal fun List<MarkdownInline>.toSegments(): List<InlineSegment> {
    if (none { it.hasEmbedded() }) return listOf(InlineSegment.Text(this))

    val segments = mutableListOf<InlineSegment>()
    val run = mutableListOf<MarkdownInline>()

    fun flush() {
        if (run.any { it !is MarkdownInline.Text || it.text.isNotBlank() }) {
            segments += InlineSegment.Text(run.toList())
        }
        run.clear()
    }

    // 图片可能嵌在链接或样式节点里，提出来的同时用 wrap 把剩下的文字包回原来的节点
    fun visit(node: MarkdownInline, link: String?, wrap: (MarkdownInline) -> MarkdownInline) {
        when {
            node is MarkdownInline.Image -> {
                flush()
                segments += InlineSegment.Image(node, link)
            }

            node is MarkdownInline.Math && node.display -> {
                flush()
                segments += InlineSegment.Math(node.formula)
            }

            node is MarkdownInline.Styled && node.hasEmbedded() -> node.children.forEach { child ->
                visit(child, link) { wrap(node.copy(children = listOf(it))) }
            }

            node is MarkdownInline.Link && node.hasEmbedded() -> node.children.forEach { child ->
                visit(child, node.destination) { wrap(node.copy(children = listOf(it))) }
            }

            else -> run += wrap(node)
        }
    }

    forEach { node -> visit(node, null) { it } }
    flush()
    return segments
}

private fun MarkdownInline.hasEmbedded(): Boolean = when (this) {
    is MarkdownInline.Image -> true
    is MarkdownInline.Math -> display
    is MarkdownInline.Styled -> children.any { it.hasEmbedded() }
    is MarkdownInline.Link -> children.any { it.hasEmbedded() }
    else -> false
}
