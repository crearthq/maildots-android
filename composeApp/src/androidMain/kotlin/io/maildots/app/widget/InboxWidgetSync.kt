package io.maildots.app.widget

import android.content.Context
import io.maildots.app.AppController
import io.maildots.app.DevServer
import io.maildots.app.WIDGET_ITEMS_ANDROID
import io.maildots.app.WidgetUpdate
import io.maildots.app.widgetUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Uygulama süreci widget'ı besler: gelen kutusu değiştikçe özet şifreli depoya yazılır ve
 * widget yeniden çizilir. Aynı özet yeniden yazılmaz (ortak [widgetUpdates]). Zamanlayıcı yok.
 */
object InboxWidgetSync {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(context: Context, controller: AppController) {
        val app = context.applicationContext
        // Depo işleri tek sırada ve ana iş parçacığı dışında (Keystore çağrıları).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        scope.launch {
            widgetUpdates(controller.state, WIDGET_ITEMS_ANDROID).collect { update ->
                // Yalnız hata ayıklama: örnek veri gösterilirken gerçek özet yazılmaz.
                if (DevServer.widgetDemoActive) return@collect
                runCatching {
                    when (update) {
                        WidgetUpdate.Clear -> WidgetStore.clear(app)
                        is WidgetUpdate.Show -> WidgetStore.write(app, update.snapshot)
                    }
                    InboxWidget.refresh(app)
                }
            }
        }
        // Kaldırılan hesabın özeti, başka hesap kalsa bile hemen silinir (yeni hesabın gelen
        // kutusu yüklenene kadar eski hesabın postası görünmesin).
        scope.launch {
            controller.state.map { s -> s.accounts.map { it.email }.toSet() }.distinctUntilChanged().collect { emails ->
                if (DevServer.widgetDemoActive) return@collect
                runCatching { if (WidgetStore.dropUnlessAccountIn(app, emails)) InboxWidget.refresh(app) }
            }
        }
    }
}
