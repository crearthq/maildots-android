package io.maildots.app.ui

import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.ui.backhandler.BackHandler
import io.maildots.app.BulkAction

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.automirrored.outlined.Label
import io.maildots.app.LabelInfo
import io.maildots.app.mail.Folder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import kotlin.math.abs
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import io.maildots.app.mail.Address
import io.maildots.app.mail.MailLabel
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maildots.app.AppController
import io.maildots.app.AppState
import io.maildots.app.Screen
import io.maildots.app.SwipeAction
import io.maildots.app.mail.Account
import io.maildots.app.mail.FolderRole
import io.maildots.app.mail.MailFilter
import io.maildots.app.mail.MessageSummary
import kotlinx.coroutines.launch

fun FolderRole.icon(): ImageVector = when (this) {
    FolderRole.INBOX -> Icons.Outlined.Inbox
    FolderRole.SENT -> Icons.AutoMirrored.Outlined.Send
    FolderRole.DRAFTS -> Icons.Outlined.Drafts
    FolderRole.TRASH -> Icons.Outlined.Delete
    FolderRole.JUNK -> Icons.Outlined.Block
    FolderRole.ARCHIVE -> Icons.Outlined.Archive
    FolderRole.OTHER -> Icons.Outlined.Folder
}

fun SwipeAction.icon(seen: Boolean = false): ImageVector = when (this) {
    SwipeAction.TOGGLE_READ -> if (seen) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead
    SwipeAction.DELETE -> Icons.Outlined.Delete
    SwipeAction.ARCHIVE -> Icons.Outlined.Archive
    SwipeAction.STAR -> Icons.Outlined.Star
    SwipeAction.SPAM -> Icons.Outlined.Report
    SwipeAction.NONE -> Icons.Outlined.Block
}

@Composable
fun Avatar(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.trim().take(1).uppercase().ifEmpty { "?" },
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Hesabın görünen adı (yoksa adresin @ öncesi): şerit sırası ve başlık için. */
internal fun accountLabel(account: Account): String = account.displayName.ifBlank { account.email.substringBefore('@') }

/** Hesap balonu rengi: adresten türetilir, her hesap hep aynı rengi alır ([AvatarStyle]). */
internal fun bubbleColor(email: String): Color = Color(io.maildots.app.mail.AvatarStyle.colorFor(email))

/** Gönderen avatarı: adresten türetilen renk + baş harfler ([AvatarStyle]; bildirimde de aynısı). */
@Composable
internal fun SenderAvatar(from: Address, modifier: Modifier = Modifier) {
    val text = io.maildots.app.mail.AvatarStyle.initials(from.name, from.email)
    Box(
        modifier.size(40.dp).clip(CircleShape).background(bubbleColor(from.email.ifBlank { from.name })),
        contentAlignment = Alignment.Center,
    ) {
        AvatarPhoto(from.email) {
            Text(text, color = Color.White, fontWeight = FontWeight.Medium, fontSize = if (text.length > 1) 15.sp else 17.sp)
        }
    }
}

private fun initials(account: Account): String {
    val local = account.displayName.ifBlank { account.email.substringBefore('@') }
    val parts = local.split(' ', '.', '_', '-').filter { it.isNotEmpty() }
    return (if (parts.size >= 2) "${parts[0].first()}${parts[1].first()}" else local.take(2)).uppercase()
}

@Composable
internal fun AccountBubble(account: Account, active: Boolean, unread: Int, size: Dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.TopEnd) {
        Box(
            Modifier
                .size(size)
                .then(if (active) Modifier.border(2.dp, colors.primary, CircleShape).padding(4.dp) else Modifier)
                .clip(CircleShape)
                .background(bubbleColor(account.email))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AvatarPhoto(account.email) {
                Text(
                    initials(account),
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = if (active) 20.sp else 15.sp,
                )
            }
        }
        if (unread > 0) {
            // Rozet: tek/çift hanede tam daire (en = boy); "99+" gibi uzun metinde
            // uçları yarım daire olan hap. Yükseklik sabit, genişlik en az yükseklik kadar.
            Box(
                Modifier
                    .offset(x = 4.dp, y = (-2).dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .padding(2.dp)
                    .height(20.dp)
                    .defaultMinSize(minWidth = 20.dp)
                    .clip(CircleShape)
                    .background(colors.error)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unread > 99) "99+" else unread.toString(),
                    color = colors.onError,
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
/** Sol menü: üstte hesap şeridi, altında gelen kutusu, klasörler ve görünümler. */
@Composable
private fun MailDrawer(state: AppState, controller: AppController, close: () -> Unit) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    val box = state.mailbox
    val folders = box.folders
    val inbox = folders.firstOrNull { it.role == FolderRole.INBOX }
    val inboxPath = inbox?.path ?: "INBOX"
    val custom = folders.filter { it.role == FolderRole.OTHER }
    var foldersOpen by rememberSaveable { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var editingFolder by remember { mutableStateOf<Folder?>(null) }
    var creatingLabel by remember { mutableStateOf(false) }
    var editingLabel by remember { mutableStateOf<LabelInfo?>(null) }

    editingFolder?.let { folder ->
        FolderEditDialog(
            folder,
            onDismiss = { editingFolder = null },
            onRename = { name ->
                editingFolder = null
                controller.renameFolder(folder.path, name)
            },
            onDelete = {
                editingFolder = null
                controller.deleteFolder(folder.path)
            },
        )
    }
    if (creatingLabel) {
        LabelEditDialog(existing = null, onDismiss = { creatingLabel = false }, onSave = { name, color ->
            creatingLabel = false
            controller.createLabel(name, color)
        })
    }
    editingLabel?.let { info ->
        LabelEditDialog(
            existing = info,
            onDismiss = { editingLabel = null },
            onSave = { _, color ->
                editingLabel = null
                controller.setLabelColor(info.label, color)
            },
            onDelete = if (info.label.isBuiltin && info.id.isEmpty()) null else {
                {
                    editingLabel = null
                    controller.deleteLabel(info.label)
                }
            },
        )
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(s.newFolder) },
            text = {
                OutlinedTextField(newName, { newName = it.take(60) }, label = { Text(s.folderName) }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        creating = false
                        controller.createFolder(newName)
                        newName = ""
                    },
                ) { Text(s.create) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(s.cancel) } },
        )
    }

    fun open(path: String, filter: MailFilter = MailFilter.ALL, label: MailLabel? = null) {
        close()
        if (path != box.folderPath || filter != box.filter || label != box.label) controller.selectFolder(path, filter, label)
    }

    ModalDrawerSheet(drawerContainerColor = colors.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            // Hesap çarkı (AccountWheel): alfabetik; kaydırırken ortadaki hesap başlıkta görünür.
            val ordered = remember(state.accounts) {
                state.accounts.sortedWith(compareBy({ accountLabel(it).lowercase() }, { it.email.lowercase() }))
            }
            var previewAccount by remember { mutableStateOf<Account?>(null) }
            Box(Modifier.padding(vertical = 12.dp)) {
                AccountWheel(
                    accounts = ordered,
                    activeEmail = state.active?.email,
                    unread = state.unreadByAccount,
                    onPreview = { previewAccount = it },
                    onSelect = { controller.switchAccount(it.email) },
                    onAdd = {
                        close()
                        controller.openAddAccount()
                    },
                )
            }
            (previewAccount ?: state.active)?.let { account ->
                Text(
                    accountLabel(account),
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    account.email,
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            DrawerRow(
                Icons.Outlined.Inbox,
                s.folderName(FolderRole.INBOX, "INBOX"),
                selected = box.folderPath == inboxPath && box.filter == MailFilter.ALL && box.label == null,
                unread = inbox?.unread ?: 0,
                total = inbox?.total ?: 0,
            ) { open(inboxPath) }

            run {
                DrawerRow(
                    Icons.Outlined.Folder,
                    s.myFolders,
                    selected = false,
                    trailing = if (foldersOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                ) { foldersOpen = !foldersOpen }
                AnimatedVisibility(foldersOpen) {
                    Column {
                        DrawerRow(Icons.Outlined.CreateNewFolder, s.newFolder, selected = false, indent = 28.dp) {
                            creating = true
                        }
                        custom.forEach { folder ->
                            DrawerRow(
                                Icons.Outlined.Folder,
                                folder.name,
                                selected = box.folderPath == folder.path,
                                unread = folder.unread,
                                total = folder.total,
                                indent = 28.dp,
                                onLongClick = { editingFolder = folder },
                            ) { open(folder.path) }
                        }
                    }
                }
            }

            folders.firstOrNull { it.role == FolderRole.SENT }?.let { f ->
                DrawerRow(
                    f.role.icon(),
                    s.folderName(f.role, f.name),
                    selected = box.folderPath == f.path,
                    unread = f.unread,
                    total = f.total,
                ) { open(f.path) }
            }
            DrawerRow(
                Icons.Outlined.AttachFile,
                s.withAttachments,
                selected = box.folderPath == inboxPath && box.filter == MailFilter.ATTACHMENTS,
            ) { open(inboxPath, MailFilter.ATTACHMENTS) }
            DrawerRow(
                Icons.Outlined.StarBorder,
                s.starredView,
                selected = box.folderPath == inboxPath && box.filter == MailFilter.FLAGGED,
            ) { open(inboxPath, MailFilter.FLAGGED) }
            DrawerRow(
                Icons.Outlined.MarkEmailUnread,
                s.unreadView,
                selected = box.folderPath == inboxPath && box.filter == MailFilter.UNREAD,
            ) { open(inboxPath, MailFilter.UNREAD) }

            val systemRoles = listOf(FolderRole.DRAFTS, FolderRole.ARCHIVE, FolderRole.JUNK, FolderRole.TRASH)
            // Sistem klasörü hiç yoksa boş bir bölüm (çift çizgi) çizme.
            if (folders.any { it.role in systemRoles }) DrawerDivider()

            systemRoles.forEach { role ->
                folders.firstOrNull { it.role == role }?.let { f ->
                    DrawerRow(
                        f.role.icon(),
                        s.folderName(f.role, f.name),
                        selected = box.folderPath == f.path,
                        unread = f.unread,
                        total = f.total,
                    ) { open(f.path) }
                }
            }

            // Etiketler: renkli nokta + ad; dokununca o etiketi taşıyan e-postalar.
            DrawerDivider()
            Text(
                s.labels,
                Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            state.labels.forEach { info ->
                val label = info.label
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 1.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(if (box.label == label) colors.onSurface.copy(alpha = 0.07f) else Color.Transparent)
                        .combinedClickable(
                            onLongClick = { editingLabel = info },
                            onLongClickLabel = s.holdToEdit,
                        ) { open(inboxPath, MailFilter.ALL, label) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(label.color()))
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        labelTitle(label),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (box.label == label) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
            DrawerRow(Icons.AutoMirrored.Outlined.Label, s.newLabel, selected = false, trailing = Icons.Outlined.Add) {
                creatingLabel = true
            }

            DrawerDivider()
            DrawerRow(Icons.Outlined.Settings, s.settings, selected = false) {
                close()
                controller.openSettings()
            }
        }
    }
}

/** Büyük sayıları kısaltır: 12 480 -> "12B" yerine dile bağımsız "12k". */
private fun compactCount(n: Int): String = when {
    n < 10_000 -> n.toString()
    n < 1_000_000 -> "${n / 1000}k"
    else -> "${n / 1_000_000}M"
}

/**
 * Hesap açılış / geçiş ekranı: hesabın balonu, çevresinde dönen ince halka, ad ve adres.
 * Boş bir gelen kutusu ve dönen simge yerine gösterilir.
 */
@Composable
private fun AccountOpening(account: Account?) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    // Yer tutucular sırayla, dalga gibi soluklanır.
    val wave by rememberInfiniteTransition().animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
    )
    val bone = colors.onSurface
    fun pulse(row: Int): Float {
        val phase = (wave - row * 0.09f).mod(1f)
        val lift = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
        return 0.06f + 0.07f * lift
    }

    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding()) {
        // Üst çubuk: gerçek kutuyla aynı yerleşim; başlık ve hesap hemen görünür.
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Menu, contentDescription = null, tint = colors.onSurfaceVariant)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(s.folderName(FolderRole.INBOX, "INBOX"), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                if (account != null) {
                    Text(
                        account.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = colors.outline)
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.outline)
            }
        }
        // İnce ilerleme çizgisi: kutunun açılmakta olduğunu söyler.
        LinearProgressIndicator(
            Modifier.fillMaxWidth().height(2.dp).semantics { contentDescription = s.openingMailbox },
            color = colors.primary,
            trackColor = colors.outlineVariant,
        )

        // İleti satırları: avatar, gönderen, konu, özet, saat ve yıldızın yer tutucuları.
        Column(Modifier.fillMaxSize()) {
            val widths = listOf(0.42f to 0.86f, 0.30f to 0.70f, 0.50f to 0.92f, 0.36f to 0.64f, 0.46f to 0.80f)
            repeat(9) { row ->
                val (name, subject) = widths[row % widths.size]
                val a = pulse(row)
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 15.dp)) {
                    Box(Modifier.size(44.dp).background(bone.copy(alpha = a), CircleShape))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.fillMaxWidth(name).height(14.dp).background(bone.copy(alpha = a * 1.25f), RoundedCornerShape(7.dp)))
                            Spacer(Modifier.weight(1f))
                            Box(Modifier.width(38.dp).height(10.dp).background(bone.copy(alpha = a), RoundedCornerShape(5.dp)))
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth(subject).height(12.dp).background(bone.copy(alpha = a), RoundedCornerShape(6.dp)))
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth(subject * 0.7f).height(12.dp).background(bone.copy(alpha = a * 0.8f), RoundedCornerShape(6.dp)))
                    }
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.padding(top = 14.dp).size(18.dp).background(bone.copy(alpha = a * 0.8f), RoundedCornerShape(6.dp)))
                }
                HorizontalDivider(Modifier.padding(start = 74.dp), color = colors.outlineVariant)
            }
        }
    }
}

@Composable
private fun DrawerDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    /** Okunmamış sayısı (kalın, marka renginde) ve toplam ileti sayısı (soluk). */
    unread: Int = 0,
    total: Int = 0,
    trailing: ImageVector? = null,
    indent: Dp = 0.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // Seçili satır: marka mavisi zemin, beyaz içerik (koyu temada da okunur).
    val content = if (selected) colors.onPrimary else colors.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) colors.primary else Color.Transparent)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(start = 12.dp + indent, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (unread > 0) {
            Text(
                compactCount(unread),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (selected) colors.onPrimary else colors.primary,
            )
        }
        if (total > 0) {
            Spacer(Modifier.width(10.dp))
            Text(
                compactCount(total),
                Modifier.defaultMinSize(minWidth = 28.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Normal,
                color = if (selected) colors.onPrimary.copy(alpha = 0.75f) else colors.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        if (trailing != null) {
            Icon(trailing, contentDescription = null, tint = if (selected) colors.onPrimary else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MailboxScreen(state: AppState, controller: AppController, platform: Platform) {
    val s = LocalStrings.current
    val box = state.mailbox
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val current = box.folders.firstOrNull { it.path == box.folderPath }
    var filterOpen by remember { mutableStateOf(false) }
    val title = if (box.label != null) labelTitle(box.label) else when (box.filter) {
        MailFilter.UNREAD -> s.unreadView
        MailFilter.FLAGGED -> s.starredView
        MailFilter.ATTACHMENTS -> s.withAttachments
        MailFilter.ALL -> current?.let { s.folderName(it.role, it.name) } ?: s.folderName(FolderRole.INBOX, "INBOX")
    }

    // Listenin sonuna yaklaşınca sonraki sayfayı iste.
    val nearEnd by remember(box.messages.size) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            box.messages.isNotEmpty() && last >= box.messages.size - 6
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd && !box.moreFailed) controller.loadMore() }

    // Sunucu bu cihazdaki şifreyi reddetti (başka yerde değişti): açıkça söylenir, yeni şifre
    // istenir; aşağı çekmek de şifre penceresini açar (sessizce eski liste gösterilmez).
    val activeEmail = state.active?.email
    val passwordRejected = activeEmail != null &&
        (state.web[activeEmail] == io.maildots.app.WebStatus.AUTH_FAILED || box.error == io.maildots.app.ErrorKind.AUTH)
    var askPassword by remember { mutableStateOf(false) }
    if (askPassword && activeEmail != null) {
        PasswordDialog(
            state,
            controller,
            title = s.extra.signInAgainTitle,
            body = s.extra.signInAgainBody(activeEmail),
            onClose = { askPassword = false },
        ) { new -> controller.updateStoredPassword(activeEmail, new) }
    }

    val selecting = state.selection.isNotEmpty()
    var bulkMoveOpen by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmBulkForever by remember { mutableStateOf(false) }
    // Geri: önce seçim kapanır.
    // Liste alt katmanda canlı kalır: seçim yalnızca liste görünürken geri tuşuyla temizlenir.
    BackHandler(enabled = selecting && state.screen == Screen.Mailbox) { controller.clearSelection() }

    if (box.opening) {
        AccountOpening(state.active)
        return
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = { MailDrawer(state, controller) { scope.launch { drawer.close() } } },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (selecting) {
                    SelectionBar(state, controller, current?.role, onMove = { bulkMoveOpen = true }, onDeleteForever = { confirmBulkForever = true })
                    return@Scaffold
                }
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(title, style = MaterialTheme.typography.titleLarge)
                                state.active?.let {
                                    Text(
                                        it.email,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawer.open() } }) {
                                Icon(Icons.Outlined.Menu, contentDescription = s.menu)
                            }
                        },
                        actions = {
                            // Süzgeç: bulunulan klasörde okunmamış / ekli / yıldızlı.
                            Box {
                                IconButton(onClick = { filterOpen = true }) {
                                    Icon(
                                        Icons.Outlined.Tune,
                                        contentDescription = s.filter,
                                        tint = if (box.filter != MailFilter.ALL) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                                    listOf(
                                        Triple(MailFilter.ALL, s.filterAll, Icons.Outlined.Inbox),
                                        Triple(MailFilter.UNREAD, s.unreadView, Icons.Outlined.MarkEmailUnread),
                                        Triple(MailFilter.ATTACHMENTS, s.withAttachments, Icons.Outlined.AttachFile),
                                        Triple(MailFilter.FLAGGED, s.starredView, Icons.Outlined.StarBorder),
                                    ).forEach { (filter, label, icon) ->
                                        val on = box.filter == filter
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    label,
                                                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    icon,
                                                    contentDescription = null,
                                                    tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            },
                                            onClick = {
                                                filterOpen = false
                                                if (!on) controller.setFilter(filter)
                                            },
                                        )
                                    }
                                }
                            }
                            // Masaüstünde arama kutusu başlık çubuğundadır.
                            if (!LocalSearchInTitleBar.current) {
                                IconButton(onClick = { controller.openSearch() }) {
                                    Icon(Icons.Outlined.Search, contentDescription = s.search)
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            },
            floatingActionButton = {
                if (selecting) return@Scaffold
                val native = platform.composeButton
                if (native != null) {
                    // Yerel düğme her şeyin üstünde çizilir: menü ya da onay penceresi açıkken,
                    // ekran geçişinde (liste altta kalırken) gösterilmez.
                    val covered = drawer.currentValue != DrawerValue.Closed || drawer.targetValue != DrawerValue.Closed ||
                        drawer.isAnimationRunning || confirmEmpty || state.screen != Screen.Mailbox
                    if (!covered) native(s.compose) { controller.openComposer() }
                    return@Scaffold
                }
                ExtendedFloatingActionButton(
                    onClick = { controller.openComposer() },
                    icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    text = { Text(s.compose) },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            // Aşağı çekince yenile. Liste boşken de çekilebilsin diye her durumda LazyColumn.
            PullToRefreshBox(
                isRefreshing = box.loading,
                onRefresh = { if (passwordRejected) askPassword = true else controller.refresh() },
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            ) {
                val rowKeys = remember(box.messages) {
                    box.messages.associate { "${it.folderPath}/${it.uid}" to io.maildots.app.MessageKey(it.folderPath, it.uid) }
                }
                val rowOrder = remember(rowKeys) { rowKeys.values.toList() }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 88.dp),
                    modifier = Modifier.fillMaxSize().avatarDragSelect(
                        listState = listState,
                        keyOf = { rowKeys[it] },
                        order = rowOrder,
                        selection = state.selection,
                        onToggle = { controller.toggleSelection(it) },
                        onSelect = { controller.setSelection(it) },
                    ),
                ) {
                    // T3: kayıtlı posta şifrelemesi kurcalandı (sunucu yeniden açtıysa "Anladım" ile kapanır).
                    val encryptionAlert = activeEmail?.let { state.encryptionAlerts[it] }
                    if (encryptionAlert != null) item(key = "encryption-alert") {
                        EncryptionAlertBanner(encryptionAlert, onAck = { activeEmail?.let { controller.acknowledgeEncryptionAlert(it) } })
                    }
                    if (passwordRejected) item(key = "password") {
                        PasswordBanner(onEnter = { askPassword = true })
                    }
                    if (activeEmail != null && activeEmail in state.onboardingPending) item(key = "onboarding") {
                        OnboardingBanner()
                    }
                    when {
                        box.error != null && box.messages.isEmpty() -> item {
                            CenterMessage(Modifier.fillParentMaxHeight(0.7f), s.error(box.error)) {
                                Button(onClick = { controller.refresh() }, shape = CircleShape) { Text(s.retry) }
                            }
                        }
                        !box.loading && box.messages.isEmpty() -> item {
                            CenterMessage(Modifier.fillParentMaxHeight(0.7f), s.emptyFolder) {}
                        }
                        else -> {
                            // Çöp / gereksiz: tek dokunuşla (onaylı) boşaltma.
                            val emptiable = box.label == null && box.filter == MailFilter.ALL &&
                                (current?.role == FolderRole.TRASH || current?.role == FolderRole.JUNK) && controller.canEmptyFolders()
                            if (emptiable) item(key = "empty") {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(onClick = { confirmEmpty = true }) {
                                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (current?.role == FolderRole.TRASH) s.extra.emptyTrash else s.extra.emptyJunk)
                                    }
                                }
                            }
                            items(box.messages, key = { "${it.folderPath}/${it.uid}" }) { message ->
                                val key = io.maildots.app.MessageKey(message.folderPath, message.uid)
                                SwipeRow(
                                    message,
                                    right = if (selecting) SwipeAction.NONE else state.settings.swipeRight,
                                    left = if (selecting) SwipeAction.NONE else state.settings.swipeLeft,
                                    onSwipe = { action -> controller.performSwipe(message, action) },
                                ) {
                                    MessageRow(
                                        message,
                                        date = platform.formatShortDate(message.dateMillis),
                                        onClick = { if (selecting) controller.toggleSelection(message) else controller.openMessage(message) },
                                        onStar = { controller.toggleFlag(message) },
                                        selected = key in state.selection,
                                        onLongClick = { controller.toggleSelection(message) },
                                        unencryptedAtRest = message.storedUnencrypted(activeEmail?.let { state.protectedSince[it] }),
                                    )
                                }
                                HorizontalDivider(
                                    Modifier.padding(start = 68.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            item(key = "footer") {
                                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    when {
                                        box.moreFailed -> {
                                            Text(s.loadMoreFailed, style = MaterialTheme.typography.bodyMedium, color = muted)
                                            TextButton(onClick = { controller.loadMore() }) { Text(s.retry) }
                                        }
                                        box.endReached -> {
                                            // Listenin sonu: yalnızca hepsi gerçekten yüklendikten sonra yazılır
                                            // (yenileme sürerken ya da sonraki sayfa beklenirken görünmez).
                                            if (!box.loading && !box.loadingMore) {
                                                Text(
                                                    s.allLoaded,
                                                    Modifier.padding(top = 8.dp, bottom = 72.dp),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = muted,
                                                    textAlign = TextAlign.Center,
                                                )
                                            }
                                        }
                                        else -> {
                                            // Daha eski ileti var: yüklenirken her zaman görünür.
                                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(12.dp))
                                            Text(s.loadingMore, style = MaterialTheme.typography.bodyMedium, color = muted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (bulkMoveOpen) {
        AlertDialog(
            onDismissRequest = { bulkMoveOpen = false },
            title = { Text(s.moveTo) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    box.folders.filter { it.path != box.folderPath }.forEach { folder ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    bulkMoveOpen = false
                                    controller.bulkMove(folder.path)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(folder.role.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(s.folderName(folder.role, folder.name), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { bulkMoveOpen = false }) { Text(s.cancel) } },
        )
    }
    if (confirmBulkForever) {
        AlertDialog(
            onDismissRequest = { confirmBulkForever = false },
            title = { Text(s.extra.deleteForever) },
            text = { Text(s.extra.deleteForeverBody(state.selection.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBulkForever = false
                    controller.bulk(BulkAction.DELETE)
                }) { Text(s.delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmBulkForever = false }) { Text(s.cancel) } },
        )
    }
    if (confirmEmpty && current != null) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text(if (current.role == FolderRole.TRASH) s.extra.emptyTrash else s.extra.emptyJunk) },
            text = { Text(s.extra.emptyFolderBody) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    controller.emptyFolder(current.path)
                }) { Text(s.extra.emptyAction, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text(s.cancel) } },
        )
    }
}

/**
 * Toplu seçim çubuğu: seçili sayısı ve işlemler. Klasöre göre "gereksiz değil" ya da
 * "gereksiz"; çöpte silme kalıcıdır (onay istenir).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    state: AppState,
    controller: AppController,
    role: FolderRole?,
    onMove: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val s = LocalStrings.current
    val m = s.extra
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val chosen = (state.mailbox.messages + state.search.results)
        .filter { io.maildots.app.MessageKey(it.folderPath, it.uid) in state.selection }
    val anyUnread = chosen.any { !it.seen }
    val anyUnstarred = chosen.any { !it.flagged }
    Column {
        TopAppBar(
            title = { Text(m.selected(state.selection.size), style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconButton(onClick = { controller.clearSelection() }) {
                    Icon(Icons.Outlined.Close, contentDescription = s.cancel)
                }
            },
            actions = {
                if (role != FolderRole.ARCHIVE) {
                    IconButton(onClick = { controller.bulk(BulkAction.ARCHIVE) }) {
                        Icon(Icons.Outlined.Archive, contentDescription = s.archive)
                    }
                }
                IconButton(onClick = { if (role == FolderRole.TRASH) onDeleteForever() else controller.bulk(BulkAction.DELETE) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = s.delete)
                }
                IconButton(onClick = { controller.bulk(if (anyUnread) BulkAction.READ else BulkAction.UNREAD) }) {
                    Icon(
                        if (anyUnread) Icons.Outlined.Drafts else Icons.Outlined.MarkEmailUnread,
                        contentDescription = if (anyUnread) m.markRead else m.markUnread,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = s.more)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (anyUnstarred) m.star else m.unstar) },
                            leadingIcon = { Icon(if (anyUnstarred) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                controller.bulk(if (anyUnstarred) BulkAction.STAR else BulkAction.UNSTAR)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(s.moveTo) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                onMove()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (role == FolderRole.JUNK) m.notSpam else s.reportSpam) },
                            leadingIcon = { Icon(if (role == FolderRole.JUNK) Icons.Outlined.MoveToInbox else Icons.Outlined.Report, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                controller.bulk(if (role == FolderRole.JUNK) BulkAction.NOT_SPAM else BulkAction.SPAM)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(m.selectAll) },
                            leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuOpen = false
                                controller.selectAllVisible()
                            },
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
        )
        HorizontalDivider(color = colors.outlineVariant)
    }
}

/**
 * Kaydırılabilir satır. Sağa/sola kaydırma, ayarlardaki işlemi uygular; ileti
 * listeden çıkmıyorsa (okundu, yıldız) satır yerine geri döner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeRow(
    message: MessageSummary,
    right: SwipeAction,
    left: SwipeAction,
    onSwipe: (SwipeAction) -> Boolean,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val currentRight by rememberUpdatedState(right)
    val currentLeft by rememberUpdatedState(left)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentOnSwipe(currentRight)
                SwipeToDismissBoxValue.EndToStart -> currentOnSwipe(currentLeft)
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = right != SwipeAction.NONE,
        enableDismissFromEndToStart = left != SwipeAction.NONE,
        backgroundContent = {
            val toEnd = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val action = if (toEnd) right else left
            val (bg, fg) = when (action) {
                SwipeAction.DELETE, SwipeAction.SPAM -> colors.error to colors.onError
                SwipeAction.NONE -> colors.background to colors.onBackground
                else -> colors.primary to colors.onPrimary
            }
            Box(
                Modifier.fillMaxSize().background(bg).padding(horizontal = 24.dp),
                contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(action.icon(message.seen), contentDescription = null, tint = fg)
            }
        },
    ) {
        Box(Modifier.background(colors.background)) { content() }
    }
}

@Composable
internal fun CenterMessage(modifier: Modifier, text: String, action: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        action()
    }
}

/** T3 kırmızı bandı: şifreleme sunucuda kapatılmış / zayıflatılmış / başka anahtara çevrilmiş bulundu. */
@Composable
private fun EncryptionAlertBanner(alert: io.maildots.app.web.EncryptionAlert, onAck: () -> Unit) {
    val more = LocalStrings.current.extra
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.errorContainer)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.GppBad, contentDescription = null, tint = colors.onErrorContainer, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(more.tamperTitle(alert.kind), style = MaterialTheme.typography.titleSmall, color = colors.onErrorContainer)
            Text(
                when {
                    !alert.restored -> more.tamperBroken
                    alert.kind == "mismatch" -> more.tamperRestoredMismatch
                    else -> more.tamperRestored
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onErrorContainer,
            )
        }
        // Yeniden açılamadıysa kapatılamaz.
        if (alert.restored) TextButton(onClick = onAck) { Text(more.tamperAck, color = colors.onErrorContainer) }
    }
}

/** T2 bilgi bandı: yeni kutuda şifreleme kurulumu bekliyor (kurulum web postada). */
@Composable
private fun OnboardingBanner() {
    val more = LocalStrings.current.extra
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.primaryContainer)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(more.onboardingTitle, style = MaterialTheme.typography.titleSmall, color = colors.onPrimaryContainer)
            Text(more.onboardingBody, style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
        }
    }
}

/** "Şifreniz değişmiş" bandı: listenin üstünde, yeni şifreyi girme düğmesiyle. */
@Composable
private fun PasswordBanner(onEnter: () -> Unit) {
    val more = LocalStrings.current.extra
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.errorContainer)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onErrorContainer, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(more.signInAgainTitle, style = MaterialTheme.typography.titleSmall, color = colors.onErrorContainer)
            Text(
                more.updatePasswordBody,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onErrorContainer,
            )
        }
        TextButton(onClick = onEnter) { Text(more.signInAgainAction, color = colors.onErrorContainer) }
    }
}

@Composable
internal fun MessageRow(
    message: MessageSummary,
    date: String,
    onClick: () -> Unit,
    onStar: () -> Unit,
    /** Arama sonuçlarında iletinin bulunduğu klasör (liste içinde gerekmez). */
    folder: String? = null,
    /** Toplu seçimde işaretli mi. */
    selected: Boolean = false,
    /** Uzun basma: toplu seçimi başlatır (null = seçim yok). */
    onLongClick: (() -> Unit)? = null,
    /** Kutu şifreliyken sunucuda şifrelenmeden saklanmış (T3 işareti). */
    unencryptedAtRest: Boolean = false,
) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    val unread = !message.seen
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.55f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            if (selected) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(colors.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(22.dp))
                }
            } else {
                SenderAvatar(message.from)
            }
            // Okunmamış: marka noktası.
            if (unread && !selected) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(colors.background).padding(2.dp)) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(colors.primary))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.from.label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    date,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unread) colors.primary else colors.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (message.encrypted) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = s.e2e.encrypted,
                        tint = colors.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (message.hasAttachment) {
                    Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = s.attachments,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    message.subject.ifBlank { s.noSubject },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread) colors.onSurface else colors.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                    maxLines = if (message.snippet.isBlank()) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Özet: gövdenin ilk satırı (motor verirse; webmail ile aynı). Şifreli iletide özet
            // yoktur (sunucu içeriği bilmez); yerine işareti yazılır.
            if (message.encrypted && message.snippet.isBlank()) {
                Text(
                    s.e2e.encrypted,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (message.snippet.isNotBlank()) {
                Text(
                    message.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (unencryptedAtRest) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.LockOpen, contentDescription = null, tint = colors.error, modifier = Modifier.size(13.dp))
                    Text(s.extra.storedUnencrypted, style = MaterialTheme.typography.labelSmall, color = colors.error, maxLines = 1)
                }
            }
            val labels = message.labels.shown()
            if (labels.isNotEmpty() || folder != null) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (folder != null) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(13.dp))
                        Text(folder, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                    labels.forEach { label -> LabelChip(label) }
                }
            }
        }
        IconButton(onClick = onStar) {
            Icon(
                if (message.flagged) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                contentDescription = if (message.flagged) s.unstar else s.star,
                tint = if (message.flagged) colors.primary else colors.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Etiket: renkli nokta + ad, kılcal çerçeveli hap. */
@Composable
internal fun LabelChip(label: MailLabel) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.clip(CircleShape).border(1.dp, colors.outlineVariant, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(label.color()))
        Spacer(Modifier.width(5.dp))
        Text(labelTitle(label), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}
