package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.DEFAULT_USER_PERSONA_ID
import me.rerere.rikkahub.data.model.UserPersona
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import kotlin.uuid.Uuid

@Composable
fun UserPersonaSheet(
    personas: List<UserPersona>,
    selectedId: Uuid,
    onSelect: (Uuid) -> Unit,
    onSave: (UserPersona, Boolean) -> Unit,
    onDelete: (Uuid) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<UserPersona?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<UserPersona?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("用户身份", style = MaterialTheme.typography.titleLargeEmphasized)
            CardGroup {
                personas.forEach { persona ->
                    item(
                        onClick = { onSelect(persona.id) },
                        headlineContent = {
                            Text(
                                persona.nickname.ifBlank { stringResource(R.string.user_default_name) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = if (persona.id == DEFAULT_USER_PERSONA_ID) {
                            { Text("默认身份") }
                        } else null,
                        leadingContent = {
                            UIAvatar(
                                name = persona.nickname,
                                value = persona.avatar,
                                modifier = Modifier.size(40.dp),
                                onClick = { onSelect(persona.id) },
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = persona.id == selectedId, onClick = null)
                                IconButton(onClick = {
                                    creating = false
                                    editing = persona
                                }) {
                                    Icon(HugeIcons.PencilEdit01, contentDescription = "编辑身份")
                                }
                                if (persona.id != DEFAULT_USER_PERSONA_ID) {
                                    IconButton(onClick = { deleting = persona }) {
                                        Icon(HugeIcons.Delete01, contentDescription = "删除身份")
                                    }
                                }
                            }
                        },
                    )
                }
            }
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    creating = true
                    editing = UserPersona()
                },
            ) {
                Text("新增身份")
            }
        }
    }

    editing?.let { persona ->
        UserPersonaEditor(
            persona = persona,
            creating = creating,
            onDismiss = { editing = null },
            onSave = {
                onSave(it, creating)
                editing = null
            },
        )
    }

    deleting?.let { persona ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除身份") },
            text = { Text("使用此身份的会话将改用默认身份。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(persona.id)
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun UserPersonaEditor(
    persona: UserPersona,
    creating: Boolean,
    onDismiss: () -> Unit,
    onSave: (UserPersona) -> Unit,
) {
    var draft by remember(persona.id) { mutableStateOf(persona) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "新增身份" else "编辑身份") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                UIAvatar(
                    name = draft.nickname,
                    value = draft.avatar,
                    onUpdate = { draft = draft.copy(avatar = it) },
                    modifier = Modifier.size(64.dp),
                )
                OutlinedTextField(
                    value = draft.nickname,
                    onValueChange = { draft = draft.copy(nickname = it) },
                    label = { Text("昵称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
