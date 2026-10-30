package me.rerere.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LongTextEditorBenchmarks(
    private val surface: String,
    private val length: Int,
    private val paragraphs: Boolean,
    private val search: Boolean,
) {
    @get:Rule val rule = MacrobenchmarkRule()

    private val packageName: String
        get() = checkNotNull(InstrumentationRegistry.getArguments().getString("targetAppId"))

    @Test
    fun scroll() = rule.measureRepeated(
        packageName = packageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = { prepareEditor() },
    ) {
        val field = findEditor()
        field.setGestureMargin(8)
        repeat(3) {
            field.swipe(Direction.DOWN, 0.9f)
            field.swipe(Direction.UP, 0.9f)
        }
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun queryResponse() {
        assumeTrue(search)
        rule.measureRepeated(
            packageName = packageName,
            metrics = listOf(TraceSectionMetric("editor_search_response", TraceSectionMetric.Mode.Average)),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = { prepareEditor() },
        ) {
            query("不存在的词", "无匹配结果")
            query("目标", "1 /")
        }
    }

    private fun MacrobenchmarkScope.prepareEditor() {
        val unit = if (paragraphs) {
            "目标：这是一段用于滚动测试的中文内容，包含 English 和 emoji 🙂。\n"
        } else {
            "目标：这是一段用于滚动测试的中文内容，包含 English 和 emoji 🙂。"
        }
        val text = unit.repeat(length / unit.length + 1).take(length)
        if (surface.startsWith("form")) {
            startActivityAndWait(
                Intent().setClassName(packageName, "me.rerere.rikkahub.benchmark.EditorBenchmarkActivity")
                    .putExtra("text", text),
            )
        } else {
            startActivityAndWait()
            checkNotNull(device.wait(Until.findObject(By.res("chat_input")), 5_000)).apply {
                click()
                this.text = text
            }
        }
        if (surface.endsWith("fullscreen")) {
            // The fullscreen resource is localized; use search to enter consistently, then close it if unnecessary.
            checkNotNull(device.wait(Until.findObject(By.desc("查找")), 5_000)).click()
            if (search) {
                query("目标", "1 /")
            } else {
                checkNotNull(device.wait(Until.findObject(By.desc("关闭查找")), 5_000)).click()
            }
        }
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.query(term: String, expected: String) {
        val input = checkNotNull(device.wait(Until.findObject(By.res("editor-search-query")), 5_000))
        input.text = term
        check(device.wait(Until.hasObject(By.res("editor-search-count").textContains(expected)), 5_000))
    }

    private fun MacrobenchmarkScope.findEditor(): UiObject2 {
        val tag = when (surface) {
            "chat" -> "chat_input"
            "chat-fullscreen" -> "chat-input-fullscreen"
            "form" -> "text-area-inline"
            else -> "text-area-fullscreen"
        }
        return checkNotNull(device.wait(Until.findObject(By.res(tag)), 5_000))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}-paragraphs={2}-search={3}")
        fun scenarios(): List<Array<Any>> = buildList {
            for (surface in listOf("chat", "chat-fullscreen", "form", "form-fullscreen")) {
                for (length in listOf(10_000, 50_000)) {
                    for (paragraphs in listOf(true, false)) {
                        add(arrayOf(surface, length, paragraphs, false))
                        if (surface.endsWith("fullscreen")) add(arrayOf(surface, length, paragraphs, true))
                    }
                }
            }
        }
    }
}
