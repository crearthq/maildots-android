package io.maildots.app.mail

import io.maildots.app.web.AccountApi
import io.maildots.app.web.AccountService
import io.maildots.app.web.MailApi
import io.maildots.app.web.WebCompose
import io.maildots.app.web.WebError
import io.maildots.app.web.WebLogin
import io.maildots.app.web.WebMessage
import io.maildots.app.web.WebSearch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Kutunun hesap servisi (webmail) oturumu. [renew] true ise eldeki oturum geçersizdir:
 * yeniden giriş yapılır. Giriş tamamlanamazsa (şifre reddedildi, iki adımlı kod bekleniyor) null.
 */
fun interface WebSessions {
    suspend fun session(account: Account, renew: Boolean): String?
}

/**
 * Posta motoru, webmail API'si üzerinden: IMAP/SMTP'yi sunucu yapar, uygulama yalnızca
 * HTTPS konuşur. Android ve iOS'un tek posta motoru.
 *
 * Davranış [MailEngine] sözleşmesiyle aynıdır: örn. klasör silinirken içindeki iletiler
 * önce çöpe taşınır, arşiv klasörü yoksa oluşturulur.
 */
class WebMailEngine(
    private val mail: MailApi,
    private val account: AccountApi,
    private val sessions: WebSessions,
    /** Hesap servisinin adresi (görsel vekili gövdedeki göreli adreslerle buradan çalışır). */
    private val baseUrl: String = AccountService.BASE_URL,
) : MailEngine {

    /** Hesap başına son bilinen klasörler (rol çözümü için; [folders] her çağrıda tazeler). */
    private val folderCache = HashMap<String, List<Folder>>()

    /** Son açılan iletinin ekleri (ek indirirken ad/tür için). */
    private val attachmentCache = HashMap<String, List<Attachment>>()
    private val cacheLock = Mutex()

    private fun Throwable.toMail(): Throwable = when (this) {
        is MailError -> this
        is WebError.Unauthorized -> MailError.Auth(this)
        is WebError.Network -> MailError.Network(this)
        is WebError.TooLarge -> MailError.TooLarge()
        // Sunucuda olmayan yeni uç: çağıran (masaüstü) kibarca "henüz desteklenmiyor" der.
        is WebError.Unsupported -> this
        else -> MailError.Unknown(this)
    }

    /** Oturumla çağırır; oturum geçersizse bir kez yenileyip tekrar dener. Hatalar [MailError] olur. */
    private suspend fun <T> withSession(account: Account, block: suspend (String) -> T): T {
        try {
            val session = sessions.session(account, renew = false) ?: throw MailError.Auth()
            return try {
                block(session)
            } catch (e: WebError.Unauthorized) {
                val fresh = sessions.session(account, renew = true) ?: throw MailError.Auth()
                block(fresh)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw e.toMail()
        }
    }

    override suspend fun verify(account: Account) {
        val result = try {
            this.account.login(account.email, account.password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw e.toMail()
        }
        when (result) {
            is WebLogin.Ok -> runCatching { this.account.logout(result.session) }
            // Şifre doğru; iki adımlı kod hesap ayarlarından istenir (oturum o zaman açılır).
            is WebLogin.NeedsCode -> Unit
            WebLogin.Invalid -> throw MailError.Auth()
        }
    }

    override suspend fun folders(account: Account): List<Folder> {
        val folders = withSession(account) { mail.folders(it) }
        cacheLock.withLock { folderCache[account.email] = folders }
        return folders
    }

    private suspend fun knownFolders(account: Account): List<Folder> =
        cacheLock.withLock { folderCache[account.email] } ?: folders(account)

    override suspend fun inboxUnread(account: Account): Int =
        folders(account).firstOrNull { it.role == FolderRole.INBOX }?.unread ?: 0

    override suspend fun displayName(account: Account): String? =
        withSession(account) { this.account.profile(it).displayName }.takeIf { it.isNotBlank() }

    override suspend fun messages(
        account: Account,
        folderPath: String,
        offset: Int,
        limit: Int,
        filter: MailFilter,
        label: MailLabel?,
    ): List<MessageSummary> = withSession(account) { session ->
        if (filter == MailFilter.ALL && label == null) {
            mail.messages(session, folderPath, offset, limit)
        } else {
            mail.search(
                session,
                WebSearch(
                    folder = folderPath,
                    unseen = filter == MailFilter.UNREAD,
                    flagged = filter == MailFilter.FLAGGED,
                    hasAttachments = filter == MailFilter.ATTACHMENTS,
                    keyword = label?.keyword,
                    offset = offset,
                    limit = limit,
                ),
            )
        }
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun search(account: Account, query: SearchQuery, limit: Int): List<MessageSummary> {
        if (!query.hasCriteria) return emptyList()
        val since = query.sinceDays?.let { Clock.System.now().toEpochMilliseconds() - it * 86_400_000L }
        val text = query.text.trim()
        val targets = query.folderPath?.let { listOf(it) }
            ?: knownFolders(account).filter { it.role != FolderRole.TRASH && it.role != FolderRole.JUNK }.map { it.path }
        return withSession(account) { session ->
            targets.flatMap { folder ->
                mail.search(
                    session,
                    WebSearch(
                        folder = folder,
                        text = if (query.scope == SearchScope.ANYWHERE) text else "",
                        subject = if (query.scope == SearchScope.SUBJECT) text else "",
                        body = if (query.scope == SearchScope.BODY) text else "",
                        // Alıcıya göre arama serviste ayrı ölçüt değil: genel metinde aranır.
                        from = query.from,
                        sinceMillis = since,
                        unseen = query.unread,
                        flagged = query.flagged,
                        hasAttachments = query.attachments,
                        keyword = query.label?.keyword,
                        limit = limit.coerceIn(1, 100),
                    ).let { if (query.to.isNotBlank() && it.text.isEmpty()) it.copy(text = query.to.trim()) else it },
                )
            }.sortedByDescending { it.dateMillis }.take(limit)
        }
    }

    override suspend fun setLabel(account: Account, folderPath: String, uid: Long, label: MailLabel, on: Boolean) =
        withSession(account) { mail.setFlags(it, folderPath, listOf(uid), keyword = label.keyword, add = on) }

    /** Hesap servisinde etiket silinince iletilerden de kaldırılır (bkz. AccountApi.deleteLabel). */
    override suspend fun clearLabel(account: Account, label: MailLabel) = Unit

    private suspend fun pathNamed(account: Account, name: String, fallback: String): String =
        folders(account).firstOrNull { it.name == name }?.path ?: fallback

    override suspend fun createFolder(account: Account, name: String): String {
        val clean = name.trim()
        knownFolders(account).firstOrNull { it.name == clean }?.let { return it.path }
        withSession(account) { mail.createFolder(it, clean) }
        return pathNamed(account, clean, clean)
    }

    override suspend fun renameFolder(account: Account, path: String, newName: String): String {
        val clean = newName.trim()
        withSession(account) { mail.renameFolder(it, path, clean) }
        return pathNamed(account, clean, clean)
    }

    override suspend fun deleteFolder(account: Account, path: String) {
        // Servis klasörü içindekilerle siler; iletiler kaybolmasın diye önce çöpe taşınır.
        withSession(account) { session ->
            var rounds = 0
            while (rounds++ < MAX_MOVE_ROUNDS) {
                val batch = mail.messages(session, path, 0, 100)
                if (batch.isEmpty()) break
                mail.trash(session, path, batch.map { it.uid })
            }
            mail.deleteFolder(session, path)
        }
        folders(account)
    }

    override suspend fun message(account: Account, folderPath: String, uid: Long): MessageDetail =
        toDetail(account, folderPath, uid, withSession(account) { mail.message(it, folderPath, uid) })

    /** Sunucu "okundu yapmadan getir"i (peek) destekliyor mu: null = henüz bilinmiyor. */
    @kotlin.concurrent.Volatile
    var peekSupported: Boolean? = null
        private set

    /**
     * Önden alma için okundu YAPMADAN getirir. Okunmamış ileti yalnız sunucunun peek'i
     * desteklediği (okunmuş bir iletide denenip `peek: true` yanıtı alınmış) biliniyorsa
     * istenir; aksi hâlde null (eski sunucu iletiyi kullanıcı görmeden okundu yapardı).
     */
    suspend fun peek(account: Account, folderPath: String, uid: Long, unread: Boolean): MessageDetail? {
        if (unread && peekSupported != true) return null
        val (m, honoured) = withSession(account) { mail.peekMessage(it, folderPath, uid) }
        peekSupported = honoured
        return toDetail(account, folderPath, uid, m)
    }

    /**
     * Klasördeki tüm iletileri okundu/okunmadı yapar. Sunucunun toplu ucu yoksa okunmamışlar
     * aranıp yüzerli gruplarla işaretlenir (en çok [MAX_MOVE_ROUNDS] tur).
     */
    suspend fun markAllRead(account: Account, folderPath: String, seen: Boolean = true) {
        withSession(account) { session ->
            try {
                mail.markAllRead(session, folderPath, seen)
            } catch (e: WebError.Unsupported) {
                var rounds = 0
                while (rounds++ < MAX_MOVE_ROUNDS) {
                    val batch = mail.search(session, WebSearch(folder = folderPath, unseen = seen, limit = 100))
                        .filter { it.seen != seen }
                    if (batch.isEmpty()) break
                    mail.setFlags(session, folderPath, batch.map { it.uid }, seen = seen)
                    if (batch.size < 100) break
                }
            }
        }
    }

    /** Klasörü başka bir üst klasöre taşır; sunucuda uç yoksa [WebError.Unsupported] (MailError'a çevrilmez). */
    suspend fun moveFolder(account: Account, path: String, newParent: String?): String? {
        val moved = withSession(account) { mail.moveFolder(it, path, newParent) }
        folders(account)
        return moved
    }

    /** İletinin ham hâli (RFC 822; kaynağı görüntüle / .eml olarak kaydet). Şifreliyse şifreli hâli. */
    suspend fun raw(account: Account, folderPath: String, uid: Long): ByteArray {
        val api = mail as? io.maildots.app.web.E2EApi ?: throw MailError.Unknown()
        return withSession(account) { api.rawMessage(it, folderPath, uid) }
    }

    private suspend fun toDetail(account: Account, folderPath: String, uid: Long, m: WebMessage): MessageDetail {
        cacheLock.withLock {
            if (attachmentCache.size > 20) attachmentCache.clear()
            attachmentCache["${account.email}|$folderPath|$uid"] = m.attachments
        }
        return MessageDetail(
            summary = m.summary,
            cc = m.cc,
            text = null,
            html = m.html,
            attachments = m.attachments,
            messageId = m.messageId,
            references = m.references,
            replyTo = m.replyTo,
            replyToAddress = m.replyToAddress,
            htmlBaseUrl = baseUrl,
        )
    }

    override suspend fun setSeen(account: Account, folderPath: String, uid: Long, seen: Boolean) =
        withSession(account) { mail.setFlags(it, folderPath, listOf(uid), seen = seen) }

    override suspend fun setFlagged(account: Account, folderPath: String, uid: Long, flagged: Boolean) =
        withSession(account) { mail.setFlags(it, folderPath, listOf(uid), flagged = flagged) }

    override suspend fun delete(account: Account, folderPath: String, uid: Long) {
        val inTrash = knownFolders(account).firstOrNull { it.path == folderPath }?.role == FolderRole.TRASH
        withSession(account) {
            if (inTrash) mail.deleteForever(it, folderPath, listOf(uid)) else mail.trash(it, folderPath, listOf(uid))
        }
    }

    override suspend fun move(account: Account, folderPath: String, uid: Long, targetPath: String) =
        withSession(account) { mail.move(it, folderPath, listOf(uid), targetPath) }

    override suspend fun folderFor(account: Account, role: FolderRole): String? {
        knownFolders(account).firstOrNull { it.role == role }?.let { return it.path }
        folders(account).firstOrNull { it.role == role }?.let { return it.path }
        if (role != FolderRole.ARCHIVE) return null
        return createFolder(account, ARCHIVE_NAME)
    }

    override suspend fun attachment(account: Account, folderPath: String, uid: Long, index: Int): AttachmentData {
        val key = "${account.email}|$folderPath|$uid"
        val list = cacheLock.withLock { attachmentCache[key] } ?: message(account, folderPath, uid).attachments
        val meta = list.getOrNull(index) ?: throw MailError.Unknown()
        if (meta.sizeBytes > MAX_ATTACHMENT_BYTES) throw MailError.TooLarge()
        val bytes = withSession(account) { mail.attachment(it, folderPath, uid, index) }
        return AttachmentData(meta.name, meta.mimeType, bytes)
    }

    override suspend fun send(account: Account, message: OutgoingMessage) {
        withSession(account) { session ->
            this.account.send(session, message.toWebCompose())
        }
    }

    override suspend fun disconnect(account: Account) {
        cacheLock.withLock {
            folderCache.remove(account.email)
            attachmentCache.keys.removeAll { it.startsWith(account.email + "|") }
        }
    }

    companion object {
        const val ARCHIVE_NAME = "Archive"
        const val MAX_MOVE_ROUNDS = 50
        const val MAX_ATTACHMENT_BYTES = 40L * 1024 * 1024
    }
}

/** Düz metni güvenli HTML'e çevirir (her satır kaçışlanır, satır sonları korunur). */
fun plainToHtml(text: String): String =
    text.replace("\r", "").lines().joinToString("<br>") { line ->
        line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

/** Gönderilecek iletiyi hesap servisinin biçimine çevirir. */
fun OutgoingMessage.toWebCompose(): WebCompose = WebCompose(
    to = to,
    cc = cc,
    bcc = bcc,
    from = from,
    subject = subject,
    html = html ?: plainToHtml(body),
    inReplyTo = inReplyTo,
    references = references,
    attachments = attachments,
    carryAttachmentsFromUid = carryAttachmentsFromUid,
    carryAttachmentsFromFolder = carryAttachmentsFromFolder,
    draftUid = draftUid,
    importance = importance,
    readReceipt = readReceipt,
)
