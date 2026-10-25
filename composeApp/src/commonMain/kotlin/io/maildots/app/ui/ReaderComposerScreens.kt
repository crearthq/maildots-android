package io.maildots.app.ui

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.maildots.app.E2EProblem
import io.maildots.app.E2EStatus
import io.maildots.app.SendConsent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import io.maildots.app.mail.AttachmentPolicy
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import io.maildots.app.mail.MailLabel
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.maildots.app.AppController
import io.maildots.app.AppState
import io.maildots.app.ComposeMode
import io.maildots.app.Screen
import io.maildots.app.mail.Attachment
import io.maildots.app.mail.FolderRole
import io.maildots.app.mail.MessageDetail
import io.maildots.app.mail.ComposeRules
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.MoveToInbox
import io.maildots.app.ComposeFields
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.ArrowDropDown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(state: AppState, controller: AppController, platform: Platform) {
    val s = LocalStrings.current
    val reader = state.reader
    val detail = reader.detail
    val colors = MaterialTheme.colorScheme
    val systemDark = LocalDarkTheme.current
    // İleti gövdesinin teması: null = sistem; menüden açık/koyu zorlanabilir.
    var forceLight by remember(detail?.summary?.uid) { mutableStateOf<Boolean?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    var labelOpen by remember { mutableStateOf(false) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var confirmForever by remember { mutableStateOf(false) }
    val more = s.extra
    val role = detail?.let { d -> state.mailbox.folders.firstOrNull { it.path == d.summary.folderPath }?.role }
    val linked = state.active?.webLinked == true

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = { controller.back() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = s.back)
                        }
                    },
                    actions = {
                        if (detail != null) {
                            val msg = detail.summary
                            IconButton(onClick = { controller.toggleFlag(msg) }) {
                                Icon(
                                    if (detail.summary.flagged) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                    contentDescription = if (detail.summary.flagged) s.unstar else s.star,
                                    tint = if (detail.summary.flagged) colors.primary else colors.onSurface,
                                )
                            }
                            IconButton(onClick = { controller.archiveMessage(msg) }) {
                                Icon(Icons.Outlined.Archive, contentDescription = s.archive)
                            }
                            IconButton(onClick = { if (role == FolderRole.TRASH) confirmForever = true else controller.deleteMessage(msg) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = s.delete)
                            }
                            IconButton(onClick = { controller.toggleSeen(msg) }) {
                                Icon(Icons.Outlined.MarkEmailUnread, contentDescription = s.markUnread)
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = s.more)
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    val showingLight = forceLight ?: !systemDark
                                    MenuItem(Icons.AutoMirrored.Outlined.DriveFileMove, s.moveTo) {
                                        menuOpen = false
                                        moveOpen = true
                                    }
                                    MenuItem(Icons.AutoMirrored.Outlined.Label, s.labelAction) {
                                        menuOpen = false
                                        labelOpen = true
                                    }
                                    HorizontalDivider(color = colors.outlineVariant)
                                    MenuItem(
                                        if (showingLight) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                                        if (showingLight) s.viewDark else s.viewLight,
                                    ) {
                                        menuOpen = false
                                        forceLight = !showingLight
                                    }
                                    // Şifreli ileti çeviri servisine (sunucuya) gönderilmez.
                                    if (linked && !detail.decrypted && !detail.summary.encrypted) {
                                        MenuItem(Icons.Outlined.Translate, more.translate) {
                                            menuOpen = false
                                            controller.translate(platform.language)
                                        }
                                    }
                                    MenuItem(Icons.Outlined.Print, s.print) {
                                        menuOpen = false
                                        platform.print(detail.summary.subject.ifBlank { s.noSubject }, detail.html, detail.text)
                                    }
                                    HorizontalDivider(color = colors.outlineVariant)
                                    if (role == FolderRole.JUNK) {
                                        MenuItem(Icons.Outlined.MoveToInbox, more.notSpam) {
                                            menuOpen = false
                                            controller.notSpam(msg)
                                        }
                                    } else {
                                        MenuItem(Icons.Outlined.Report, s.reportSpam, tint = colors.error) {
                                            menuOpen = false
                                            controller.spamMessage(msg)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
                )
                if (reader.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                else HorizontalDivider(color = colors.outlineVariant)
            }
        },
        // Yanıtla / İlet: ekranın altında sabit.
        bottomBar = {
            if (detail != null) {
                Surface(color = colors.background) {
                    Column {
                        HorizontalDivider(color = colors.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            BottomAction(Icons.AutoMirrored.Outlined.Reply, s.reply, Modifier.weight(1f)) {
                                controller.openComposer(ComposeMode.REPLY, detail)
                            }
                            val everyone = remember(detail, state.active?.email) {
                                ComposeRules.replyRecipients(detail, state.active?.email.orEmpty(), all = true)
                                    .let { (to, cc) -> to.size + cc.size }
                            }
                            if (everyone > 1) {
                                BottomAction(Icons.AutoMirrored.Outlined.ReplyAll, more.replyAll, Modifier.weight(1f)) {
                                    controller.openComposer(ComposeMode.REPLY_ALL, detail)
                                }
                            }
                            BottomAction(Icons.AutoMirrored.Outlined.Forward, s.forward, Modifier.weight(1f)) {
                                controller.openComposer(ComposeMode.FORWARD, detail)
                            }
                        }
                    }
                }
            }
        },
        containerColor = colors.background,
    ) { padding ->
        when {
            reader.error != null -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), Alignment.Center) {
                Text(s.error(reader.error), color = colors.onSurfaceVariant)
            }
            detail != null -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            ) {
                val m = detail.summary
                SelectionContainer {
                    Text(
                        m.subject.ifBlank { s.noSubject },
                        Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                val shownLabels = m.labels.shown()
                if (shownLabels.isNotEmpty()) {
                    Row(
                        Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) { shownLabels.forEach { LabelChip(it) } }
                }
                Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    SenderAvatar(m.from)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.from.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            m.from.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(platform.formatFullDate(m.dateMillis), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    val recipients = (m.to + detail.cc).joinToString(", ") { it.label }
                    if (recipients.isNotEmpty()) {
                        Text(
                            "${s.toMe}: $recipients",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(top = 16.dp), color = colors.outlineVariant)

                if (detail.decrypted) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(s.e2e.decryptedNote, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            s.e2e.signature[detail.signature]?.let { verdict ->
                                val good = detail.signature == io.maildots.app.e2e.SignatureVerdict.VERIFIED
                                val soft = detail.signature in setOf(
                                    io.maildots.app.e2e.SignatureVerdict.VERIFIED_BODY_ONLY,
                                    io.maildots.app.e2e.SignatureVerdict.UNKNOWN_KEY,
                                    io.maildots.app.e2e.SignatureVerdict.UNSIGNED,
                                    io.maildots.app.e2e.SignatureVerdict.NOT_ADDRESSED,
                                    io.maildots.app.e2e.SignatureVerdict.WRONG_ADDRESS,
                                )
                                Text(
                                    verdict,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (good) colors.primary else if (soft) colors.onSurfaceVariant else colors.error,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                }
                reader.e2eProblem?.let { problem ->
                    E2EPanel(problem, reader.unlocking) { password ->
                        state.active?.email?.let { controller.unlockE2E(it, password) }
                    }
                }
                if (reader.translating || reader.translatedHtml != null || reader.translateFailed) {
                    TranslateBanner(reader, controller)
                }
                val html = reader.translatedHtml ?: detail.html
                if (!html.isNullOrBlank()) {
                    platform.htmlBody(html, forceLight ?: !systemDark, detail.htmlBaseUrl, Modifier.fillMaxWidth().heightIn(min = 160.dp))
                } else {
                    SelectionContainer {
                        Text(detail.text.orEmpty(), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }

                // Ekler: iletinin altında, yatay kaydırılabilir kartlar.
                if (detail.attachments.isNotEmpty()) {
                    HorizontalDivider(color = colors.outlineVariant)
                    Text(
                        "${s.attachments} (${detail.attachments.size})",
                        Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        detail.attachments.forEachIndexed { index, attachment ->
                            AttachmentCard(
                                attachment,
                                opening = reader.openingAttachment == index,
                                enabled = reader.openingAttachment == null,
                            ) { controller.openAttachment(index, platform.openAttachment) }
                        }
                    }
                    reader.attachmentError?.let {
                        Text(
                            s.error(it),
                            Modifier.padding(horizontal = 20.dp),
                            color = colors.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (reader.thread.size > 1) {
                    ThreadList(reader.thread, detail, platform, controller)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmForever && detail != null) {
        AlertDialog(
            onDismissRequest = { confirmForever = false },
            title = { Text(more.deleteForever) },
            text = { Text(more.deleteForeverBody(1)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForever = false
                    controller.deleteMessage(detail.summary)
                }) { Text(s.delete, color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForever = false }) { Text(s.cancel) } },
        )
    }

    if (labelOpen && detail != null) LabelDialog(detail, controller) { labelOpen = false }

    if (newFolderOpen && detail != null) {
        AlertDialog(
            onDismissRequest = { newFolderOpen = false },
            title = { Text(s.newFolder) },
            text = {
                OutlinedTextField(newFolderName, { newFolderName = it.take(60) }, label = { Text(s.folderName) }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    enabled = newFolderName.isNotBlank(),
                    onClick = {
                        newFolderOpen = false
                        controller.createFolder(newFolderName, moveHere = detail.summary)
                        newFolderName = ""
                    },
                ) { Text(s.create) }
            },
            dismissButton = { TextButton(onClick = { newFolderOpen = false }) { Text(s.cancel) } },
        )
    }

    if (moveOpen && detail != null) {
        val folders = state.mailbox.folders.filter { it.path != detail.summary.folderPath }
        AlertDialog(
            onDismissRequest = { moveOpen = false },
            title = { Text(s.moveTo) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                moveOpen = false
                                newFolderOpen = true
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(s.newFolder, style = MaterialTheme.typography.bodyLarge, color = colors.primary)
                    }
                    folders.forEach { folder ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    moveOpen = false
                                    controller.moveMessage(detail.summary, folder.path)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(folder.role.icon(), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(s.folderName(folder.role, folder.name), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveOpen = false }) { Text(s.cancel) } },
        )
    }
}

/** Çeviri durumu: çevriliyor / çevrildi (orijinali göster) / yapılamadı. */
@Composable
private fun TranslateBanner(reader: io.maildots.app.ReaderState, controller: AppController) {
    val m = LocalStrings.current.extra
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .background(colors.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Translate, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                reader.translating -> m.translating
                reader.translateFailed -> m.translateFailed
                else -> m.translatedFrom(reader.translatedFrom)
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (reader.translatedHtml != null) {
            TextButton(onClick = { controller.showOriginal() }) { Text(m.showOriginal) }
        }
    }
}

/** Konuşmadaki diğer iletiler: dokununca açılır; açık olan vurgulanır. */
@Composable
private fun ThreadList(items: List<io.maildots.app.web.WebThreadItem>, detail: MessageDetail, platform: Platform, controller: AppController) {
    val m = LocalStrings.current.extra
    val colors = MaterialTheme.colorScheme
    HorizontalDivider(color = colors.outlineVariant)
    Text(
        m.conversation(items.size),
        Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceVariant,
    )
    items.forEach { item ->
        val current = item.uid == detail.summary.uid && item.folder == detail.summary.folderPath
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !current) { controller.openThreadMessage(item) }
                .background(if (current) colors.surfaceContainerHigh else Color.Transparent)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (item.outgoing) m.you else (item.from?.label ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.snippet.isNotBlank()) {
                    Text(item.snippet, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (item.dateMillis > 0) {
                Spacer(Modifier.width(12.dp))
                Text(platform.formatShortDate(item.dateMillis), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** Etiket seçimi: işaretle/kaldır (hemen uygulanır) ya da yeni etiket oluştur. */
@Composable
private fun LabelDialog(detail: MessageDetail, controller: AppController, onClose: () -> Unit) {
    val s = LocalStrings.current
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        LabelEditDialog(existing = null, onDismiss = { creating = false }, onSave = { name, color ->
            creating = false
            controller.createLabel(name, color, applyTo = detail.summary)
        })
        return
    }
    // Tanımlı etiketler + bu iletide olup bu cihazda tanımı olmayanlar.
    val all = (LocalLabels.current.map { it.label } + detail.summary.labels.shown()).distinct()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.labelAction) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                all.forEach { label ->
                    val on = label in detail.summary.labels
                    Row(
                        Modifier.fillMaxWidth().clickable { controller.setLabel(detail.summary, label, !on) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { controller.setLabel(detail.summary, label, it) })
                        Box(Modifier.size(12.dp).clip(CircleShape).background(label.color()))
                        Spacer(Modifier.width(10.dp))
                        Text(labelTitle(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s.done) } },
        dismissButton = { TextButton(onClick = { creating = true }) { Text(s.newLabel) } },
    )
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    val color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    DropdownMenuItem(
        text = { Text(label, color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

@Composable
private fun BottomAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.height(48.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun Attachment.kindLabel(): String {
    val ext = name.substringAfterLast('.', "").uppercase()
    return when {
        ext.isNotEmpty() && ext.length <= 5 -> ext
        else -> mimeType.substringAfter('/').uppercase().take(8)
    }
}

private fun Attachment.icon(): ImageVector = when {
    mimeType == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> Icons.Outlined.PictureAsPdf
    mimeType.startsWith("image/") -> Icons.Outlined.Image
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable
private fun AttachmentCard(attachment: Attachment, opening: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.width(168.dp).clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                attachment.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 44.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (opening) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(s.opening, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                } else {
                    Icon(attachment.icon(), contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${attachment.kindLabel()} · ${formatSize(attachment.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${(bytes * 10 / 1_048_576) / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** Yanıt için alıntı gövde (düz metin yoksa HTML'den çıkarılır). */
private fun quote(detail: MessageDetail, wrote: String, date: String): String {
    val original = ComposeRules.plainText(detail).ifBlank { return "" }
    val quoted = original.lineSequence().take(200).joinToString("\n") { "> $it" }
    val who = detail.summary.from.label
    return "\n\n" + (if (date.isNotEmpty()) "$date, " else "") + "$who $wrote\n$quoted"
}

/** İletme için özgün iletinin başlığı ve metni. */
private fun forwarded(detail: MessageDetail, s: Strings, date: String): String {
    val m = detail.summary
    return buildString {
        append("\n\n").append(s.forwardedHeader).append('\n')
        append(m.from.label).append(" <").append(m.from.email).append(">\n")
        if (date.isNotEmpty()) append(date).append('\n')
        append(s.subject).append(": ").append(m.subject).append("\n\n")
        append(ComposeRules.plainText(detail))
    }
}

/** Alıcı alanı: yazdıkça öneriler gösterilir; öneri seçilince son yazılan parçanın yerine geçer. */
private fun acceptSuggestion(current: String, email: String): String {
    val cut = maxOf(current.lastIndexOf(','), current.lastIndexOf(';'))
    val head = if (cut >= 0) current.substring(0, cut + 1).trimEnd() + " " else ""
    return "$head$email, "
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerScreen(state: AppState, screen: Screen.Composer, controller: AppController, platform: Platform) {
    val s = LocalStrings.current
    val m = s.extra
    val colors = MaterialTheme.colorScheme
    val source = screen.source
    val self = state.active?.email.orEmpty()
    val replying = screen.mode == ComposeMode.REPLY || screen.mode == ComposeMode.REPLY_ALL
    val initialRecipients = remember(screen) {
        when {
            source == null -> "" to ""
            replying -> ComposeRules.replyRecipients(source, self, all = screen.mode == ComposeMode.REPLY_ALL)
                .let { (to, cc) -> to.joinToString(", ") to cc.joinToString(", ") }
            screen.mode == ComposeMode.DRAFT ->
                source.summary.to.joinToString(", ") { it.email } to source.cc.joinToString(", ") { it.email }
            else -> "" to ""
        }
    }
    var to by rememberSaveable { mutableStateOf(initialRecipients.first) }
    var cc by rememberSaveable { mutableStateOf(initialRecipients.second) }
    var bcc by rememberSaveable { mutableStateOf("") }
    var showCopies by rememberSaveable { mutableStateOf(cc.isNotBlank()) }
    var subject by rememberSaveable {
        mutableStateOf(
            when (screen.mode) {
                ComposeMode.REPLY, ComposeMode.REPLY_ALL -> ComposeRules.prefixed(source?.summary?.subject.orEmpty(), s.replyPrefix, "Re:")
                ComposeMode.FORWARD -> ComposeRules.prefixed(source?.summary?.subject.orEmpty(), s.forwardPrefix, "Fwd:")
                ComposeMode.DRAFT -> source?.summary?.subject.orEmpty()
                ComposeMode.NEW -> ""
            },
        )
    }
    // İmza: yazılacak metnin altında, alıntının / iletilen metnin üstünde durur (taslakta zaten vardır).
    val signature = state.active
        ?.takeIf { screen.mode != ComposeMode.DRAFT }
        ?.takeIf { if (screen.mode == ComposeMode.NEW) it.signatureOnNew else it.signatureOnReply }
        ?.signature?.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
    var body by rememberSaveable {
        mutableStateOf(
            when (screen.mode) {
                ComposeMode.DRAFT -> source?.let { ComposeRules.plainText(it) }.orEmpty()
                else -> signature + when (screen.mode) {
                    ComposeMode.REPLY, ComposeMode.REPLY_ALL ->
                        source?.let { quote(it, s.wrote, if (it.summary.dateMillis > 0) platform.formatFullDate(it.summary.dateMillis) else "") }.orEmpty()
                    ComposeMode.FORWARD ->
                        source?.let { forwarded(it, s, if (it.summary.dateMillis > 0) platform.formatFullDate(it.summary.dateMillis) else "") }.orEmpty()
                    else -> ""
                }
            },
        )
    }
    // Hangi alıcı alanına öneri gösterileceği (0 = Kime, 1 = Bilgi, 2 = Gizli).
    var suggestFor by remember { mutableStateOf(-1) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var fromOpen by remember { mutableStateOf(false) }

    // Değişiklikler taslağa kaydedilir (ilk açılıştaki hazır metin kayıt sayılmaz).
    var touched by rememberSaveable { mutableStateOf(screen.mode == ComposeMode.DRAFT) }
    LaunchedEffect(to, cc, bcc, subject, body) {
        if (touched) controller.draftChanged(ComposeFields(to, cc, bcc, subject, body))
    }
    fun edit(change: () -> Unit) {
        touched = true
        change()
    }

    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        focusedIndicatorColor = colors.primary,
        unfocusedIndicatorColor = colors.outlineVariant,
    )

    // Gönderildi: ekran kapanmadan önce ortada kısa bir onay.
    state.sentTo?.let { recipients ->
        SentConfirmation(recipients, state.compose.sentEncrypted, state.compose.failedBcc)
        return
    }

    // Şifresiz gönderim ya da değişmiş alıcı anahtarı: kullanıcı karar verir.
    state.compose.consent?.let { consent ->
        val e = s.e2e
        AlertDialog(
            onDismissRequest = { controller.answerConsent(false) },
            title = { Text(if (consent is SendConsent.KeyChanged) e.keyChangedTitle else e.plainTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (consent) {
                        is SendConsent.Plain -> {
                            Text(e.plainBody(consent.addresses.joinToString(", ")))
                            if (consent.quotesEncrypted) Text(e.plainQuoteWarning, color = colors.error)
                        }
                        is SendConsent.KeyChanged -> Text(e.keyChangedBody(consent.addresses.joinToString(", ")))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { controller.answerConsent(true) }) {
                    Text(if (consent is SendConsent.KeyChanged) e.trustAndSend else e.sendPlain)
                }
            },
            dismissButton = { TextButton(onClick = { controller.answerConsent(false) }) { Text(s.cancel) } },
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            when (screen.mode) {
                                ComposeMode.REPLY -> s.reply
                                ComposeMode.REPLY_ALL -> m.replyAll
                                ComposeMode.FORWARD -> s.forward
                                ComposeMode.DRAFT -> m.draftLabel
                                ComposeMode.NEW -> s.compose
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { controller.back() }, enabled = !state.busy) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = s.back)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val used = state.drafts.sumOf { it.sizeBytes }
                                platform.pickAttachments(AttachmentPolicy.MAX_TOTAL_BYTES - used) {
                                    touched = true
                                    controller.addAttachments(it)
                                }
                            },
                            enabled = !state.busy && !state.compose.preparing,
                        ) {
                            Icon(Icons.Outlined.AttachFile, contentDescription = s.attachFile)
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }, enabled = !state.busy) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = s.more)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (!showCopies) {
                                    MenuItem(Icons.Outlined.GroupAdd, m.showCcBcc) {
                                        menuOpen = false
                                        showCopies = true
                                    }
                                }
                                MenuItem(Icons.Outlined.Delete, m.discardDraft, tint = colors.error) {
                                    menuOpen = false
                                    confirmDiscard = true
                                }
                            }
                        }
                        Button(
                            onClick = { controller.send(to, cc, subject, body, bcc) },
                            enabled = !state.busy && !state.compose.preparing,
                            shape = CircleShape,
                            modifier = Modifier.padding(end = 12.dp),
                        ) {
                            if (state.busy) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text(s.sending)
                            } else {
                                Text(s.send)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
                )
                HorizontalDivider(color = colors.outlineVariant)
            }
        },
        containerColor = colors.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()),
        ) {
            // Gönderen: takma adı olan kutuda seçilebilir; taslak durumu sağda.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fromOptions = state.compose.fromOptions
                val fromShown = state.compose.from ?: self
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = fromOptions.size > 1 && !state.busy) { fromOpen = true }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${m.from}: $fromShown",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (fromOptions.size > 1) {
                            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                    DropdownMenu(expanded = fromOpen, onDismissRequest = { fromOpen = false }) {
                        fromOptions.forEach { address ->
                            DropdownMenuItem(
                                text = { Text(address) },
                                onClick = {
                                    fromOpen = false
                                    touched = true
                                    controller.setComposeFrom(address)
                                },
                            )
                        }
                    }
                }
                val status = when {
                    state.compose.savingDraft -> m.draftSaving
                    state.compose.draftSaved -> m.draftSaved
                    else -> null
                }
                if (status != null) {
                    Text(status, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            state.formError?.let {
                Text(
                    s.error(it),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = colors.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.active?.let { state.e2e[it.email] } == E2EStatus.READY) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.e2e.composeNote, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            val emailKeyboard = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Email,
            )

            @Composable
            fun RecipientField(index: Int, value: String, label: String, onChange: (String) -> Unit) {
                TextField(
                    value,
                    { text ->
                        edit { onChange(text) }
                        suggestFor = index
                        controller.suggestRecipients(text)
                    },
                    label = { Text(label) },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = emailKeyboard,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                val suggestions = state.compose.suggestions
                if (suggestFor == index && suggestions.isNotEmpty()) {
                    Suggestions(suggestions) { picked ->
                        onChange(acceptSuggestion(value, picked))
                        controller.clearSuggestions()
                    }
                }
            }

            RecipientField(0, to, s.to) { to = it }
            if (showCopies) {
                RecipientField(1, cc, s.cc) { cc = it }
                RecipientField(2, bcc, m.bcc) { bcc = it }
            }
            TextField(
                subject,
                { edit { subject = it } },
                label = { Text(s.subject) },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.compose.preparing) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(m.preparingAttachments, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if (state.drafts.isNotEmpty() || state.attachProblem != null) {
                DraftAttachments(state, controller)
            }
            TextField(
                body,
                { edit { body = it } },
                placeholder = { Text(s.message) },
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(m.discardDraft) },
            text = { Text(m.discardDraftBody) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    controller.discardDraft()
                }) { Text(s.delete, color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(s.cancel) } },
        )
    }
}

/** Alıcı önerileri: ad, adres ve nereden geldiği (kişi / son yazışma / şirket). */
@Composable
private fun Suggestions(items: List<io.maildots.app.web.WebSuggestion>, onPick: (String) -> Unit) {
    val m = LocalStrings.current.extra
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(item.email) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SenderAvatar(io.maildots.app.mail.Address(item.name, item.email))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name.ifBlank { item.email },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.name.isNotBlank()) {
                            Text(item.email, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(m.suggestionSource(item.source), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** Yazılan iletinin ekleri: ad, boyut, kaldırma; eklenemeyen dosyanın nedeni. */
@Composable
private fun DraftAttachments(state: AppState, controller: AppController) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        state.drafts.forEach { draft ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(colors.surfaceContainerHigh, RoundedCornerShape(12.dp))
                    .padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(draft.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatSize(draft.sizeBytes), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                IconButton(onClick = { controller.removeAttachment(draft.id) }, enabled = !state.busy) {
                    Icon(Icons.Outlined.Close, contentDescription = s.removeAttachment, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (state.drafts.isNotEmpty()) {
            Text(
                formatSize(state.drafts.sumOf { it.sizeBytes }) + " / " + formatSize(AttachmentPolicy.MAX_TOTAL_BYTES),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        state.attachProblem?.let { problem ->
            Text(
                listOfNotNull(state.attachProblemName, s.attachProblem(problem)).joinToString(": "),
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
        }
    }
}

/** Gönderim onayı: sade, ekranın ortasında; ardından yazma ekranı kapanır. */
@Composable
private fun SentConfirmation(recipients: String, encrypted: Boolean = false, failedBcc: List<String> = emptyList()) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 0.6f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(180))
    Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp).graphicsLayer { this.alpha = alpha },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(64.dp).graphicsLayer { scaleX = scale; scaleY = scale }.background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(s.sent, style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)
            Spacer(Modifier.height(6.dp))
            Text(
                recipients,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (encrypted) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.e2e.sentEncrypted, style = MaterialTheme.typography.bodySmall, color = colors.primary)
                }
            }
            if (failedBcc.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(s.e2e.failedBcc(failedBcc.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
        }
    }
}

/** Şifreli ileti açılamadı: neden; kilitliyse şifreyle kilit açma (şifre cihazdan çıkmaz). */
@Composable
private fun E2EPanel(problem: E2EProblem, unlocking: Boolean, onUnlock: (String) -> Unit) {
    val s = LocalStrings.current
    val e = s.e2e
    val colors = MaterialTheme.colorScheme
    var password by remember { mutableStateOf("") }
    val askPassword = problem == E2EProblem.LOCKED || problem == E2EProblem.WRONG_PASSWORD
    Surface(
        Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        color = colors.surface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(e.lockedTitle, style = MaterialTheme.typography.titleSmall)
            }
            if (askPassword) Text(e.lockedBody, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (problem != E2EProblem.LOCKED) {
                Text(e.problems[problem].orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
            if (askPassword) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(s.password) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onUnlock(password) }, enabled = password.isNotEmpty() && !unlocking, shape = CircleShape) {
                    if (unlocking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(e.unlocking)
                    } else {
                        Text(e.unlock)
                    }
                }
            }
        }
    }
}
