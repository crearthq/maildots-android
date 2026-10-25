package io.maildots.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import io.maildots.app.mail.Account
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private val SMALL = 46.dp
private val LARGE = 68.dp

/** İki hesap merkezi arası. */
private val STEP = 66.dp

/** Kaydırırken parmak bu kadar aşağı inerse vazgeçilir, çark etkin hesaba döner. */
private val CANCEL_DROP = 44.dp

/**
 * Takılan konum: tam sayıların (hesap noktalarının) çevresinde yapışkan, aralarda hızlı.
 * Parmak düzgün ilerlerken çark noktadan noktaya takılarak döner (çark-ı felek hissi).
 */
private fun detent(x: Float): Float {
    val k = x.roundToInt()
    val d = x - k
    return k + 4f * d * d * d
}

/**
 * Hesap çarkı (Yandex Mail tarzı): hesaplar tek sırada, ortadaki çizgiye tık tık oturur.
 * Kaydırırken ortaya gelen hesap [onPreview] ile bildirilir (başlık canlı değişir), her
 * geçişte kısa titreşim olur. Bırakınca en yakın hesaba oturulur ve [onSelect] çağrılır;
 * kaydırırken parmak aşağı çekilirse vazgeçilir, çark etkin hesaba döner. "+" son hesabın
 * sağındadır ([onAdd]). Bir balona dokunmak da çarkı oraya döndürür.
 */
@Composable
internal fun AccountWheel(
    accounts: List<Account>,
    activeEmail: String?,
    unread: Map<String, Int>,
    onPreview: (Account?) -> Unit,
    onSelect: (Account) -> Unit,
    onAdd: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val activeIndex = accounts.indexOfFirst { it.email == activeEmail }.coerceAtLeast(0)
    val position = remember { Animatable(activeIndex.toFloat()) }
    val current by rememberUpdatedState(accounts)
    val currentActive by rememberUpdatedState(activeIndex)
    val preview by rememberUpdatedState(onPreview)
    val select by rememberUpdatedState(onSelect)
    val last = (accounts.size - 1).coerceAtLeast(0)

    // Etkin hesap başka yoldan değişince çark ona döner.
    LaunchedEffect(activeIndex, accounts.size) {
        if (!position.isRunning) position.animateTo(activeIndex.toFloat(), spring(stiffness = 500f))
    }
    // Ortadan geçen her hesapta tık.
    LaunchedEffect(last) {
        snapshotFlow { position.value.roundToInt().coerceIn(0, last) }.distinctUntilChanged().drop(1).collect {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }

    fun settle(target: Int) {
        scope.launch {
            position.animateTo(target.toFloat(), spring(dampingRatio = 0.75f, stiffness = 600f))
            preview(null)
            current.getOrNull(target)?.let { if (target != currentActive) select(it) }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(LARGE + 16.dp)
            .clipToBounds()
            .pointerInput(last) {
                val stepPx = STEP.toPx()
                val cancelPx = CANCEL_DROP.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = position.value
                    var dragging = false
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!change.pressed) {
                            if (dragging) {
                                change.consume()
                                settle(if (cancelled) currentActive else position.value.roundToInt().coerceIn(0, last))
                            }
                            break
                        }
                        if (!dragging) {
                            if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) break // dikey: menü kayar
                            if (abs(dx) > viewConfiguration.touchSlop) dragging = true else continue
                        }
                        change.consume()
                        if (cancelled) continue
                        if (dy > cancelPx) {
                            cancelled = true
                            preview(null)
                            scope.launch { position.animateTo(currentActive.toFloat(), spring(stiffness = 500f)) }
                            continue
                        }
                        val raw = (start - dx / stepPx).coerceIn(-0.35f, last + 0.35f)
                        val shown = detent(raw)
                        scope.launch { position.snapTo(shown) }
                        preview(current.getOrNull(shown.roundToInt().coerceIn(0, last)))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val p = position.value
        accounts.forEachIndexed { i, account ->
            val near = (1f - abs(i - p)).coerceIn(0f, 1f)
            val x = with(density) { ((i - p) * STEP.toPx()).roundToInt() }
            Box(Modifier.offset { IntOffset(x, 0) }) {
                AccountBubble(
                    account,
                    active = i == p.roundToInt(),
                    unread = unread[account.email] ?: 0,
                    size = lerp(SMALL, LARGE, near),
                ) { if (i != p.roundToInt()) settle(i) }
            }
        }
        val addX = with(density) { ((accounts.size - p) * STEP.toPx()).roundToInt() }
        Box(
            Modifier
                .offset { IntOffset(addX, 0) }
                .size(SMALL)
                .clip(CircleShape)
                .border(1.dp, colors.outline, CircleShape)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = LocalStrings.current.addMailbox, tint = colors.onSurface, modifier = Modifier.size(22.dp))
        }
    }
}
