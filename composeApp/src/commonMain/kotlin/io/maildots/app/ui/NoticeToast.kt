package io.maildots.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.maildots.app.AppController
import io.maildots.app.AppState
import io.maildots.app.Notice
import kotlinx.coroutines.delay

private const val NOTICE_MILLIS = 2400L

/**
 * Kısa işlem bilgisi ("E-posta silindi", "Klasör oluşturuldu" …): altta sade bir satır.
 *
 * Uygulamanın en üst katmanında durur, işlemi başlatan ekran kapansa da görünür.
 * Denetleyicideki bildirim alındığı anda temizlenir; süre burada tutulur.
 */
@Composable
fun NoticeToast(state: AppState, controller: AppController, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    var shown by remember { mutableStateOf<Notice?>(null) }
    var serial by remember { mutableStateOf(0) }
    var visible by remember { mutableStateOf(false) }

    val incoming = state.notice
    LaunchedEffect(incoming) {
        if (incoming != null) {
            shown = incoming
            serial++
            visible = true
            controller.clearNotice()
        }
    }
    LaunchedEffect(serial) {
        if (serial > 0) {
            delay(NOTICE_MILLIS)
            visible = false
        }
    }

    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 100.dp), Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible && shown != null,
            enter = slideInVertically(tween(200)) { it / 2 } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(160)) { it / 2 } + fadeOut(tween(140)),
        ) {
            shown?.let { notice ->
                Text(
                    s.notice(notice),
                    Modifier
                        .clip(CircleShape)
                        .background(colors.inverseSurface)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { visible = false }
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.inverseOnSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
