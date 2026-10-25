package io.maildots.app.web

import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.maildots.app.mail.Address
import io.maildots.app.mail.Attachment
import io.maildots.app.mail.Folder
import io.maildots.app.mail.FolderRole
import io.maildots.app.mail.MailLabel
import io.maildots.app.mail.MessageSummary
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Hesap servisinin (webmail) istemcisi: hesap ayarları ([AccountApi]) ve posta
 * işlemleri ([MailApi]). Ağ kuralları [WebClient]'tadır (yalnızca HTTPS, yönlendirme
 * izlenmez, yanıt boyutu sınırlı, oturum günlüğe yazılmaz).
 */
class HttpAccountApi(
    baseUrl: String = AccountService.BASE_URL,
    allowInsecureForTests: Boolean = false,
    userAgent: String = "MailDots-App",
    log: (String) -> Unit = {},
    /** Bildirimlerin mühürleneceği kurulum anahtarı (T6); yoksa sunucu içeriksiz bildirim yollar. */
    private val pushKey: () -> String? = { null },
    /** Sunucunun bizim adımıza göndereceği e-postaların dili ("tr" | "en"). */
    private val locale: () -> String = { "tr" },
    /** Çevrimdışı çalışma (masaüstü): true iken hiçbir istek gönderilmez, [WebError.Network] olur. */
    offline: () -> Boolean = { false },
) : AccountApi, MailApi, E2EApi {
    private val client = WebClient(baseUrl, allowInsecureForTests, userAgent, log, offline)

    private suspend fun call(
        method: String,
        path: String,
        cookie: Pair<String, String>? = null,
        body: JsonObject? = null,
    ): WebReply = client.call(method, path, cookie, body?.let { WebBody.Json(it) })

    private val WebReply.body: JsonObject get() = json

    private fun WebReply.error(): String = (json["error"] as? JsonPrimitive)?.content.orEmpty()

    /** Başarısız yanıtı hata sınıfına çevirir (başarılıysa yanıtı döndürür). */
    private fun WebReply.orThrow(): WebReply = when {
        status in 200..299 -> this
        status == 401 -> throw WebError.Unauthorized()
        status == 409 -> throw WebError.NameTaken()
        status == 413 -> throw WebError.TooLarge()
        status == 429 -> throw WebError.RateLimited()
        status == 400 || status == 403 || status == 404 || status == 422 ->
            if (error() == "name_taken") throw WebError.NameTaken() else throw WebError.Invalid()
        else -> throw WebError.Server(status)
    }

    private fun session(value: String) = AccountService.SESSION_COOKIE to value

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun JsonObject.optString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull
        ?: (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L

    private fun JsonObject.obj(key: String): JsonObject = (this[key] as? JsonObject) ?: throw WebError.Server(200)

    private fun JsonObject.array(key: String): JsonArray = (this[key] as? JsonArray) ?: throw WebError.Server(200)

    private fun JsonElement.toLabel(): WebLabel = jsonObject.let {
        WebLabel(id = it.string("id"), name = it.string("name"), color = it.string("color"), keyword = it.string("imapKeyword"))
    }.also { if (it.id.isEmpty() || it.keyword.isEmpty()) throw WebError.Server(200) }

    private fun JsonElement.toSignature(): WebSignature = jsonObject.let {
        WebSignature(
            id = it.string("id"),
            name = it.string("name"),
            html = it.string("html"),
            isDefault = it.bool("isDefault"),
            identity = it.optString("identity"),
            placement = it.string("placement").ifEmpty { "reply_below" },
        )
    }.also { if (it.id.isEmpty()) throw WebError.Server(200) }

    private fun JsonElement?.toAddress(): Address? {
        val o = this as? JsonObject ?: return null
        val email = o.string("address").ifEmpty { o.string("email") }
        if (email.isEmpty()) return null
        val name = o.string("name").takeIf { it != email }.orEmpty()
        return Address(name, email)
    }

    private fun JsonElement?.toAddresses(): List<Address> = (this as? JsonArray)?.mapNotNull { it.toAddress() }.orEmpty()

    private fun JsonElement?.toStrings(): List<String> =
        (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    @OptIn(ExperimentalTime::class)
    private fun isoMillis(value: String?): Long =
        value?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0L

    @OptIn(ExperimentalTime::class)
    private fun isoOf(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString()

    private fun JsonElement.toSummary(folder: String): MessageSummary = jsonObject.let {
        MessageSummary(
            uid = it.long("uid"),
            folderPath = folder,
            subject = it.string("subject"),
            from = it["from"].toAddress() ?: Address("", ""),
            to = it["to"].toAddresses(),
            dateMillis = isoMillis(it.optString("date")),
            seen = it.bool("seen"),
            flagged = it.bool("flagged"),
            hasAttachment = it.bool("hasAttachments"),
            labels = it["keywords"].toStrings().mapNotNull { k -> MailLabel.fromKeyword(k) }.distinct(),
            snippet = it.string("snippet"),
            encrypted = it.bool("encrypted"),
            receivedAtMillis = isoMillis(it.optString("receivedAt")),
        )
    }.also { if (it.uid <= 0) throw WebError.Server(200) }

    private fun role(raw: String): FolderRole = when (raw) {
        "inbox" -> FolderRole.INBOX
        "sent" -> FolderRole.SENT
        "drafts" -> FolderRole.DRAFTS
        "trash" -> FolderRole.TRASH
        "junk" -> FolderRole.JUNK
        "archive" -> FolderRole.ARCHIVE
        else -> FolderRole.OTHER
    }

    private fun q(value: String) = value.encodeURLParameter()

    private fun seg(value: String) = value.encodeURLPathPart()

    // ---- hesap

    override suspend fun login(email: String, password: String): WebLogin {
        val reply = call("POST", "/api/auth/login", body = buildJsonObject {
            put("email", email)
            // Giriş korumalı kutuda kayıtlı olan, şifreden türetilmiş giriş anahtarıdır
            // (şifrenin kendisi cihazda bile saklanmaz); sunucu onu ayrı alanda bekler.
            if (LOGIN_KEY.matches(password)) put("loginKey", password) else put("password", password)
        })
        if (reply.status == 401) return WebLogin.Invalid
        reply.orThrow()
        reply.cookies[AccountService.SESSION_COOKIE]?.takeIf { it.isNotEmpty() }?.let { return WebLogin.Ok(it) }
        val pending = reply.cookies[MFA_PENDING_COOKIE]?.takeIf { it.isNotEmpty() }
        if (reply.body.bool("twoFactorRequired") && pending != null) return WebLogin.NeedsCode(pending)
        throw WebError.Server(reply.status)
    }

    override suspend fun verifyCode(pending: String, code: String): String {
        val reply = call("POST", "/api/auth/2fa", cookie = MFA_PENDING_COOKIE to pending, body = buildJsonObject { put("token", code) })
        if (reply.status == 429) throw WebError.RateLimited()
        if (reply.status == 400 || reply.status == 401) {
            val error = reply.error()
            throw WebError.BadCode(expired = error == "expired" || error == "no_pending")
        }
        reply.orThrow()
        return reply.cookies[AccountService.SESSION_COOKIE]?.takeIf { it.isNotEmpty() } ?: throw WebError.Server(reply.status)
    }

    override suspend fun logout(session: String) {
        call("POST", "/api/auth/logout", cookie = session(session))
    }

    override suspend fun profile(session: String): WebProfile {
        val reply = call("GET", "/api/settings/personal", cookie = session(session)).orThrow()
        return WebProfile(displayName = reply.body.obj("profile").string("displayName").trim())
    }

    override suspend fun avatar(session: String, email: String): ByteArray? {
        val reply = client.call("GET", "/api/avatar?email=${q(email)}", cookie = session(session), accept = "image/*", maxBytes = MAX_AVATAR_BYTES)
        if (reply.status == 404) return null
        reply.orThrow()
        return reply.bytes.takeIf { it.isNotEmpty() && reply.contentType.startsWith("image/") && !reply.contentType.contains("svg") }
    }

    override suspend fun setDisplayName(session: String, name: String): String {
        val reply = call("PUT", "/api/settings/personal", cookie = session(session), body = buildJsonObject { put("displayName", name) }).orThrow()
        return reply.body.string("displayName")
    }

    override suspend fun labels(session: String): List<WebLabel> {
        val reply = call("GET", "/api/labels", cookie = session(session)).orThrow()
        return reply.body["labels"]?.jsonArray?.map { it.toLabel() } ?: throw WebError.Server(reply.status)
    }

    override suspend fun createLabel(session: String, name: String, color: String): WebLabel =
        call("POST", "/api/labels", cookie = session(session), body = buildJsonObject {
            put("name", name)
            put("color", color)
        }).orThrow().body.obj("label").toLabel()

    override suspend fun updateLabel(session: String, id: String, name: String?, color: String?): WebLabel =
        call("PATCH", "/api/labels/${seg(id)}", cookie = session(session), body = buildJsonObject {
            if (name != null) put("name", name)
            if (color != null) put("color", color)
        }).orThrow().body.obj("label").toLabel()

    override suspend fun deleteLabel(session: String, id: String) {
        call("DELETE", "/api/labels/${seg(id)}?purge=1", cookie = session(session)).orThrow()
    }

    override suspend fun signatures(session: String): List<WebSignature> {
        val reply = call("GET", "/api/settings/personal/signatures", cookie = session(session)).orThrow()
        return reply.body["signatures"]?.jsonArray?.map { it.toSignature() } ?: throw WebError.Server(reply.status)
    }

    override suspend fun saveSignature(session: String, existing: WebSignature?, html: String): WebSignature {
        val body = buildJsonObject {
            put("name", existing?.name?.ifBlank { DEFAULT_SIGNATURE_NAME } ?: DEFAULT_SIGNATURE_NAME)
            put("html", html)
            existing?.identity?.let { put("identity", it) }
            put("placement", existing?.placement ?: "all_below")
            put("isDefault", existing?.isDefault ?: true)
        }
        val reply = if (existing == null) call("POST", "/api/settings/personal/signatures", cookie = session(session), body = body)
        else call("PUT", "/api/settings/personal/signatures/${seg(existing.id)}", cookie = session(session), body = body)
        return reply.orThrow().body.obj("signature").toSignature()
    }

    override suspend fun deleteSignature(session: String, id: String) {
        call("DELETE", "/api/settings/personal/signatures/${seg(id)}", cookie = session(session)).orThrow()
    }

    override suspend fun prefs(session: String): WebPrefs {
        val prefs = call("GET", "/api/settings/prefs", cookie = session(session)).orThrow().body.obj("prefs")
        return WebPrefs(
            signatureOnNew = (prefs["signatureOnNew"] as? JsonPrimitive)?.booleanOrNull ?: true,
            signatureOnReply = (prefs["signatureOnReply"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }

    private fun pushBody(token: String) = buildJsonObject {
        put("token", token)
        put("platform", PushPlatform.current)
        pushKey()?.let { put("key", it) }
    }

    override suspend fun registerPush(session: String, token: String) {
        call("POST", "/api/push/devices", cookie = session(session), body = pushBody(token)).orThrow()
    }

    override suspend fun unregisterPush(session: String, token: String) {
        call("DELETE", "/api/push/devices", cookie = session(session), body = pushBody(token)).orThrow()
    }

    // ---- yazma

    private fun composeBody(m: WebCompose) = buildJsonObject {
        putJsonArray("to") { m.to.forEach { add(it) } }
        if (m.cc.isNotEmpty()) putJsonArray("cc") { m.cc.forEach { add(it) } }
        if (m.bcc.isNotEmpty()) putJsonArray("bcc") { m.bcc.forEach { add(it) } }
        m.from?.let { put("from", it) }
        put("subject", m.subject)
        put("html", m.html)
        m.inReplyTo?.let { put("inReplyTo", it) }
        if (m.references.isNotEmpty()) putJsonArray("references") { m.references.take(100).forEach { add(it) } }
        if (m.attachments.isNotEmpty()) {
            putJsonArray("attachments") {
                m.attachments.forEach { a ->
                    add(buildJsonObject {
                        put("filename", a.name)
                        put("contentType", a.mimeType)
                        put("contentBase64", Base64.encode(a.bytes))
                        // Satır içi görsel: gövdede <img src="cid:…">; sunucu alanı "cid".
                        a.contentId?.let { put("cid", it) }
                    })
                }
            }
        }
        m.carryAttachmentsFromUid?.let { put("forwardAttachmentsFromUid", it) }
        m.carryAttachmentsFromFolder?.let { put("forwardFromFolder", it) }
        m.draftUid?.let { put("draftUid", it) }
        // Yalnız verilmişse (eski sunucu tanımaz; bkz. sendCompat).
        m.importance?.takeIf { it == "high" || it == "low" }?.let { put("importance", it) }
        if (m.readReceipt) put("readReceipt", true)
    }

    override suspend fun send(session: String, message: WebCompose) {
        sendCompat(session, message)
    }

    override suspend fun sendCompat(session: String, message: WebCompose): Boolean {
        val reply = call("POST", "/api/mail/send", cookie = session(session), body = composeBody(message))
        if (reply.status == 400 && reply.error() == "no_recipient") throw WebError.Invalid()
        // Ek alanları tanımayan sunucu: 400 = gönderilmedi; onlarsız bir kez daha.
        if (rejectedExtras(reply, message.hasExtras)) {
            val again = call("POST", "/api/mail/send", cookie = session(session), body = composeBody(message.withoutExtras()))
            if (again.status == 400 && again.error() == "no_recipient") throw WebError.Invalid()
            again.orThrow()
            return true
        }
        reply.orThrow()
        return false
    }

    /**
     * Sunucu ek alanları (önem, okundu bilgisi, satır içi görsel) tanımadığı için mi reddetti?
     * Yalnız biçim hatası ("invalid_request" ya da bilinmeyen/geçersiz alan) ve ek alan gönderilmişse;
     * başka her 400 (ör. geçersiz gönderen, alıcı yok) olduğu gibi kullanıcıya döner.
     */
    private fun rejectedExtras(reply: WebReply, extras: Boolean): Boolean =
        extras && reply.status == 400 && reply.error() in EXTRA_FIELD_ERRORS

    override suspend fun saveDraft(session: String, message: WebCompose): WebDraftSaved {
        var reply = call("POST", "/api/mail/draft", cookie = session(session), body = composeBody(message))
        if (rejectedExtras(reply, message.hasExtras)) {
            reply = call("POST", "/api/mail/draft", cookie = session(session), body = composeBody(message.withoutExtras()))
        }
        val body = reply.orThrow().body
        return WebDraftSaved(uid = body.long("uid").takeIf { it > 0 }, folder = body.string("folder"))
    }

    override suspend fun deleteDraft(session: String, uid: Long) {
        call("DELETE", "/api/mail/draft?uid=$uid", cookie = session(session)).orThrow()
    }

    override suspend fun fromAddresses(session: String): List<String> =
        call("GET", "/api/settings/from-addresses", cookie = session(session)).orThrow().body["from"].toStrings()

    override suspend fun suggest(session: String, query: String): List<WebSuggestion> {
        val clean = query.trim().take(100)
        if (clean.isEmpty()) return emptyList()
        val body = call("GET", "/api/contacts/suggest?q=${q(clean)}", cookie = session(session)).orThrow().body
        return (body["suggestions"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val email = o.string("email").ifEmpty { return@mapNotNull null }
            WebSuggestion(name = o.string("name"), email = email, source = o.string("source"))
        }
    }

    // ---- okuma

    override suspend fun thread(session: String, folder: String, uid: Long): List<WebThreadItem> {
        val body = call("GET", "/api/mail/thread?folder=${q(folder)}&uid=$uid", cookie = session(session)).orThrow().body
        return body.array("messages").map { e ->
            val o = e.jsonObject
            WebThreadItem(
                uid = o.long("uid"),
                folder = o.string("folder").ifEmpty { folder },
                outgoing = o.bool("outgoing"),
                from = o["from"].toAddress(),
                subject = o.string("subject"),
                dateMillis = isoMillis(o.optString("date")),
                snippet = o.string("snippet"),
            )
        }
    }

    override suspend fun translate(session: String, html: String, target: String): WebTranslation {
        val reply = call("POST", "/api/translate", cookie = session(session), body = buildJsonObject {
            put("html", html.take(500_000))
            put("target", target)
            put("mode", "translate")
        })
        if (reply.status == 503) throw WebError.Server(503)
        val body = reply.orThrow().body
        return WebTranslation(html = body.string("html"), sourceLanguage = body.string("source"))
    }

    // ---- hesap

    override suspend fun usage(session: String): WebUsage {
        val body = call("GET", "/api/usage", cookie = session(session)).orThrow().body
        return WebUsage(
            usedBytes = body.long("usedBytes"),
            totalBytes = body.long("totalBytes"),
            percent = body.long("percent").toInt(),
            unlimited = body.bool("unlimited"),
        )
    }

    override suspend fun changePassword(session: String, current: String, new: String) {
        val reply = call("POST", "/api/settings/password", cookie = session(session), body = buildJsonObject {
            put("currentPassword", current)
            put("newPassword", new)
        })
        when {
            reply.status == 403 && reply.error() == "wrong_current" -> throw WebError.WrongPassword()
            reply.status == 422 && reply.error() == "policy" -> throw WebError.Policy(reply.body.string("detail"))
        }
        reply.orThrow()
    }

    // ---- posta (MailApi)

    override suspend fun folders(session: String): List<Folder> =
        call("GET", "/api/mail/folders", cookie = session(session)).orThrow().body.array("folders").map { e ->
            val o = e.jsonObject
            Folder(
                path = o.string("path"),
                name = o.string("name").ifEmpty { o.string("path") },
                role = role(o.string("role")),
                unread = o.long("unread").toInt(),
                total = o.long("total").toInt(),
            )
        }.filter { it.path.isNotEmpty() }

    override suspend fun messages(session: String, folder: String, offset: Int, limit: Int): List<MessageSummary> {
        val path = "/api/mail/messages?folder=${q(folder)}&offset=$offset&limit=${limit.coerceIn(1, 100)}"
        return call("GET", path, cookie = session(session)).orThrow().body.array("messages").map { it.toSummary(folder) }
    }

    override suspend fun search(session: String, query: WebSearch): List<MessageSummary> {
        val params = buildList {
            add("folder=" + q(query.folder))
            if (query.text.isNotBlank()) add("q=" + q(query.text.trim().take(256)))
            if (query.from.isNotBlank()) add("from=" + q(query.from.trim().take(256)))
            if (query.subject.isNotBlank()) add("subject=" + q(query.subject.trim().take(256)))
            if (query.body.isNotBlank()) add("body=" + q(query.body.trim().take(256)))
            query.sinceMillis?.let { add("since=" + q(isoOf(it))) }
            if (query.unseen) add("unseen=1")
            if (query.flagged) add("flagged=1")
            if (query.hasAttachments) add("hasAttachments=1")
            query.keyword?.let { add("keyword=" + q(it)) }
            add("offset=${query.offset}")
            add("limit=${query.limit.coerceIn(1, 100)}")
        }
        val body = call("GET", "/api/mail/search?" + params.joinToString("&"), cookie = session(session)).orThrow().body
        return body.array("messages").map { it.toSummary(query.folder) }
    }

    override suspend fun message(session: String, folder: String, uid: Long): WebMessage = fetchMessage(session, folder, uid, peek = false).first

    override suspend fun peekMessage(session: String, folder: String, uid: Long): Pair<WebMessage, Boolean> =
        fetchMessage(session, folder, uid, peek = true)

    private suspend fun fetchMessage(session: String, folder: String, uid: Long, peek: Boolean): Pair<WebMessage, Boolean> {
        val extra = if (peek) "&peek=1" else ""
        val reply = call("GET", "/api/mail/messages/$uid?folder=${q(folder)}&images=0$extra", cookie = session(session))
        val o = reply.orThrow().body
        val summary = MessageSummary(
            uid = o.long("uid").takeIf { it > 0 } ?: uid,
            folderPath = folder,
            subject = o.string("subject"),
            from = o["from"].toAddress() ?: Address("", ""),
            to = o["to"].toAddresses(),
            dateMillis = isoMillis(o.optString("date")),
            seen = o.bool("seen"),
            flagged = o.bool("flagged"),
            hasAttachment = (o["attachments"] as? JsonArray)?.isNotEmpty() == true,
            labels = o["keywords"].toStrings().mapNotNull { MailLabel.fromKeyword(it) }.distinct(),
            encrypted = o.bool("encrypted"),
        )
        val message = WebMessage(
            summary = summary,
            cc = o["cc"].toAddresses(),
            replyTo = o["replyTo"].toAddresses(),
            replyToAddress = o.optString("replyToAddress"),
            messageId = o.optString("messageId"),
            references = o["references"].toStrings(),
            html = o.string("html"),
            hasRemoteContent = o.bool("hasRemoteContent"),
            attachments = (o["attachments"] as? JsonArray).orEmpty().map { e ->
                val a = e.jsonObject
                Attachment(name = a.string("filename").ifEmpty { "ek" }, mimeType = a.string("contentType").ifEmpty { "application/octet-stream" }, sizeBytes = a.long("size"))
            },
        )
        // Yalnız sunucu açıkça "okundu yapmadım" derse güvenilir (eski sunucu peek'i yok sayar).
        return message to (peek && o.bool("peek"))
    }

    override suspend fun attachment(session: String, folder: String, uid: Long, index: Int): ByteArray {
        val reply = client.call(
            "GET",
            "/api/mail/messages/$uid/attachments/$index?folder=${q(folder)}",
            cookie = session(session),
            accept = "application/octet-stream",
            maxBytes = MAX_ATTACHMENT_BYTES,
        )
        reply.orThrow()
        return reply.bytes
    }

    private fun uidsBody(folder: String, uids: List<Long>, extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null) =
        buildJsonObject {
            put("folder", folder)
            putJsonArray("uids") { uids.take(1000).forEach { add(it) } }
            extra?.invoke(this)
        }

    override suspend fun setFlags(
        session: String,
        folder: String,
        uids: List<Long>,
        seen: Boolean?,
        flagged: Boolean?,
        keyword: String?,
        add: Boolean,
    ) {
        if (uids.isEmpty()) return
        call("POST", "/api/mail/flag", cookie = session(session), body = uidsBody(folder, uids) {
            seen?.let { put("seen", it) }
            flagged?.let { put("flagged", it) }
            keyword?.let {
                put("keyword", it)
                put("add", add)
            }
        }).orThrow()
    }

    override suspend fun trash(session: String, folder: String, uids: List<Long>) {
        if (uids.isEmpty()) return
        call("POST", "/api/mail/delete", cookie = session(session), body = uidsBody(folder, uids)).orThrow()
    }

    override suspend fun deleteForever(session: String, folder: String, uids: List<Long>) {
        if (uids.isEmpty()) return
        call("DELETE", "/api/mail/delete", cookie = session(session), body = uidsBody(folder, uids)).orThrow()
    }

    override suspend fun archive(session: String, folder: String, uids: List<Long>) {
        if (uids.isEmpty()) return
        call("POST", "/api/mail/archive", cookie = session(session), body = uidsBody(folder, uids)).orThrow()
    }

    override suspend fun spam(session: String, folder: String, uids: List<Long>, notSpam: Boolean) {
        if (uids.isEmpty()) return
        val path = if (notSpam) "/api/mail/spam?notSpam=1" else "/api/mail/spam"
        call("POST", path, cookie = session(session), body = uidsBody(folder, uids)).orThrow()
    }

    override suspend fun move(session: String, folder: String, uids: List<Long>, target: String) {
        if (uids.isEmpty() || folder == target) return
        call("POST", "/api/mail/move", cookie = session(session), body = uidsBody(folder, uids) { put("to", target) }).orThrow()
    }

    override suspend fun createFolder(session: String, name: String) {
        call("POST", "/api/settings/folders", cookie = session(session), body = buildJsonObject { put("name", name) }).orThrow()
    }

    override suspend fun renameFolder(session: String, path: String, name: String) {
        call("POST", "/api/settings/folders/rename", cookie = session(session), body = buildJsonObject {
            put("path", path)
            put("name", name)
        }).orThrow()
    }

    override suspend fun deleteFolder(session: String, path: String) {
        call("POST", "/api/settings/folders/delete", cookie = session(session), body = buildJsonObject { put("path", path) }).orThrow()
    }

    override suspend fun emptyFolder(session: String, path: String) {
        call("POST", "/api/settings/folders/empty", cookie = session(session), body = buildJsonObject { put("path", path) }).orThrow()
    }

    /** Uç sunucuda yok (eski sürüm): Next.js'in JSON'suz 404'ü ya da 405/501. */
    private fun WebReply.unsupported(): Boolean = (status == 404 && error().isEmpty()) || status == 405 || status == 501

    override suspend fun markAllRead(session: String, folder: String, seen: Boolean) {
        val reply = call("POST", "/api/mail/mark-all-read", cookie = session(session), body = buildJsonObject {
            put("folder", folder)
            put("seen", seen)
        })
        if (reply.unsupported()) throw WebError.Unsupported()
        reply.orThrow()
    }

    override suspend fun moveFolder(session: String, path: String, newParent: String?): String? {
        val reply = call("POST", "/api/settings/folders/move", cookie = session(session), body = buildJsonObject {
            put("path", path)
            if (newParent == null) put("newParent", JsonNull) else put("newParent", newParent)
        })
        if (reply.unsupported()) throw WebError.Unsupported()
        return reply.orThrow().body.optString("path")?.takeIf { it.isNotEmpty() }
    }

    // ---- uçtan uca şifreleme (E2EApi)

    override suspend fun prelogin(email: String): Prelogin {
        val reply = call("POST", "/api/auth/prelogin", body = buildJsonObject { put("email", email) }).orThrow()
        val o = reply.body
        val hosted = o.bool("hosted")
        return when (o.string("mode")) {
            "derived" -> Prelogin(hosted = true, kdf = kdfOf(o.obj("kdf")))
            "password" -> Prelogin(hosted = hosted, kdf = null)
            else -> throw WebError.Server(reply.status)
        }
    }

    /**
     * Sunucunun verdiği Argon2id parametreleri. Her kasa 64 MiB / 3 turla açılır; daha
     * zayıfı, şifrenin çevrimdışı tahminini ucuzlatmak isteyen bir sunucu demektir:
     * reddedilir (webmail'deki meetsKdfFloor ile aynı taban).
     */
    private fun kdfOf(o: JsonObject): io.maildots.app.e2e.KdfParams = io.maildots.app.e2e.KdfParams(
        alg = o.string("alg").also { if (it != "argon2id") throw WebError.Server(200) },
        memoryKiB = o.long("memoryKiB").toInt().also { if (it < KDF_MIN_MEMORY_KIB) throw WebError.Server(200) },
        iterations = o.long("iterations").toInt().also { if (it < KDF_MIN_ITERATIONS) throw WebError.Server(200) },
        parallelism = o.long("parallelism").toInt(),
        salt = o.string("salt").ifEmpty { throw WebError.Server(200) },
    )

    override suspend fun vault(session: String): WebVault? {
        val o = call("GET", "/api/keys/vault", cookie = session(session)).orThrow().body
        if (!o.bool("available")) return null
        val v = o["vault"] as? JsonObject ?: return null
        return WebVault(
            owner = o.string("owner"),
            vault = v,
            certificatePem = v.string("certificatePem").ifEmpty { throw WebError.Server(200) },
            kdf = kdfOf(v.obj("kdf")),
            loginMode = v.string("loginMode").ifEmpty { "password" },
            encryptionEnabled = v.bool("encryptionEnabled"),
        )
    }

    override suspend fun encryptionGuard(session: String): EncryptionGuard? {
        val lang = if (locale() == "en") "en" else "tr"
        val o = call("GET", "/api/keys/vault/guard?locale=$lang", cookie = session(session)).orThrow().body
        val alert = (o["alert"] as? JsonObject)?.let {
            EncryptionAlert(atMillis = isoMillis(it.optString("at")), kind = it.string("kind"), restored = it.bool("restored"))
        }
        val since = o.optString("protectedSince")?.let { isoMillis(it) }?.takeIf { it > 0 }
        return EncryptionGuard(protectedSinceMillis = since, alert = alert)
    }

    /** Çerezsiz: kayıt herkese açık, sunucu kimin sorduğunu bilmesin (kişiye özel kayıt gösteremesin). */
    override suspend fun keyLog(session: String, after: Int): JsonObject =
        call("GET", "/api/keys/log?after=$after").orThrow().body

    override suspend fun onboardingRequired(session: String): Boolean =
        call("GET", "/api/keys/onboarding", cookie = session(session)).orThrow().body.bool("required")

    override suspend fun acknowledgeEncryptionAlert(session: String) {
        call("POST", "/api/keys/vault/guard", cookie = session(session)).orThrow()
    }

    override suspend fun rawMessage(session: String, folder: String, uid: Long): ByteArray {
        val reply = client.call(
            "GET",
            "/api/mail/messages/$uid/raw?folder=${q(folder)}",
            cookie = session(session),
            accept = "message/rfc822",
            maxBytes = MAX_RAW_BYTES,
        )
        reply.orThrow()
        return reply.bytes
    }

    override suspend fun keyDirectory(session: String, addresses: List<String>): Map<String, String> {
        val unique = addresses.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (unique.isEmpty()) return emptyMap()
        val keys = call("POST", "/api/keys/directory", cookie = session(session), body = buildJsonObject {
            putJsonArray("addresses") { unique.take(100).forEach { add(it) } }
        }).orThrow().body.obj("keys")
        return keys.mapNotNull { (address, value) ->
            val pem = (value as? JsonObject)?.string("certificatePem")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            address.lowercase().takeIf { it in unique }?.let { it to pem }
        }.toMap()
    }

    override suspend fun sendEncrypted(session: String, message: EncryptedSend): List<String> =
        sendEncryptedCompat(session, message).first

    override suspend fun sendEncryptedCompat(session: String, message: EncryptedSend): Pair<List<String>, Boolean> {
        val extras = message.importance != null || message.readReceipt
        val reply = postEncrypted(session, message)
        if (rejectedExtras(reply, extras)) {
            val again = postEncrypted(session, message.copy(importance = null, readReceipt = false))
            if (again.status == 400 && again.error() == "no_recipient") throw WebError.Invalid()
            return again.orThrow().body["failedBcc"].toStrings() to true
        }
        if (reply.status == 400 && reply.error() == "no_recipient") throw WebError.Invalid()
        return reply.orThrow().body["failedBcc"].toStrings() to false
    }

    private suspend fun postEncrypted(session: String, message: EncryptedSend): WebReply =
        call("POST", "/api/mail/send-encrypted", cookie = session(session), body = buildJsonObject {
            putJsonArray("to") { message.to.forEach { add(it) } }
            if (message.cc.isNotEmpty()) putJsonArray("cc") { message.cc.forEach { add(it) } }
            message.from?.let { put("from", it) }
            put("subject", message.subject)
            message.inReplyTo?.let { put("inReplyTo", it) }
            if (message.references.isNotEmpty()) putJsonArray("references") { message.references.take(100).forEach { add(it) } }
            message.draftUid?.let { put("draftUid", it) }
            put("ciphertext", message.ciphertext)
            if (message.bccCopies.isNotEmpty()) {
                putJsonArray("bccCopies") {
                    message.bccCopies.forEach { (address, ciphertext) ->
                        add(buildJsonObject {
                            put("address", address)
                            put("ciphertext", ciphertext)
                        })
                    }
                }
            }
            message.importance?.takeIf { it == "high" || it == "low" }?.let { put("importance", it) }
            if (message.readReceipt) put("readReceipt", true)
        })

    override suspend fun saveEncryptedDraft(session: String, message: EncryptedDraft): WebDraftSaved {
        val body = call("POST", "/api/mail/draft-encrypted", cookie = session(session), body = buildJsonObject {
            putJsonArray("to") { message.to.forEach { add(it) } }
            if (message.cc.isNotEmpty()) putJsonArray("cc") { message.cc.forEach { add(it) } }
            if (message.bcc.isNotEmpty()) putJsonArray("bcc") { message.bcc.forEach { add(it) } }
            message.from?.let { put("from", it) }
            put("subject", message.subject)
            message.inReplyTo?.let { put("inReplyTo", it) }
            if (message.references.isNotEmpty()) putJsonArray("references") { message.references.take(100).forEach { add(it) } }
            message.draftUid?.let { put("draftUid", it) }
            put("ciphertext", message.ciphertext)
        }).orThrow().body
        return WebDraftSaved(uid = body.long("uid").takeIf { it > 0 }, folder = body.string("folder"))
    }

    override suspend fun changePasswordWithVault(
        session: String,
        currentSecret: String,
        newSecret: String,
        vault: JsonObject,
        target: String,
    ) {
        val reply = call("POST", "/api/settings/password", cookie = session(session), body = buildJsonObject {
            put("currentPassword", currentSecret)
            put("newPassword", newSecret)
            put("vault", vault)
            put("target", target)
        })
        when {
            reply.status == 403 && reply.error() == "wrong_current" -> throw WebError.WrongPassword()
            reply.status == 422 && reply.error() == "policy" -> throw WebError.Policy(reply.body.string("detail"))
            reply.error() == "vault_update_failed" -> {
                // Şifre değişti ama yeni kilitli kopya kaydedilemedi: yeni şifreyle bir kez daha.
                call("PUT", "/api/keys/vault/password", cookie = session(session), body = buildJsonObject {
                    put("currentPassword", newSecret)
                    vault.forEach { (k, v) -> put(k, v) }
                }).orThrow()
                return
            }
        }
        reply.orThrow()
    }

    private companion object {
        /** Giriş korumalı kutunun giriş anahtarı biçimi (webmail: lib/crypto/vault-schema). */
        val LOGIN_KEY = Regex("^md1_[A-Za-z0-9_-]{43}$")
        const val MAX_RAW_BYTES = 32 * 1024 * 1024
        const val MFA_PENDING_COOKIE = "webmail_mfa_pending"
        const val DEFAULT_SIGNATURE_NAME = "MailDots"
        const val MAX_ATTACHMENT_BYTES = 40 * 1024 * 1024
        const val MAX_AVATAR_BYTES = 256 * 1024

        /** Ek alanların tanınmadığını gösteren sunucu hataları (zod: invalid_request). */
        val EXTRA_FIELD_ERRORS = setOf("invalid_request", "unknown_field", "unrecognized_keys", "invalid_field")
    }
}

/** Bildirim kaydında bildirilen platform ("android" / "ios"); platform kodu açılışta ayarlar. */
object PushPlatform {
    var current: String = "android"
}

/** Kabul edilen en düşük Argon2id maliyeti (webmail DEFAULT_KDF ile aynı). */
internal const val KDF_MIN_MEMORY_KIB = 65_536
internal const val KDF_MIN_ITERATIONS = 3
