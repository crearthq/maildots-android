package io.maildots.app.web

/**
 * MailDots hesap servisi = webmail (maildots.com): giriş, posta, ayarlar, bildirim ve
 * uçtan uca şifreleme aynı adresteki API'den yürür.
 *
 * Buradaki her çağrı kutunun kendi oturumuyla yapılır; sunucu sahibi oturumdan
 * çözer, başka kutunun verisine erişilemez.
 */
object AccountService {
    /** API ve web posta adresi (Cloudflare arkasında). */
    const val BASE_URL = "https://webmail.maildots.com"
    const val HOST = "webmail.maildots.com"

    /** Uygulama içi web sayfaları (ayarlar) bu adreste açılır, oturum çerezi bu adrese kurulur. */
    const val WEB_URL = BASE_URL
    const val WEB_HOST = HOST
    const val SESSION_COOKIE = "webmail_session"
    const val SETTINGS_PATH = "/settings"
    /** Şifre ve iki adımlı doğrulama: tarayıcıda, web'e giriş yapılarak değiştirilir. */
    const val SECURITY_PATH = "/settings/security"

    /** Görsel vekili için izin verilen taban adresler (görüntüleyici yalnızca bunlara çıkar). */
    val IMAGE_BASES = setOf(BASE_URL)
}

/** Giriş sonucu. Oturum ve bekleyen-giriş değerleri gizlidir; günlüğe yazılmaz. */
sealed interface WebLogin {
    class Ok(val session: String) : WebLogin
    /** Kutuda iki adımlı doğrulama açık: [pending] ile kod doğrulanmalı. */
    class NeedsCode(val pending: String) : WebLogin
    data object Invalid : WebLogin
}

sealed class WebError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Oturum yok ya da süresi dolmuş: yeniden giriş gerekir. */
    class Unauthorized : WebError("unauthorized")
    /** Doğrulama kodu yanlış ya da süresi dolmuş. */
    class BadCode(val expired: Boolean) : WebError("bad_code")
    /** Aynı adla kayıt var (etiket adı). */
    class NameTaken : WebError("name_taken")
    class Invalid : WebError("invalid")
    class RateLimited : WebError("rate_limited")
    class Network(cause: Throwable? = null) : WebError("network", cause)
    /** Ek(ler) gönderim sınırını aşıyor. */
    class TooLarge : WebError("too_large")
    /** Mevcut şifre yanlış (şifre değiştirme). */
    class WrongPassword : WebError("wrong_current")
    /** Yeni şifre kurala uymuyor; [detail] sunucunun açıklaması (gösterilebilir). */
    class Policy(val detail: String) : WebError("policy")
    class Server(val status: Int) : WebError("server")
    /** Sunucu bu işlemi henüz bilmiyor (uç yok: 404/405); istemci kibarca vazgeçer. */
    class Unsupported : WebError("unsupported")
}

data class WebProfile(val displayName: String)

/** Etiket: [color] servisin renk anahtarıdır (ör. "blue"), [keyword] iletideki IMAP anahtar sözcüğü. */
data class WebLabel(val id: String, val name: String, val color: String, val keyword: String)

data class WebSignature(
    val id: String,
    val name: String,
    val html: String,
    val isDefault: Boolean,
    val identity: String? = null,
    val placement: String = "reply_below",
)

data class WebPrefs(val signatureOnNew: Boolean = true, val signatureOnReply: Boolean = true)

/** Yazma ekranından servise giden ileti (gönderim ve taslak aynı biçimi kullanır). */
data class WebCompose(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    /** Gönderen adres (kutunun kendisi ya da takma adı); null = kutunun adresi. */
    val from: String? = null,
    val subject: String = "",
    val html: String = "",
    /** Yanıtlanan iletinin Message-ID'si (konuşma zinciri için). */
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val attachments: List<io.maildots.app.mail.AttachmentData> = emptyList(),
    /** İletilen (ya da taslaktan açılan) iletinin ekleri sunucuda bu iletiden alınır. */
    val carryAttachmentsFromUid: Long? = null,
    val carryAttachmentsFromFolder: String? = null,
    /** Kaydedilen taslağın yerini alır (gönderilince taslak silinir). */
    val draftUid: Long? = null,
    /** Önem: "high" | "low"; null = normal (gönderilmez). */
    val importance: String? = null,
    /** Okundu bilgisi iste (yalnız true ise gönderilir). */
    val readReceipt: Boolean = false,
) {
    /** Eski sunucunun bilmediği alanlar var mı (bkz. [AccountApi.sendCompat]). */
    val hasExtras: Boolean get() = importance != null || readReceipt || attachments.any { it.contentId != null }

    /** Ek alanlar çıkarılmış hâli: satır içi görseller sıradan ek olur (kaybolmaz). */
    fun withoutExtras(): WebCompose = copy(
        importance = null,
        readReceipt = false,
        attachments = attachments.map { if (it.contentId != null) io.maildots.app.mail.AttachmentData(it.name, it.mimeType, it.bytes) else it },
    )
}

data class WebDraftSaved(val uid: Long?, val folder: String)

/** Alıcı önerisi: [source] "contact" (adres defteri), "recent" (son yazılanlar) ya da "directory" (şirket). */
data class WebSuggestion(val name: String, val email: String, val source: String)

/** Konuşmadaki bir ileti (en eskiden yeniye). */
data class WebThreadItem(
    val uid: Long,
    val folder: String,
    val outgoing: Boolean,
    val from: io.maildots.app.mail.Address?,
    val subject: String,
    val dateMillis: Long,
    val snippet: String,
)

data class WebTranslation(val html: String, val sourceLanguage: String)

/** Kutunun depolama kullanımı. */
data class WebUsage(val usedBytes: Long, val totalBytes: Long, val percent: Int, val unlimited: Boolean)

interface AccountApi {
    suspend fun login(email: String, password: String): WebLogin

    /** İki adımlı doğrulama kodunu doğrular ve oturumu döndürür. */
    suspend fun verifyCode(pending: String, code: String): String

    suspend fun logout(session: String)

    suspend fun profile(session: String): WebProfile

    /**
     * Bir adresin avatarı (webmail sunucu tarafında çözer: aynı sunucudaki kullanıcının
     * profil fotoğrafı, gönderen alan adının BIMI logosu ya da Gravatar). Yoksa null.
     */
    suspend fun avatar(session: String, email: String): ByteArray? = null

    suspend fun setDisplayName(session: String, name: String): String

    suspend fun labels(session: String): List<WebLabel>

    suspend fun createLabel(session: String, name: String, color: String): WebLabel

    suspend fun updateLabel(session: String, id: String, name: String?, color: String?): WebLabel

    /** Etiketi siler ve iletilerden kaldırır. */
    suspend fun deleteLabel(session: String, id: String)

    suspend fun signatures(session: String): List<WebSignature>

    /** [existing] varsa günceller, yoksa varsayılan imza olarak oluşturur. */
    suspend fun saveSignature(session: String, existing: WebSignature?, html: String): WebSignature

    suspend fun deleteSignature(session: String, id: String)

    suspend fun prefs(session: String): WebPrefs

    /** Bu cihazı, oturumun kutusuna gelen yeni postalar için bildirime kaydeder. */
    suspend fun registerPush(session: String, token: String)

    /** Bu cihazın bildirim kaydını siler (çıkış). */
    suspend fun unregisterPush(session: String, token: String)

    // ---- yazma (webmail ile aynı yol: Gönderilenler kopyası, takma ad denetimi, alıcı geçmişi sunucuda)

    /**
     * Gönderir. Gönderilenler'e kopyayı sunucu koyar; [WebCompose.draftUid] verilmişse taslak silinir.
     * Boyut aşımı [WebError.TooLarge], geçersiz gönderen [WebError.Invalid].
     */
    suspend fun send(session: String, message: WebCompose)

    /**
     * [send] gibi; sunucu ek alanları (önem, okundu bilgisi, satır içi görsel) tanımayıp isteği
     * reddederse ileti onlarsız bir kez daha gönderilir ve true döner (arayüz kısa bir uyarı gösterir).
     */
    suspend fun sendCompat(session: String, message: WebCompose): Boolean {
        send(session, message)
        return false
    }

    suspend fun saveDraft(session: String, message: WebCompose): WebDraftSaved

    suspend fun deleteDraft(session: String, uid: Long)

    /** Gönderen olarak seçilebilecek adresler (kutunun adresi ve takma adları). */
    suspend fun fromAddresses(session: String): List<String>

    /** Yazarken alıcı önerileri (boş sorgu = boş liste). */
    suspend fun suggest(session: String, query: String): List<WebSuggestion>

    // ---- okuma

    /** Konuşmadaki iletiler (en eskiden yeniye). */
    suspend fun thread(session: String, folder: String, uid: Long): List<WebThreadItem>

    /** İleti gövdesini [target] diline çevirir. */
    suspend fun translate(session: String, html: String, target: String): WebTranslation

    // ---- hesap

    suspend fun usage(session: String): WebUsage

    /** Şifreyi değiştirir; mevcut şifre yanlışsa [WebError.WrongPassword], kurala uymuyorsa [WebError.Policy]. */
    suspend fun changePassword(session: String, current: String, new: String)
}

/** İmza metni: webmail HTML saklar, mobil yazma ekranı düz metindir. */
object SignatureText {
    private val BLOCK_END = Regex("</(p|div|li|tr|h[1-6])\\s*>", RegexOption.IGNORE_CASE)
    private val BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val DROPPED = Regex("<(style|script)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** Yalnızca paragraf ve satır sonu içeren, niteliksiz (düz) HTML mi? Değilse mobilde düzenlenmez. */
    private val SIMPLE_TAG = Regex("</?(p|div|br)\\s*/?>", RegexOption.IGNORE_CASE)

    fun toText(html: String): String {
        var s = DROPPED.replace(html, "")
        s = BREAK.replace(s, "\n")
        s = BLOCK_END.replace(s, "\n")
        s = TAG.replace(s, "")
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
        return s.lines().joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Biçimlendirme (kalın, bağlantı, görsel, stil) içeriyor mu? */
    fun isRich(html: String): Boolean = TAG.findAll(html).any { !SIMPLE_TAG.matches(it.value) }

    fun toHtml(text: String): String =
        text.replace("\r", "").trim().lines().joinToString("<br>") { line ->
            line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        }
}

/** Servisin renk anahtarları ve uygulamadaki karşılıkları (ARGB). */
object LabelPalette {
    val COLORS: List<Pair<String, Long>> = listOf(
        "red" to 0xFFD92D20,
        "orange" to 0xFFE8830C,
        "yellow" to 0xFFB45309,
        "green" to 0xFF1A8245,
        "teal" to 0xFF0E7490,
        "cyan" to 0xFF0891B2,
        "blue" to 0xFF2230BA,
        "purple" to 0xFF7C3AED,
        "pink" to 0xFFDB2777,
        "gray" to 0xFF5B6478,
    )

    fun argb(token: String): Long = COLORS.firstOrNull { it.first == token }?.second ?: COLORS.last().second

    /** Bilinmeyen renk "gray" olur (servis de aynısını yapar). */
    fun token(argb: Long): String = COLORS.firstOrNull { it.second == argb }?.first ?: "gray"
}
