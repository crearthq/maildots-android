package io.maildots.app

import io.maildots.app.data.AccountRepository
import io.maildots.app.mail.Address
import io.maildots.app.mail.ComposeRules
import io.maildots.app.mail.MessageDetail
import io.maildots.app.mail.MessageSummary
import io.maildots.app.web.WebError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

/** Webmail ile eşitlenen özellikler: yanıt kuralları, gönderim yolu, taslak, toplu işlem, şifre. */
@OptIn(ExperimentalCoroutinesApi::class)
class ParityTest {
    private val store = MemoryStore()
    private val engine = FakeEngine()
    private val web = FakeAccountApi()
    private val me = "store@b.co"

    private fun detail(
        from: String = "ali@x.test",
        to: List<String> = listOf(me),
        cc: List<String> = emptyList(),
        replyTo: List<String> = emptyList(),
        messageId: String? = "<m1@x.test>",
        references: List<String> = emptyList(),
        text: String? = "Merhaba",
        html: String? = null,
    ) = MessageDetail(
        summary = MessageSummary(
            uid = 5, subject = "Teklif", from = Address("Ali", from), to = to.map { Address("", it) },
            dateMillis = 0, seen = true, flagged = false, hasAttachment = false,
        ),
        cc = cc.map { Address("", it) },
        text = text,
        html = html,
        attachments = emptyList(),
        messageId = messageId,
        references = references,
        replyTo = replyTo.map { Address("", it) },
    )

    // ---- yanıt kuralları (webmail'in replyState'iyle aynı)

    @Test
    fun replyGoesToReplyToAndReplyAllSkipsMeAndDuplicates() {
        val d = detail(replyTo = listOf("destek@x.test"), to = listOf(me, "veli@x.test", "DESTEK@x.test"), cc = listOf("ayse@x.test", me))
        assertEquals(listOf("destek@x.test") to emptyList<String>(), ComposeRules.replyRecipients(d, me, all = false))
        val (to, cc) = ComposeRules.replyRecipients(d, "Store@B.co", all = true)
        assertEquals(listOf("destek@x.test", "veli@x.test"), to)
        assertEquals(listOf("ayse@x.test"), cc)
    }

    @Test
    fun replyToOwnSentCopyGoesToTheOriginalRecipient() {
        val d = detail(from = me, to = listOf("musteri@x.test"))
        assertEquals(listOf("musteri@x.test"), ComposeRules.replyRecipients(d, me, all = false).first)
    }

    @Test
    fun referencesChainAndQuoteFromHtml() {
        val d = detail(references = listOf("<a@x>", "<b@x>"), text = null, html = "<p>Fiyat</p><p>listesi</p>")
        assertEquals(listOf("<a@x>", "<b@x>", "<m1@x.test>"), ComposeRules.references(d))
        assertEquals("Fiyat\nlistesi", ComposeRules.plainText(d))
        assertEquals("Ynt: Teklif", ComposeRules.prefixed("Teklif", "Ynt: ", "Re:"))
        assertEquals("Re: Teklif", ComposeRules.prefixed("Re: Teklif", "Ynt: ", "Re:"))
    }

    // ---- gönderim yolu

    private fun linked(scope: TestScope): AppController =
        AppController(engine, AccountRepository(store), TestScope(UnconfinedTestDispatcher(scope.testScheduler)), web)
            .also { it.addAccount(me, "dogru-sifre") }

    @Test
    fun linkedAccountSendsThroughTheWebmailWithThreadHeaders() = runTest {
        val c = linked(this)
        assertEquals(WebStatus.SYNCED, c.state.value.web[me])
        val d = detail(references = listOf("<a@x>"))
        c.openComposer(ComposeMode.REPLY_ALL, d)
        c.send("ali@x.test", "", "Ynt: Teklif", "Tamam <b>", bccRaw = "gizli@x.test")
        advanceTimeBy(AppController.SENT_CONFIRM_MS + 10)
        val sent = web.sent.single()
        assertEquals("<m1@x.test>", sent.inReplyTo)
        assertEquals(listOf("<a@x>", "<m1@x.test>"), sent.references)
        assertEquals(listOf("gizli@x.test"), sent.bcc)
        assertEquals("Tamam &lt;b&gt;", sent.html)
        assertTrue(engine.sent.isEmpty(), "SMTP'den ikinci kez gönderilmez")
    }

    @Test
    fun sendErrorFromTheServiceIsShownAndNotRetriedOverSmtp() = runTest {
        val c = linked(this)
        web.sendError = WebError.TooLarge()
        c.openComposer()
        c.send("a@x.test", "", "Konu", "Govde")
        assertEquals(ErrorKind.TOO_LARGE, c.state.value.formError)
        assertTrue(engine.sent.isEmpty())
    }

    // ---- taslak

    @Test
    fun draftIsSavedAfterAPauseReplacedOnEditAndRemovedOnSend() = runTest {
        val c = linked(this)
        c.openComposer()
        c.draftChanged(ComposeFields(to = "a@x.test", subject = "Plan", body = "ilk"))
        advanceTimeBy(AppController.DRAFT_DELAY_MS + 10)
        assertEquals(1, web.drafts.size)
        assertTrue(c.state.value.compose.draftSaved)
        c.draftChanged(ComposeFields(to = "a@x.test", subject = "Plan", body = "ikinci"))
        advanceTimeBy(AppController.DRAFT_DELAY_MS + 10)
        assertEquals(listOf("ikinci"), web.drafts.values.map { it.html }, "yeni kayıt eskisinin yerini alır")
        c.send("a@x.test", "", "Plan", "ikinci")
        advanceTimeBy(AppController.SENT_CONFIRM_MS + 10)
        assertTrue(web.drafts.isEmpty(), "gönderilen taslak Taslaklar'da kalmaz")
        assertEquals(web.sent.single().draftUid != null, true)
    }

    @Test
    fun leavingTheComposerSavesTheDraftAndBlankDraftIsNotSaved() = runTest {
        val c = linked(this)
        c.openComposer()
        c.draftChanged(ComposeFields(to = "a@x.test", body = "yarim kaldi"))
        c.back()
        assertEquals(1, web.drafts.size)
        assertEquals(Notice.DRAFT_SAVED, c.state.value.notice)

        c.openComposer()
        c.draftChanged(ComposeFields())
        c.back()
        assertEquals(1, web.drafts.size)
    }

    @Test
    fun discardingDeletesTheSavedDraft() = runTest {
        val c = linked(this)
        c.openComposer()
        c.draftChanged(ComposeFields(subject = "sil beni"))
        advanceTimeBy(AppController.DRAFT_DELAY_MS + 10)
        assertEquals(1, web.drafts.size)
        c.discardDraft()
        assertTrue(web.drafts.isEmpty())
        assertIs<Screen.Mailbox>(c.state.value.screen)
    }

    // ---- toplu seçim

    @Test
    fun bulkActionsApplyToEverySelectedMessage() = runTest {
        engine.inbox += summary(1)
        engine.inbox += summary(2)
        engine.inbox += summary(3)
        val c = AppController(engine, AccountRepository(store), TestScope(UnconfinedTestDispatcher(testScheduler)), web)
        c.addAccount(me, "dogru-sifre")
        val (a, b) = c.state.value.mailbox.messages.take(2)
        c.toggleSelection(a)
        c.toggleSelection(b)
        assertEquals(2, c.state.value.selection.size)
        c.bulk(BulkAction.READ)
        assertTrue(c.state.value.selection.isEmpty())
        assertEquals(setOf(a.uid to true, b.uid to true), engine.seenCalls.toSet())

        c.toggleSelection(a)
        c.toggleSelection(b)
        c.bulk(BulkAction.DELETE)
        assertEquals(setOf(a.uid, b.uid), engine.deleted.toSet())
        assertEquals(1, c.state.value.mailbox.messages.size)
        assertEquals(Notice.DELETED, c.state.value.notice)
    }

    @Test
    fun failedBulkMoveBringsTheMessagesBack() = runTest {
        engine.inbox += summary(1)
        engine.inbox += summary(2)
        val c = AppController(engine, AccountRepository(store), TestScope(UnconfinedTestDispatcher(testScheduler)), web)
        c.addAccount(me, "dogru-sifre")
        c.selectAllVisible()
        engine.failWith = io.maildots.app.mail.MailError.Network()
        c.bulkMove("Projeler")
        assertEquals(2, c.state.value.mailbox.messages.size)
        assertEquals(ErrorKind.NETWORK, c.state.value.mailbox.error)
    }

    // ---- şifre

    @Test
    fun changingThePasswordUpdatesTheStoredOne() = runTest {
        val c = linked(this)
        c.changePassword(me, "yanlis", "yeni-sifre-1")
        assertEquals(PasswordProblem.WRONG_CURRENT, c.state.value.passwordProblem)
        c.changePassword(me, "dogru-sifre", "kisa")
        assertEquals(PasswordProblem.POLICY, c.state.value.passwordProblem)
        c.changePassword(me, "dogru-sifre", "yeni-sifre-1")
        assertNull(c.state.value.passwordProblem)
        assertEquals("yeni-sifre-1", c.state.value.accounts.single().password)
        assertEquals(Notice.PASSWORD_CHANGED, c.state.value.notice)
    }

    @Test
    fun updatingTheStoredPasswordIsVerifiedFirst() = runTest {
        val c = AppController(engine, AccountRepository(store), TestScope(UnconfinedTestDispatcher(testScheduler)), web)
        c.addAccount(me, "dogru-sifre")
        // Şifre başka yerde değişti: yeni şifre önce sunucuda doğrulanır.
        web.password = "baska-yerde-degisti"
        engine.validPassword = "baska-yerde-degisti"
        c.updateStoredPassword(me, "tahmin")
        assertEquals(PasswordProblem.AUTH, c.state.value.passwordProblem)
        assertEquals("dogru-sifre", c.state.value.accounts.single().password)
        c.updateStoredPassword(me, "baska-yerde-degisti")
        assertNull(c.state.value.passwordProblem)
        assertEquals("baska-yerde-degisti", c.state.value.accounts.single().password)
    }
}
