package io.maildots.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import io.maildots.app.kvo.MDKeyPathObserver
import io.maildots.app.web.AccountService
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.viewPrintFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.WebKit.WKContentRuleList
import platform.WebKit.WKContentRuleListStore
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationTypeLinkActivated
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebpagePreferences
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/** Gövde sayfası (Android'deki mailPage ile aynı görünüm). */
internal fun mailPage(body: String, light: Boolean, heading: String? = null, imageOrigin: String? = null): String {
    val fg = if (light) "#090c1d" else "#ededee"
    val bg = if (light) "#ffffff" else "#18181a"
    val link = if (light) "#2a38ec" else "#8f98ff"
    val rule = if (light) "#cfccc1" else "#45454a"
    val title = heading?.let { "<h2 style=\"font-weight:500;margin:0 0 16px\">${htmlEncode(it)}</h2>" }.orEmpty()
    return """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:${imageOrigin?.let { " $it" }.orEmpty()}; font-src data:">
        <style>
          html,body{margin:0;padding:0;background:$bg;color:$fg;font:16px/1.55 -apple-system,sans-serif;word-wrap:break-word;overflow-wrap:break-word;-webkit-text-size-adjust:100%}
          body{padding:16px 20px}
          a{color:$link} img{max-width:100%;height:auto} table{max-width:100%}
          blockquote{margin:0 0 0 8px;padding-left:12px;border-left:2px solid $rule}
          pre{white-space:pre-wrap;font:inherit}
        </style></head><body>$title$body</body></html>
    """.trimIndent()
}

internal fun htmlEncode(text: String): String = buildString(text.length) {
    text.forEach { c ->
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}

/**
 * Posta gövdesi için ağ kuralları (WKContentRuleList). Belge dışındaki her yükleme engellenir;
 * vekilli kuralda tek istisna webmail'in görsel vekilidir. Alt çerçeve ve sayfa geçişlerini
 * gezinme temsilcisi keser.
 */
@OptIn(ExperimentalForeignApi::class)
internal object MailContentRules {
    private const val BLOCK_ALL = """[{"trigger":{"url-filter":".*","resource-type":["image","style-sheet","script","font","raw","svg-document","media","popup","ping","fetch","websocket","other"]},"action":{"type":"block"}}]"""
    private const val BLOCK_BUT_PROXY = """[{"trigger":{"url-filter":".*","resource-type":["image","style-sheet","script","font","raw","svg-document","media","popup","ping","fetch","websocket","other"]},"action":{"type":"block"}},{"trigger":{"url-filter":"^https://webmail\\.maildots\\.io/api/img-proxy","resource-type":["image"]},"action":{"type":"ignore-previous-rules"}}]"""

    private val compiled = mutableMapOf<Boolean, WKContentRuleList>()
    private val waiting = mutableMapOf<Boolean, MutableList<(WKContentRuleList?) -> Unit>>()

    /** Kural listesini (bir kez derleyip) ana iş parçacığında verir; derlenemezse null. */
    fun get(withProxy: Boolean, onReady: (WKContentRuleList?) -> Unit) {
        compiled[withProxy]?.let { return onReady(it) }
        val queue = waiting.getOrPut(withProxy) { mutableListOf() }
        queue += onReady
        if (queue.size > 1) return
        val id = if (withProxy) "maildots.mail.proxy.v1" else "maildots.mail.offline.v1"
        WKContentRuleListStore.defaultStore()?.compileContentRuleListForIdentifier(id, if (withProxy) BLOCK_BUT_PROXY else BLOCK_ALL) { list, _ ->
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 0), dispatch_get_main_queue()) {
                if (list != null) compiled[withProxy] = list
                waiting.remove(withProxy)?.forEach { it(list) }
            }
        } ?: waiting.remove(withProxy)?.forEach { it(null) }
    }
}

/** http, https ve mailto bağlantılarını sistemde açar; diğerleri yok sayılır. */
internal fun openExternally(url: NSURL?) {
    val scheme = url?.scheme?.lowercase() ?: return
    if (scheme !in setOf("http", "https", "mailto")) return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}

/**
 * Posta gövdesinin gezinme temsilcisi: yalnızca uygulamanın başlattığı yükleme geçer;
 * bağlantılar dışarıda açılır, diğer her gezinme (meta yenileme, çerçeve) iptal edilir.
 */
@OptIn(ExperimentalForeignApi::class)
internal class MailNavigationDelegate(private val onFinished: (WKWebView) -> Unit) : NSObject(), WKNavigationDelegateProtocol {
    var allowNextLoad = false

    override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
        val action = decidePolicyForNavigationAction
        val mainFrame = action.targetFrame?.mainFrame == true
        when {
            action.navigationType == WKNavigationTypeLinkActivated -> {
                val url = action.request.URL
                // Sayfa içi çapa bağlantıları dışarı gönderilmez.
                if (url?.fragment == null || url.absoluteString?.substringBefore('#') != webView.URL?.absoluteString?.substringBefore('#')) {
                    openExternally(url)
                }
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            }
            mainFrame && allowNextLoad -> {
                allowNextLoad = false
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            }
            else -> decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        }
    }

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onFinished(webView)
    }
}

/** Betik ve ağ kapalı, kendi içinde kaymayan WKWebView yapılandırması. */
@OptIn(ExperimentalForeignApi::class)
internal fun hardenedMailWebView(): WKWebView {
    val config = WKWebViewConfiguration().apply {
        websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
        defaultWebpagePreferences = WKWebpagePreferences().apply { allowsContentJavaScript = false }
        preferences.javaScriptCanOpenWindowsAutomatically = false
        allowsInlineMediaPlayback = false
        dataDetectorTypes = 0u
    }
    return WKWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = config).apply {
        setOpaque(false)
        backgroundColor = UIColor.clearColor
        scrollView.backgroundColor = UIColor.clearColor
        scrollView.scrollEnabled = false
        scrollView.bounces = false
        allowsLinkPreview = false
    }
}

/**
 * Gövde yüksekliğini ölçer; ekrandan geniş içerik (ör. 600px bültenler) genişliğe sığdırılır.
 * Bu betik uygulamanın kendisidir; sayfanın betikleri kapalı kalır.
 */
private const val MEASURE_SCRIPT = """(function(){var d=document.documentElement,b=document.body;if(!b)return '0';
if(!b.dataset.fit){b.dataset.fit='1';var w=d.clientWidth,s=d.scrollWidth;if(w>0&&s>w+1){b.style.zoom=String(w/s);}}
return String(Math.ceil(d.getBoundingClientRect().height));})()"""

/** Ölçülen yüksekliği verir; görseller geç yüklenebileceği için birkaç kez yinelenir. */
@OptIn(ExperimentalForeignApi::class)
private fun measure(webView: WKWebView, onHeight: (Double) -> Unit) {
    listOf(0L, 300L, 1000L, 2500L, 5000L).forEach { delayMs ->
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, delayMs * 1_000_000), dispatch_get_main_queue()) {
            webView.evaluateJavaScript(MEASURE_SCRIPT) { result, _ ->
                val value = (result as? String)?.toDoubleOrNull()
                    ?: (result as? NSNumber)?.doubleValue
                    ?: (result as? Number)?.toDouble()
                if (value != null && value > 0) onHeight(value)
            }
        }
    }
}

/**
 * E-posta HTML'ini güvenli gösterir (Android'deki SafeHtml'in karşılığı):
 * - JavaScript KAPALI; çerez ve önbellek kalıcı değil.
 * - Ağ KAPALI (WKContentRuleList). [baseUrl] hesap servisiyse tek istisna görsel vekili.
 * - CSP ayrıca betik, çerçeve ve form gönderimini yasaklar.
 * - Bağlantılar dışarıda açılır; içerik yüksekliği ölçülüp Compose'a verilir.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun SafeHtml(html: String, light: Boolean?, baseUrl: String?, modifier: Modifier) {
    val useLight = light ?: !isSystemInDarkTheme()
    // Görsel vekiline yalnızca hesap servisinin kendi adresinden izin verilir.
    val origin = baseUrl?.trimEnd('/')?.takeIf { it == AccountService.BASE_URL }
    var height by remember { mutableStateOf(0.0) }
    val holder = remember { arrayOfNulls<String>(1) }
    val delegate = remember { MailNavigationDelegate { view -> measure(view) { height = it } } }
    UIKitView(
        factory = { hardenedMailWebView().apply { navigationDelegate = delegate } },
        modifier = if (height > 0) modifier.height(height.dp) else modifier,
        update = { view ->
            val page = mailPage(html, useLight, imageOrigin = origin)
            if (holder[0] != page) {
                holder[0] = page
                MailContentRules.get(withProxy = origin != null) { rules ->
                    if (holder[0] != page) return@get
                    val controller = view.configuration.userContentController
                    controller.removeAllContentRuleLists()
                    if (rules != null) {
                        controller.addContentRuleList(rules)
                        delegate.allowNextLoad = true
                        view.loadHTMLString(page, baseURL = origin?.let { NSURL.URLWithString(it) })
                    } else {
                        // Kurallar derlenemezse vekil de kapalı: ağsız sayfa, adres yok.
                        delegate.allowNextLoad = true
                        view.loadHTMLString(mailPage(html, useLight), baseURL = null)
                    }
                }
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.navigationDelegate = null
        },
    )
}

/** Ağı kapalı aynı sayfayı sistem yazdırma penceresiyle yazdırır. */
@OptIn(ExperimentalForeignApi::class)
internal object MailPrinter {
    private var active: Pair<WKWebView, MailNavigationDelegate>? = null

    fun print(title: String, html: String?, text: String?) {
        val body = html?.takeIf { it.isNotBlank() } ?: "<pre>${htmlEncode(text.orEmpty())}</pre>"
        val view = hardenedMailWebView().apply { setFrame(CGRectMake(0.0, 0.0, 600.0, 800.0)) }
        val delegate = MailNavigationDelegate { loaded ->
            val printer = platform.UIKit.UIPrintInteractionController.sharedPrintController
            printer.printInfo = platform.UIKit.UIPrintInfo.printInfo().apply { jobName = title }
            printer.printFormatter = loaded.viewPrintFormatter()
            printer.presentAnimated(true) { _, _, _ -> active = null }
        }
        view.navigationDelegate = delegate
        active = view to delegate
        MailContentRules.get(withProxy = false) { rules ->
            rules?.let { view.configuration.userContentController.addContentRuleList(it) }
            delegate.allowNextLoad = true
            view.loadHTMLString(mailPage(body, light = true, heading = title), baseURL = null)
        }
    }
}

/**
 * Web ayarları (webmail) gezinme temsilcisi: ana çerçeve yalnızca hesap servisinin
 * HTTPS adresinde gezinir; diğer her bağlantı sistem tarayıcısında açılır.
 */
@OptIn(ExperimentalForeignApi::class)
private class AccountWebDelegate : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
        val action = decidePolicyForNavigationAction
        val url = action.request.URL
        val inside = url?.scheme == "https" && url.host == AccountService.WEB_HOST
        val frame = action.targetFrame
        when {
            // Alt çerçeveler sayfanın kendi işidir; çerez yalnızca webmail adresine kurulu.
            frame != null && !frame.mainFrame -> decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            inside && frame != null -> decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            url?.absoluteString == "about:blank" -> decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            else -> {
                // Yeni pencere isteyen (target=_blank) iç bağlantı aynı görünümde açılır.
                if (inside) webView.loadRequest(action.request) else openExternally(url)
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            }
        }
    }
}

/**
 * Web girişten sonra ayarlara dönüş. Web, girişten sonra sayfayı yenilemeden (geçmiş API'siyle)
 * gelen kutusuna geçer; WKNavigationDelegate bunu görmez, bu yüzden `WKWebView.URL` KVO ile izlenir
 * ([MDKeyPathObserver], kvo.def). Kural Android'deki `doUpdateVisitedHistory` ile aynı: `/login`
 * görülünce giriş başlar, ardından `/settings` dışında bir adres gelirse [url] bir kez yeniden yüklenir.
 */
@OptIn(ExperimentalForeignApi::class)
private class AccountWebReturn(private val url: String) {
    /** Giriş sayfası görüldü; giriş sonrası ayarlara bir kez dönülecek. */
    private var signingIn = false
    private var observer: MDKeyPathObserver? = null

    fun attach(view: WKWebView) {
        observer = MDKeyPathObserver(target = view, keyPath = "URL") { (it as? WKWebView)?.let(::onUrlChanged) }
    }

    fun detach() {
        observer?.invalidate()
        observer = null
    }

    private fun onUrlChanged(view: WKWebView) {
        val current = view.URL ?: return
        if (current.scheme != "https" || current.host != AccountService.WEB_HOST) return
        val path = current.path.orEmpty()
        if (path == "/login") {
            signingIn = true
        } else if (signingIn && !path.contains("/settings")) {
            signingIn = false
            NSURL.URLWithString(url)?.let { view.loadRequest(NSURLRequest.requestWithURL(it)) }
        }
    }
}

/**
 * Hesabın web ayar sayfası, uygulama içinde:
 * - Uygulamanın oturumu aktarılmaz: kullanıcı web'e sayfada kendisi giriş yapar,
 *   girişten sonra ayarlara geri dönülür ([AccountWebReturn]).
 * - Kalıcı olmayan veri deposu: sayfa kapanınca çerez ve önbellek atılır.
 * - Yalnızca o adreste gezinilir; başka bağlantılar sistem tarayıcısına gider.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun AccountWebPage(url: String, modifier: Modifier) {
    val delegate = remember { AccountWebDelegate() }
    val returner = remember(url) { AccountWebReturn(url) }
    UIKitView(
        factory = {
            val store = WKWebsiteDataStore.nonPersistentDataStore()
            val config = WKWebViewConfiguration().apply {
                websiteDataStore = store
                dataDetectorTypes = 0u
            }
            val view = WKWebView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), configuration = config)
            view.navigationDelegate = delegate
            returner.attach(view)
            NSURL.URLWithString(url)?.let { view.loadRequest(NSURLRequest.requestWithURL(it)) }
            view
        },
        modifier = modifier,
        onRelease = { view ->
            view.stopLoading()
            returner.detach()
            view.navigationDelegate = null
            val store = view.configuration.websiteDataStore
            store.removeDataOfTypes(WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince = platform.Foundation.NSDate.dateWithTimeIntervalSince1970(0.0)) {}
        },
    )
}
