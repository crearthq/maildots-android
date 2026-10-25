package io.maildots.app.ui

import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncProblem
import io.maildots.app.WebStatus
import io.maildots.app.ErrorKind
import androidx.compose.ui.platform.LocalUriHandler
import io.maildots.app.web.AccountService
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maildots.app.AppController
import io.maildots.app.AppState
import io.maildots.app.LabelInfo
import io.maildots.app.Screen
import io.maildots.app.SwipeAction
import io.maildots.app.ThemeMode
import io.maildots.app.mail.Account
import io.maildots.app.mail.Folder
import io.maildots.app.mail.FolderRole

/** Ayar sayfalarının ortak iskeleti: geri oku, büyük başlık, kaydırılabilir içerik. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = s.back) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
        containerColor = colors.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Text(
                title,
                Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}

/** Birbirine ait satırları toplayan yuvarlak köşeli grup. */
@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f)),
        content = content,
    )
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector?,
    title: String,
    value: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    chevron: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!value.isNullOrBlank()) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (chevron && onClick != null) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun AccountAvatar(account: Account) {
    val words = account.displayName.ifBlank { account.email.substringBefore('@') }
        .split(' ', '.', '_', '-').filter { it.isNotEmpty() }
    val text = (if (words.size >= 2) "${words[0].first()}${words[1].first()}" else words.firstOrNull()?.take(2).orEmpty()).uppercase()
    Box(Modifier.size(44.dp).clip(CircleShape).background(bubbleColor(account.email)), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    }
}

/** Ayarlar: önce hesaplar (her biri kendi ayar sayfasına gider), altında uygulama ayarları. */
@Composable
fun SettingsScreen(state: AppState, controller: AppController) {
    val s = LocalStrings.current
    val settings = state.settings
    SettingsPage(s.settings, onBack = { controller.back() }) {
        Group {
            state.accounts.forEach { account ->
                SettingRow(
                    icon = null,
                    title = account.displayName.ifBlank { account.email.substringBefore('@') },
                    value = account.email,
                    leading = { AccountAvatar(account) },
                ) { controller.openAccountSettings(account.email) }
            }
            SettingRow(Icons.Outlined.Add, s.addMailbox, tint = MaterialTheme.colorScheme.primary, chevron = false) {
                controller.openAddAccount()
            }
        }

        GroupTitle(s.appearance)
        Group {
            listOf(ThemeMode.SYSTEM to s.themeSystem, ThemeMode.LIGHT to s.themeLight, ThemeMode.DARK to s.themeDark).forEach { (mode, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { controller.setTheme(mode) }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.theme == mode, onClick = { controller.setTheme(mode) })
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        GroupTitle(s.swipeSection)
        Group {
            SwipePicker(s.swipeRight, settings.swipeRight) { controller.setSwipeActions(it, settings.swipeLeft) }
            SwipePicker(s.swipeLeft, settings.swipeLeft) { controller.setSwipeActions(settings.swipeRight, it) }
        }
        Text(
            s.swipeHint,
            Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwipePicker(label: String, current: SwipeAction, onPick: (SwipeAction) -> Unit) {
    val s = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(current.icon(), label, value = s.swipeName(current), chevron = false) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SwipeAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = { Text(s.swipeName(action)) },
                    leadingIcon = { Icon(action.icon(), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        open = false
                        onPick(action)
                    },
                )
            }
        }
    }
}

/** Kaydedilemeyen değişiklik vb. için sayfa üstü uyarı. */
@Composable
private fun ProblemBanner(state: AppState) {
    val s = LocalStrings.current
    state.webProblem?.let { problem ->
        Text(
            s.webProblem(problem),
            Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Tek hesabın ayarları: ad, imza, klasörler, etiketler, web eşleşmesi, çıkış. */
@Composable
fun AccountSettingsScreen(state: AppState, screen: Screen.AccountSettings, controller: AppController) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    val account = state.accounts.firstOrNull { it.email == screen.email }
    if (account == null) {
        // Hesap bu sırada kaldırıldıysa boş sayfa göstermek yerine geri dön.
        controller.back()
        return
    }
    val status = state.web[account.email]
    var editingName by remember { mutableStateOf(false) }
    var editingSignature by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var enteringCode by remember { mutableStateOf(false) }
    var updatingPassword by remember { mutableStateOf(false) }
    val more = s.extra
    val uriHandler = LocalUriHandler.current
    // Sunucu bu cihazdaki şifreyi reddetti (web eşleşmesi ya da posta kutusu girişi).
    val passwordRejected = status == WebStatus.AUTH_FAILED ||
        (state.active?.email == account.email && state.mailbox.error == ErrorKind.AUTH)

    if (updatingPassword) {
        PasswordDialog(state, controller, title = more.updatePassword, body = more.updatePasswordBody, onClose = { updatingPassword = false }) { new ->
            controller.updateStoredPassword(account.email, new)
        }
    }

    if (editingName) {
        TextDialog(
            title = s.displayName,
            hint = s.displayNameHint,
            initial = account.displayName,
            maxLength = 60,
            multiline = false,
            onDismiss = { editingName = false },
        ) {
            editingName = false
            controller.setDisplayName(account.email, it)
        }
    }
    if (editingSignature) {
        if (account.webLinked && account.signatureRich) {
            // Biçimlendirilmiş imza düz metinle ezilmesin: web'de düzenlenir.
            AlertDialog(
                onDismissRequest = { editingSignature = false },
                title = { Text(s.signature) },
                text = { Text(s.signatureRichBody) },
                confirmButton = {
                    TextButton(onClick = {
                        editingSignature = false
                        controller.openWebSettings(account.email)
                    }) { Text(s.editOnWeb) }
                },
                dismissButton = { TextButton(onClick = { editingSignature = false }) { Text(s.cancel) } },
            )
        } else {
            TextDialog(
                title = s.signature,
                hint = s.signatureHint,
                initial = account.signature,
                maxLength = 1000,
                multiline = true,
                onDismiss = { editingSignature = false },
            ) {
                editingSignature = false
                controller.setSignature(account.email, it)
            }
        }
    }
    if (enteringCode) {
        var code by remember { mutableStateOf("") }
        // Doğrulama başarılı olunca pencere kendiliğinden kapanır.
        if (status == WebStatus.SYNCED || status == WebStatus.SYNCING) enteringCode = false
        AlertDialog(
            onDismissRequest = {
                enteringCode = false
                controller.clearWebProblem()
            },
            title = { Text(s.twoFactorTitle) },
            text = {
                Column {
                    Text(s.twoFactorBody, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        code,
                        { code = it.filter { c -> c.isLetterOrDigit() }.take(20) },
                        label = { Text(s.twoFactorCode) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.webProblem?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(s.webProblem(it), color = colors.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = code.length >= 6, onClick = { controller.submitWebCode(account.email, code) }) { Text(s.verify) }
            },
            dismissButton = {
                TextButton(onClick = {
                    enteringCode = false
                    controller.clearWebProblem()
                }) { Text(s.cancel) }
            },
        )
    }
    if (loggingOut) {
        AlertDialog(
            onDismissRequest = { loggingOut = false },
            title = { Text(s.logOut) },
            text = { Text("${account.email}\n\n${s.removeAccountBody}") },
            confirmButton = {
                TextButton(onClick = {
                    loggingOut = false
                    controller.removeAccount(account.email)
                }) { Text(s.logOut, color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { loggingOut = false }) { Text(s.cancel) } },
        )
    }

    SettingsPage(
        title = account.displayName.ifBlank { account.email.substringBefore('@') },
        subtitle = account.email,
        onBack = { controller.back() },
    ) {
        if (!enteringCode) ProblemBanner(state)
        Group {
            SettingRow(Icons.Outlined.Badge, s.displayName, value = account.displayName.ifBlank { s.notSet }) { editingName = true }
            SettingRow(
                Icons.Outlined.Draw,
                s.signature,
                value = account.signature.lineSequence().firstOrNull { it.isNotBlank() } ?: s.notSet,
            ) { editingSignature = true }
        }
        Group {
            SettingRow(Icons.Outlined.Folder, s.folders) { controller.openAccountFolders(account.email) }
            SettingRow(Icons.AutoMirrored.Outlined.Label, s.labels) { controller.openAccountLabels(account.email) }
        }
        // Hesap servisi (webmail) yoksa (yerel deneme sunucusu) bu grup hiç gösterilmez.
        if (status != null || account.webLinked) {
            Group {
                when (status) {
                    WebStatus.NEEDS_CODE -> SettingRow(Icons.Outlined.Sync, s.webSync, value = s.webNeedsCode, tint = colors.primary) {
                        controller.clearWebProblem()
                        enteringCode = true
                    }
                    // Şifre başka yerde değişmiş olabilir: yeni şifre istenir (yeniden denemek yasaklanmaya yol açar).
                    WebStatus.AUTH_FAILED -> SettingRow(Icons.Outlined.SyncProblem, s.webSync, value = s.webAuthFailed, tint = colors.error) {
                        controller.clearPasswordProblem()
                        updatingPassword = true
                    }
                    WebStatus.OFFLINE -> SettingRow(Icons.Outlined.SyncProblem, s.webSync, value = s.webOffline) {
                        controller.syncWeb(account.email, force = true)
                    }
                    WebStatus.SYNCING -> SettingRow(Icons.Outlined.Sync, s.webSync, value = s.webSyncing, onClick = null)
                    WebStatus.SYNCED, null -> SettingRow(Icons.Outlined.Sync, s.webSync, value = s.webSynced, chevron = false) {
                        controller.syncWeb(account.email, force = true)
                    }
                }
                if (account.webLinked) {
                    SettingRow(Icons.Outlined.Language, s.webSettings, value = s.webSettingsHint) {
                        controller.openWebSettings(account.email)
                    }
                }
            }
        }
        // Depolama ve şifre (hesap servisiyle eşleşmiş kutuda).
        val usage = state.usage[account.email]
        if (account.webLinked || usage != null || passwordRejected) {
            Group {
                if (usage != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Storage, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(more.storage, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (usage.unlimited || usage.totalBytes <= 0) more.storageUnlimited(humanBytes(usage.usedBytes))
                                    else more.storageUsed(humanBytes(usage.usedBytes), humanBytes(usage.totalBytes), usage.percent),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                        if (!usage.unlimited && usage.totalBytes > 0) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { (usage.percent.coerceIn(0, 100)) / 100f },
                                modifier = Modifier.fillMaxWidth().padding(start = 38.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = if (usage.percent >= 90) colors.error else colors.primary,
                                trackColor = colors.outlineVariant,
                            )
                        }
                    }
                }
                // Uçtan uca şifreleme (yeni sistem): anahtar bu cihazda açık mı, kilitli mi.
                when (state.e2e[account.email]) {
                    io.maildots.app.E2EStatus.READY ->
                        SettingRow(Icons.Outlined.Lock, s.e2e.statusReady, chevron = false, onClick = null)
                    io.maildots.app.E2EStatus.LOCKED ->
                        SettingRow(Icons.Outlined.Lock, s.e2e.statusLocked, tint = colors.error, chevron = false, onClick = null)
                    else -> {}
                }
                // Şifre uygulamada değiştirilmez: telefonu eline alan biri hesabı ele geçiremesin
                // diye web'in güvenlik sayfası sistem tarayıcısında açılır, orada giriş istenir.
                if (account.webLinked) {
                    SettingRow(Icons.Outlined.Password, more.changePassword, value = more.changePasswordOnWeb) {
                        uriHandler.openUri(AccountService.WEB_URL + AccountService.SECURITY_PATH)
                    }
                }
                // Yalnızca sunucu kayıtlı şifreyi reddedince: başka yerde değişen şifreyi buraya almak için.
                if (passwordRejected) {
                    SettingRow(Icons.Outlined.Key, more.updatePassword, tint = colors.error) {
                        controller.clearPasswordProblem()
                        updatingPassword = true
                    }
                }
            }
        }
        Group {
            SettingRow(Icons.AutoMirrored.Outlined.Logout, s.logOut, tint = colors.error, chevron = false) { loggingOut = true }
        }
    }
}

/**
 * Şifre penceresi: yalnızca cihazdaki kaydı günceller (yeni şifre sunucuda doğrulanır;
 * hesabın şifresi değişmez). Başarılı olunca kapanır.
 */
@Composable
internal fun PasswordDialog(
    state: AppState,
    controller: AppController,
    title: String,
    body: String? = null,
    onClose: () -> Unit,
    onSubmit: (new: String) -> Unit,
) {
    val s = LocalStrings.current
    val more = s.extra
    val colors = MaterialTheme.colorScheme
    var new by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(state.passwordBusy, state.passwordProblem) {
        if (submitted && !state.passwordBusy && state.passwordProblem == null) onClose()
    }
    AlertDialog(
        onDismissRequest = { if (!state.passwordBusy) onClose() },
        title = { Text(title) },
        text = {
            Column {
                body?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    new,
                    { new = it.take(512) },
                    label = { Text(more.newPassword) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.passwordProblem?.let { problem ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        listOf(more.passwordProblems.getValue(problem), state.passwordPolicy).filter { it.isNotBlank() }.joinToString(" "),
                        color = colors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.passwordBusy && new.isNotEmpty(),
                onClick = {
                    submitted = true
                    onSubmit(new)
                },
            ) {
                if (state.passwordBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(s.save)
            }
        },
        dismissButton = { TextButton(enabled = !state.passwordBusy, onClick = onClose) { Text(s.cancel) } },
    )
}

/**
 * Hesabın web ayarları: webmail'in ayar sayfası, uygulama içinde. Uygulamanın oturumu
 * aktarılmaz; filtre, uygulama şifresi gibi ayarlar için web'e ayrıca giriş istenir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSettingsScreen(state: AppState, screen: Screen.WebSettings, controller: AppController, platform: Platform) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    if (state.accounts.none { it.email == screen.email }) {
        controller.back()
        return
    }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(s.webSettings, style = MaterialTheme.typography.titleMedium)
                            Text(screen.email, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { controller.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = s.back) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
                )
                HorizontalDivider(color = colors.outlineVariant)
            }
        },
        containerColor = colors.background,
    ) { padding ->
        val account = state.accounts.first { it.email == screen.email }
        platform.webPage(
            AccountService.WEB_URL + AccountService.SETTINGS_PATH,
            Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
private fun TextDialog(
    title: String,
    hint: String,
    initial: String,
    maxLength: Int,
    multiline: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val s = LocalStrings.current
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value,
                    { value = it.take(maxLength) },
                    singleLine = !multiline,
                    minLines = if (multiline) 4 else 1,
                    maxLines = if (multiline) 8 else 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text(s.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/** Hesabın klasörleri: oluştur, yeniden adlandır, sil. Sistem klasörleri yalnızca listelenir. */
@Composable
fun AccountFoldersScreen(state: AppState, screen: Screen.AccountFolders, controller: AppController) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    val box = state.mailbox
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Folder?>(null) }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(s.newFolder) },
            text = { OutlinedTextField(newName, { newName = it.take(60) }, label = { Text(s.folderName) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    creating = false
                    controller.createFolder(newName)
                    newName = ""
                }) { Text(s.create) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(s.cancel) } },
        )
    }
    editing?.let { folder ->
        FolderEditDialog(
            folder,
            onDismiss = { editing = null },
            onRename = {
                editing = null
                controller.renameFolder(folder.path, it)
            },
            onDelete = {
                editing = null
                controller.deleteFolder(folder.path)
            },
        )
    }

    SettingsPage(s.folders, subtitle = screen.email, onBack = { controller.back() }) {
        if (box.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
        box.error?.let {
            Text(s.error(it), Modifier.padding(horizontal = 24.dp, vertical = 6.dp), color = colors.error, style = MaterialTheme.typography.bodyMedium)
        }
        val custom = box.folders.filter { it.role == FolderRole.OTHER }
        Group {
            SettingRow(Icons.Outlined.CreateNewFolder, s.newFolder, tint = colors.primary, chevron = false) { creating = true }
            custom.forEach { folder ->
                SettingRow(Icons.Outlined.Folder, folder.name, value = folder.total.toString()) { editing = folder }
            }
        }
        val system = box.folders.filter { it.role != FolderRole.OTHER }
        if (system.isNotEmpty()) {
            Group {
                system.forEach { folder ->
                    SettingRow(folder.role.icon(), s.folderName(folder.role, folder.name), value = folder.total.toString(), onClick = null)
                }
            }
        }
    }
}

/** Etiketler: oluştur, renk ver, sil. */
@Composable
fun AccountLabelsScreen(state: AppState, controller: AppController) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LabelInfo?>(null) }

    if (creating) {
        LabelEditDialog(existing = null, onDismiss = { creating = false }, onSave = { name, color ->
            creating = false
            controller.createLabel(name, color)
        })
    }
    editing?.let { info ->
        LabelEditDialog(
            existing = info,
            onDismiss = { editing = null },
            onSave = { _, color ->
                editing = null
                controller.setLabelColor(info.label, color)
            },
            onDelete = if (info.label.isBuiltin && info.id.isEmpty()) null else {
                {
                    editing = null
                    controller.deleteLabel(info.label)
                }
            },
        )
    }

    SettingsPage(s.labels, onBack = { controller.back() }) {
        ProblemBanner(state)
        Group {
            SettingRow(Icons.Outlined.Add, s.newLabel, tint = colors.primary, chevron = false) { creating = true }
            state.labels.forEach { info ->
                SettingRow(
                    icon = null,
                    title = labelTitle(info.label),
                    leading = {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(info.color)))
                        }
                    },
                ) { editing = info }
            }
        }
    }
}
