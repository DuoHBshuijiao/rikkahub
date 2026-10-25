package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

@Serializable
enum class ExtensionEditType { QUICK_MESSAGE, MODE_INJECTION, LOREBOOK }

@Serializable
private data class ExtensionDraft(
    val quickMessage: QuickMessage? = null,
    val modeInjection: PromptInjection.ModeInjection? = null,
    val lorebook: Lorebook? = null,
) {
    val loaded get() = quickMessage != null || modeInjection != null || lorebook != null
}

/** A separate route lets the existing editors return directly to the originating chat. */
@Composable
fun ExtensionEditPage(type: ExtensionEditType, id: String) {
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    val navigator = LocalNavController.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var draft by rememberSaveable(type, id, stateSaver = Saver(
        save = { Json.encodeToString(ExtensionDraft.serializer(), it) },
        restore = { Json.decodeFromString(ExtensionDraft.serializer(), it) },
    )) { mutableStateOf(ExtensionDraft()) }

    LaunchedEffect(settings, type, id) {
        val loadedSettings = settings?.takeUnless { it.init } ?: return@LaunchedEffect
        if (draft.loaded) return@LaunchedEffect
        draft = when (type) {
            ExtensionEditType.QUICK_MESSAGE -> ExtensionDraft(quickMessage = loadedSettings.quickMessages.find { it.id.toString() == id })
            ExtensionEditType.MODE_INJECTION -> ExtensionDraft(modeInjection = loadedSettings.modeInjections.find { it.id.toString() == id })
            ExtensionEditType.LOREBOOK -> ExtensionDraft(lorebook = loadedSettings.lorebooks.find { it.id.toString() == id })
        }
        if (!draft.loaded) {
            toaster.show("该扩展已不存在")
            navigator.popBackStack()
        }
    }

    fun dismiss() {
        if (!saving) navigator.popBackStack()
    }

    fun save(updated: ExtensionDraft) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                store.update { latest ->
                    when (type) {
                        ExtensionEditType.QUICK_MESSAGE -> {
                            require(latest.quickMessages.any { it.id.toString() == id }) { "该快捷消息已被删除" }
                            latest.copy(quickMessages = latest.quickMessages.map { if (it.id.toString() == id) updated.quickMessage!! else it })
                        }
                        ExtensionEditType.MODE_INJECTION -> {
                            require(latest.modeInjections.any { it.id.toString() == id }) { "该提示词注入已被删除" }
                            latest.copy(modeInjections = latest.modeInjections.map { if (it.id.toString() == id) updated.modeInjection!! else it })
                        }
                        ExtensionEditType.LOREBOOK -> {
                            require(latest.lorebooks.any { it.id.toString() == id }) { "该世界书已被删除" }
                            latest.copy(lorebooks = latest.lorebooks.map { if (it.id.toString() == id) updated.lorebook!! else it })
                        }
                    }
                }
                navigator.popBackStack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toaster.show("保存失败：${e.message}")
            } finally {
                saving = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {}
    draft.quickMessage?.let { message ->
        EditQuickMessageDialog(
            title = stringResource(R.string.quick_messages_page_edit_title),
            initialQuickMessage = message,
            onDismiss = ::dismiss,
            onConfirm = { title, content -> save(draft.copy(quickMessage = message.copy(title = title, content = content))) },
        )
    }
    draft.modeInjection?.let { injection ->
        ModeInjectionEditSheet(
            injection = injection,
            onDismiss = ::dismiss,
            onConfirm = { save(draft) },
            onEdit = { draft = draft.copy(modeInjection = it) },
        )
    }
    draft.lorebook?.let { book ->
        LorebookEditSheet(
            book = book,
            onDismiss = ::dismiss,
            onConfirm = { save(draft) },
            onEdit = { draft = draft.copy(lorebook = it) },
        )
    }
}
