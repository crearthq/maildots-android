package io.maildots.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.maildots.app.MessageKey
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Listenin üst/alt kenarına bu kadar yaklaşınca sürükleme listeyi kaydırır. */
private val EDGE = 48.dp
private val SCROLL_STEP = 12.dp

/**
 * Avatarla toplu seçim (webmail'in telefon görünümüyle aynı, Gmail tarzı).
 *
 * - Avatara dokunmak o iletiyi seçer/bırakır (seçim yoksa seçimi başlatır).
 * - Avatardan basıp yukarı/aşağı sürüklemek geçilen her satıra ilk satırın YENİ
 *   durumunu verir; geri sürükleyince aralık daralır (bırakılan satırlar sürüklemeden
 *   önceki durumuna döner). Liste kenarında liste kendiliğinden kayar.
 * - Avatar sütunu dışındaki dokunuşlara karışmaz (kaydırma, açma, kaydırarak eylem).
 *
 * Liste düzeyinde, Initial geçişte çalışır: avatar sütunundaki basışı satırın
 * tıklamasından ve listenin kaydırmasından ÖNCE alıp tüketir, böylece çakışma olmaz.
 * Satırlar kaydırılıp ekrandan çıksa da sürükleme sürer (satıra bağlı değil).
 *
 * [keyOf] LazyColumn öğe anahtarını iletiye çevirir (ileti olmayan öğe → null).
 */
@Composable
internal fun Modifier.avatarDragSelect(
    listState: LazyListState,
    keyOf: (Any) -> MessageKey?,
    order: List<MessageKey>,
    selection: Set<MessageKey>,
    onToggle: (MessageKey) -> Unit,
    onSelect: (Set<MessageKey>) -> Unit,
    columnStart: Dp = 8.dp,
    columnEnd: Dp = 64.dp,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentKeyOf by rememberUpdatedState(keyOf)
    val currentOrder by rememberUpdatedState(order)
    val currentSelection by rememberUpdatedState(selection)
    val currentToggle by rememberUpdatedState(onToggle)
    val currentSelect by rememberUpdatedState(onSelect)

    return pointerInput(listState, rtl) {
        coroutineScope {
            val scope = this
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val x = if (rtl) size.width - down.position.x else down.position.x
                if (x < columnStart.toPx() || x > columnEnd.toPx()) return@awaitEachGesture

                fun keyAt(y: Float): MessageKey? = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { y >= it.offset && y < it.offset + it.size }
                    ?.let { currentKeyOf(it.key) }

                val anchor = keyAt(down.position.y) ?: return@awaitEachGesture
                down.consume()

                val original = currentSelection
                val value = anchor !in original
                var lastY = down.position.y
                var moved = false
                var scroller: Job? = null

                fun apply(y: Float) {
                    val key = keyAt(y) ?: return
                    val keys = currentOrder
                    val from = keys.indexOf(anchor)
                    val to = keys.indexOf(key)
                    if (from < 0 || to < 0) return
                    val range = keys.subList(minOf(from, to), maxOf(from, to) + 1)
                    val next = if (value) original + range else original - range.toSet()
                    if (next != currentSelection) currentSelect(next)
                }

                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            if (!moved) currentToggle(anchor)
                            break
                        }
                        change.consume()
                        lastY = change.position.y
                        if (!moved && abs(lastY - down.position.y) > viewConfiguration.touchSlop) {
                            moved = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val edge = EDGE.toPx()
                            val step = SCROLL_STEP.toPx()
                            scroller = scope.launch {
                                while (isActive) {
                                    val h = size.height.toFloat()
                                    val delta = when {
                                        lastY < edge -> -step
                                        lastY > h - edge -> step
                                        else -> 0f
                                    }
                                    if (delta != 0f) {
                                        listState.scrollBy(delta)
                                        apply(lastY.coerceIn(2f, h - 2f))
                                    }
                                    delay(16)
                                }
                            }
                        }
                        if (moved) apply(lastY)
                    }
                } finally {
                    scroller?.cancel()
                }
            }
        }
    }
}
