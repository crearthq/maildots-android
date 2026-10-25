package io.maildots.app

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import io.maildots.app.mail.Account
import io.maildots.app.mail.AttachmentData
import io.maildots.app.mail.FolderRole
import io.maildots.app.mail.MailError
import io.maildots.app.mail.MailFilter
import io.maildots.app.mail.OutgoingMessage
import io.maildots.app.mail.SearchQuery
import io.maildots.app.mail.WebMailEngine
import io.maildots.app.mail.WebSessions
import io.maildots.app.web.HttpAccountApi
import io.maildots.app.web.WebCompose
import io.maildots.app.web.WebLogin
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test

/**
 * Uçtan uca: posta motoru webmail API'si üzerinden ([WebMailEngine], iOS'un motoru).
 * Yerel webmail'in çalışıyor olması gerekir (MAIL_HOST=127.0.0.1 IMAP_PORT=4993 SMTP_PORT=4465
 * SMTP_SECURE=true MAIL_TLS_REJECT_UNAUTHORIZED=false). Deneme posta sunucusunu test kendisi açar.
 *
 *   MAILDOTS_WEB_IT=http://localhost:4319 ./gradlew :composeApp:testDebugUnitTest --tests "*WebMailEngineIT*"
 */
class WebMailEngineIT {
    @Test
    fun endToEndThroughTheWebmailApi() = runBlocking {
        val base = System.getenv("MAILDOTS_WEB_IT")
        Assume.assumeTrue(!base.isNullOrBlank())
        Locale.setDefault(Locale.ROOT)
        val smtp = ServerSetup(4025, "127.0.0.1", ServerSetup.PROTOCOL_SMTP)
        val smtps = ServerSetup(4465, "127.0.0.1", ServerSetup.PROTOCOL_SMTPS)
        val imap = ServerSetup(4143, "127.0.0.1", ServerSetup.PROTOCOL_IMAP)
        val imaps = ServerSetup(4993, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS)
        val server = GreenMail(arrayOf(smtp, smtps, imap, imaps))
        server.start()
        try {
            val me = "deniz@kuzeyatolye.test"
            server.setUser(me, me, "dev-parola-1")
            GreenMailUtil.sendTextEmail(me, "elif@kuzeyatolye.test", "Cephe revizyonu", "Merhaba Deniz,\n\nKesitler hazır.", smtp)
            GreenMailUtil.sendTextEmail(me, "muhasebe@ornek.test", "Ekim faturası", "Fatura ektedir.", smtp)
            val pdf = "%PDF-1.4\n%%EOF".toByteArray()
            GreenMailUtil.sendAttachmentEmail(me, "sozlesme@ornek.test", "Sözleşme", "Belgeler ektedir.", pdf, "application/pdf", "Sozlesme.pdf", "sozlesme", smtp)
            server.waitForIncomingEmail(5000, 3)
            val store = javax.mail.Session.getInstance(java.util.Properties()).getStore("imap")
            store.connect("127.0.0.1", 4143, me, "dev-parola-1")
            for (name in listOf("Sent", "Drafts", "Junk", "Trash")) store.getFolder(name).takeIf { !it.exists() }?.create(javax.mail.Folder.HOLDS_MESSAGES)
            store.close()

            val api = HttpAccountApi(base!!, allowInsecureForTests = true, userAgent = "MailDots-IT")
            var session: String? = null
            var logins = 0
            val sessions = WebSessions { account, renew ->
                if (renew) session = null
                session ?: (api.login(account.email, account.password) as? WebLogin.Ok)?.session.also { logins++; session = it }
            }
            val engine = WebMailEngine(api, api, sessions)
            val account = Account(me, "dev-parola-1")

            assertFailsWith<MailError.Auth> { engine.verify(Account(me, "yanlis")) }
            engine.verify(account)

            val folders = engine.folders(account)
            assertEquals(FolderRole.INBOX, folders.first { it.path == "INBOX" }.role)
            assertTrue(folders.any { it.role == FolderRole.TRASH }, "çöp klasörü rolü: $folders")

            val inbox = engine.messages(account, "INBOX", 0, 30)
            assertEquals(3, inbox.size)
            assertTrue(inbox.all { it.snippet.isNotEmpty() || it.hasAttachment }, "özet metin")
            assertEquals(3, engine.inboxUnread(account))

            val withFile = engine.messages(account, "INBOX", 0, 30, MailFilter.ATTACHMENTS)
            assertEquals(listOf("Sözleşme"), withFile.map { it.subject })

            val found = engine.search(account, SearchQuery(text = "fatura"), 50)
            assertEquals(listOf("Ekim faturası"), found.map { it.subject })

            val contract = withFile.single()
            val detail = engine.message(account, "INBOX", contract.uid)
            assertEquals("Sozlesme.pdf", detail.attachments.single().name)
            assertTrue(detail.messageId?.contains("@") == true, "Message-ID")
            val file = engine.attachment(account, "INBOX", contract.uid, 0)
            assertTrue(String(file.bytes).startsWith("%PDF"))

            engine.setSeen(account, "INBOX", contract.uid, true)
            engine.setFlagged(account, "INBOX", contract.uid, true)
            val flagged = engine.messages(account, "INBOX", 0, 30, MailFilter.FLAGGED)
            assertEquals(listOf(contract.uid), flagged.map { it.uid })

            // Yanıt: konuşma başlıklarıyla, Gönderilenler'e kopya sunucuda.
            engine.send(
                account,
                OutgoingMessage(
                    to = listOf("sozlesme@ornek.test"),
                    subject = "Ynt: Sözleşme",
                    body = "Teşekkürler <b>kaçış</b>",
                    inReplyTo = detail.messageId,
                    references = listOf(detail.messageId!!),
                    attachments = listOf(AttachmentData("not.txt", "text/plain", "merhaba".toByteArray())),
                ),
            )
            val delivered = server.receivedMessages.last()
            assertEquals(detail.messageId, delivered.getHeader("In-Reply-To")?.firstOrNull())
            val sent = engine.messages(account, folders.first { it.role == FolderRole.SENT }.path, 0, 10)
            assertTrue(sent.any { it.subject == "Ynt: Sözleşme" }, "Gönderilenler kopyası")

            // Taslak: kaydet, değiştir (eskisinin yerini alır), sil.
            val draft = api.saveDraft(session!!, WebCompose(to = listOf("a@b.test"), subject = "Taslak", html = "ilk"))
            val draft2 = api.saveDraft(session!!, WebCompose(to = listOf("a@b.test"), subject = "Taslak", html = "ikinci", draftUid = draft.uid))
            val draftsPath = folders.first { it.role == FolderRole.DRAFTS }.path
            assertEquals(1, engine.messages(account, draftsPath, 0, 10).size)
            api.deleteDraft(session!!, draft2.uid!!)
            assertEquals(0, engine.messages(account, draftsPath, 0, 10).size)

            // Klasör: oluştur, taşı, yeniden adlandır, sil (iletiler çöpe).
            val projects = engine.createFolder(account, "Projeler")
            engine.move(account, "INBOX", inbox.first { it.subject == "Cephe revizyonu" }.uid, projects)
            assertEquals(1, engine.messages(account, projects, 0, 10).size)
            val renamed = engine.renameFolder(account, projects, "Projeler 2026")
            engine.deleteFolder(account, renamed)
            val trash = engine.folders(account).first { it.role == FolderRole.TRASH }.path
            assertTrue(engine.messages(account, trash, 0, 10).any { it.subject == "Cephe revizyonu" }, "silinen klasörün iletisi çöpte")

            // Arşiv klasörü yoksa oluşturulur; silme çöpe, çöpte kalıcı.
            val archive = engine.folderFor(account, FolderRole.ARCHIVE)
            assertTrue(archive != null)
            val bill = inbox.first { it.subject == "Ekim faturası" }
            engine.delete(account, "INBOX", bill.uid)
            val inTrash = engine.messages(account, trash, 0, 10).first { it.subject == "Ekim faturası" }
            engine.delete(account, trash, inTrash.uid)
            assertTrue(engine.messages(account, trash, 0, 10).none { it.subject == "Ekim faturası" })

            assertTrue(logins <= 3, "oturum yeniden kullanılır (giriş sayısı $logins)")
        } finally {
            server.stop()
        }
    }
}
