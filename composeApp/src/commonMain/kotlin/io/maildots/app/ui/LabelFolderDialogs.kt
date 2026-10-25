package io.maildots.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.maildots.app.LabelColors
import io.maildots.app.LabelInfo
import io.maildots.app.mail.Folder
import io.maildots.app.mail.MailLabel

/** Etiket tanımları (renkler); uygulama durumundan sağlanır. */
val LocalLabels = staticCompositionLocalOf { LabelColors.DEFAULTS }

/** Etiketin rengi: bu cihazdaki tanımdan; tanım yoksa etikete göre sabit bir renk. */
@Composable
fun MailLabel.color(): Color =
    Color(LocalLabels.current.firstOrNull { it.label == this }?.color ?: LabelColors.fallback(this))

/** Etiketin adı: tanımdaki ad (hesap servisi), yoksa hazır etiket adı ya da kullanıcının verdiği ad. */
@Composable
fun labelTitle(label: MailLabel): String =
    LocalLabels.current.firstOrNull { it.label == label }?.name?.takeIf { it.isNotBlank() }
        ?: LocalStrings.current.labelName(label)

/**
 * İletideki anahtar sözcüklerden gösterilecek olanlar: tanımı olan etiketler ve
 * uygulamanın kendi etiketleri. Başka istemcilerin anahtar sözcükleri gösterilmez.
 */
@Composable
fun List<MailLabel>.shown(): List<MailLabel> {
    val defined = LocalLabels.current
    return filter { label -> defined.any { it.label == label } || label.customName != null }
}

/**
 * Etiket oluşturma / düzenleme. [existing] null ise yeni etiket (ad + renk);
 * değilse yalnızca renk değişir, kullanıcı etiketi ise silinebilir.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelEditDialog(
    existing: LabelInfo?,
    onDismiss: () -> Unit,
    onSave: (name: String, color: Long) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(existing?.color ?: LabelColors.PALETTE[6]) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete && existing != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(s.deleteLabel) },
            text = { Text(s.deleteLabelBody) },
            confirmButton = { TextButton(onClick = onDelete) { Text(s.delete, color = colors.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(s.cancel) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) s.newLabel else labelTitle(existing.label)) },
        text = {
            Column {
                if (existing == null) {
                    OutlinedTextField(
                        name,
                        { name = it.take(MailLabel.MAX_NAME_LENGTH) },
                        label = { Text(s.labelNameField) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(18.dp))
                }
                Text(s.labelColor, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabelColors.PALETTE.forEach { option ->
                        val selected = option == color
                        Box(
                            Modifier
                                .size(40.dp)
                                .then(if (selected) Modifier.border(2.dp, colors.onSurface, CircleShape) else Modifier)
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(Color(option))
                                .clickable { color = option },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (existing != null && onDelete != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { confirmDelete = true }) { Text(s.deleteLabel, color = colors.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = existing != null || name.isNotBlank(), onClick = { onSave(name, color) }) {
                Text(if (existing == null) s.create else s.save)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/** Kullanıcı klasörü: yeniden adlandır ya da sil (iletiler çöpe taşınır). */
@Composable
fun FolderEditDialog(folder: Folder, onDismiss: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(folder.name) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(s.deleteFolder) },
            text = { Text(s.deleteFolderBody) },
            confirmButton = { TextButton(onClick = onDelete) { Text(s.delete, color = colors.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(s.cancel) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.editFolder) },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text(s.folderName) }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { confirmDelete = true }) { Text(s.deleteFolder, color = colors.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && name.trim() != folder.name, onClick = { onRename(name) }) { Text(s.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}
