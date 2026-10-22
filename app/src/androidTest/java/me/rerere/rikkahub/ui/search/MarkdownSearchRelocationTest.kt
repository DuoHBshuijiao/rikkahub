package me.rerere.rikkahub.ui.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ui.markdown.Markdown
import me.rerere.ui.markdown.MarkdownSearchTarget
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MarkdownSearchRelocationTest {
    @get:Rule val rule = createComposeRule()

    private fun assertRelocated(source: String, target: MarkdownSearchTarget) {
        val viewport = AtomicReference<Rect?>()
        val match = AtomicReference<Rect?>()
        rule.setContent {
            MaterialTheme {
                LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize().onGloballyPositioned { viewport.set(it.boundsInRoot()) },
                ) {
                    item {
                        Markdown(content = source, searchTarget = target, onSearchPosition = { match.set(it) })
                    }
                }
            }
        }
        rule.waitUntil(timeoutMillis = 10_000) {
            val bounds = viewport.get()
            val line = match.get()
            bounds != null && line != null && line.top >= bounds.top - 1 && line.bottom <= bounds.bottom + 1
        }
        assertTrue(match.get()!!.height > 0)
    }

    @Test
    fun bringsExactLineOfThirtyThousandCharacterParagraphIntoViewport() {
        val prefix = "长篇正文".repeat(7_500)
        assertRelocated(prefix + "唯一关键词" + "后文".repeat(100), MarkdownSearchTarget(
            "/0/text/0",
            prefix.length,
            prefix.length + 5,
            "唯一关键词",
            "long-paragraph",
        ))
    }

    @Test
    fun expandsDetailsAndSelectsTheRequestedRepeatedOccurrence() {
        val prefix = "上文".repeat(2_000)
        assertRelocated(
            "关键词\n\n<details><summary>折叠章节</summary><p>${prefix}关键词</p></details>",
            MarkdownSearchTarget("/1/0/text/0", prefix.length, prefix.length + 3, "关键词", "details"),
        )
    }
}
