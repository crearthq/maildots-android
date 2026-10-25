package io.maildots.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Ana ekran widget'ı köprüsü. Swift tarafı [publish]'i kurar (iosApp/WidgetPublisher.swift):
 * anlık görüntüyü widget ile paylaşılan Keychain grubuna yazar ve widget'ı yeniler.
 */
object WidgetBridge {
    /** JSON anlık görüntü; null = widget'ı boşalt (hesap kalmadı). */
    var publish: ((String?) -> Unit)? = null
}

/**
 * Etkin hesabın gelen kutusundaki son iletiler, widget için (oluşturucu ortak kodda:
 * [widgetUpdate]). Dönüş: null = değişiklik yok (gelen kutusu ekranda değil), "" = boşalt,
 * diğerleri JSON.
 */
internal fun widgetSnapshot(state: AppState): String? = when (val update = widgetUpdate(state, WIDGET_ITEMS_IOS)) {
    null -> null
    WidgetUpdate.Clear -> ""
    is WidgetUpdate.Show -> update.snapshot.toJson()
}

/** Durum değiştikçe widget'ı günceller; aynı görüntü yeniden yazılmaz. */
internal fun startWidgetSync(controller: AppController, scope: CoroutineScope) {
    scope.launch {
        controller.state.map(::widgetSnapshot).filterNotNull().distinctUntilChanged().collect { snapshot ->
            WidgetBridge.publish?.invoke(snapshot.ifEmpty { null })
        }
    }
}
