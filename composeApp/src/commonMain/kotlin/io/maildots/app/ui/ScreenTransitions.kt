package io.maildots.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.PredictiveBackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import io.maildots.app.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Gezinme derinliği: derine gidiş sağdan girer, geri dönüş sağa doğru çıkar. */
internal fun Screen.depth(): Int = when (this) {
    is Screen.AddAccount, Screen.Mailbox -> 0
    Screen.Search, Screen.Settings -> 1
    is Screen.Reader, is Screen.AccountSettings -> 2
    is Screen.Composer, is Screen.AccountFolders, is Screen.AccountLabels, is Screen.WebSettings -> 3
}

/**
 * Geri kaydırılırken altta gösterilen ekran; [io.maildots.app.AppController.back] ile aynı hedef
 * (orada değişirse burası da güncellenmeli). Yazma ekranı ve hesap ekleme için null: onlarda
 * parmağı izleyen kaydırma yapılmaz, geri dönüş bırakınca olur.
 */
internal fun backTarget(screen: Screen): Screen? = when (screen) {
    is Screen.Reader -> if (screen.fromSearch) Screen.Search else Screen.Mailbox
    Screen.Settings, Screen.Search -> Screen.Mailbox
    is Screen.AccountSettings -> Screen.Settings
    is Screen.AccountFolders -> Screen.AccountSettings(screen.email)
    is Screen.AccountLabels -> Screen.AccountSettings(screen.email)
    is Screen.WebSettings -> Screen.AccountSettings(screen.email)
    is Screen.Composer, is Screen.AddAccount, Screen.Mailbox -> null
}

private const val SLIDE_MS = 280

/**
 * Ekranlar arası geçiş. Geri hareketi (iOS'ta kenardan kaydırma, Android'de tahmini geri)
 * parmağı izler: üstteki ekran sağa kayarken altında dönülecek ekran görünür; bırakınca
 * tamamlanır ya da geri yerine oturur. Düğmeyle gezinmede kısa bir kayma animasyonu oynar.
 */
// PredictiveBackHandler'ın yerine önerilen NavigationEventHandler henüz deneysel; ilerlemeyi bu da veriyor.
@Suppress("DEPRECATION")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ScreenHost(
    screen: Screen,
    /** Gelen kutusu kalıcı alt katmanda tutulur (hesap varken). */
    keepMailbox: Boolean,
    backEnabled: Boolean,
    onBack: () -> Unit,
    content: @Composable (Screen) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(screen)
    val back by rememberUpdatedState(onBack)
    /** Geri kaydırma oranı (0..1). */
    val swipe = remember { Animatable(0f) }
    /** Parmakla kaydırılan ekran (yalnızca o kayar). */
    var dragged by remember { mutableStateOf<Screen?>(null) }
    /** Hareketle tamamlanan dönüşte kayma zaten oynandı: geçiş animasyonsuz olur. */
    var skipTransition by remember { mutableStateOf(false) }

    PredictiveBackHandler(enabled = backEnabled) { progress ->
        val from = current
        if (backTarget(from) == null) {
            progress.collect {}
            back()
            return@PredictiveBackHandler
        }
        dragged = from
        try {
            progress.collect { swipe.snapTo(it.progress.coerceIn(0f, 1f)) }
            swipe.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
            skipTransition = true
            back()
        } catch (e: CancellationException) {
            // Vazgeçildi: ekran yerine oturur.
            scope.launch {
                swipe.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                dragged = null
            }
            throw e
        }
    }
    LaunchedEffect(screen) {
        skipTransition = false
        if (dragged != null && dragged != screen) {
            dragged = null
            swipe.snapTo(0f)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val underlay = backTarget(screen)
        val swiping by remember { derivedStateOf { swipe.value > 0f } }
        val swipingHere = swiping && dragged == screen && underlay != null
        val mailboxUnder = keepMailbox && underlay == Screen.Mailbox
        // Üstte gösterilen ekran; gelen kutusu kalıcı katmandaysa üstte bir şey yok (null).
        val top = screen.takeUnless { keepMailbox && it == Screen.Mailbox }
        val transition = updateTransition(top, label = "screens")

        // Gelen kutusu kalıcı alt katman: geri dönüşte yeniden kurulmaz (ilk kuruluş takılması
        // olmaz), kaydırma konumu korunur. Tamamen örtülüyken çizilmez.
        if (keepMailbox) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val revealing = swipe.value > 0f && dragged == current && backTarget(current) == Screen.Mailbox
                    alpha = if (top == null || transition.isRunning || revealing) 1f else 0f
                    translationX = if (revealing) -(1f - swipe.value) * width * 0.3f else 0f
                },
            ) { content(Screen.Mailbox) }
        }
        if (swipingHere && !mailboxUnder) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = -(1f - swipe.value) * width * 0.3f }) {
                content(underlay!!)
            }
        }
        if (swipingHere) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - swipe.value }.background(Color.Black.copy(alpha = 0.12f)))
        }
        transition.AnimatedContent(
            transitionSpec = {
                val from = initialState?.depth() ?: 0
                val to = targetState?.depth() ?: 0
                val slide = tween<androidx.compose.ui.unit.IntOffset>(SLIDE_MS, easing = FastOutSlowInEasing)
                when {
                    skipTransition -> EnterTransition.None togetherWith ExitTransition.None
                    to > from -> slideInHorizontally(slide) { it } togetherWith slideOutHorizontally(slide) { -it / 4 }
                    // Geri dönüşte çıkan ekran üstte kalır ve sağa kayar.
                    to < from -> ContentTransform(
                        targetContentEnter = slideInHorizontally(slide) { -it / 4 },
                        initialContentExit = slideOutHorizontally(slide) { it },
                        targetContentZIndex = -1f,
                    )
                    else -> fadeIn(tween(160)) togetherWith fadeOut(tween(160))
                }
            },
        ) { target ->
            if (target != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { if (target == dragged) translationX = swipe.value * width }
                        // Üstteki ekran opaktır ve dokunuşları alttaki gelen kutusuna geçirmez.
                        .background(MaterialTheme.colorScheme.background)
                        .pointerInput(Unit) {},
                ) {
                    content(target)
                }
            }
        }
    }
}
