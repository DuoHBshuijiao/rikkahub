package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

val DEFAULT_USER_PERSONA_ID = Uuid.parse("1281a128-c134-4f74-b799-570ef86a1710")

@Serializable
data class UserPersona(
    val id: Uuid = Uuid.random(),
    val nickname: String = "",
    val avatar: Avatar = Avatar.Dummy,
) {
    val placeholderName: String get() = nickname.ifBlank { "user" }
}

fun Settings.getUserPersona(id: Uuid): UserPersona =
    userPersonas.find { it.id == id }
        ?: userPersonas.find { it.id == DEFAULT_USER_PERSONA_ID }
        ?: UserPersona(
            id = DEFAULT_USER_PERSONA_ID,
            nickname = displaySetting.userNickname,
            avatar = displaySetting.userAvatar,
        )

fun Settings.getCurrentUserPersona(): UserPersona = getUserPersona(selectedUserPersonaId)

fun Settings.getUserPersonaOf(conversation: Conversation): UserPersona =
    if (conversation.config == null) getCurrentUserPersona() else getUserPersona(conversation.config.userPersonaId)

internal fun Settings.withUserPersonaDefaults(): Settings {
    val personas = userPersonas.distinctBy { it.id }.toMutableList()
    if (personas.none { it.id == DEFAULT_USER_PERSONA_ID }) {
        personas.add(0, getUserPersona(DEFAULT_USER_PERSONA_ID))
    }
    return copy(
        userPersonas = personas,
        selectedUserPersonaId = selectedUserPersonaId.takeIf { id -> personas.any { it.id == id } }
            ?: DEFAULT_USER_PERSONA_ID,
    )
}

fun Settings.withoutUserPersona(id: Uuid): Settings {
    if (id == DEFAULT_USER_PERSONA_ID) return this
    return copy(userPersonas = userPersonas.filterNot { it.id == id }).withUserPersonaDefaults()
}
