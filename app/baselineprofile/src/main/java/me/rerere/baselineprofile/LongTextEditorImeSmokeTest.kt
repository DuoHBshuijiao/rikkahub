package me.rerere.baselineprofile

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test

/** Validate large fixture payloads and all background modes without treating a smoke run as a benchmark. */
class LongTextEditorImeSmokeTest {
    @Test
    fun shortAndLongMessagesOpenTheImeWithEachBackgroundEffect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = checkNotNull(InstrumentationRegistry.getArguments().getString("targetAppId"))
        require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
        val device = UiDevice.getInstance(instrumentation)
        for (length in listOf(100, 50_000)) {
            for (effect in listOf("off", "blur", "glass")) {
                device.executeShellCommand(
                    "am start -W -f 0x10008000 -n " +
                        "$packageName/me.rerere.rikkahub.benchmark.EditorBenchmarkActivity " +
                        "--ez chat true --ei length $length --es effect $effect --ez paragraphs true",
                )
                device.awaitEditorIme("ime-closed")
                val field = checkNotNull(device.wait(Until.findObject(By.res("chat_input")), 5_000))
                val actualLength = field.text?.length
                check(actualLength == length) { "Expected $length characters, got $actualLength ($effect)" }
                field.click()
                device.awaitEditorIme("ime-open")
                device.pressBack()
                device.awaitEditorIme("ime-closed")
            }
        }
    }
}

internal fun UiDevice.awaitEditorIme(description: String) {
    check(wait(Until.hasObject(By.res("editor-ime-state").desc(description)), 5_000)) {
        "IME did not settle: $description"
    }
    // Insets arrive before placement. Wait for accessibility bounds to settle as well, so
    // the next tap cannot use the text field's position from before IME dismissal.
    waitForIdle()
}
