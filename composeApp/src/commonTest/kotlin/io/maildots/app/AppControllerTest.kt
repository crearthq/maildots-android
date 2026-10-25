package io.maildots.app

import io.maildots.app.web.WebError

import kotlinx.coroutines.test.advanceUntilIdle
import io.maildots.app.mail.PickedFile
import io.maildots.app.mail.AttachmentPolicy
import io.maildots.app.mail.AttachProblem
import io.maildots.app.data.AccountRepository
import io.maildots.app.data.SecureStore
import io.maildots.app.mail.Account
import io.maildots.app.mail.Address
import io.maildots.app.mail.AttachmentData
import io.maildots.app.mail.Folder
import io.maildots.app.mail.FolderRole
import io.maildots.app.mail.MailEngine
import io.maildots.app.mail.MailError
import io.maildots.app.mail.MailFilter
import io.maildots.app.mail.MailLabel
import io.maildots.app.mail.SearchQuery
import io.maildots.app.mail.MessageDetail
import io.maildots.app.mail.MessageSummary
import io.maildots.app.mail.OutgoingMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

internal class MemoryStore : SecureStore {
    val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) {
        map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
}

internal class FakeEngine : MailEngine {
    var validPassword = "dogru-sifre"
    var failWith: MailError? = null
    val sent = ArrayList<OutgoingMessage>()
    val inbox = ArrayList<MessageSummary>()
    val deleted = ArrayList<Long>()

    private fun check(account: Account) {
        failWith?.let { throw it }
        if (account.password != validPassword) throw MailError.Auth()
    }

    override suspend fun verify(account: Account) = check(account)

    var folderCalls = 0
    var inboxUnreadCalls = 0

    override suspend fun inboxUnread(account: Account): Int {
        inboxUnreadCalls++
        return 0
    }

    override suspend fun folders(account: Account): List<Folder> {
        check(account)
        folderCalls++
        return listOf(Folder("INBOX", "INBOX", FolderRole.INBOX, 0))
    }

    var ownerName: String? = null
    var nameLookups = 0

    override suspend fun displayName(account: Account): String? {
        nameLookups++
        return ownerName
    }

    var failPaging = false
    val filtersSeen = ArrayList<MailFilter>()

    override suspend fun messages(
        account: Account,
        folderPath: String,
        offset: Int,
        limit: Int,
        filter: MailFilter,
        label: MailLabel?,
    ): List<MessageSummary> {
        check(account)
        if (failPaging && offset > 0) throw MailError.Network()
        filtersSeen += filter
        val byLabel = if (label == null) inbox else inbox.filter { label in it.labels }
        val source = when (filter) {
            MailFilter.UNREAD -> byLabel.filter { !it.seen }
            MailFilter.FLAGGED -> byLabel.filter { it.flagged }
            MailFilter.ATTACHMENTS -> byLabel.filter { it.hasAttachment }
            MailFilter.ALL -> byLabel
        }
        return source.drop(offset).take(limit)
    }

    val searches = ArrayList<SearchQuery>()
    val labelCalls = ArrayList<Triple<Long, MailLabel, Boolean>>()
    val created = ArrayList<String>()

    override suspend fun search(account: Account, query: SearchQuery, limit: Int): List<MessageSummary> {
        failWith?.let { throw it }
        searches += query
        return inbox.filter { it.subject.contains(query.text.trim(), ignoreCase = true) }
            .filter { !query.unread || !it.seen }
            .filter { query.label == null || query.label in it.labels }
            .take(limit)
    }

    override suspend fun setLabel(account: Account, folderPath: String, uid: Long, label: MailLabel, on: Boolean) {
        failWith?.let { throw it }
        labelCalls += Triple(uid, label, on)
    }

    val cleared = ArrayList<MailLabel>()
    val renamed = ArrayList<Pair<String, String>>()
    val removedFolders = ArrayList<String>()

    override suspend fun clearLabel(account: Account, label: MailLabel) {
        cleared += label
    }

    override suspend fun renameFolder(account: Account, path: String, newName: String): String {
        failWith?.let { throw it }
        renamed += path to newName.trim()
        return newName.trim()
    }

    override suspend fun deleteFolder(account: Account, path: String) {
        failWith?.let { throw it }
        removedFolders += path
    }

    override suspend fun createFolder(account: Account, name: String): String {
        failWith?.let { throw it }
        created += name.trim()
        return name.trim()
    }

    /** Ayrıntısı özel olarak verilmiş iletiler (yanıt/iletme testleri). */
    val details = HashMap<Long, MessageDetail>()

    override suspend fun message(account: Account, folderPath: String, uid: Long) =
        details[uid] ?: MessageDetail(inbox.first { it.uid == uid }, emptyList(), "govde", null, emptyList())

    override suspend fun setSeen(account: Account, folderPath: String, uid: Long, seen: Boolean) {
        seenCalls += uid to seen
    }

    override suspend fun setFlagged(account: Account, folderPath: String, uid: Long, flagged: Boolean) {}

    override suspend fun delete(account: Account, folderPath: String, uid: Long) {
        failWith?.let { throw it }
        deleted += uid
    }

    override suspend fun send(account: Account, message: OutgoingMessage) {
        failWith?.let { throw it }
        sent += message
    }

    val moved = ArrayList<Pair<Long, String>>()
    val seenCalls = ArrayList<Pair<Long, Boolean>>()

    override suspend fun move(account: Account, folderPath: String, uid: Long, targetPath: String) {
        failWith?.let { throw it }
        moved += uid to targetPath
    }

    override suspend fun folderFor(account: Account, role: FolderRole): String? = when (role) {
        FolderRole.ARCHIVE -> "Archive"
        FolderRole.JUNK -> "Junk"
        else -> null
    }

    override suspend fun attachment(account: Account, folderPath: String, uid: Long, index: Int): AttachmentData {
        failWith?.let { throw it }
        return AttachmentData("dosya.pdf", "application/pdf", byteArrayOf(1, 2, 3))
    }

    override suspend fun disconnect(account: Account) {}
}

internal fun summary(uid: Long) = MessageSummary(
    uid = uid, subject = "konu $uid", from = Address("Gonderen", "g@x.test"), to = emptyList(), dateMillis = 0,
    seen = false, flagged = false, hasAttachment = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerTest {
    private val store = MemoryStore()
    private val engine = FakeEngine()

    private val web = FakeAccountApi()

    private fun controller(scope: TestScope) =
        AppController(engine, AccountRepository(store), TestScope(UnconfinedTestDispatcher(scope.testScheduler)), web)

    @Test
    fun startsOnSignInWhenNoAccount() = runTest {
        val c = controller(this)
        assertIs<Screen.AddAccount>(c.state.value.screen)
        assertNull(c.state.value.active)
    }

    @Test
    fun lastInboxIsShownImmediatelyOnNextLaunch() = runTest {
        engine.inbox += summary(7)
        engine.inbox += summary(8)
        val first = controller(this)
        first.addAccount("a@b.co", "dogru-sifre")
        assertEquals(2, first.state.value.mailbox.messages.size)

        // Yeniden acilis, sunucuya ulasilamiyor: son gorulen kutu yine de ekranda.
        engine.failWith = MailError.Network()
        val second = controller(this)
        assertEquals(listOf(7L, 8L), second.state.value.mailbox.messages.map { it.uid }.sorted())
        assertTrue(!second.state.value.mailbox.opening)
        assertEquals(ErrorKind.NETWORK, second.state.value.mailbox.error)

        // Sunucu donunce guncel hal ustune yazilir.
        engine.failWith = null
        engine.inbox.removeAll { it.uid == 7L }
        second.refresh()
        assertEquals(listOf(8L), second.state.value.mailbox.messages.map { it.uid })

        // Hesap cikinca onbellek silinir.
        second.removeAccount("a@b.co")
        assertTrue(AccountRepository(store).loadMailbox("a@b.co") == null)
    }

    @Test
    fun welcomeIsShownOnceOnFirstLaunch() = runTest {
        val first = controller(this)
        assertTrue(first.state.value.welcome)
        first.finishWelcome()
        assertTrue(!first.state.value.welcome)
        assertIs<Screen.AddAccount>(first.state.value.screen)
        // Yeniden acilista (ayni depo) bir daha gosterilmez.
        assertTrue(!controller(this).state.value.welcome)
    }

    @Test
    fun rejectsInvalidInputBeforeContactingServer() = runTest {
        val c = controller(this)
        c.addAccount("gecersiz", "x")
        assertEquals(ErrorKind.INVALID_EMAIL, c.state.value.formError)
        c.addAccount("a@b.co", "")
        assertEquals(ErrorKind.EMPTY_PASSWORD, c.state.value.formError)
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun wrongPasswordIsNotStored() = runTest {
        val c = controller(this)
        c.addAccount("a@b.co", "yanlis")
        assertEquals(ErrorKind.AUTH, c.state.value.formError)
        assertTrue(c.state.value.accounts.isEmpty())
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun signInStoresAccountAndOpensMailbox() = runTest {
        engine.inbox += listOf(summary(2), summary(1))
        val c = controller(this)
        c.addAccount("  A@B.co ", "dogru-sifre")
        val s = c.state.value
        assertEquals(Screen.Mailbox, s.screen)
        assertEquals("a@b.co", s.active?.email)
        assertEquals(listOf(2L, 1L), s.mailbox.messages.map { it.uid })
        // Yeniden acilista hesap geri gelir.
        assertEquals("a@b.co", controller(this).state.value.active?.email)
    }

    @Test
    fun multipleAccountsSwitchAndRemove() = runTest {
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.openAddAccount()
        c.addAccount("a@b.co", "dogru-sifre")
        assertEquals(ErrorKind.DUPLICATE, c.state.value.formError)
        c.addAccount("c@d.co", "dogru-sifre")
        assertEquals(listOf("a@b.co", "c@d.co"), c.state.value.accounts.map { it.email })
        assertEquals("c@d.co", c.state.value.active?.email)
        c.switchAccount("a@b.co")
        assertEquals("a@b.co", c.state.value.active?.email)
        c.removeAccount("a@b.co")
        assertEquals("c@d.co", c.state.value.active?.email)
        c.removeAccount("c@d.co")
        assertIs<Screen.AddAccount>(c.state.value.screen)
        assertNull(store.map["accounts.v1"])
    }

    @Test
    fun sendValidatesRecipients() = runTest {
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.openComposer()
        c.send("", "", "konu", "govde")
        assertEquals(ErrorKind.NO_RECIPIENT, c.state.value.formError)
        c.send("bozuk-adres", "", "konu", "govde")
        assertEquals(ErrorKind.INVALID_EMAIL, c.state.value.formError)
        assertTrue(web.sent.isEmpty())
        c.send("x@y.co; Z@y.co", "k@y.co", " konu ", "govde")
        assertEquals(listOf("x@y.co", "z@y.co"), web.sent.single().to)
        assertEquals("konu", web.sent.single().subject)
        // Yazma ekrani hemen kapanmaz: once onay (alici ozeti) gosterilir.
        assertEquals("x@y.co +2", c.state.value.sentTo)
        assertIs<Screen.Composer>(c.state.value.screen)
        advanceUntilIdle()
        assertNull(c.state.value.sentTo)
        assertEquals(Screen.Mailbox, c.state.value.screen)
    }

    private fun file(name: String, size: Int = 10, mime: String? = "application/pdf") =
        PickedFile(name, mime, ByteArray(size) { 1 })

    @Test
    fun attachmentsAreCheckedBeforeTheyAreSent() = runTest {
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        // Yazma ekrani disinda ek kabul edilmez.
        c.addAttachments(listOf(file("a.pdf")))
        assertTrue(c.state.value.drafts.isEmpty())

        c.openComposer()
        c.addAttachments(listOf(file("../../etc/Teklif\r\nBcc: x@y.co.pdf"), file("fatura.pdf.exe"), file("bos.txt", 0)))
        val drafts = c.state.value.drafts
        assertEquals(listOf("TeklifBcc_ x@y.co.pdf"), drafts.map { it.name })
        assertEquals(AttachProblem.BLOCKED_TYPE, c.state.value.attachProblem)
        assertEquals("fatura.pdf.exe", c.state.value.attachProblemName)

        // Toplam boyut siniri.
        c.addAttachments(listOf(PickedFile("buyuk.zip", "application/zip", null, tooLarge = true)))
        assertEquals(AttachProblem.TOO_LARGE, c.state.value.attachProblem)
        assertEquals(1, c.state.value.drafts.size)

        c.addAttachments(listOf(file("ikinci.png", 5, "image/png; charset=x\r\nX-Evil: 1")))
        c.removeAttachment(drafts.single().id)
        c.send("x@y.co", "", "konu", "govde")
        val sent = web.sent.single().attachments.single()
        assertEquals("ikinci.png", sent.name)
        assertEquals("image/png", sent.mimeType)
        assertEquals(5, sent.bytes.size)
        // Gonderimden sonra ekler temizlenir; yeni iletiye tasinmaz.
        advanceUntilIdle()
        c.openComposer()
        assertTrue(c.state.value.drafts.isEmpty())
    }

    @Test
    fun attachmentPolicyLimitsAndNames() {
        assertTrue(AttachmentPolicy.isBlocked("a.EXE"))
        assertTrue(AttachmentPolicy.isBlocked("makro.docm"))
        assertTrue(!AttachmentPolicy.isBlocked("rapor.pdf"))
        assertTrue(!AttachmentPolicy.isBlocked("exe"))
        assertEquals("dosya", AttachmentPolicy.safeName("///"))
        assertEquals("Şükrü Özgeçmiş.pdf", AttachmentPolicy.safeName("Şükrü Özgeçmiş.pdf"))
        // Yon degistiren gorunmez karakter atilir: gercek uzanti gorunur ve engellenir.
        assertTrue(AttachmentPolicy.isBlocked(AttachmentPolicy.safeName("fatura\u202Efdp.exe")))
        val long = AttachmentPolicy.safeName("a".repeat(300) + ".pdf")
        assertEquals(AttachmentPolicy.MAX_NAME_LENGTH, long.length)
        assertTrue(long.endsWith(".pdf"))
        assertEquals("application/octet-stream", AttachmentPolicy.safeMimeType("text/html\r\nX: 1"))
        assertEquals("application/octet-stream", AttachmentPolicy.safeMimeType(null))

        val many = (1..12).map { PickedFile("d$it.txt", "text/plain", ByteArray(1)) }
        val outcome = AttachmentPolicy.admit(emptyList(), many, 1)
        assertEquals(AttachmentPolicy.MAX_FILES, outcome.accepted.size)
        assertEquals(AttachProblem.TOO_MANY, outcome.problem)
    }

    @Test
    fun swipeDefaultsAreReadAndDelete() = runTest {
        engine.inbox += listOf(summary(3), summary(2))
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        val settings = c.state.value.settings
        assertEquals(SwipeAction.TOGGLE_READ, settings.swipeRight)
        assertEquals(SwipeAction.DELETE, settings.swipeLeft)

        // Saga: okundu yapar, ileti listede kalir.
        assertEquals(false, c.performSwipe(summary(3), settings.swipeRight))
        assertEquals(true, c.state.value.mailbox.messages.first { it.uid == 3L }.seen)
        assertEquals(listOf(3L to true), engine.seenCalls)

        // Sola: siler, ileti listeden cikar.
        assertEquals(true, c.performSwipe(summary(2), settings.swipeLeft))
        assertEquals(listOf(3L), c.state.value.mailbox.messages.map { it.uid })
        assertEquals(listOf(2L), engine.deleted)
    }

    @Test
    fun swipeActionsAreConfigurableAndPersist() = runTest {
        engine.inbox += summary(5)
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.setSwipeActions(right = SwipeAction.ARCHIVE, left = SwipeAction.SPAM)
        assertEquals(true, c.performSwipe(summary(5), c.state.value.settings.swipeRight))
        assertEquals(listOf(5L to "Archive"), engine.moved)
        assertEquals(Notice.ARCHIVED, c.state.value.notice)
        // Yeniden acilista ayarlar korunur.
        val again = controller(this).state.value.settings
        assertEquals(SwipeAction.ARCHIVE, again.swipeRight)
        assertEquals(SwipeAction.SPAM, again.swipeLeft)
    }

    @Test
    fun markUnreadFromReaderReturnsToList() = runTest {
        engine.inbox += summary(9)
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.openMessage(summary(9))
        assertIs<Screen.Reader>(c.state.value.screen)
        c.toggleSeen(summary(9))
        assertEquals(Screen.Mailbox, c.state.value.screen)
        assertEquals(false, c.state.value.mailbox.messages.single().seen)
    }

    @Test
    fun markUnreadWorksWhenServerAlreadyMarkedTheMessageRead() = runTest {
        // Webmail gövdeyi getirirken iletiyi okundu yapar; ayrıntı seen = true döner.
        engine.inbox += summary(9)
        engine.details[9] = MessageDetail(summary(9).copy(seen = true), emptyList(), "govde", null, emptyList())
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.openMessage(summary(9))
        assertEquals(true, c.state.value.mailbox.messages.single().seen)
        // Tek dokunuş yeter: okunmadı yapılır ve listeye dönülür.
        c.toggleSeen(c.state.value.reader.detail!!.summary)
        assertEquals(Screen.Mailbox, c.state.value.screen)
        assertEquals(false, c.state.value.mailbox.messages.single().seen)
        assertEquals(listOf(9L to false), engine.seenCalls)
    }

    @Test
    fun attachmentIsHandedToPlatformOnce() = runTest {
        engine.inbox += summary(4)
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.openMessage(summary(4))
        val opened = ArrayList<String>()
        c.openAttachment(0) { opened += it.name }
        assertEquals(listOf("dosya.pdf"), opened)
        assertNull(c.state.value.reader.openingAttachment)

        engine.failWith = MailError.TooLarge()
        c.openAttachment(0) { opened += it.name }
        assertEquals(1, opened.size)
        assertEquals(ErrorKind.TOO_LARGE, c.state.value.reader.attachmentError)
    }

    @Test
    fun forwardAndReplyReturnToTheMessage() = runTest {
        engine.inbox += summary(6)
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.openMessage(summary(6))
        c.openComposer(ComposeMode.FORWARD, c.state.value.reader.detail)
        assertIs<Screen.Composer>(c.state.value.screen)
        c.back()
        assertEquals(Screen.Reader("INBOX", 6), c.state.value.screen)
    }

    @Test
    fun themeSettingPersists() = runTest {
        val c = controller(this)
        assertEquals(ThemeMode.SYSTEM, c.state.value.settings.theme)
        c.setTheme(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, controller(this).state.value.settings.theme)
        // Tema degisince kaydirma ayarlari bozulmaz.
        assertEquals(SwipeAction.DELETE, controller(this).state.value.settings.swipeLeft)
    }

    @Test
    fun folderViewsFilterOnTheServer() = runTest {
        engine.inbox += listOf(summary(3).copy(flagged = true), summary(2), summary(1).copy(seen = true))
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.selectFolder("INBOX", MailFilter.FLAGGED)
        assertEquals(listOf(3L), c.state.value.mailbox.messages.map { it.uid })
        c.selectFolder("INBOX", MailFilter.UNREAD)
        assertEquals(listOf(3L, 2L), c.state.value.mailbox.messages.map { it.uid })
        c.selectFolder("INBOX")
        assertEquals(3, c.state.value.mailbox.messages.size)
        assertEquals(MailFilter.ALL, engine.filtersSeen.last())
    }

    @Test
    fun unreadBadgesAreTrackedPerAccount() = runTest {
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.openAddAccount()
        c.addAccount("c@d.co", "dogru-sifre")
        assertEquals(setOf("a@b.co", "c@d.co"), c.state.value.unreadByAccount.keys)
    }

    @Test
    fun failedDeleteRestoresTheMessage() = runTest {
        engine.inbox += summary(7)
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        engine.failWith = MailError.Network()
        c.deleteMessage(summary(7))
        assertEquals(listOf(7L), c.state.value.mailbox.messages.map { it.uid })
        assertEquals(ErrorKind.NETWORK, c.state.value.mailbox.error)
    }

    @Test
    fun topBarFilterKeepsTheFolder() = runTest {
        engine.inbox += listOf(summary(2).copy(hasAttachment = true), summary(1))
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.setFilter(MailFilter.ATTACHMENTS)
        assertEquals("INBOX", c.state.value.mailbox.folderPath)
        assertEquals(MailFilter.ATTACHMENTS, c.state.value.mailbox.filter)
        assertEquals(listOf(2L), c.state.value.mailbox.messages.map { it.uid })
        c.setFilter(MailFilter.ALL)
        assertEquals(2, c.state.value.mailbox.messages.size)
    }

    @Test
    fun searchStartsInTheCurrentFolderAndRemembersQueries() = runTest {
        engine.inbox += listOf(summary(3), summary(2).copy(seen = true), summary(1))
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.openSearch()
        assertEquals(Screen.Search, c.state.value.screen)
        assertEquals("INBOX", c.state.value.search.query.folderPath)

        // Olcut yokken sunucuya gidilmez.
        c.runSearch()
        assertTrue(engine.searches.isEmpty())
        assertEquals(false, c.state.value.search.searched)

        // Yazarken aranmaz; gonderince aranir ve son aramalara eklenir.
        c.setQuery(c.state.value.search.query.copy(text = "konu"), run = false)
        assertTrue(engine.searches.isEmpty())
        c.runSearch()
        assertEquals(listOf(3L, 2L, 1L), c.state.value.search.results.map { it.uid })
        assertEquals(listOf("konu"), c.state.value.recentSearches)

        // Suzgec degisince hemen yeniden aranir.
        c.setQuery(c.state.value.search.query.copy(unread = true))
        assertEquals(listOf(3L, 1L), c.state.value.search.results.map { it.uid })
        assertEquals(true, engine.searches.last().unread)

        // Ayni sozcuk iki kez yazilmaz, en yeni ustte; yeniden acilista korunur.
        c.setQuery(SearchQuery(text = "konu 1"))
        c.setQuery(SearchQuery(text = "KONU"))
        assertEquals(listOf("KONU", "konu 1"), c.state.value.recentSearches)
        assertEquals(listOf("KONU", "konu 1"), controller(this).state.value.recentSearches)
        c.clearRecentSearches()
        assertTrue(controller(this).state.value.recentSearches.isEmpty())
    }

    @Test
    fun messageOpenedFromSearchReturnsToSearch() = runTest {
        engine.inbox += summary(8)
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.openSearch()
        c.setQuery(SearchQuery(text = "konu"))
        c.openMessage(c.state.value.search.results.single())
        assertEquals(Screen.Reader("INBOX", 8, fromSearch = true), c.state.value.screen)
        c.back()
        assertEquals(Screen.Search, c.state.value.screen)
        assertEquals(1, c.state.value.search.results.size)
        // Sonuclardan silinen ileti hem aramadan hem listeden cikar.
        c.deleteMessage(summary(8))
        assertTrue(c.state.value.search.results.isEmpty())
        assertTrue(c.state.value.mailbox.messages.isEmpty())
    }

    @Test
    fun searchFailureIsShownNotSwallowed() = runTest {
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.openSearch()
        engine.failWith = MailError.Network()
        c.setQuery(SearchQuery(text = "x"))
        assertEquals(ErrorKind.NETWORK, c.state.value.search.error)
        assertEquals(false, c.state.value.search.loading)
    }

    @Test
    fun labelsAreAppliedAndRolledBackOnFailure() = runTest {
        engine.inbox += summary(4)
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.setLabel(summary(4), MailLabel.WORK, true)
        c.setLabel(summary(4), MailLabel.IMPORTANT, true)
        assertEquals(listOf(MailLabel.IMPORTANT, MailLabel.WORK), c.state.value.mailbox.messages.single().labels)
        c.setLabel(summary(4), MailLabel.WORK, false)
        assertEquals(listOf(MailLabel.IMPORTANT), c.state.value.mailbox.messages.single().labels)
        assertEquals(3, engine.labelCalls.size)

        engine.failWith = MailError.Network()
        c.setLabel(summary(4), MailLabel.TODO, true)
        assertEquals(listOf(MailLabel.IMPORTANT), c.state.value.mailbox.messages.single().labels)
    }

    @Test
    fun labelViewListsOnlyLabelledMessages() = runTest {
        engine.inbox += listOf(summary(2).copy(labels = listOf(MailLabel.TODO)), summary(1))
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.selectFolder("INBOX", MailFilter.ALL, MailLabel.TODO)
        assertEquals(MailLabel.TODO, c.state.value.mailbox.label)
        assertEquals(listOf(2L), c.state.value.mailbox.messages.map { it.uid })
    }

    @Test
    fun createFolderAndMoveIntoIt() = runTest {
        engine.inbox += summary(5)
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.createFolder("  ")
        assertTrue(engine.created.isEmpty())
        c.createFolder("Teklifler")
        assertEquals(listOf("Teklifler"), engine.created)
        assertEquals(Notice.FOLDER_CREATED, c.state.value.notice)

        c.createFolder("Sozlesmeler", moveHere = summary(5))
        assertEquals(listOf(5L to "Sozlesmeler"), engine.moved)
        assertEquals(Notice.MOVED, c.state.value.notice)
        assertTrue(c.state.value.mailbox.messages.isEmpty())
    }

    @Test
    fun typingSearchesAfterAPauseWithoutPollutingRecents() = runTest {
        engine.inbox += listOf(summary(2), summary(1))
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.openSearch()
        c.typeQuery("k")
        c.typeQuery("ko")
        c.typeQuery("kon")
        assertTrue(engine.searches.isEmpty())
        testScheduler.advanceUntilIdle()
        // Ara harfler icin ayri ayri aranmaz: yalnizca son hali.
        assertEquals(listOf("kon"), engine.searches.map { it.text })
        assertEquals(2, c.state.value.search.results.size)
        assertTrue(c.state.value.recentSearches.isEmpty())

        // Tek harf (baska olcut yokken) aranmaz.
        c.typeQuery("k")
        testScheduler.advanceUntilIdle()
        assertEquals(1, engine.searches.size)

        // Bulunan sonuc acilinca sozcuk son aramalara girer.
        c.typeQuery("konu")
        testScheduler.advanceUntilIdle()
        c.openMessage(c.state.value.search.results.first())
        assertEquals(listOf("konu"), c.state.value.recentSearches)
    }

    @Test
    fun customLabelsAreCreatedColouredDeletedAndPersist() = runTest {
        engine.inbox += summary(3)
        // Servisle eşitleme olmadan (cihazdaki etiketler): servis ad/etiket okumayı reddeder.
        web.fail = WebError.Server(500)
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        assertEquals(MailLabel.BUILTIN, c.state.value.labels.map { it.label })
        c.createLabel("   ", 0xFF000000)
        assertEquals(5, c.state.value.labels.size)

        c.createLabel("  Müşteri   Teklifi ", 0xFF0E9F8E, applyTo = summary(3))
        val label = MailLabel.custom("Müşteri Teklifi")!!
        assertEquals("Müşteri Teklifi", label.customName)
        assertEquals(false, label.isBuiltin)
        // Ayni ad ayni etikettir; sunucudan gelen anahtar sozcukten de ayni etiket okunur.
        assertEquals(label, MailLabel.custom("Müşteri Teklifi"))
        assertEquals(label, MailLabel.fromKeyword(label.keyword.uppercase()))
        assertNull(MailLabel.fromKeyword("NonJunk"))
        assertEquals(listOf(label), c.state.value.mailbox.messages.single().labels)
        assertEquals(6, c.state.value.labels.size)

        c.setLabelColor(MailLabel.WORK, 0xFF6B7280)
        val again = controller(this).state.value.labels
        assertEquals(0xFF6B7280, again.first { it.label == MailLabel.WORK }.color)
        assertEquals(0xFF0E9F8E, again.first { it.label == label }.color)

        // Hazir etiket silinmez; kullanici etiketi silinince iletilerden de kalkar.
        c.deleteLabel(MailLabel.WORK)
        assertEquals(6, c.state.value.labels.size)
        c.deleteLabel(label)
        assertEquals(5, c.state.value.labels.size)
        assertTrue(c.state.value.mailbox.messages.single().labels.isEmpty())
        assertEquals(listOf(label), engine.cleared)
        assertEquals(5, controller(this).state.value.labels.size)
    }

    @Test
    fun foldersCanBeRenamedAndDeleted() = runTest {
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.renameFolder("Teklifler", "  ")
        assertTrue(engine.renamed.isEmpty())
        c.renameFolder("Teklifler", "Teklifler 2026")
        assertEquals(listOf("Teklifler" to "Teklifler 2026"), engine.renamed)
        assertEquals(Notice.FOLDER_RENAMED, c.state.value.notice)
        c.deleteFolder("Teklifler 2026")
        assertEquals(listOf("Teklifler 2026"), engine.removedFolders)
        assertEquals(Notice.FOLDER_DELETED, c.state.value.notice)

        engine.failWith = MailError.Network()
        c.deleteFolder("Baska")
        assertEquals(ErrorKind.NETWORK, c.state.value.mailbox.error)
    }

    @Test
    fun displayNameIsLearnedFromTheServerOnceAndPersisted() = runTest {
        engine.ownerName = " Deniz Yel "
        val c = controller(this)
        c.addAccount("store@b.co", "dogru-sifre")
        assertEquals("Deniz Yel", c.state.value.active?.displayName)
        assertEquals("Deniz Yel", c.state.value.accounts.single().displayName)
        c.refresh()
        c.refresh()
        assertEquals(1, engine.nameLookups)
        // Yeniden acilista ad kayitlidir; sunucuya yeniden sorulmaz.
        val again = controller(this)
        assertEquals("Deniz Yel", again.state.value.active?.displayName)
        assertEquals(1, engine.nameLookups)
    }

    @Test
    fun unknownDisplayNameLeavesTheAccountUntouched() = runTest {
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        assertEquals("", c.state.value.active?.displayName)
        // Elle verilen ad kaydedilir ve sunucudan gelen adla ezilmez.
        c.setDisplayName("a@b.co", "  Deniz\nYel ")
        assertEquals("Deniz Yel", c.state.value.active?.displayName)
        engine.ownerName = "Baska Ad"
        c.refresh()
        assertEquals("Deniz Yel", controller(this).state.value.active?.displayName)
        // Bos birakilinca yeniden adres gosterilir; sunucuya yeniden sorulmaz.
        c.setDisplayName("a@b.co", "")
        c.refresh()
        assertEquals("", c.state.value.active?.displayName)
    }

    @Test
    fun failedPagingIsShownAndCanBeRetried() = runTest {
        engine.inbox += (40L downTo 1L).map { summary(it) }
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        assertEquals(30, c.state.value.mailbox.messages.size)
        assertEquals(false, c.state.value.mailbox.endReached)

        engine.failPaging = true
        c.loadMore()
        assertEquals(true, c.state.value.mailbox.moreFailed)
        assertEquals(false, c.state.value.mailbox.loadingMore)
        assertEquals(30, c.state.value.mailbox.messages.size)

        engine.failPaging = false
        c.loadMore()
        assertEquals(false, c.state.value.mailbox.moreFailed)
        assertEquals(40, c.state.value.mailbox.messages.size)
        assertEquals(true, c.state.value.mailbox.endReached)
    }

    @Test
    fun accountSettingsNavigationSignatureAndLogout() = runTest {
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.openAddAccount()
        c.addAccount("c@d.co", "dogru-sifre")
        c.openSettings()
        c.openAccountSettings("yok@b.co")
        assertEquals(Screen.Settings, c.state.value.screen)
        c.openAccountSettings("a@b.co")
        assertEquals(Screen.AccountSettings("a@b.co"), c.state.value.screen)

        // Imza hesaba ozgudur ve kalicidir.
        c.setSignature("a@b.co", "  Deniz Yel\r\nSatis  ")
        assertEquals("Deniz Yel\nSatis", c.state.value.accounts.first { it.email == "a@b.co" }.signature)
        assertEquals("", c.state.value.accounts.first { it.email == "c@d.co" }.signature)
        assertEquals("Deniz Yel\nSatis", controller(this).state.value.accounts.first { it.email == "a@b.co" }.signature)

        // Baska hesabin klasorleri acilirken o hesaba gecilir; geri = hesap ayarlari = ayarlar.
        assertEquals("c@d.co", c.state.value.active?.email)
        c.openAccountFolders("a@b.co")
        assertEquals("a@b.co", c.state.value.active?.email)
        assertEquals(Screen.AccountFolders("a@b.co"), c.state.value.screen)
        c.back()
        assertEquals(Screen.AccountSettings("a@b.co"), c.state.value.screen)
        c.openAccountLabels("a@b.co")
        c.back()
        c.back()
        assertEquals(Screen.Settings, c.state.value.screen)

        // Hesap ayarlarindan cikis: baska hesap varsa ayarlar listesine donulur.
        c.openAccountSettings("a@b.co")
        c.removeAccount("a@b.co")
        assertEquals(Screen.Settings, c.state.value.screen)
        assertEquals(listOf("c@d.co"), c.state.value.accounts.map { it.email })
        c.openAccountSettings("c@d.co")
        c.removeAccount("c@d.co")
        assertIs<Screen.AddAccount>(c.state.value.screen)
    }

    @Test
    fun switchingFilterLoadsMessagesWithoutWaitingForFolderCounts() = runTest {
        engine.inbox += listOf(summary(2).copy(flagged = true), summary(1))
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        val afterOpen = engine.folderCalls
        val messagesBefore = engine.filtersSeen.size

        c.setFilter(MailFilter.FLAGGED)
        // Iletiler tek istekle gelir; klasorler liste icin yeniden cekilmez.
        assertEquals(messagesBefore + 1, engine.filtersSeen.size)
        assertEquals(listOf(2L), c.state.value.mailbox.messages.map { it.uid })
        // Sayilar yalnizca arka planda, bir kez tazelenir.
        assertEquals(afterOpen + 1, engine.folderCalls)

        // Asagi cekip yenilemek tam yenilemedir.
        c.refresh()
        assertEquals(afterOpen + 2, engine.folderCalls)
    }

    @Test
    fun otherAccountsBadgeUsesASingleLightQuery() = runTest {
        val c = controller(this)
        c.addAccount("a@b.co", "dogru-sifre")
        c.openAddAccount()
        c.addAccount("c@d.co", "dogru-sifre")
        val folderCalls = engine.folderCalls
        val light = engine.inboxUnreadCalls
        c.refresh()
        // Etkin hesap icin bir klasor listesi; diger hesap icin yalnizca hafif sorgu.
        assertEquals(folderCalls + 1, engine.folderCalls)
        assertEquals(light + 1, engine.inboxUnreadCalls)
    }
}
