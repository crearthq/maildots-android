package io.maildots.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.uikit.ComposeUIViewControllerDelegate
import androidx.compose.ui.window.ComposeUIViewController
import io.maildots.app.data.AccountRepository
import io.maildots.app.data.KeychainSecureStore
import io.maildots.app.mail.WebMailEngine
import io.maildots.app.ui.App
import io.maildots.app.web.HttpAccountApi
import io.maildots.app.web.PushPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import platform.Foundation.NSLog
import platform.UIKit.UIApplication
import platform.UIKit.UIStatusBarStyle
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent
import platform.UIKit.UIViewController
import platform.UIKit.registerForRemoteNotifications
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** Uygulama ömrü boyunca tek denetleyici. */
object IosApp {
    val controller: AppController by lazy {
        // Bildirim kaydı "ios" olarak gitsin: denetleyiciden önce ayarlanmalı.
        PushPlatform.current = "ios"
        clearAttachmentCache()
        // NSLog'un değişken argümanlarına Kotlin String vermek K/N'de çöker; ileti tek parça verilir.
        val api = HttpAccountApi(userAgent = "MailDots-iOS", log = { NSLog("MailDotsWeb " + it.replace("%", "%%")) })
        lateinit var ref: AppController
        val engine = WebMailEngine(api, api) { account, renew -> ref.webSession(account, renew) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // Demo modu (yalnızca hata ayıklama, -MDDemo): bellekte örnek hesap; Keychain ve ağ yok.
        AppController(
            engine = if (demoMode) DemoMailEngine() else engine,
            repository = if (demoMode) demoRepository() else AccountRepository(KeychainSecureStore()),
            scope = scope,
            api = if (demoMode) null else api,
        ).also {
            ref = it
            // Denetleyici kurulduktan sonra: ana ekran widget'ı gelen kutusunu izler.
            startWidgetSync(it, scope)
        }
    }
}

private val platformParts by lazy { iosPlatform() }

private var notificationAsked = false

/**
 * Bildirim izni, en az bir hesap varken açılış başına bir kez sorulur (karar verildiyse
 * sistem yeniden sormaz). İzin varsa APNs kaydı yapılır; belirteç Swift tarafından gelir.
 */
private fun askNotificationPermission() {
    if (notificationAsked || demoMode) return
    notificationAsked = true
    val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
    UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(options) { granted, _ ->
        if (granted) dispatch_async(dispatch_get_main_queue()) { UIApplication.sharedApplication.registerForRemoteNotifications() }
    }
}

/** Uygulama koyu temadaysa durum çubuğu açık renkli içerik gösterir (sistem temasına değil, uygulamaya uyar). */
private var appDark = false
private var rootController: UIViewController? = null

internal fun applyStatusBar(dark: Boolean) {
    appDark = dark
    rootController?.setNeedsStatusBarAppearanceUpdate()
}

/** Pencerenin kök ekranı (SceneDelegate kurar). */
fun MainViewController(): UIViewController = ComposeUIViewController(configure = {
    delegate = object : ComposeUIViewControllerDelegate {
        override val preferredStatusBarStyle: UIStatusBarStyle?
            get() = if (appDark) UIStatusBarStyleLightContent else UIStatusBarStyleDarkContent
    }
}) {
    val controller = IosApp.controller
    val hasAccount = controller.state.collectAsState().value.accounts.isNotEmpty()
    LaunchedEffect(hasAccount) {
        if (!hasAccount) return@LaunchedEffect
        // İzin penceresi açılış animasyonunun (≈1 sn) üstüne binmesin.
        delay(1200)
        askNotificationPermission()
    }
    App(controller, platformParts)
}.also { rootController = it }

/** Firebase'in verdiği bildirim belirteci (Swift'ten çağrılır). */
fun onPushToken(token: String) = IosApp.controller.onPushToken(token)

/**
 * Widget başlığına dokunulunca: hesabın gelen kutusu listesi açılır (Swift'ten çağrılır).
 * Açık ekranlardan geri dönülür; yazma ekranındaysa taslak her zamanki gibi kaydedilir.
 */
fun openInbox(account: String) = IosApp.controller.openInbox(account)

/** Bildirime dokunulunca ilgili hesabı ve iletiyi açar (Swift'ten çağrılır). */
fun openFromNotification(account: String, folder: String, uid: Long) =
    IosApp.controller.openFromNotification(account, folder, uid)
