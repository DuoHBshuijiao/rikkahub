package me.rerere.rikkahub.ui.setting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.pages.setting.CleanFilesSheet
import me.rerere.rikkahub.ui.pages.setting.CleanRange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class CleanFilesSheetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun defaultScopeAndWholeRowSwitchWorkOnSmallScreen() {
        val enabled = mutableStateOf(false)
        compose.setContent {
            MaterialExpressiveTheme {
                Box(Modifier.requiredSize(320.dp, 480.dp)) {
                    CleanFilesSheet(CleanRange.DAYS_7, {}, enabled.value, { enabled.value = it }, {})
                }
            }
        }
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText(context.getString(R.string.setting_files_page_all_cleanup_warning))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.setting_files_page_only_conversation_attachments))
            .performScrollTo().performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText(context.getString(R.string.setting_files_page_all_cleanup_warning))
            .assertDoesNotExist()
    }

    @Test fun allRangesAndCleanActionRemainReachableWithLargeFont() {
        val range = mutableStateOf(CleanRange.DAYS_7)
        var cleanedRange: CleanRange? = null
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                MaterialExpressiveTheme {
                    Box(Modifier.requiredSize(320.dp, 480.dp)) {
                        CleanFilesSheet(range.value, { range.value = it }, true, {}, { cleanedRange = range.value })
                    }
                }
            }
        }
        for (days in listOf(7, 14, 30)) {
            compose.onNodeWithText(context.getString(R.string.setting_files_page_clean_older_than_days, days))
                .performScrollTo().assertIsDisplayed().performClick()
        }
        compose.onNodeWithText(context.getString(R.string.setting_files_page_clean_all))
            .performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.setting_files_page_clean_action))
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(CleanRange.ALL, cleanedRange) }
    }
}
