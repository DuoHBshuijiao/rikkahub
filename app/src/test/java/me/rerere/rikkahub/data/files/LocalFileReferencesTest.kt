package me.rerere.rikkahub.data.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

class LocalFileReferencesTest {
    @get:Rule val temporary = TemporaryFolder()

    private val oldUri = "file:///data/user/0/me.rerere.rikkahub/files/upload/photo.png"

    @Test fun rebuildsAcrossPackagesUsersAndAndroidDirectoryAliases() {
        val root = temporary.newFolder("files")
        val references = LocalFileReferences(root)
        listOf(
            "/data/user/0/me.rerere.rikkahub",
            "/data/user/10/com.example.custom.debug",
            "/data/data/com.example.custom",
            "/data/user_de/10/com.example.custom",
        ).forEach { base ->
            assertEquals(
                File(root, "upload/photo.png").canonicalPath,
                references.rebuild("$base/files/upload/photo.png"),
            )
            assertEquals(
                File(root, "upload/photo.png").toURI().path,
                URI(references.rebuild("file://$base/files/upload/photo.png")).path,
            )
        }
        // The attachment has not been extracted yet; rebuilding must still work.
        assertFalse(File(root, "upload/photo.png").exists())
    }

    @Test fun preservesEncodedFileNamesAndIsIdempotent() {
        val root = temporary.newFolder("files")
        val references = LocalFileReferences(root)
        val old = "file:///data/user/0/old.package/files/upload/%E5%9B%BE%20%231%25.png"
        val rebuilt = references.rebuild(old)
        assertEquals(File(root, "upload/图 #1%.png").toURI().path, URI(rebuilt).path)
        assertEquals(rebuilt, references.rebuild(rebuilt))
    }

    @Test fun leavesExternalUnknownAndUnsafePathsUnchanged() {
        val references = LocalFileReferences(temporary.newFolder("files"))
        listOf(
            "https://example.com/files/upload/photo.png",
            "content://media/external/images/1",
            "data:image/png;base64,abcd",
            "/storage/emulated/0/Download/photo.png",
            "upload/photo.png",
            "/data/user/0/old.package/files/datastore/settings.preferences_pb",
            "/data/user/0/old.package/files/upload/../secret.png",
            "file:///data/user/0/old.package/files/upload/%2e%2e/secret.png",
            "file:///data/user/0/old.package/files/upload/%00.png",
            "file://remote/data/user/0/old.package/files/upload/photo.png",
        ).forEach { assertEquals(it, references.rebuild(it)) }
    }

    @Test fun rebuildsSettingsAvatarsBackgroundAndPresetAttachments() {
        val references = LocalFileReferences(temporary.newFolder("files"))
        val oldAvatar = buildJsonObject {
            put("type", "me.rerere.rikkahub.data.model.Avatar.Image")
            put("url", oldUri)
        }
        val settings = buildJsonObject {
            put("displaySetting", buildJsonObject { put("userAvatar", oldAvatar) })
            put("assistants", buildJsonArray {
                add(buildJsonObject {
                    put("id", "assistant")
                    put("avatar", oldAvatar)
                    put("background", oldUri)
                    put("systemPrompt", oldUri)
                    put("presetMessages", buildJsonArray {
                        add(buildJsonObject { put("parts", JsonArray(listOf(media("document")))) })
                    })
                })
            })
        }
        val rebuilt = Json.parseToJsonElement(references.rebuildJson(settings.toString())).jsonObject
        val avatar = rebuilt["displaySetting"]!!.jsonObject["userAvatar"]!!.jsonObject
        assertEquals(references.rebuild(oldUri), avatar["url"]!!.jsonPrimitive.content)
        val assistant = rebuilt["assistants"]!!.jsonArray.single().jsonObject
        assertEquals(references.rebuild(oldUri), assistant["background"]!!.jsonPrimitive.content)
        assertEquals(oldUri, assistant["systemPrompt"]!!.jsonPrimitive.content)
        val part = assistant["presetMessages"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray.single()
        assertEquals(references.rebuild(oldUri), part.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test fun rebuildsAllAlternativesAndNestedToolMediaWithoutChangingTextOrArguments() {
        val references = LocalFileReferences(temporary.newFolder("files"))
        val messages = JsonArray(listOf("image", "audio", "video", "document").map { type ->
            buildJsonObject {
                put("parts", buildJsonArray {
                    add(media(type))
                    add(buildJsonObject {
                        put("type", "tool")
                        put("input", media("image"))
                        put("output", JsonArray(listOf(media("image"))))
                    })
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", oldUri)
                    })
                })
            }
        })
        val result = references.rebuildJson(messages.toString())
        Json.parseToJsonElement(result).jsonArray.forEach { message ->
            val parts = message.jsonObject["parts"]!!.jsonArray
            assertEquals(references.rebuild(oldUri), parts[0].jsonObject["url"]!!.jsonPrimitive.content)
            val tool = parts[1].jsonObject
            assertEquals(oldUri, tool["input"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            assertEquals(
                references.rebuild(oldUri),
                tool["output"]!!.jsonArray.single().jsonObject["url"]!!.jsonPrimitive.content,
            )
            assertEquals(oldUri, parts[2].jsonObject["text"]!!.jsonPrimitive.content)
        }
        assertEquals(result, references.rebuildJson(result))
    }

    @Test fun preservesMalformedAndUnchangedJsonVerbatim() {
        val references = LocalFileReferences(temporary.newFolder("files"))
        listOf("{broken", "", "  [ ]\n", "{\"url\":\"$oldUri\"}").forEach {
            assertEquals(it, references.rebuildJson(it))
        }
    }

    private fun media(type: String): JsonObject = buildJsonObject {
        put("type", type)
        put("url", oldUri)
    }
}
