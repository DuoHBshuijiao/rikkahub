package me.rerere.rikkahub.benchmark

import android.os.Bundle
import android.view.WindowManager
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

/** Exercises production editors without modifying saved conversations or assistant settings. */
class EditorBenchmarkActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val content = intent.getStringExtra("text") ?: run {
            // Macrobenchmark starts activities through `am`: long Unicode extras exceed its
            // argument-size limit. Generate the IME fixture inside the app instead.
            val length = intent.getIntExtra("length", 0)
            val unit = "这是一段用于输入法动画测试的中文内容，包含 English 和 emoji 🙂。" +
                if (intent.getBooleanExtra("paragraphs", true)) "\n" else ""
            unit.repeat(length / unit.length + 1).take(length)
        }
        setContent {
            RikkahubTheme {
                CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
                    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        if (intent.getBooleanExtra("chat", false)) {
                            ChatEditorBenchmark(
                                content = content,
                                effect = intent.getStringExtra("effect") ?: "off",
                            )
                        } else {
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
}
