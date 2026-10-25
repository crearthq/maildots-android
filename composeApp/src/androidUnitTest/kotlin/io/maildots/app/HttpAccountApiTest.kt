package io.maildots.app

import io.maildots.app.web.HttpAccountApi
import io.maildots.app.web.WebError
import io.maildots.app.web.WebLogin
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Hesap servisi istemcisini gerçek HTTP üzerinden, süreç içi küçük bir sunucuya karşı
 * sınar (yanıtlar webmail'in gerçek yanıt biçimleridir). Yalnızca testte düz HTTP.
 */
class HttpAccountApiTest {
    private class Request(val method: String, val path: String, val cookie: String, val body: String)
    private class Response(val status: Int, val body: String, val headers: List<String> = emptyList())

    private lateinit var server: ServerSocket
    private lateinit var api: HttpAccountApi
    private val seen = ArrayList<Request>()
    private var handler: (Request) -> Response = { Response(404, "{}") }

    @Before
    fun start() {
        server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { serve(socket) }
                runCatching { socket.close() }
            }
        }
        api = HttpAccountApi("http://127.0.0.1:${server.localPort}", allowInsecureForTests = true)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().buffered()
        fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
            }
            return sb.toString()
        }
        val start = line().split(' ')
        var length = 0
        var cookie = ""
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val name = header.substringBefore(':').trim().lowercase()
            val value = header.substringAfter(':').trim()
            if (name == "content-length") length = value.toInt()
            if (name == "cookie") cookie = value
        }
        val body = String(ByteArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }, Charsets.UTF_8)
        val request = Request(start[0], start.getOrElse(1) { "/" }, cookie, body)
        synchronized(seen) { seen += request }
        val response = handler(request)
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ${response.status} X\r\n")
            append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
            response.headers.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    @Test
    fun rejectsPlainHttpOutsideTests() {
        assertFailsWith<IllegalArgumentException> { HttpAccountApi("http://webmail.maildots.io") }
        HttpAccountApi() // varsayılan adres HTTPS
    }

    @Test
    fun encryptionGuardIsParsedAndBadgeRuleHolds() = runBlocking<Unit> {
        handler = { r ->
            if (r.method == "POST") Response(200, """{"ok":true}""")
            else Response(
                200,
                """{"protectedSince":"2026-10-20T10:05:00.000Z","state":"restored","alert":{"at":"2026-10-23T20:37:40.000Z","kind":"weakened","restored":true}}""",
            )
        }
        val guard = api.encryptionGuard("oturum")!!
        assertEquals("weakened", guard.alert?.kind)
        assertEquals(true, guard.alert?.restored)
        val since = guard.protectedSinceMillis!!
        assertTrue(seen.last().path.startsWith("/api/keys/vault/guard"))
        val plain = io.maildots.app.mail.MessageSummary(
            uid = 1, subject = "", from = io.maildots.app.mail.Address("", ""), to = emptyList(),
            dateMillis = 0, seen = true, flagged = false, hasAttachment = false,
            receivedAtMillis = since + 1,
        )
        assertTrue(plain.storedUnencrypted(since))
        assertEquals(false, plain.copy(encrypted = true).storedUnencrypted(since))
        assertEquals(false, plain.copy(receivedAtMillis = since - 1).storedUnencrypted(since))
        assertEquals(false, plain.storedUnencrypted(null))
        api.acknowledgeEncryptionAlert("oturum")
        assertEquals("POST", seen.last().method)
    }

    @Test
    fun preloginRefusesAWeakenedArgon2Cost() = runBlocking<Unit> {
        var memory = 65_536
        var iterations = 3
        handler = {
            Response(
                200,
                """{"mode":"derived","hosted":true,"kdf":{"alg":"argon2id","memoryKiB":$memory,"iterations":$iterations,"parallelism":1,"salt":"c2FsdHNhbHRzYWx0c2FsdA=="}}""",
            )
        }
        assertEquals(65_536, api.prelogin("a@b.co").kdf?.memoryKiB)
        memory = 19_456
        assertFailsWith<WebError> { api.prelogin("a@b.co") }
        memory = 65_536
        iterations = 2
        assertFailsWith<WebError> { api.prelogin("a@b.co") }
    }

    @Test
    fun loginReturnsSessionOrPendingOrInvalid() = runBlocking<Unit> {
        handler = { r ->
            assertEquals("POST", r.method)
            assertEquals("/api/auth/login", r.path)
            when {
                r.body.contains("yanlis") -> Response(401, """{"error":"invalid_credentials"}""")
                r.body.contains("iki-adim") -> Response(
                    200,
                    """{"ok":true,"twoFactorRequired":true}""",
                    listOf("Set-Cookie: webmail_mfa_pending=bekleyen123; Path=/; HttpOnly; Max-Age=300"),
                )
                else -> Response(
                    200,
                    """{"ok":true}""",
                    listOf("Set-Cookie: webmail_session=oturum123; Path=/; HttpOnly; SameSite=lax", "Set-Cookie: NEXT_LOCALE=tr; Path=/"),
                )
            }
        }
        assertEquals("oturum123", assertIs<WebLogin.Ok>(api.login("a@b.co", "dogru")).session)
        assertTrue(seen.last().body.contains("\"email\":\"a@b.co\""))
        assertEquals("bekleyen123", assertIs<WebLogin.NeedsCode>(api.login("a@b.co", "iki-adim")).pending)
        assertIs<WebLogin.Invalid>(api.login("a@b.co", "yanlis"))
    }

    @Test
    fun verifyCodeSendsPendingCookieAndMapsErrors() = runBlocking<Unit> {
        handler = { r ->
            assertEquals("webmail_mfa_pending=bekleyen123", r.cookie)
            when {
                r.body.contains("111111") -> Response(401, """{"error":"invalid_code","remaining":2}""")
                r.body.contains("222222") -> Response(401, """{"error":"expired"}""")
                r.body.contains("333333") -> Response(429, """{"error":"rate_limited"}""")
                else -> Response(200, """{"ok":true}""", listOf("Set-Cookie: webmail_session=oturum9; Path=/; HttpOnly"))
            }
        }
        assertEquals(false, assertFailsWith<WebError.BadCode> { api.verifyCode("bekleyen123", "111111") }.expired)
        assertEquals(true, assertFailsWith<WebError.BadCode> { api.verifyCode("bekleyen123", "222222") }.expired)
        assertFailsWith<WebError.RateLimited> { api.verifyCode("bekleyen123", "333333") }
        assertEquals("oturum9", api.verifyCode("bekleyen123", "123456"))
    }

    @Test
    fun profileLabelsSignaturesAndPrefsAreParsed() = runBlocking<Unit> {
        handler = { r ->
            assertEquals("webmail_session=s1", r.cookie)
            when (r.path) {
                "/api/settings/personal" -> Response(200, """{"profile":{"displayName":" Deniz Yel ","avatarPath":null,"defaultIdentity":null,"hasAvatar":false}}""")
                "/api/labels" -> Response(200, """{"labels":[{"id":"l1","name":"Teklif","color":"green","imapKeyword":"MDL3f9a"}]}""")
                "/api/settings/personal/signatures" -> Response(
                    200,
                    """{"signatures":[{"id":"s1","name":"Ana","html":"<p>Deniz</p>","isDefault":true,"identity":null,"placement":"all_below"}]}""",
                )
                "/api/settings/prefs" -> Response(200, """{"prefs":{"theme":"dark","signatureOnNew":true,"signatureOnReply":false}}""")
                else -> Response(404, "{}")
            }
        }
        assertEquals("Deniz Yel", api.profile("s1").displayName)
        val label = api.labels("s1").single()
        assertEquals(listOf("l1", "Teklif", "green", "MDL3f9a"), listOf(label.id, label.name, label.color, label.keyword))
        val signature = api.signatures("s1").single()
        assertEquals("<p>Deniz</p>", signature.html)
        assertTrue(signature.isDefault)
        assertEquals(null, signature.identity)
        assertEquals("all_below", signature.placement)
        assertEquals(false, api.prefs("s1").signatureOnReply)
    }

    @Test
    fun writesSendTheExpectedRequests() = runBlocking<Unit> {
        handler = { r ->
            when {
                r.method == "PUT" && r.path == "/api/settings/personal" -> Response(200, """{"displayName":"Yeni Ad"}""")
                r.method == "POST" && r.path == "/api/labels" ->
                    if (r.body.contains("Var olan")) Response(409, """{"error":"name_taken"}""")
                    else Response(200, """{"ok":true,"label":{"id":"l2","name":"Yeni","color":"teal","imapKeyword":"MDLffff"}}""")
                r.method == "DELETE" && r.path == "/api/labels/l2?purge=1" -> Response(200, """{"ok":true}""")
                r.method == "POST" && r.path == "/api/settings/personal/signatures" ->
                    Response(201, """{"signature":{"id":"s9","name":"MailDots","html":"a<br>b","isDefault":true,"identity":null,"placement":"all_below"}}""")
                r.method == "POST" && r.path == "/api/auth/logout" -> Response(200, """{"ok":true}""")
                r.path == "/api/push/devices" && (r.method == "POST" || r.method == "DELETE") ->
                    if (r.body.contains("\"platform\":\"android\"") && r.body.contains("\"token\":\"belirtec-123\"")) Response(200, """{"ok":true}""")
                    else Response(400, """{"error":"invalid_request"}""")
                else -> Response(400, """{"error":"invalid"}""")
            }
        }
        assertEquals("Yeni Ad", api.setDisplayName("s1", "Yeni Ad"))
        assertEquals("""{"displayName":"Yeni Ad"}""", seen.last().body)
        assertEquals("MDLffff", api.createLabel("s1", "Yeni", "teal").keyword)
        assertFailsWith<WebError.NameTaken> { api.createLabel("s1", "Var olan", "red") }
        api.deleteLabel("s1", "l2")
        val saved = api.saveSignature("s1", null, "a<br>b")
        assertEquals("s9", saved.id)
        assertTrue(seen.last().body.contains("\"isDefault\":true"))
        assertTrue(seen.last().body.contains("\"html\":\"a<br>b\""))
        api.registerPush("s1", "belirtec-123")
        assertEquals("POST", seen.last().method)
        api.unregisterPush("s1", "belirtec-123")
        assertEquals("DELETE", seen.last().method)
        api.logout("s1")
        assertEquals("webmail_session=s1", seen.last().cookie)
        assertFailsWith<WebError.Invalid> { api.deleteSignature("s1", "yok") }
    }

    @Test
    fun errorsAreClassifiedAndRedirectsAreNotFollowed() = runBlocking<Unit> {
        handler = { r ->
            when (r.path) {
                "/api/labels" -> Response(401, """{"error":"unauthorized"}""")
                "/api/settings/personal" -> Response(302, "", listOf("Location: http://127.0.0.1:${server.localPort}/baska"))
                "/api/settings/prefs" -> Response(500, "sunucu hatasi")
                else -> Response(200, """{"profile":{"displayName":"yonlendirildi"}}""")
            }
        }
        assertFailsWith<WebError.Unauthorized> { api.labels("eski") }
        // Yönlendirme izlenmez: oturum çerezi başka bir adrese gitmez.
        assertFailsWith<WebError.Server> { api.profile("s1") }
        assertTrue(seen.none { it.path == "/baska" })
        assertFailsWith<WebError.Server> { api.prefs("s1") }

        val dead = HttpAccountApi("http://127.0.0.1:1", allowInsecureForTests = true)
        assertFailsWith<WebError.Network> { dead.profile("s1") }
    }

    @Test
    fun newEndpointsDegradeWhenServerLacksThem() = runBlocking<Unit> {
        // Eski sunucu: uç yok (Next.js'in JSON'suz 404'ü) → Unsupported; uç var ama klasör yok → Invalid.
        handler = { r ->
            when (r.path) {
                "/api/mail/mark-all-read" -> Response(404, "<!DOCTYPE html><html></html>")
                "/api/settings/folders/move" -> Response(404, """{"error":"not_found"}""")
                else -> Response(405, "")
            }
        }
        assertFailsWith<WebError.Unsupported> { api.markAllRead("s1", "INBOX") }
        assertFailsWith<WebError.Invalid> { api.moveFolder("s1", "A", "B") }
        handler = { r ->
            assertTrue(r.body.contains("\"newParent\":null"))
            Response(200, """{"ok":true,"path":"A"}""")
        }
        assertEquals("A", api.moveFolder("s1", "Ust/A", null))
        handler = { r ->
            assertTrue(r.body.contains("\"seen\":true") && r.body.contains("\"folder\":\"Projeler\""))
            Response(200, """{"ok":true}""")
        }
        api.markAllRead("s1", "Projeler")
    }

    @Test
    fun peekIsTrustedOnlyWhenServerEchoesIt() = runBlocking<Unit> {
        var echo = false
        handler = { r ->
            assertTrue(r.path.contains("peek=1"))
            Response(200, """{"uid":7,"subject":"S","seen":false,"html":"<p>x</p>"${if (echo) ",\"peek\":true" else ""}}""")
        }
        assertEquals(false, api.peekMessage("s1", "INBOX", 7).second)
        echo = true
        val (m, honoured) = api.peekMessage("s1", "INBOX", 7)
        assertTrue(honoured)
        assertEquals(false, m.summary.seen)
        handler = { r ->
            assertTrue(!r.path.contains("peek"))
            Response(200, """{"uid":7,"subject":"S","seen":true,"html":""}""")
        }
        api.message("s1", "INBOX", 7)
    }

    @Test
    fun composeExtrasAreSentOnlyWhenSetAndDroppedOnceIfRejected() = runBlocking<Unit> {
        // Eski sunucu: ek alanlı istek 400, alansız istek başarılı.
        handler = { r ->
            val extras = r.body.contains("\"importance\"") || r.body.contains("\"readReceipt\"") || r.body.contains("\"cid\"")
            when (r.path) {
                "/api/mail/send", "/api/mail/draft" ->
                    if (extras) Response(400, """{"error":"invalid_request"}""") else Response(200, """{"ok":true,"uid":7,"folder":"Drafts"}""")
                else -> Response(404, "{}")
            }
        }
        val plain = io.maildots.app.web.WebCompose(to = listOf("a@b.example"), subject = "s", html = "x")
        assertEquals(false, api.sendCompat("s1", plain))
        assertTrue(!seen.last().body.contains("importance") && !seen.last().body.contains("readReceipt"))
        val image = io.maildots.app.mail.AttachmentData("p.png", "image/png", byteArrayOf(1, 2), contentId = "img1@maildots")
        val rich = plain.copy(importance = "high", readReceipt = true, html = "<img src=\"cid:img1@maildots\">", attachments = listOf(image))
        val before = seen.size
        assertEquals(true, api.sendCompat("s1", rich))
        assertEquals(before + 2, seen.size)
        assertTrue(seen[before].body.contains("\"importance\":\"high\"") && seen[before].body.contains("\"readReceipt\":true"))
        assertTrue(seen[before].body.contains("\"cid\":\"img1@maildots\""))
        // İkinci deneme: ek alanlar yok ama görsel sıradan ek olarak kalır.
        assertTrue(!seen[before + 1].body.contains("importance") && seen[before + 1].body.contains("p.png"))
        assertEquals(7L, api.saveDraft("s1", rich).uid)
        // Ek alanlı ama başka nedenli 400 (ör. geçersiz gönderen) yeniden denenmez, kullanıcıya döner.
        handler = { Response(400, """{"error":"invalid_from"}""") }
        val n = seen.size
        assertFailsWith<WebError.Invalid> { api.sendCompat("s1", rich) }
        assertEquals(n + 1, seen.size)
        // Ek alanı olmayan 400 yeniden denenmez.
        handler = { Response(400, """{"error":"invalid_request"}""") }
        val count = seen.size
        assertFailsWith<WebError.Invalid> { api.sendCompat("s1", plain) }
        assertEquals(count + 1, seen.size)
    }
}
