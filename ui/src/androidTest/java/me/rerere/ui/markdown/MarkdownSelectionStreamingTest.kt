package me.rerere.ui.markdown

import android.content.ClipboardManager
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MarkdownSelectionStreamingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun completedReasoningRemainsSelectableWhileTheResponseStreams() {
        var response by mutableStateOf("Initial response.")
        var inset by mutableIntStateOf(0)
        val rendered = AtomicReference<MarkdownDocument>()
        val toolbar = AtomicReference<RecordingToolbarProvider>()
        val reasoning = "Reasoning remains selectable during streaming."
        val secondParagraph = "This second paragraph has already finished."
        compose.setContent {
            MaterialTheme {
                val platform = requireNotNull(LocalTextContextMenuToolbarProvider.current)
                val recording = remember(platform) { RecordingToolbarProvider(platform) }
                SideEffect { toolbar.set(recording) }
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides recording) {
                    // Small layout changes exercise selection-bound invalidation without
                    // scrolling the completed reasoning offscreen and closing the toolbar.
                    Column(Modifier.fillMaxSize().padding(top = (8 + inset).dp)) {
                        SelectionContainer {
                            Markdown("# Finished reasoning\n\n$reasoning\n\n$secondParagraph")
                        }
                        val document = rememberMarkdownDocument(response)
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
        compose.waitUntil(10_000) { toolbar.get()?.active?.get() != null }

        repeat(40) { step ->
            val source = streamedResponse(step)
            compose.runOnIdle {
                response = source
                inset = step % 2
            }
            compose.waitUntil(10_000) { rendered.get()?.source == source }
        }

        compose.runOnIdle {
            val recording = toolbar.get()
            assertTrue("The real toolbar must recompute selection bounds", recording.boundsReads.get() > 1)
            assertNull("Selection bounds were recalculated off the main thread", recording.offMainThread.get())
            clickMenuItem(recording, TextContextMenuKeys.SelectAllKey)
        }
        compose.waitForIdle()
        compose.runOnIdle { clickMenuItem(toolbar.get(), TextContextMenuKeys.CopyKey) }
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

    private fun clickMenuItem(toolbar: RecordingToolbarProvider, key: Any) {
        val provider = checkNotNull(toolbar.active.get()) { "The selection toolbar closed during streaming" }
        val item = provider.data().components.filterIsInstance<TextContextMenuItem>().single { it.key == key }
        item.onClick(object : TextContextMenuSession {
            override fun close() = Unit
        })
    }
}

/** Uses the platform toolbar, while making its derived-bound recalculation observable to the test. */
private class RecordingToolbarProvider(private val platform: TextContextMenuProvider) : TextContextMenuProvider {
    val active = AtomicReference<TextContextMenuDataProvider?>()
    val boundsReads = AtomicInteger()
    val offMainThread = AtomicReference<String?>()

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        val recording = object : TextContextMenuDataProvider {
            private var coordinates: LayoutCoordinates? = null
            private val bounds by derivedStateOf {
                boundsReads.incrementAndGet()
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    offMainThread.compareAndSet(null, Thread.currentThread().name)
                }
                dataProvider.contentBounds(checkNotNull(coordinates))
            }

            override fun contentBounds(destinationCoordinates: LayoutCoordinates): Rect {
                coordinates = destinationCoordinates
                return bounds
            }

            override fun position(destinationCoordinates: LayoutCoordinates) = contentBounds(
                destinationCoordinates
            ).topLeft

            override fun data() = dataProvider.data()
        }
        active.set(recording)
        try {
            platform.showTextContextMenu(recording)
        } finally {
            active.compareAndSet(recording, null)
        }
    }
}
