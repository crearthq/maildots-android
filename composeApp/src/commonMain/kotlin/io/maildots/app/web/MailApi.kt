package io.maildots.app.web

import io.maildots.app.mail.Folder
import io.maildots.app.mail.MessageSummary

/** Webmail'deki aramanın ölçütleri (tek klasörde). Boş bırakılan ölçüt uygulanmaz. */
data class WebSearch(
    val folder: String,
    val text: String = "",
    val from: String = "",
    val subject: String = "",
    val body: String = "",
    /** Bu andan (epoch ms) sonraki iletiler. */
    val sinceMillis: Long? = null,
    val unseen: Boolean = false,
    val flagged: Boolean = false,
    val hasAttachments: Boolean = false,
    /** Etiketin IMAP anahtar sözcüğü. */
    val keyword: String? = null,
    val offset: Int = 0,
    val limit: Int = 50,
)

/** İletinin webmail'deki tam hâli (gövde sunucuda temizlenmiştir; uzak görseller gizlilik vekilinden yüklenir). */
data class WebMessage(
    val summary: MessageSummary,
    val cc: List<io.maildots.app.mail.Address>,
    /** Gönderenin istediği yanıt adresleri (boşsa gönderene yanıt verilir). */
    val replyTo: List<io.maildots.app.mail.Address>,
    /** Kendi Gönderilenler kopyamıza yanıtta asıl alıcı. */
    val replyToAddress: String?,
    val messageId: String?,
    val references: List<String>,
    /** Temizlenmiş HTML (göreli /api/img-proxy görselleri içerebilir: taban adres [AccountService.BASE_URL]). */
    val html: String,
    val hasRemoteContent: Boolean,
    val attachments: List<io.maildots.app.mail.Attachment>,
) {
    /** Uçtan uca şifreli: gövde ve ekler sunucuda yok, cihazda açılır. */
    val encrypted: Boolean get() = summary.encrypted
}

/**
 * Posta işlemleri webmail API'si üzerinden (IMAP'i sunucu yapar). iOS'taki posta
 * motoru ([io.maildots.app.mail.WebMailEngine]) bunun üzerine kuruludur; Android
 * doğrudan IMAP kullanır, ama toplu işlemler ve "spam değil" gibi işlemler burada da vardır.
 *
 * Tüm çağrılar kutunun oturumuyla yapılır; sunucu kutuyu oturumdan çözer.
 */
interface MailApi {
    suspend fun folders(session: String): List<Folder>

    suspend fun messages(session: String, folder: String, offset: Int, limit: Int): List<MessageSummary>

    suspend fun search(session: String, query: WebSearch): List<MessageSummary>

    suspend fun message(session: String, folder: String, uid: Long): WebMessage

    /** Ekin içeriği (sıra [WebMessage.attachments] ile aynı). */
    suspend fun attachment(session: String, folder: String, uid: Long, index: Int): ByteArray

    suspend fun setFlags(
        session: String,
        folder: String,
        uids: List<Long>,
        seen: Boolean? = null,
        flagged: Boolean? = null,
        keyword: String? = null,
        add: Boolean = true,
    )

    /** Çöp kutusuna taşır. */
    suspend fun trash(session: String, folder: String, uids: List<Long>)

    /** Çöp kutusundakileri kalıcı siler (başka klasörde reddedilir). */
    suspend fun deleteForever(session: String, folder: String, uids: List<Long>)

    /** Arşive taşır; arşiv klasörü yoksa [WebError.Invalid]. */
    suspend fun archive(session: String, folder: String, uids: List<Long>)

    /** Gereksiz klasörüne taşır; [notSpam] ise gereksizden gelen kutusuna geri alır. */
    suspend fun spam(session: String, folder: String, uids: List<Long>, notSpam: Boolean = false)

    suspend fun move(session: String, folder: String, uids: List<Long>, target: String)

    suspend fun createFolder(session: String, name: String)

    suspend fun renameFolder(session: String, path: String, name: String)

    suspend fun deleteFolder(session: String, path: String)

    /** Klasörü boşaltır (çöp/gereksiz). */
    suspend fun emptyFolder(session: String, path: String)

    // ---- yeni uçlar (sunucuda yoksa [WebError.Unsupported]; şimdilik yalnız masaüstü kullanır)

    /** Klasördeki tüm iletileri okundu ([seen]) ya da okunmadı yapar. */
    suspend fun markAllRead(session: String, folder: String, seen: Boolean = true): Unit = throw WebError.Unsupported()

    /** Klasörü başka bir üst klasörün altına taşır ([newParent] null = en üst); yeni yolu döndürür (bilinmiyorsa null). */
    suspend fun moveFolder(session: String, path: String, newParent: String?): String? = throw WebError.Unsupported()

    /**
     * İletiyi okundu YAPMADAN getirir (önden alma). Sunucu yanıtta `peek: true` demezse ikinci
     * değer false'tur: eski sunucu parametreyi yok sayıp iletiyi okundu yapmış olabilir.
     */
    suspend fun peekMessage(session: String, folder: String, uid: Long): Pair<WebMessage, Boolean> =
        message(session, folder, uid) to false
}
