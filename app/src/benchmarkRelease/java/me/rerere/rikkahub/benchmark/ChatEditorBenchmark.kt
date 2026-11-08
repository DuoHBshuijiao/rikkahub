package me.rerere.rikkahub.benchmark

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRState
import me.rerere.rikkahub.data.datastore.BackgroundEffectType
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.pages.chat.ChatList
import me.rerere.rikkahub.ui.pages.chat.MessageSelection

/** Production chat components with an in-memory history; no chat service or settings writes. */
@Composable
internal fun ChatEditorBenchmark(content: String, effect: String) {
    val assistant = remember { Assistant(name = "性能测试") }
    val settings = remember(assistant, effect) {
        Settings(
            assistants = listOf(assistant),
            assistantId = assistant.id,
            providers = emptyList(),
            displaySetting = DisplaySetting(
                enableBlurEffect = effect != "off",
                backgroundEffectType = if (effect == "glass") BackgroundEffectType.GLASS else BackgroundEffectType.BLUR,
            ),
        )
    }
    val conversation = remember(assistant, content) {
        Conversation(
            assistantId = assistant.id,
            messageNodes = List(20) { index ->
                MessageNode(messages = listOf(UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(if (index == 10) content else "历史消息 $index\n".repeat(8))),
                )))
            },
        )
    }
    val input = remember(conversation) {
        ChatInputState().apply {
            editingMessage = conversation.messageNodes[10].currentMessage.id
            setContents(conversation.messageNodes[10].currentMessage.parts)
        }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 10)
    val haze = rememberHazeState()
    val ime = WindowInsets.ime
    val target = WindowInsets.imeAnimationTarget
    val density = LocalDensity.current
    val navigator = remember { Navigator(mutableListOf()) }
    val asr = remember {
        object : CustomAsrState {
            override val state = MutableStateFlow(ASRState())
            override fun start(onTranscriptChange: (String) -> Unit) = Unit
            override fun stop() = Unit
            override fun cleanup() = Unit
        }
    }
    SharedTransitionLayout {
        CompositionLocalProvider(
            LocalSettings provides settings,
            LocalNavController provides navigator,
            LocalSharedTransitionScope provides this,
            LocalASRState provides asr,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(title = {
                        Text("长文本性能测试", Modifier.testTag("editor-ime-state").semantics {
                            // Read animated insets in semantics, never in composition.
                            val current = ime.getBottom(density)
                            contentDescription = when {
                                current != target.getBottom(density) -> "ime-animating"
                                current > 0 -> "ime-open"
                                else -> "ime-closed"
                            }
                        })
                    })
                },
                bottomBar = {
                    ChatInput(
                        state = input,
                        loading = false,
                        settings = settings,
                        assistant = assistant,
                        chatModel = null,
                        hazeState = haze,
                        onUpdateChatModel = {},
                        onUpdateAssistant = {},
                        onMoreClick = {},
                        onCancelClick = {},
                        onSendClick = {},
                        onLongSendClick = {},
                    )
                },
            ) { padding ->
                ChatList(
                    innerPadding = padding,
                    isEditingMessage = input.isEditing(),
                    conversation = conversation,
                    state = listState,
                    loading = false,
                    previewMode = false,
                    onPreviewModeChange = {},
                    selection = MessageSelection(),
                    onSelectionChange = {},
                    onSelectMessage = {},
                    settings = settings,
                    hazeState = haze,
                )
            }
        }
    }
}
