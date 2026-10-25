package io.maildots.app.mail

import kotlinx.serialization.Serializable

/** Cihazda kayıtlı bir posta hesabı. Parola yalnızca şifreli depoda tutulur. */
@Serializable
data class Account(
    val email: String,
    val password: String,
    val displayName: String = "",
    /** Yeni iletilerin ve yanıtların altına eklenen imza (düz metin). */
    val signature: String = "",
    /** Hesap servisindeki (webmail) oturum; gizlidir, yalnızca şifreli depoda durur. */
    val webSession: String = "",
    /**
     * Hesabın kaydedildiği servis adresi ([io.maildots.app.web.AccountService.BASE_URL]).
     * Boş = artık desteklenmeyen eski sistemden kalma kayıt (açılışta bırakılır).
     */
    val service: String = "",
    /**
     * Giriş koruması açık: [password] şifrenin kendisi DEĞİL, ondan cihazda türetilmiş
     * giriş anahtarıdır (`md1_…`). Şifre hiçbir yerde saklanmaz, sunucuya gitmez.
     */
    val loginProtected: Boolean = false,
    /** Hesap servisiyle en az bir kez eşleşti: etiketler ve imza oradan gelir. */
    val webLinked: Boolean = false,
    /** Servisteki varsayılan imzanın kimliği (yoksa boş). */
    val signatureId: String = "",
    /** İmza biçimlendirme içeriyor: mobilde düz metin olarak düzenlenmez. */
    val signatureRich: Boolean = false,
    val signatureOnNew: Boolean = true,
    val signatureOnReply: Boolean = true,
) {
    /** Günlüklere ve hata mesajlarına parola sızmasın. */
    override fun toString(): String = "Account(email=$email)"
}

@Serializable
enum class FolderRole { INBOX, SENT, DRAFTS, TRASH, JUNK, ARCHIVE, OTHER }

/** Klasör içinde süzme (menüdeki "Okunmamış", "Yıldızlı", "Ekli" görünümleri). */
enum class MailFilter { ALL, UNREAD, FLAGGED, ATTACHMENTS }

/**
 * Etiket. Sunucuda IMAP anahtar sözcüğü olarak saklanır.
 *
 * Hazır beş etiket, webmail ve Thunderbird'ün kullandığı standart anahtar
 * sözcüklerdir. Kullanıcının oluşturduğu etiketlerde ad, anahtar sözcüğün içine
 * (onaltılık UTF-8 olarak) yazılır; böylece etiket adı başka bir cihazda da okunabilir.
 */
@Serializable
data class MailLabel(val keyword: String) {
    val isBuiltin: Boolean get() = BUILTIN.any { it.keyword == keyword }

    /** Kullanıcının verdiği ad (hazır etiketlerde null; adı dil dosyasından gelir). */
    val customName: String?
        get() {
            if (!keyword.startsWith(CUSTOM_PREFIX)) return null
            val hex = keyword.removePrefix(CUSTOM_PREFIX)
            if (hex.isEmpty() || hex.length % 2 != 0) return null
            return runCatching {
                ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }.decodeToString()
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }

    companion object {
        val IMPORTANT = MailLabel("\$label1")
        val WORK = MailLabel("\$label2")
        val PERSONAL = MailLabel("\$label3")
        val TODO = MailLabel("\$label4")
        val LATER = MailLabel("\$label5")
        val BUILTIN = listOf(IMPORTANT, WORK, PERSONAL, TODO, LATER)

        const val CUSTOM_PREFIX = "md_"
        const val MAX_NAME_LENGTH = 30

        /** Addan etiket üretir; ad boşsa null. Aynı ad her zaman aynı anahtar sözcüğü verir. */
        fun custom(name: String): MailLabel? {
            val clean = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH)
            if (clean.isEmpty()) return null
            val hex = clean.encodeToByteArray().joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }
            return MailLabel(CUSTOM_PREFIX + hex)
        }

        /** İstemcilerin kendi işleri için kullandığı, etiket olmayan anahtar sözcükler. */
        private val SYSTEM = setOf("nonjunk", "notjunk", "junk", "forwarded", "redirected", "mdnsent", "phishing")

        /**
         * Sunucudan gelen anahtar sözcüğü etikete çevirir. Hazır beş etiket ve uygulamanın
         * kendi etiketleri büyük/küçük harfe bakılmadan tanınır; hesap servisinin etiketleri
         * sunucunun bildirdiği yazımla korunur (tanım listesiyle eşleşmesi için). Sistem
         * anahtar sözcükleri (dolar işaretiyle başlayanlar, "NonJunk" vb.) etiket değildir.
         */
        fun fromKeyword(raw: String): MailLabel? {
            val k = raw.lowercase()
            BUILTIN.firstOrNull { it.keyword == k }?.let { return it }
            if (k.startsWith('$') || k.startsWith('\\') || k in SYSTEM) return null
            MailLabel(k).takeIf { it.customName != null }?.let { return it }
            return MailLabel(raw)
        }
    }
}

/** Aramanın metni nerede arayacağı. */
enum class SearchScope { ANYWHERE, SUBJECT, BODY }

/** Arama ölçütleri. Boş bırakılan ölçüt uygulanmaz. */
data class SearchQuery(
    val text: String = "",
    val scope: SearchScope = SearchScope.ANYWHERE,
    /** null = tüm klasörler (çöp ve gereksiz hariç). */
    val folderPath: String? = null,
    val unread: Boolean = false,
    val flagged: Boolean = false,
    val attachments: Boolean = false,
    val from: String = "",
    val to: String = "",
    /** Son N gün (null = tarih sınırı yok). */
    val sinceDays: Int? = null,
    val label: MailLabel? = null,
) {
    /** En az bir ölçüt var mı (boş aramayı sunucuya göndermemek için). */
    val hasCriteria: Boolean
        get() = text.isNotBlank() || unread || flagged || attachments || from.isNotBlank() ||
            to.isNotBlank() || sinceDays != null || label != null
}

@Serializable
data class Folder(
    /** Sunucudaki tam yol (ör. "INBOX", "Sent"). */
    val path: String,
    val name: String,
    val role: FolderRole,
    val unread: Int,
    /** Klasördeki toplam ileti sayısı. */
    val total: Int = 0,
)

@Serializable
data class Address(val name: String, val email: String) {
    val label: String get() = name.ifBlank { email }
}

@Serializable
data class MessageSummary(
    val uid: Long,
    /** İletinin bulunduğu klasör (arama sonuçları farklı klasörlerden gelebilir). */
    val folderPath: String = "INBOX",
    val subject: String,
    val from: Address,
    val to: List<Address>,
    /** Epoch milisaniye (sunucunun bildirdiği gönderim zamanı). */
    val dateMillis: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
    val labels: List<MailLabel> = emptyList(),
    /** Gövdenin ilk satırları (listede konunun yanında; bilinmiyorsa boş). */
    val snippet: String = "",
    /** Uçtan uca şifreli (önizleme yok; içerik yalnızca cihazda açılır). */
    val encrypted: Boolean = false,
    /** Sunucunun iletiyi sakladığı an (gönderenin beyanı değil); bilinmiyorsa 0. */
    val receivedAtMillis: Long = 0,
) {
    /** Kutu şifreliyken ([protectedSince] sonrası) sunucuda şifrelenmeden saklanmış (T3). */
    fun storedUnencrypted(protectedSince: Long?): Boolean =
        protectedSince != null && !encrypted && receivedAtMillis > protectedSince
}

data class Attachment(val name: String, val mimeType: String, val sizeBytes: Long)

/**
 * İndirilmiş ek (bellekte). Boyut sınırı motor tarafında uygulanır.
 * [contentId]: gövdede `cid:` ile gösterilen satır içi görsel (masaüstü yazma); null = sıradan ek.
 */
class AttachmentData(val name: String, val mimeType: String, val bytes: ByteArray, val contentId: String? = null)

data class MessageDetail(
    val summary: MessageSummary,
    val cc: List<Address>,
    /** Düz metin gövde (varsa). */
    val text: String?,
    /** HTML gövde (varsa). Güvenli görüntüleyicide, betikler kapalı gösterilir. */
    val html: String?,
    val attachments: List<Attachment>,
    /** RFC 822 Message-ID (yanıtın konuşmaya bağlanması için; bilinmiyorsa null). */
    val messageId: String? = null,
    /** İletinin References zinciri. */
    val references: List<String> = emptyList(),
    /** Gönderenin istediği yanıt adresleri (Reply-To; boşsa gönderene yanıt verilir). */
    val replyTo: List<Address> = emptyList(),
    /** Kendi Gönderilenler kopyamıza yanıtta asıl alıcı (webmail bildirir). */
    val replyToAddress: String? = null,
    /**
     * [html] göreli adresler içeriyorsa (webmail'in görsel vekili, /api/img-proxy) taban adres.
     * Görüntüleyici yalnızca bu adresin görsel vekiline ağ izni verir; null = ağ tamamen kapalı.
     */
    val htmlBaseUrl: String? = null,
    /**
     * Uçtan uca şifreli ileti bu cihazda açıldı: gövde ve ekler cihazın belleğinde.
     * Gizli içerik: çeviriye gönderilmez, uzak içerik yüklenmez (ağ kapalı).
     */
    val decrypted: Boolean = false,
    /** Cihazda açılan iletinin imza hükmü (T4; imzasızsa NONE). */
    val signature: io.maildots.app.e2e.SignatureVerdict = io.maildots.app.e2e.SignatureVerdict.NONE,
)

data class OutgoingMessage(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String,
    /** Düz metin gövde (yazma ekranı). */
    val body: String,
    /** Denetimden geçmiş ekler (bkz. [AttachmentPolicy]). */
    val attachments: List<AttachmentData> = emptyList(),
    val bcc: List<String> = emptyList(),
    /** Gönderen adres (takma ad); null = hesabın adresi. */
    val from: String? = null,
    /** Yanıtlanan iletinin Message-ID'si ve References zinciri (konuşma). */
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    /** HTML gövde (verilmezse [body] güvenli biçimde HTML'e çevrilir). Alıntı burada durur. */
    val html: String? = null,
    /** İletilen / taslaktan açılan iletinin ekleri sunucuda bu iletiden eklenir (yalnızca hesap servisiyle). */
    val carryAttachmentsFromUid: Long? = null,
    val carryAttachmentsFromFolder: String? = null,
    /** Kayıtlı taslak (gönderilince silinir). */
    val draftUid: Long? = null,
    /** Önem: "high" | "low"; null = normal (yalnız sunucu destekliyorsa başlığa yazılır). */
    val importance: String? = null,
    /** Okundu bilgisi istenir (Disposition-Notification-To; sunucu destekliyorsa). */
    val readReceipt: Boolean = false,
)

/** Kullanıcıya gösterilecek hata sınıfı; ham sunucu mesajı gösterilmez. */
sealed class MailError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Adres ya da parola yanlış. */
    class Auth(cause: Throwable? = null) : MailError("auth", cause)

    /** Sunucuya ulaşılamadı (ağ yok, zaman aşımı). */
    class Network(cause: Throwable? = null) : MailError("network", cause)

    /** Güvenli bağlantı kurulamadı (sertifika doğrulanamadı). Bağlantı KURULMAZ. */
    class Security(cause: Throwable? = null) : MailError("security", cause)

    /** Ek, izin verilen boyuttan büyük. */
    class TooLarge : MailError("too_large")

    class Unknown(cause: Throwable? = null) : MailError("unknown", cause)
}

/**
 * Posta motoru: IMAP/SMTP işlemleri. Platform uygulaması `MailEngineFactory` ile gelir.
 * Tüm çağrılar askıya alınabilir ve arka plan iş parçacığında çalışır.
 */
interface MailEngine {
    /** Giriş bilgilerini doğrular; başarısızsa [MailError] fırlatır. */
    suspend fun verify(account: Account)

    suspend fun folders(account: Account): List<Folder>

    /** Yalnızca gelen kutusundaki okunmamış sayısı (diğer hesapların rozeti için; tek sorgu). */
    suspend fun inboxUnread(account: Account): Int

    /**
     * Hesap sahibinin görünen adı. Sunucu bunu IMAP ile vermez; kutunun kendi gönderdiği
     * iletilerdeki gönderen adından bulunur. Gelen iletilerdeki alıcı adı KULLANILMAZ:
     * onu gönderen belirler, güvenilmez.
     */
    suspend fun displayName(account: Account): String?

    /** En yeni iletiden başlayarak sayfa döndürür. */
    suspend fun messages(
        account: Account,
        folderPath: String,
        offset: Int,
        limit: Int,
        filter: MailFilter = MailFilter.ALL,
        label: MailLabel? = null,
    ): List<MessageSummary>

    /** Sunucuda arar; sonuçlar en yeniden eskiye, en çok [limit] ileti. */
    suspend fun search(account: Account, query: SearchQuery, limit: Int): List<MessageSummary>

    suspend fun setLabel(account: Account, folderPath: String, uid: Long, label: MailLabel, on: Boolean)

    /** Etiketi tüm klasörlerdeki iletilerden kaldırır (etiket silinirken). */
    suspend fun clearLabel(account: Account, label: MailLabel)

    /** Yeni klasör oluşturur ve yolunu döndürür (aynı adla varsa onu döndürür). */
    suspend fun createFolder(account: Account, name: String): String

    /** Klasörü yeniden adlandırır ve yeni yolunu döndürür. */
    suspend fun renameFolder(account: Account, path: String, newName: String): String

    /** Klasörü siler; içindeki iletiler önce çöp klasörüne taşınır (kaybolmaz). */
    suspend fun deleteFolder(account: Account, path: String)

    suspend fun message(account: Account, folderPath: String, uid: Long): MessageDetail

    suspend fun setSeen(account: Account, folderPath: String, uid: Long, seen: Boolean)

    suspend fun setFlagged(account: Account, folderPath: String, uid: Long, flagged: Boolean)

    /** İletiyi çöp klasörüne taşır (çöpteyse kalıcı siler). */
    suspend fun delete(account: Account, folderPath: String, uid: Long)

    /** İletiyi başka bir klasöre taşır. */
    suspend fun move(account: Account, folderPath: String, uid: Long, targetPath: String)

    /** Verilen role sahip klasörün yolunu döndürür; yoksa (arşiv için) oluşturur. */
    suspend fun folderFor(account: Account, role: FolderRole): String?

    /** İletinin [index] sıradaki ekini indirir (liste sırası [MessageDetail.attachments] ile aynıdır). */
    suspend fun attachment(account: Account, folderPath: String, uid: Long, index: Int): AttachmentData

    /** Gönderir ve bir kopyasını Gönderilenler klasörüne koyar. */
    suspend fun send(account: Account, message: OutgoingMessage)

    /** Hesaba ait açık bağlantıları kapatır (hesap silinirken). */
    suspend fun disconnect(account: Account)
}
