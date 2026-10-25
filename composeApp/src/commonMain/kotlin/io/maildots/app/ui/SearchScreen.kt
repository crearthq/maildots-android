package io.maildots.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.maildots.app.AppController
import io.maildots.app.AppState
import io.maildots.app.mail.FolderRole
import io.maildots.app.mail.MailLabel
import io.maildots.app.mail.SearchQuery
import io.maildots.app.mail.SearchScope

/**
 * Arama kutusu pencerenin kendi başlık çubuğunda mı (Windows masaüstü). Öyleyse arama ekranı
 * kendi kutusunu göstermez, odak da almaz; metin aynı durumdan (state.search.query) okunur.
 */
val LocalSearchInTitleBar = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Arama: üstte arama kutusu, altında kaydırılabilir süzgeçler, sonra sonuçlar.
 * Yazarken (kısa bir duraklamadan sonra) ve süzgeç değişince kendiliğinden aranır.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(state: AppState, controller: AppController, platform: Platform) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    val search = state.search
    val query = search.query
    val folders = state.mailbox.folders
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var asking by remember { mutableStateOf<PersonField?>(null) }
    val inTitleBar = LocalSearchInTitleBar.current

    // Ekran ilk açıldığında klavye hazır gelsin; sonuçtan geri dönünce açılmasın.
    LaunchedEffect(Unit) { if (!search.searched && !inTitleBar) focus.requestFocus() }

    fun update(next: SearchQuery) = controller.setQuery(next)
    fun submit() {
        focusManager.clearFocus()
        controller.runSearch()
    }

    asking?.let { field ->
        PersonDialog(
            title = if (field == PersonField.FROM) s.chipSender else s.chipRecipient,
            hint = if (field == PersonField.FROM) s.senderHint else s.recipientHint,
            initial = if (field == PersonField.FROM) query.from else query.to,
            onDismiss = { asking = null },
        ) { value ->
            asking = null
            update(if (field == PersonField.FROM) query.copy(from = value) else query.copy(to = value))
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            Column(Modifier.background(colors.background).statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { controller.back() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = s.back)
                    }
                    if (inTitleBar) {
                        // Kutu başlık çubuğunda: burada yalnız ekran adı.
                        Text(s.search, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
                    } else Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.text.isEmpty()) {
                            Text(s.searchHint, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = query.text,
                            onValueChange = { controller.typeQuery(it.take(120)) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                            cursorBrush = SolidColor(colors.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submit() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).padding(vertical = 12.dp),
                        )
                    }
                    if (query.text.isNotEmpty() && !inTitleBar) {
                        IconButton(onClick = { update(query.copy(text = "")) }) {
                            Icon(Icons.Outlined.Close, contentDescription = s.clear)
                        }
                    }
                }

                // Süzgeçler
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Klasör: bulunulan klasör / tümü / seçilen klasör
                    val folderLabel = query.folderPath?.let { path ->
                        folders.firstOrNull { it.path == path }?.let { s.folderName(it.role, it.name) } ?: path
                    } ?: s.allFolders
                    MenuChip(label = folderLabel, active = true) { close ->
                        MenuOption(s.allFolders, query.folderPath == null) {
                            close()
                            update(query.copy(folderPath = null))
                        }
                        folders.forEach { folder ->
                            MenuOption(s.folderName(folder.role, folder.name), query.folderPath == folder.path) {
                                close()
                                update(query.copy(folderPath = folder.path))
                            }
                        }
                    }
                    ToggleChip(s.unreadView, query.unread) { update(query.copy(unread = !query.unread)) }
                    ToggleChip(s.withAttachments, query.attachments) { update(query.copy(attachments = !query.attachments)) }
                    ToggleChip(s.starredView, query.flagged) { update(query.copy(flagged = !query.flagged)) }

                    val dates = listOf(null to s.anyDate, 7 to s.last7, 30 to s.last30, 365 to s.lastYear)
                    MenuChip(
                        label = dates.firstOrNull { it.first == query.sinceDays && it.first != null }?.second ?: s.chipDate,
                        active = query.sinceDays != null,
                    ) { close ->
                        dates.forEach { (days, name) ->
                            MenuOption(name, query.sinceDays == days) {
                                close()
                                update(query.copy(sinceDays = days))
                            }
                        }
                    }

                    MenuChip(
                        label = query.label?.let { labelTitle(it) } ?: s.chipLabel,
                        active = query.label != null,
                        dot = query.label,
                    ) { close ->
                        MenuOption(s.anyLabel, query.label == null) {
                            close()
                            update(query.copy(label = null))
                        }
                        LocalLabels.current.map { it.label }.forEach { label ->
                            MenuOption(labelTitle(label), query.label == label, dot = label) {
                                close()
                                update(query.copy(label = label))
                            }
                        }
                    }

                    ToggleChip(
                        if (query.from.isBlank()) s.chipSender else "${s.chipSender}: ${query.from}",
                        query.from.isNotBlank(),
                    ) { asking = PersonField.FROM }
                    ToggleChip(
                        if (query.to.isBlank()) s.chipRecipient else "${s.chipRecipient}: ${query.to}",
                        query.to.isNotBlank(),
                    ) { asking = PersonField.TO }

                    val scopes = listOf(
                        SearchScope.ANYWHERE to s.scopeAnywhere,
                        SearchScope.SUBJECT to s.scopeSubject,
                        SearchScope.BODY to s.scopeBody,
                    )
                    MenuChip(
                        label = if (query.scope == SearchScope.ANYWHERE) s.chipSearchIn else scopes.first { it.first == query.scope }.second,
                        active = query.scope != SearchScope.ANYWHERE,
                    ) { close ->
                        scopes.forEach { (scope, name) ->
                            MenuOption(name, query.scope == scope) {
                                close()
                                update(query.copy(scope = scope))
                            }
                        }
                    }
                }
                if (search.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider(color = colors.outlineVariant)
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
        ) {
            when {
                search.error != null -> item {
                    CenterMessage(Modifier.fillParentMaxHeight(0.7f), s.error(search.error)) {
                        Button(onClick = { controller.runSearch() }, shape = CircleShape) { Text(s.retry) }
                    }
                }

                // Henüz aranmadı: son aramalar ya da yönlendirme.
                !search.searched && !search.loading -> {
                    if (state.recentSearches.isEmpty()) {
                        item { CenterMessage(Modifier.fillParentMaxHeight(0.6f), s.searchPrompt) {} }
                    } else {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    s.recentSearches,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.onSurfaceVariant,
                                )
                                TextButton(onClick = { controller.clearRecentSearches() }) { Text(s.clear) }
                            }
                        }
                        items(state.recentSearches, key = { it }) { recent ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        focusManager.clearFocus()
                                        update(query.copy(text = recent))
                                    }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.History, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(16.dp))
                                Text(recent, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                search.searched && search.results.isEmpty() && !search.loading -> item {
                    CenterMessage(Modifier.fillParentMaxHeight(0.6f), s.noResults) {}
                }

                else -> items(search.results, key = { "${it.folderPath}/${it.uid}" }) { message ->
                    Column {
                        MessageRow(
                            message,
                            date = platform.formatShortDate(message.dateMillis),
                            onClick = { controller.openMessage(message) },
                            onStar = { controller.toggleFlag(message) },
                            // Tüm klasörlerde aranırken iletinin nerede olduğunu göster.
                            folder = if (query.folderPath != null) null else {
                                folders.firstOrNull { it.path == message.folderPath }?.let { s.folderName(it.role, it.name) }
                                    ?: message.folderPath
                            },
                        )
                        HorizontalDivider(Modifier.padding(start = 68.dp), color = colors.outlineVariant)
                    }
                }
            }
        }
    }
}

private enum class PersonField { FROM, TO }

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    selectedLabelColor = MaterialTheme.colorScheme.primary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.primary,
)

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        shape = CircleShape,
        colors = chipColors(),
    )
}

/** Açılır seçenekli süzgeç. [dot] verilirse etiket rengi gösterilir. */
@Composable
private fun MenuChip(
    label: String,
    active: Boolean,
    dot: MailLabel? = null,
    options: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active,
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            leadingIcon = dot?.let { { Box(Modifier.size(8.dp).clip(CircleShape).background(it.color())) } },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            shape = CircleShape,
            colors = chipColors(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { options { open = false } }
    }
}

@Composable
private fun MenuOption(label: String, selected: Boolean, dot: MailLabel? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    DropdownMenuItem(
        text = { Text(label, color = if (selected) colors.primary else colors.onSurface) },
        leadingIcon = dot?.let { { Box(Modifier.size(10.dp).clip(CircleShape).background(it.color())) } },
        trailingIcon = if (selected) {
            { Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp)) }
        } else null,
        onClick = onClick,
    )
}

@Composable
private fun PersonDialog(title: String, hint: String, initial: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    val s = LocalStrings.current
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value,
                { value = it.take(120) },
                label = { Text(hint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onApply(value.trim()) }),
            )
        },
        confirmButton = { TextButton(onClick = { onApply(value.trim()) }) { Text(s.apply) } },
        dismissButton = {
            TextButton(onClick = { if (initial.isBlank()) onDismiss() else onApply("") }) {
                Text(if (initial.isBlank()) s.cancel else s.clear)
            }
        },
    )
}
