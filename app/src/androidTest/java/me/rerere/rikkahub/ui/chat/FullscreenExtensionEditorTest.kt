package me.rerere.rikkahub.ui.chat

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.TextArea
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.ui.toast.rememberToasterState
import org.junit.Rule
import org.junit.Test

class FullscreenExtensionEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longDraftSurvivesFullscreenRestorationAndReturnsToForm() {
        val restoration = StateRestorationTester(compose)
        val content = "长文本草稿\n".repeat(2000)
        restoration.setContent {
            val draft = rememberTextFieldState("原始内容")
            CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
                RikkahubTheme { TextArea(state = draft, label = "注入内容") }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fullscreenLabel = context.getString(R.string.text_area_fullscreen_edit)
        compose.onNodeWithContentDescription(fullscreenLabel).performClick()
        compose.onNodeWithTag("text-area-fullscreen").performTextReplacement(content)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("text-area-fullscreen").assertTextContains(content)
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithContentDescription(fullscreenLabel).performClick()
        compose.onNodeWithTag("text-area-fullscreen").assertTextContains(content)
    }
}
