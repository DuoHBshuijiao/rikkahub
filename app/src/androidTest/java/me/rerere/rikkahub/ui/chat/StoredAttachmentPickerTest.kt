package me.rerere.rikkahub.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ai.ChatFilePickerSheet
import me.rerere.rikkahub.ui.components.ai.StoredAttachment
import me.rerere.rikkahub.ui.components.ai.StoredAttachmentPickerContent
import me.rerere.rikkahub.ui.components.ai.StoredAttachmentPickerState
import me.rerere.rikkahub.ui.components.ai.storedAttachmentKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.io.File

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class StoredAttachmentPickerTest {
    @get:Rule val compose = createComposeRule()

    private fun attachment(id: Long): StoredAttachment {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "历史附件$id.txt"
        return StoredAttachment(
            ManagedFileEntity(
                id = id,
                folder = "upload",
                relativePath = "upload/$name",
                displayName = name,
                mimeType = "text/plain",
                sizeBytes = 128,
                createdAt = 0,
                updatedAt = 0,
            ),
            File(context.cacheDir, name),
        )
    }

    private fun content(
        state: StoredAttachmentPickerState,
        dark: Boolean = false,
        fontScale: Float = 1f,
        onAdd: () -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialExpressiveTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.requiredSize(320.dp, 400.dp)) {
                        StoredAttachmentPickerContent(state, onAdd)
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = File(context.getExternalFilesDir("attachment-picker-previews"), "$name.png")
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test
    fun internalSourceReusesSavedImageAndDocumentAndKeepsSelectionWhenGoingBack() {
        val manager = GlobalContext.get().get<FilesManager>()
        val sources = mutableListOf<ManagedFileEntity>()
        val visible = mutableStateOf(true)
        var delivered: List<UIMessagePart> = emptyList()
        try {
            runBlocking {
                val image = Bitmap.createBitmap(
                    96,
                    96,
                    Bitmap.Config.ARGB_8888
                ).apply { eraseColor(0xFF80CBC4.toInt()) }
                val png = ByteArrayOutputStream().use { output ->
                    image.compress(Bitmap.CompressFormat.PNG, 100, output)
                    image.recycle()
                    output.toByteArray()
                }
                sources.add(manager.saveManagedFromBytes(FileFolders.UPLOAD, png, "复用测试图片.png", "image/png"))
                sources.add(manager.saveManagedText(FileFolders.UPLOAD, "Saved document", "复用测试文档.txt"))
            }
            val before = runBlocking { manager.countChatFiles() }
            compose.setContent {
                MaterialExpressiveTheme {
                    if (visible.value) {
                        ChatFilePickerSheet(
                            workspaceId = null,
                            existingAttachments = emptyList(),
                            onPickLocal = {},
                            onAttachmentsAdded = {
                                delivered = it
                                visible.value = false
                            },
                            onDismiss = { visible.value = false },
                        )
                    }
                }
            }
            compose.onNodeWithText("从内部储存添加").assertIsDisplayed()
            capture("source")
            compose.onNodeWithText("从内部储存添加").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(sources.first().displayName).fetchSemanticsNodes().isNotEmpty()
            }
            sources.forEach { compose.onNodeWithText(it.displayName).performScrollTo().performClick() }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("从内部储存添加").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(sources.first().displayName).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("添加所选附件（2）").assertIsEnabled()
            capture("library")
            compose.onNodeWithText("添加所选附件（2）").performClick()
            compose.waitUntil { !visible.value }
            compose.runOnIdle {
                assertEquals(2, delivered.size)
                assertTrue(delivered.first() is UIMessagePart.Document)
                assertTrue(delivered.last() is UIMessagePart.Image)
                assertEquals("复用测试文档.txt", (delivered.first() as UIMessagePart.Document).fileName)
                assertEquals("text/plain", (delivered.first() as UIMessagePart.Document).mime)
            }
            assertEquals(before, runBlocking { manager.countChatFiles() })
            assertEquals(
                sources.map { manager.getFile(it).canonicalPath }.toSet(),
                storedAttachmentKeys(delivered),
            )
            assertTrue(sources.all { manager.getFile(it).isFile })
        } finally {
            runBlocking { sources.forEach { manager.delete(it.id) } }
        }
    }

    @Test
    fun wholeRowsSelectMultipleAttachmentsAndConfirmOnSmallScreen() {
        val files = (1L..12L).map(::attachment)
        val state = StoredAttachmentPickerState().apply {
            attachments = files
            loading = false
        }
        var selected: Set<String> = emptySet()
        content(state, onAdd = { selected = state.pendingSelection })
        compose.onNodeWithText("添加所选附件（0）").assertIsNotEnabled()
        compose.onNodeWithText(files.first().entity.displayName).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(files.lastIndex)
        compose.onNodeWithText(files.last().entity.displayName).performClick()
        compose.onNodeWithText("添加所选附件（2）").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(setOf(files.first().key, files.last().key), selected) }
        capture("light-small")
    }

    @Test
    fun alreadyAddedFileCannotBeSelectedAndConfirmStaysReachableWithLargeFontInDarkTheme() {
        val file = attachment(1)
        val state = StoredAttachmentPickerState().apply {
            attachments = listOf(file, attachment(2))
            alreadyAdded = setOf(file.key)
            loading = false
        }
        content(state, dark = true, fontScale = 2f)
        compose.onNodeWithText("已添加").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(file.entity.displayName).assertIsNotEnabled().assertIsOn()
        compose.onNodeWithText("历史附件2.txt").performScrollTo().performClick()
        compose.onNodeWithText("添加所选附件（1）").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(setOf(state.attachments.last().key), state.selected) }
        capture("dark-large-font")
    }

    @Test
    fun emptyLibraryAndLoadingDisableConfirmation() {
        val state = StoredAttachmentPickerState()
        content(state)
        compose.onNodeWithText("添加所选附件（0）").assertIsNotEnabled()
        compose.runOnIdle { state.loading = false }
        compose.onNodeWithText("暂无可复用的图片或文档").assertIsDisplayed()
        compose.onNodeWithText("添加所选附件（0）").assertIsNotEnabled()
    }

    @Test
    fun readingFailureOffersRetryAndAddingPreventsSelectionChanges() {
        val file = attachment(1)
        val state = StoredAttachmentPickerState().apply {
            loading = false
            loadError = "无法读取内部附件：请重试"
        }
        content(state)
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle {
            assertEquals(1, state.retry)
            state.loadError = null
            state.attachments = listOf(file)
            state.selected = setOf(file.key)
            state.adding = true
        }
        compose.onNodeWithText(file.entity.displayName).assertIsNotEnabled()
        compose.onNodeWithText("正在添加附件…").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(file.key in state.selected) }
    }
}
