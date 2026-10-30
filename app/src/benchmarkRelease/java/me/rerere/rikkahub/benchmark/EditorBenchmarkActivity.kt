package me.rerere.rikkahub.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.ui.TextArea
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.ui.toast.rememberToasterState

/** Exercises the production TextArea without modifying a user's saved assistant settings. */
class EditorBenchmarkActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val content = intent.getStringExtra("text").orEmpty()
        setContent {
            RikkahubTheme {
                CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
                    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        TextArea(
                            state = rememberTextFieldState(content),
                            modifier = Modifier.safeDrawingPadding().padding(16.dp),
                            label = "长文本性能测试",
                        )
                    }
                }
            }
        }
    }
}
