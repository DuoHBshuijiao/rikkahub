package me.rerere.ui.markdown

import android.content.ClipboardManager
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import me.rerere.bettertext.markdown.MarkdownDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownSelectionStreamingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun completedReasoningRemainsSelectableWhileTheResponseStreams() {
        var response by mutableStateOf("Initial response.")
        var generating by mutableStateOf(true)
        var inset by mutableIntStateOf(0)
        val rendered = AtomicReference<MarkdownDocument>()
        val toolbar = AtomicReference<RecordingTextToolbar>()
        val reasoning = "Reasoning remains selectable during streaming."
        val secondParagraph = "This second paragraph has already finished."
        compose.setContent {
            MaterialTheme {
                val platform = LocalTextToolbar.current
                val recording = remember(platform) { RecordingTextToolbar(platform) }
                SideEffect { toolbar.set(recording) }
                CompositionLocalProvider(LocalTextToolbar provides recording) {
                    // Small layout changes exercise selection-bound invalidation without
                    // scrolling the completed reasoning offscreen and closing the toolbar.
                    Column(Modifier.fillMaxSize().padding(top = (8 + inset).dp)) {
                        SelectableContent {
                            Markdown("# Finished reasoning\n\n$reasoning\n\n$secondParagraph")
                        }
                        val document = rememberMarkdownDocument(response, streaming = generating)
                        SideEffect { rendered.set(document) }
                        Markdown(
                            document = document,
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(reasoning).performTouchInput { longClick() }
        compose.waitUntil(10_000) { toolbar.get()?.selectAll != null }

        repeat(40) { step ->
            val source = streamedResponse(step)
            compose.runOnIdle {
                response = source
                inset = step % 2
            }
            compose.waitUntil(10_000) { rendered.get()?.source == source }
        }

        compose.runOnIdle { generating = false }
        compose.waitUntil(10_000) { rendered.get() == parseMarkdown(response) }

        compose.runOnIdle {
            val recording = toolbar.get()
            assertTrue("The real toolbar must refresh selection bounds", recording.boundsReads.get() > 1)
            assertNull("Selection bounds were recalculated off the main thread", recording.offMainThread.get())
            checkNotNull(recording.selectAll).invoke()
        }
        compose.waitForIdle()
        compose.runOnIdle { checkNotNull(toolbar.get().copy).invoke() }
        compose.waitForIdle()
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            assertTrue(copied.contains("Finished reasoning"))
            assertTrue(copied.contains(reasoning))
            assertTrue(copied.contains(secondParagraph))
            assertFalse(copied.contains("Response paragraph"))
            assertEquals(parseMarkdown(response), rendered.get())
            assertNull(toolbar.get().offMainThread.get())
        }
    }

    @Test
    fun sharedContainerCopiesReasoningAndStreamingBodyTogether() {
        var response by mutableStateOf("Initial answer.")
        val toolbar = AtomicReference<RecordingTextToolbar>()
        val reasoning = "Completed reasoning."
        compose.setContent {
            MaterialTheme {
                val platform = LocalTextToolbar.current
                val recording = remember(platform) { RecordingTextToolbar(platform) }
                SideEffect { toolbar.set(recording) }
                CompositionLocalProvider(LocalTextToolbar provides recording) {
                    SelectableContent {
                        Column {
                            SelectableContent { Markdown(reasoning) }
                            Markdown(response, streaming = true)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText(reasoning).performTouchInput { longClick() }
        compose.waitUntil(10_000) { toolbar.get()?.selectAll != null }
        repeat(6) { index ->
            compose.runOnIdle { response = "Answer update $index." }
            compose.waitForIdle()
        }
        compose.runOnIdle { checkNotNull(toolbar.get().selectAll).invoke() }
        compose.waitForIdle()
        compose.runOnIdle { checkNotNull(toolbar.get().copy).invoke() }
        compose.waitForIdle()
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val copied = context.getSystemService(ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            assertTrue(copied.contains(reasoning))
            assertTrue(copied.contains("Answer update 5."))
            assertNull(toolbar.get().offMainThread.get())
        }
    }

    @Test
    fun rapidUpdatesEventuallyRenderTheLatestCompleteDocument() {
        var content by mutableStateOf("Initial document.")
        val rendered = AtomicReference<MarkdownDocument>()
        compose.setContent {
            val document = rememberMarkdownDocument(content)
            SideEffect { rendered.set(document) }
        }

        repeat(40) { step ->
            compose.runOnIdle { content = streamedResponse(step) }
        }

        val expected = parseMarkdown(streamedResponse(39))
        compose.waitUntil(10_000) { rendered.get() == expected }
    }

    @Test
    fun changingLineBreakOptionsReplacesPendingParsing() {
        var content by mutableStateOf("first\nsecond")
        var softBreakAsLineBreak by mutableStateOf(true)
        val rendered = AtomicReference<MarkdownDocument>()
        compose.setContent {
            val document = rememberMarkdownDocument(content, softBreakAsLineBreak)
            SideEffect { rendered.set(document) }
        }
        compose.runOnIdle {
            assertEquals(parseMarkdown(content, true), rendered.get())
            content = "pending first\npending second\n\n".repeat(2_000)
        }
        compose.runOnIdle {
            content = "latest first\nlatest second"
            softBreakAsLineBreak = false
        }
        val expected = parseMarkdown("latest first\nlatest second", false)
        compose.waitUntil(10_000) { rendered.get() == expected }
        compose.runOnIdle { content = "final first\nfinal second" }
        compose.waitUntil(10_000) { rendered.get() == parseMarkdown("final first\nfinal second", false) }
    }

    @Test
    fun leavingAndReenteringDuringAnUpdateStartsWithTheNewContent() {
        var visible by mutableStateOf(true)
        var content by mutableStateOf("Initial document.")
        val rendered = AtomicReference<MarkdownDocument>()
        val disposals = AtomicInteger()
        compose.setContent {
            if (visible) {
                DisposableEffect(Unit) { onDispose { disposals.incrementAndGet() } }
                val document = rememberMarkdownDocument(content)
                SideEffect { rendered.set(document) }
                Text(document.source.take(20), modifier = Modifier.testTag("document"))
            }
        }
        compose.runOnIdle { content = streamedResponse(39).repeat(50) }
        compose.runOnIdle { visible = false }
        compose.onNodeWithTag("document").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, disposals.get())
            content = "Replacement document."
            visible = true
        }
        compose.onNodeWithTag("document").assertExists()
        compose.runOnIdle { content = "Updated replacement document." }
        compose.waitUntil(10_000) { rendered.get() == parseMarkdown("Updated replacement document.") }
    }

    private fun streamedResponse(step: Int): String = buildString {
        repeat(step + 1) { index ->
            append("Response paragraph $index.\n\n- First item\n- Second item\n\n")
            append("```kotlin\nval response$index = $index\n```\n\n")
        }
    }

}

/** Records the actual toolbar used by BetterText while retaining native copy behavior. */
private class RecordingTextToolbar(private val platform: TextToolbar) : TextToolbar {
    val boundsReads = AtomicInteger()
    val offMainThread = AtomicReference<String?>()
    var copy: (() -> Unit)? = null
    var selectAll: (() -> Unit)? = null
    override val status: TextToolbarStatus get() = platform.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        boundsReads.incrementAndGet()
        if (Looper.myLooper() != Looper.getMainLooper()) {
            offMainThread.compareAndSet(null, Thread.currentThread().name)
        }
        copy = onCopyRequested
        selectAll = onSelectAllRequested
        platform.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() {
        copy = null
        selectAll = null
        platform.hide()
    }
}
