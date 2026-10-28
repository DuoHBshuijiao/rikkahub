package me.rerere.ui.markdown

import me.rerere.bettertext.markdown.MarkdownDocument
import me.rerere.bettertext.markdown.MarkdownStreamParser
import me.rerere.bettertext.markdown.parseMarkdown as betterParseMarkdown

/** Full parsing is also used by search, TTS and completed exports. */
fun parseMarkdown(content: String, softBreakAsLineBreak: Boolean = true): MarkdownDocument =
    betterParseMarkdown(content, softBreakAsLineBreak, streaming = false)

/** Owned by one rendering instance and called serially, never shared between messages. */
internal class MarkdownDocumentParser(softBreakAsLineBreak: Boolean) {
    private val incremental = MarkdownStreamParser(softBreakAsLineBreak)
    private val softBreaks = softBreakAsLineBreak
    private var lastContent: String? = null
    private var lastStreaming = false
    private var lastDocument: MarkdownDocument? = null

    fun parse(content: String, streaming: Boolean): MarkdownDocument {
        lastDocument?.let { if (content == lastContent && streaming == lastStreaming) return it }
        val document = if (streaming) incremental.parse(content, streaming = true)
        else parseMarkdown(content, softBreaks)
        lastContent = content
        lastStreaming = streaming
        lastDocument = document
        return document
    }
}
