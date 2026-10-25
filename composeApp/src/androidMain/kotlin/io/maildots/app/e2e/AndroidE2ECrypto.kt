package io.maildots.app.e2e

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Android'de kripto çekirdeği: webmail'in tarayıcı kodu (assets/e2e/core.js), ekranda
 * görünmeyen bir WebView'da çalışır.
 *
 * - Sayfa yalnızca uygulamanın kendi varlıklarından gelir (https://appassets.androidplatform.net
 *   güvenli bağlam olduğu için WebCrypto çalışır). Başka HER istek 403 ile kesilir; sayfanın
 *   CSP'si de ağı yasaklar. Gezinme kapalıdır, dosya/içerik erişimi kapalıdır.
 * - Köprüde yalnızca iki işlev vardır: bağımsız değişkeni almak ve yanıtı vermek.
 * - Büyük iletiler betik içine gömülmez: çekirdek bağımsız değişkeni köprüden çeker.
 */
class AndroidE2ECrypto(context: Context) : BridgedE2ECrypto() {
    private val app = context.applicationContext
    private val loadLock = Mutex()
    private var webView: WebView? = null
    private var ready: CompletableDeferred<Unit>? = null
    private val pendingArgs = ConcurrentHashMap<String, String>()
    private val pendingReplies = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, String>>>()
    private val seq = AtomicLong()

    /** Çekirdeğin çağırdığı tek arayüz (JavaBridge iş parçacığında çalışır). */
    private inner class Bridge {
        @JavascriptInterface
        fun args(id: String): String = pendingArgs.remove(id) ?: "null"

        @JavascriptInterface
        fun reply(id: String, ok: Boolean, payload: String) {
            pendingReplies.remove(id)?.complete(ok to payload)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun create(): WebView = WebView(app).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.setGeolocationEnabled(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.saveFormData = false
        addJavascriptInterface(Bridge(), "MailDotsBridge")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                val asset = ASSETS[url.path].takeIf { url.scheme == "https" && url.host == ASSET_HOST && request.method == "GET" }
                    ?: return blocked()
                return runCatching {
                    WebResourceResponse(asset, "utf-8", 200, "OK", NO_CACHE, app.assets.open("e2e" + url.path!!.removePrefix("/e2e")))
                }.getOrElse { blocked() }
            }

            // Çekirdeğin işlemcisi çökerse (ör. bellek) uygulama kapanmaz: görünüm atılır,
            // bekleyen çağrılar hemen hata döner, sonraki çağrı çekirdeği yeniden yükler.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                failAll("core_crashed")
                if (webView === view) webView = null
                view.destroy()
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("typeof MailDotsCore === 'object' && MailDotsCore.version") { v ->
                    if (v != null && v != "false" && v != "null") ready?.complete(Unit)
                    else ready?.completeExceptionally(E2EError("core_unavailable"))
                }
            }
        }
    }

    private fun failAll(code: String) {
        ready?.completeExceptionally(E2EError(code))
        val pending = pendingReplies.values.toList()
        pendingReplies.clear()
        pendingArgs.clear()
        pending.forEach { it.completeExceptionally(E2EError(code)) }
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", NO_CACHE, ByteArrayInputStream(ByteArray(0)))

    private suspend fun ensureLoaded() {
        loadLock.withLock {
            val current = ready
            if (current != null && !(current.isCompleted && current.getCompletionExceptionOrNull() != null)) return@withLock
            val deferred = CompletableDeferred<Unit>()
            ready = deferred
            withContext(Dispatchers.Main) {
                webView?.destroy()
                webView = create().also { it.loadUrl(CORE_URL) }
            }
        }
        val current = ready!!
        try {
            withTimeout(LOAD_TIMEOUT_MS) { current.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Bir sonraki çağrı yeniden yüklesin (yoksa çekirdek uygulama kapanana kadar kullanılamaz).
            current.completeExceptionally(E2EError("core_unavailable"))
            throw E2EError("core_unavailable")
        }
    }

    override suspend fun invoke(method: String, args: String): String {
        ensureLoaded()
        val id = "c" + seq.incrementAndGet()
        val reply = CompletableDeferred<Pair<Boolean, String>>()
        pendingReplies[id] = reply
        pendingArgs[id] = args
        try {
            withContext(Dispatchers.Main) {
                // Yöntem adı sabit listeden gelir; kimlik yalnızca harf+rakam: betiğe güvenle girer.
                require(METHOD.matches(method) && ID.matches(id))
                webView?.evaluateJavascript("MailDotsCore.run('$id','$method')", null) ?: throw E2EError("core_unavailable")
            }
            val (ok, payload) = withTimeout(CALL_TIMEOUT_MS) { reply.await() }
            if (ok) return payload
            val code = runCatching {
                ((json.parseToJsonElement(payload) as? JsonObject)?.get("error") as? JsonPrimitive)?.content
            }.getOrNull() ?: "core_error"
            throw E2EError(code)
        } finally {
            pendingReplies.remove(id)
            pendingArgs.remove(id)
        }
    }

    private companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val CORE_URL = "https://$ASSET_HOST/e2e/core.html"
        val ASSETS = mapOf("/e2e/core.html" to "text/html", "/e2e/core.js" to "application/javascript")
        val NO_CACHE = mapOf("Cache-Control" to "no-store")
        val METHOD = Regex("^[A-Za-z]{1,40}$")
        val ID = Regex("^c[0-9]{1,19}$")
        const val LOAD_TIMEOUT_MS = 20_000L
        const val CALL_TIMEOUT_MS = 120_000L
    }
}
