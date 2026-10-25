package io.maildots.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Karşılama sayfası metni: başlık, açıklama ve "özellik — durum" satırları. */
class WelcomePage(val title: String, val body: String, val rows: List<Pair<String, String>>)

/** Sayfa sırasına göre satır simgeleri (metinler dile göre değişir, simgeler değişmez). */
private val ROW_ICONS: List<List<ImageVector>> = listOf(
    listOf(Icons.Outlined.Inbox, Icons.Outlined.MarkEmailUnread, Icons.AutoMirrored.Outlined.Label),
    listOf(Icons.Outlined.Lock, Icons.Outlined.VisibilityOff, Icons.Outlined.Sync),
    listOf(Icons.Outlined.Block, Icons.Outlined.Visibility, Icons.Outlined.Image),
    listOf(Icons.Outlined.Badge, Icons.Outlined.Lock),
)

/**
 * İlk açılış karşılaması: uygulamayı, güvenliği ve gizliliği dört kısa sayfada anlatır.
 * Yalnızca bir kez gösterilir; "Atla" ya da son sayfadaki "Başla" giriş ekranına götürür.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val s = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    val pages = s.welcomePages
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex

    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }

    Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Wordmark()
            Spacer(Modifier.weight(1f))
            if (!last) {
                TextButton(onClick = onDone) { Text(s.welcomeSkip, color = colors.onSurfaceVariant) }
            }
        }

        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { index ->
            val page = pages[index]
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
                    WelcomeArt(index, active = pager.currentPage == index, Modifier.fillMaxWidth().height(232.dp))
                    Spacer(Modifier.height(20.dp))
                    Text(
                        (index + 1).toString().padStart(2, '0') + " / " + pages.size.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                        letterSpacing = 0.08.em,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        page.title,
                        Modifier.semantics { heading() },
                        fontSize = 34.sp,
                        lineHeight = 39.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.03).em,
                        color = colors.onBackground,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(page.body, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(32.dp))
                    SpecCard(page.rows, ROW_ICONS.getOrElse(index) { emptyList() })
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                pages.indices.forEach { i ->
                    val active = i == pager.currentPage
                    val width by animateDpAsState(if (active) 22.dp else 6.dp, tween(220))
                    Box(
                        Modifier.height(6.dp).width(width)
                            .background(if (active) colors.primary else colors.outline, CircleShape),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                shape = CircleShape,
                modifier = Modifier.height(52.dp).widthIn(min = 132.dp),
            ) {
                Text(if (last) s.welcomeStart else s.welcomeNext)
            }
        }
    }
}

/** Özellik satırları: ince çizgili kart, solda ad, sağda durumu. */
@Composable
private fun SpecCard(rows: List<Pair<String, String>>, icons: List<ImageVector>) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainerLow, shape).border(1.dp, colors.outlineVariant, shape)) {
        rows.forEachIndexed { i, (name, value) ->
            if (i > 0) HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                icons.getOrNull(i)?.let {
                    Icon(it, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                }
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                Spacer(Modifier.width(12.dp))
                Text(value, style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }
    }
}
