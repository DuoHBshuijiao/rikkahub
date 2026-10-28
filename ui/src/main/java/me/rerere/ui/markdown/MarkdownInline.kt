package me.rerere.ui.markdown

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.util.fastForEach
import me.rerere.bettertext.markdown.LocalMarkdownTextStyle
import me.rerere.bettertext.markdown.MarkdownInline
import me.rerere.bettertext.text.BetterInline
import me.rerere.bettertext.text.BetterText
import me.rerere.bettertext.text.appendInline

internal class MarkdownContext(
    val theme: MarkdownTheme,
    val components: MarkdownComponents,
    /** 为 null 时链接交给系统的 UriHandler 打开 */
    val linkListener: LinkInteractionListener?,
)

internal val LocalMarkdownContext = staticCompositionLocalOf<MarkdownContext> {
    error("LocalMarkdownContext is only available inside Markdown()")
}

/** 把一组行内节点渲染成一个 Text */
@Composable
internal fun InlineText(content: List<MarkdownInline>, modifier: Modifier = Modifier, path: String) {
    val context = LocalMarkdownContext.current
    val style = LocalMarkdownTextStyle.current
    val androidContext = LocalContext.current
    val density = LocalDensity.current
    val contentColor = style.color.takeOrElse { LocalContentColor.current }
    val rendered = remember(content, context, style, density, contentColor, androidContext) {
        InlineTextBuilder(context, MarkdownInlineScope(style, density, contentColor, androidContext)).build(content)
    }
    MarkdownSearchText(
        text = remember(content) { content.searchText() },
        path = path,
        renderedOffset = rendered::renderedOffset,
    ) { highlight, onTextLayout ->
        BetterText(
            text = rendered.text,
            modifier = modifier.then(highlight),
            inlineContent = rendered.inlineContent,
            onTextLayout = onTextLayout,
            style = style.copy(color = contentColor),
        )
    }
}

private class RenderedInlineText(
    val text: AnnotatedString,
    val inlineContent: Map<String, BetterInline>,
    val offsetShifts: List<Pair<Int, Int>>,
) {
    fun renderedOffset(offset: Int): Int = offset + (offsetShifts.lastOrNull { it.first <= offset }?.second ?: 0)
}

private val MATH_SOURCE_STYLE = SpanStyle(fontFamily = FontFamily.Monospace)

// 开引号 -> 闭引号。单引号常被当作撇号（don't、it’s）使用，不参与配对
private val QUOTE_PAIRS = mapOf('"' to '"', '“' to '”', '「' to '」', '『' to '』')

private class InlineTextBuilder(
    private val context: MarkdownContext,
    private val scope: MarkdownInlineScope,
) {
    private val builder = AnnotatedString.Builder()
    private val inlineContent = mutableMapOf<String, BetterInline>()
    private var searchOffset = 0
    private val offsetShifts = mutableListOf<Pair<Int, Int>>()

    // 还没闭合的引号：等待的闭引号，以及开引号在输出文本里的位置。引号可以跨越加粗、链接等节点
    private var pendingQuoteClose: Char? = null
    private var pendingQuoteStart = 0

    fun build(content: List<MarkdownInline>): RenderedInlineText {
        append(content)
        return RenderedInlineText(builder.toAnnotatedString(), inlineContent, offsetShifts)
    }

    private fun append(nodes: List<MarkdownInline>) = nodes.fastForEach { append(it) }

    private fun append(node: MarkdownInline) {
        when (node) {
            is MarkdownInline.Text -> {
                appendText(node.text)
                searchOffset += node.text.length
            }
            is MarkdownInline.Styled -> builder.withStyle(node.style) { append(node.children) }
            is MarkdownInline.Code -> {
                context.components.inlineCodePrefix?.invoke(scope, node.code)?.let { appendWidget(it, "") }
                builder.withStyle(context.theme.inlineCode) { append(node.code) }
                searchOffset += node.code.length
            }
            is MarkdownInline.Link -> appendLink(node)
            is MarkdownInline.Math -> {
                appendMath(node)
                searchOffset += node.formula.length
                offsetShifts += searchOffset to builder.length - searchOffset
            }
            // 图片在分段时已经被拆出去单独渲染，走到这里只剩替代文本可用
            is MarkdownInline.Image -> {
                builder.append(node.alt.orEmpty())
                searchOffset += node.alt.orEmpty().length
            }
        }
    }

    private fun appendText(text: String) {
        val offset = builder.length
        val decorations = context.components.textDecorations?.invoke(scope, text).orEmpty()
        var copied = 0
        for (decoration in decorations.sortedBy { it.offset }) {
            val at = decoration.offset.coerceIn(copied, text.length)
            builder.append(text, copied, at)
            appendWidget(decoration.item, "")
            copied = at
        }
        builder.append(text, copied, text.length)
        val quoteStyle = context.theme.quotedText ?: return
        text.forEachIndexed { index, char ->
            val close = pendingQuoteClose
            if (close == null) {
                QUOTE_PAIRS[char]?.let {
                    pendingQuoteClose = it
                    pendingQuoteStart = offset + index
                }
            } else if (char == close) {
                // 只高亮已经闭合的引号，避免流式输出时或孤立的引号把后面整段都染色
                builder.addStyle(quoteStyle, pendingQuoteStart, offset + index + 1)
                pendingQuoteClose = null
            }
        }
    }

    private fun appendLink(link: MarkdownInline.Link) {
        val widget = context.components.inlineLink?.invoke(scope, link)
        if (widget != null) {
            val alternateText = link.children.plainText()
            appendWidget(widget, alternateText = alternateText)
            searchOffset += alternateText.length
            offsetShifts += searchOffset to builder.length - searchOffset
            return
        }
        val annotation = LinkAnnotation.Url(
            url = link.destination,
            styles = TextLinkStyles(style = context.theme.link),
            linkInteractionListener = context.linkListener,
        )
        builder.withLink(annotation) { append(link.children) }
    }

    private fun appendMath(math: MarkdownInline.Math) {
        val item = context.components.inlineMath?.invoke(scope, math.formula)
        if (item == null) {
            builder.withStyle(MATH_SOURCE_STYLE) { append(math.formula) }
        } else {
            appendWidget(item, alternateText = "\$${math.formula}\$")
        }
    }

    private fun appendWidget(widget: BetterInline, alternateText: String) {
        val id = "markdown:${inlineContent.size}"
        inlineContent[id] = widget
        builder.appendInline(id, alternateText)
    }
}
