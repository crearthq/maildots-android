package io.maildots.app

import io.maildots.app.ThemeMode
import android.app.UiModeManager
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import io.maildots.app.mail.PickedFile
import io.maildots.app.mail.AttachmentPolicy
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.TextUtils
import android.text.format.DateUtils
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import io.maildots.app.data.AccountRepository
import io.maildots.app.data.KeystoreSecureStore
import io.maildots.app.mail.AttachmentData
import io.maildots.app.mail.WebMailEngine
import io.maildots.app.e2e.AndroidE2ECrypto
import io.maildots.app.e2e.E2EKeyStore
import io.maildots.app.e2e.E2EService
import kotlinx.coroutines.CompletableDeferred
import io.maildots.app.ui.App
import io.maildots.app.ui.Platform
import io.maildots.app.ui.mailPage
import com.google.firebase.messaging.FirebaseMessaging
import io.maildots.app.push.MailNotifications
import io.maildots.app.push.PushExtras
import io.maildots.app.web.AccountService
import io.maildots.app.web.HttpAccountApi
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Uygulama ömrü boyunca tek denetleyici (ekran döndürmede durum korunur). */
class MailDotsApp : Application() {
    private val store by lazy { KeystoreSecureStore(this) }

    /** Hesaplar ve kurulum sırları (bildirim mühür anahtarı dahil) için şifreli depo. */
    val repository by lazy { AccountRepository(store) }

    val controller: AppController by lazy {
        // Teşhis satırları (içerik yok) yalnız hata ayıklama derlemesinde yazılır.
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val log: (String) -> Unit = { if (debuggable) runCatching { android.util.Log.w("MailDotsWeb", it) } }
        // Tek sistem: maildots.com webmail API'si (posta, ayarlar, bildirim, uçtan uca şifreleme).
        // Hata ayıklama derlemesinde yerel webmail'e yönlendirilebilir (bkz. DevServer).
        val api = DevServer.webApi() ?: HttpAccountApi(
            AccountService.BASE_URL,
            userAgent = "MailDots-Android",
            log = log,
            pushKey = { io.maildots.app.push.PushSeal.ensureKey(repository) },
            locale = { java.util.Locale.getDefault().language },
        )
        // Posta motoru oturumu denetleyiciden alır (kurulunca).
        val ref = CompletableDeferred<AppController>()
        val engine = WebMailEngine(api, api, { account, renew -> ref.await().webSession(account, renew) }, DevServer.webBase ?: AccountService.BASE_URL)
        AppController(
            engine = engine,
            repository = repository,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            api = api,
            e2e = E2EService(AndroidE2ECrypto(this), E2EKeyStore(store)),
            diagnostic = { if (debuggable) println(it) },
        ).also { ref.complete(it) }
    }

    override fun onCreate() {
        super.onCreate()
        // Bildirim belirtecini al (yalnızca gerçek sunucuyla çalışırken; yerel denemede kayıt yapılmaz).
        if (!DevServer.local) {
            runCatching {
                MailNotifications.ensureChannel(this)
                FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> controller.onPushToken(token) }
            }
            // Simge rozeti: uygulama içindeki gelen kutusu okunmamışları bildirimlere yansır.
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                controller.state.map { it.unreadByAccount }.distinctUntilChanged().collect {
                    MailNotifications.syncUnread(this@MailDotsApp, it)
                }
            }
            // Kaldırılan hesabın bildirim izleri silinir.
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                controller.state.map { s -> s.accounts.map { it.email }.toSet() }.distinctUntilChanged().collect {
                    MailNotifications.forgetAccountsExcept(this@MailDotsApp, it)
                }
            }
        }
        // Ana ekran widget'ı: etkin hesabın gelen kutusu özeti şifreli depoya yazılır.
        io.maildots.app.widget.InboxWidgetSync.start(this, controller)
        // Önceki oturumdan kalan indirilmiş ekleri temizle (diskte birikmesin).
        runCatching { attachmentDir(this).listFiles()?.forEach { it.delete() } }
    }
}

private fun attachmentDir(context: Context): File = File(context.cacheDir, "attachments").apply { mkdirs() }

class MainActivity : ComponentActivity() {
    private var attachmentLimit = 0L
    private var onAttachmentsPicked: ((List<PickedFile>) -> Unit)? = null

    /**
     * Sistem dosya seçicisi. Depolama izni istenmez; uygulama yalnızca kullanıcının
     * bu ekranda tek tek seçtiği dosyaları, o an, okuyabilir.
     */
    private val attachmentPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val deliver = onAttachmentsPicked
        onAttachmentsPicked = null
        if (deliver == null || uris.isEmpty()) return@registerForActivityResult
        val limit = attachmentLimit
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { readPicked(uris, limit) }
            deliver(files)
        }
    }

    private fun pickAttachments(maxBytes: Long, onPicked: (List<PickedFile>) -> Unit) {
        attachmentLimit = maxBytes
        onAttachmentsPicked = onPicked
        runCatching { attachmentPicker.launch(arrayOf("*/*")) }.onFailure { onAttachmentsPicked = null }
    }

    /**
     * Seçilen dosyaları okur. Toplamda [limit] bayttan fazlası belleğe alınmaz
     * (dosyanın bildirdiği boyuta güvenilmez; okurken sayılır).
     */
    private fun readPicked(uris: List<Uri>, limit: Long): List<PickedFile> {
        var remaining = limit
        // Sayı sınırı denetleyicide uygulanır; burada yalnızca gereksiz okuma önlenir.
        return uris.take(AttachmentPolicy.MAX_FILES + 1).map { uri ->
            var name = AttachmentPolicy.FALLBACK_NAME
            // Yalnızca seçicinin verdiği içerik adresleri okunur (dosya yolu kabul edilmez).
            if (uri.scheme != "content") return@map PickedFile(name, null, null)
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.let { name = it }
                }
            }
            val type = runCatching { contentResolver.getType(uri) }.getOrNull()
            if (remaining <= 0) return@map PickedFile(name, type, null, tooLarge = true)
            val budget = remaining
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > budget) return@use null
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
            }
            when {
                bytes.isFailure -> PickedFile(name, type, null)
                bytes.getOrNull() == null -> PickedFile(name, type, null, tooLarge = true)
                else -> bytes.getOrNull()!!.let { data ->
                    remaining -= data.size
                    PickedFile(name, type, data)
                }
            }
        }
    }

    /**
     * Uygulamaya dönülünce dış uygulamada açılmış eklerin geçici kopyaları silinir (cihazda
     * açılmış şifreli iletinin ekleri de düz metin olarak diskte beklemesin). Görüntüleyici
     * dosyayı açarken uygulama arka plandadır; dönüş, işinin bittiğini gösterir.
     */
    override fun onStart() {
        super.onStart()
        runCatching { attachmentDir(this).listFiles()?.forEach { it.delete() } }
        // Uygulama öne gelince şifreleme denetimi (T3; hesap başına en çok dakikada bir).
        (application as MailDotsApp).controller.onForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        holdSplashUntilLogoIsDrawn()
        // Yayın derlemesinde hiçbir şey yapmaz (bkz. DevServer).
        DevServer.configure(intent)
        DevServer.widgetDemo(this, intent)
        val controller = (application as MailDotsApp).controller
        val platform = Platform(
            language = Locale.getDefault().language,
            formatShortDate = ::formatShortDate,
            formatFullDate = { DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(it)) },
            htmlBody = { html, light, baseUrl, modifier -> SafeHtml(html, light, baseUrl, modifier) },
            openAttachment = ::openAttachment,
            print = ::printMessage,
            webPage = { url, modifier -> AccountWebPage(url, modifier) },
            pickAttachments = ::pickAttachments,
            onThemeChanged = ::applySystemBars,
            onThemeMode = ::applyNightMode,
        )
        setContent {
            // İlk hesap eklenir eklenmez (yeniden açmayı beklemeden) bildirim izni sorulur.
            val hasAccount = controller.state.collectAsState().value.accounts.isNotEmpty()
            LaunchedEffect(hasAccount) { if (hasAccount) askNotificationPermission() }
            App(controller, platform)
        }
        openFromPush(intent)
        // Yalnızca hata ayıklama derlemesi: karşılama sayfalarını oturumu kapatmadan gösterir.
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent?.getBooleanExtra("welcome", false) == true) controller.previewWelcome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DevServer.widgetDemo(this, intent)
        openFromPush(intent)
    }

    /**
     * Bildirime ya da widget'a dokunularak açıldıysa ilgili hesabı ve iletiyi (widget başlığı:
     * gelen kutusunu) açar. Etkinlik dışa açıktır: ek veriler başka uygulamadan da gelebilir,
     * bu yüzden doğrulanır ([parseOpenRequest]: hesap kayıtlı olmalı, uid sayı olmalı) ve
     * yalnızca gezinme yapılır.
     */
    private fun openFromPush(intent: Intent?) {
        val extras = runCatching { intent?.extras?.takeIf { it.containsKey(PushExtras.ACCOUNT) } }.getOrNull() ?: return
        val controller = (application as MailDotsApp).controller
        val request = runCatching {
            @Suppress("DEPRECATION")
            parseOpenRequest(
                account = extras.get(PushExtras.ACCOUNT) as? String,
                folder = extras.get(PushExtras.FOLDER)?.let { it as? String ?: return@runCatching null },
                uid = extras.get(PushExtras.UID),
                inbox = extras.get(PushExtras.INBOX) == true,
                knownAccounts = controller.state.value.accounts.map { it.email },
            )
        }.getOrNull()
        listOf(PushExtras.ACCOUNT, PushExtras.FOLDER, PushExtras.UID, PushExtras.INBOX).forEach { intent?.removeExtra(it) }
        request ?: return
        if (request.inbox) controller.openInbox(request.account)
        else controller.openFromNotification(request.account, request.folder, request.uid)
    }

    private var notificationAsked = false

    /** Android 13+: bildirim izni, en az bir hesap varken açılış başına bir kez sorulur. */
    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33 || MailNotifications.canNotify(this)) return
        if (notificationAsked || DevServer.local) return
        notificationAsked = true
        runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41) }
    }

    /** Durum ve gezinme çubuğu simgelerini uygulama temasına uydurur (sistem temasına değil). */
    /**
     * Uygulamanın tema seçimini sisteme bildirir (Android 12+). Böylece uygulama açılırken
     * sistemin gösterdiği ilk ekran da seçilen temada gelir; telefon açık temadayken
     * koyu seçilmiş uygulama beyaz bir ekranla açılmaz.
     */
    private fun applyNightMode(mode: ThemeMode) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val night = when (mode) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        runCatching { getSystemService(UiModeManager::class.java)?.setApplicationNightMode(night) }
    }

    /**
     * Açılış logosu çizimini bitirmeden ekran değişmesin (Android 12+).
     * Uygulama logodan hızlı hazır olursa kalan süre kadar beklenir, sonra logo
     * yumuşakça kaybolur. Üst sınır vardır: açılış hiçbir durumda uzamaz.
     */
    private fun holdSplashUntilLogoIsDrawn() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        splashScreen.setOnExitAnimationListener { view ->
            val started = view.iconAnimationStart?.toEpochMilli() ?: 0L
            val duration = view.iconAnimationDuration?.toMillis() ?: 0L
            val remaining = if (started > 0L) (started + duration - System.currentTimeMillis()).coerceIn(0L, 900L) else 0L
            view.postDelayed({
                view.animate().alpha(0f).setDuration(200L).withEndAction { view.remove() }.start()
            }, remaining)
        }
    }

    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private fun formatShortDate(millis: Long): String =
        if (DateUtils.isToday(millis)) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
        else DateUtils.formatDateTime(this, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

    /**
     * Eki uygulamanın özel önbelleğine yazar ve sistem görüntüleyicisinde açar.
     * Dosya yalnızca FileProvider üzerinden, tek seferlik OKUMA izniyle paylaşılır;
     * dosya adı yol ayraçlarından arındırılır (dizin dışına yazılamaz).
     */
    private fun openAttachment(data: AttachmentData) {
        try {
            val safeName = data.name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120).ifBlank { "ek" }
            val file = File(attachmentDir(this), safeName)
            file.writeBytes(data.bytes)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val extension = safeName.substringAfterLast('.', "").lowercase()
            val mime = data.mimeType.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, if (isTurkish()) "Bu dosyayı açacak bir uygulama yok." else "No app can open this file.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, if (isTurkish()) "Ek açılamadı." else "Could not open the attachment.", Toast.LENGTH_LONG).show()
        }
    }

    private fun isTurkish() = Locale.getDefault().language == "tr"

    /** İletiyi sistem yazdırma penceresiyle yazdırır (betik ve ağ kapalı bir WebView ile). */
    private fun printMessage(title: String, html: String?, text: String?) {
        val body = html?.takeIf { it.isNotBlank() } ?: "<pre>${TextUtils.htmlEncode(text.orEmpty())}</pre>"
        val view = WebView(this).apply { hardenForMail() }
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String?) {
                val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                manager.print(title, v.createPrintDocumentAdapter(title), PrintAttributes.Builder().build())
            }
        }
        view.loadDataWithBaseURL(null, mailPage(body, light = true, heading = title), "text/html", "utf-8", null)
    }
}

/** E-posta gösteren WebView için ortak sertleştirme. */
private fun WebView.hardenForMail() {
    settings.javaScriptEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.blockNetworkLoads = true
    settings.blockNetworkImage = true
    settings.domStorageEnabled = false
    settings.databaseEnabled = false
    settings.setGeolocationEnabled(false)
    settings.setSupportMultipleWindows(false)
    settings.saveFormData = false
}

/**
 * E-posta HTML'ini güvenli gösterir:
 * - JavaScript KAPALI, dosya ve içerik erişimi KAPALI.
 * - Ağ yüklemeleri KAPALI: uzak görseller ve izleme pikselleri yüklenmez
 *   (gönderen, postanın açıldığını ve IP adresini öğrenemez).
 * - Bağlantılar WebView içinde açılmaz; yalnızca http/https/mailto dış uygulamaya gider.
 * - İçerik güvenlik politikası (CSP) ile betik, çerçeve ve form gönderimi ayrıca yasaklanır.
 */
@Composable
private fun SafeHtml(html: String, light: Boolean?, baseUrl: String?, modifier: Modifier) {
    val useLight = light ?: !isSystemInDarkTheme()
    // Gövde, uygulamanın o anki zeminini kullanır (açık/koyu temada aynı renk). Kullanıcı
    // gövdeyi uygulamanın tersi temaya zorladıysa o temanın kendi zemini kullanılır.
    val appBackground = androidx.compose.material3.MaterialTheme.colorScheme.background
    val appIsLight = appBackground.luminance() > 0.5f
    val pageBackground = if (useLight == appIsLight) {
        "#%06X".format(0xFFFFFF and appBackground.toArgb())
    } else null
    // Görsel vekiline yalnızca hesap servisinin kendi adresinden izin verilir.
    val origin = baseUrl?.trimEnd('/')?.takeIf { it in AccountService.IMAGE_BASES }
    val proxy = origin?.let { "$it/api/img-proxy?" }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                hardenForMail()
                setTag(R.id.mail_proxy, java.util.concurrent.atomic.AtomicReference<String?>(null))
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                isVerticalScrollBarEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri: Uri = request.url
                        if (uri.scheme in setOf("http", "https", "mailto")) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        return true
                    }

                    // Ağ açıksa bile yalnızca görsel vekili yüklenir; diğer her istek boş yanıtla kesilir.
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                        val url = request.url.toString()
                        @Suppress("UNCHECKED_CAST")
                        val prefix = (view.getTag(R.id.mail_proxy) as? java.util.concurrent.atomic.AtomicReference<String?>)?.get()
                        // Ağa çıkmayan kendi sayfamız (cihazda açılmış şifreli ileti: taban adres yok) ve
                        // gömülü görseller (data:) engellenmez; ağ isteklerinden yalnızca görsel vekili geçer.
                        val local = url.startsWith("data:") || url.startsWith("about:")
                        val allowed = request.method == "GET" && (local || (prefix != null && url.startsWith(prefix)))
                        return if (allowed) null else android.webkit.WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
            }
        },
        update = { view ->
            val page = mailPage(html, useLight, imageOrigin = origin, background = pageBackground)
            // Sayfa yüklenene kadar beyaz bir an görünmesin.
            view.setBackgroundColor(if (pageBackground != null) appBackground.toArgb() else android.graphics.Color.TRANSPARENT)
            if (view.tag != page.hashCode()) {
                view.tag = page.hashCode()
                @Suppress("UNCHECKED_CAST")
                (view.getTag(R.id.mail_proxy) as? java.util.concurrent.atomic.AtomicReference<String?>)?.set(proxy)
                // Vekil yoksa ağ tamamen kapalı kalır.
                view.settings.blockNetworkLoads = proxy == null
                view.settings.blockNetworkImage = proxy == null
                // Taban adres yoksa sayfa "about:blank" kökenli yüklenir (ağ kapalı kalır).
                view.loadDataWithBaseURL(origin ?: "about:blank", page, "text/html", "utf-8", null)
            }
        },
    )
}

/**
 * Hesabın web ayar sayfası (webmail), uygulama içinde:
 * - Uygulamanın oturumu aktarılmaz: sayfa çerezsiz açılır, kullanıcı web'e kendisi giriş yapar.
 *   Web girişten sonra gelen kutusuna gider; o zaman bir kez [url]'e dönülür.
 * - Yalnızca hesap servisinin adresinde (HTTPS) gezinilir; başka her bağlantı dış tarayıcıya gider.
 * - Dosya ve içerik erişimi kapalıdır; sayfa kapanınca çerezler ve önbellek silinir
 *   (web oturumu sonraki açılışa kalmaz, her seferinde yeniden giriş istenir).
 */
@Composable
private fun AccountWebPage(url: String, modifier: Modifier) {
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            WebView(context).apply {
                // Tam boyut: aksi halde WebView "icerik kadar" kipinde calisir ve sayfadaki
                // gorunum yuksekligi birimleri (vh/dvh) sifir olur; tam ekran duzenler bos gorunur.
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                cookies.setAcceptThirdPartyCookies(this, false)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setGeolocationEnabled(false)
                settings.setSupportMultipleWindows(false)
                settings.saveFormData = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClient() {
                    /** Giriş sayfası görüldü; giriş sonrası ayarlara bir kez dönülecek. */
                    private var signingIn = false

                    // Web girişten sonra sayfa yenilemeden (geçmiş API'siyle) gelen kutusuna geçer.
                    override fun doUpdateVisitedHistory(view: WebView, address: String?, isReload: Boolean) {
                        val uri = address?.let(Uri::parse) ?: return
                        if (uri.scheme != "https" || uri.host != AccountService.WEB_HOST) return
                        val path = uri.path.orEmpty()
                        if (path == "/login") {
                            signingIn = true
                        } else if (signingIn && !path.contains("/settings")) {
                            signingIn = false
                            view.loadUrl(url)
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri: Uri = request.url
                        if (uri.scheme == "https" && uri.host == AccountService.WEB_HOST) return false
                        if (uri.scheme in setOf("http", "https", "mailto")) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        return true
                    }
                }
                // Yalnizca hata ayiklama derlemesinde: sayfa hatalarini gunluge yaz (teshis icin).
                if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                            if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                android.util.Log.w("MailDotsWebPage", message.message().take(600) + " @" + message.sourceId().substringAfterLast("/") + ":" + message.lineNumber())
                            }
                            return true
                        }
                    }
                }
                // Önceki açılıştan kalan çerez olmasın: önce silinir, sonra (çerezsiz) yüklenir.
                cookies.removeAllCookies { loadUrl(url) }
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.clearHistory()
            view.clearCache(true)
            view.clearFormData()
            android.webkit.WebStorage.getInstance().deleteAllData()
            view.destroy()
        },
    )
}
