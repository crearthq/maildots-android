package io.maildots.app

import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import io.maildots.app.data.AccountRepository
import io.maildots.app.mail.MailLabel
import io.maildots.app.web.AccountApi
import io.maildots.app.web.E2EApi
import io.maildots.app.web.EncryptedDraft
import io.maildots.app.web.EncryptedSend
import io.maildots.app.web.Prelogin
import io.maildots.app.web.WebVault
import kotlinx.serialization.json.JsonObject
import io.maildots.app.web.LabelPalette
import io.maildots.app.web.SignatureText
import io.maildots.app.web.WebError
import io.maildots.app.web.WebLabel
import io.maildots.app.web.WebLogin
import io.maildots.app.web.WebPrefs
import io.maildots.app.web.WebProfile
import io.maildots.app.web.WebSignature
import io.maildots.app.web.WebCompose
import io.maildots.app.web.WebDraftSaved
import io.maildots.app.web.WebSuggestion
import io.maildots.app.web.WebThreadItem
import io.maildots.app.web.WebTranslation
import io.maildots.app.web.WebUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** Bellekte çalışan sahte hesap servisi (webmail). */
internal class FakeAccountApi : AccountApi, E2EApi {
    /** Kutu sunucuda mı (giriş öncesi keşif). */
    var hosted = true
    var password = "dogru-sifre"
    var twoFactorCode: String? = null
    var name = ""
    val labels = ArrayList<WebLabel>()
    val signatures = ArrayList<WebSignature>()
    var prefs = WebPrefs()
    var prefsMissing = false
    var fail: WebError? = null

    var logins = 0
    val sessions = HashSet<String>()
    val loggedOut = ArrayList<String>()
    private var counter = 0

    private fun newSession() = "oturum-${++counter}".also { sessions += it }

    private fun check(session: String) {
        if (session !in sessions) throw WebError.Unauthorized()
        fail?.let { throw it }
    }

    /** Girişte atılacak geçici hata (ör. deneme sınırı). */
    var loginFail: WebError? = null

    override suspend fun login(email: String, password: String): WebLogin {
        logins++
        loginFail?.let { throw it }
        if (password != this.password) return WebLogin.Invalid
        return if (twoFactorCode != null) WebLogin.NeedsCode("bekleyen-$logins") else WebLogin.Ok(newSession())
    }

    override suspend fun verifyCode(pending: String, code: String): String {
        if (!pending.startsWith("bekleyen-")) throw WebError.BadCode(expired = true)
        if (code != twoFactorCode) throw WebError.BadCode(expired = false)
        return newSession()
    }

    override suspend fun logout(session: String) {
        sessions -= session
        loggedOut += session
    }

    override suspend fun profile(session: String): WebProfile {
        check(session)
        return WebProfile(name)
    }

    override suspend fun setDisplayName(session: String, name: String): String {
        check(session)
        this.name = name
        return name
    }

    override suspend fun labels(session: String): List<WebLabel> {
        check(session)
        return labels.toList()
    }

    override suspend fun createLabel(session: String, name: String, color: String): WebLabel {
        check(session)
        if (labels.any { it.name == name }) throw WebError.NameTaken()
        return WebLabel("id-${labels.size + 1}", name, color, "MDL${labels.size + 1}aa").also { labels += it }
    }

    override suspend fun updateLabel(session: String, id: String, name: String?, color: String?): WebLabel {
        check(session)
        val index = labels.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: throw WebError.Invalid()
        labels[index] = labels[index].copy(name = name ?: labels[index].name, color = color ?: labels[index].color)
        return labels[index]
    }

    override suspend fun deleteLabel(session: String, id: String) {
        check(session)
        labels.removeAll { it.id == id }
    }

    override suspend fun signatures(session: String): List<WebSignature> {
        check(session)
        return signatures.toList()
    }

    override suspend fun saveSignature(session: String, existing: WebSignature?, html: String): WebSignature {
        check(session)
        val saved = existing?.copy(html = html) ?: WebSignature("imza-${signatures.size + 1}", "MailDots", html, isDefault = true)
        signatures.removeAll { it.id == saved.id }
        signatures += saved
        return saved
    }

    override suspend fun deleteSignature(session: String, id: String) {
        check(session)
        signatures.removeAll { it.id == id }
    }

    val pushRegistered = ArrayList<Pair<String, String>>()
    val pushUnregistered = ArrayList<Pair<String, String>>()
    var pushFails = false

    override suspend fun registerPush(session: String, token: String) {
        check(session)
        if (pushFails) throw WebError.Network()
        pushRegistered += session to token
    }

    override suspend fun unregisterPush(session: String, token: String) {
        check(session)
        pushUnregistered += session to token
    }

    override suspend fun prefs(session: String): WebPrefs {
        check(session)
        if (prefsMissing) throw WebError.Server(404)
        return prefs
    }

    // ---- yazma / okuma (webmail ile aynı yol)

    val sent = ArrayList<WebCompose>()
    val drafts = HashMap<Long, WebCompose>()
    val deletedDrafts = ArrayList<Long>()
    var sendError: WebError? = null
    var fromList = listOf<String>()
    val suggestions = ArrayList<WebSuggestion>()
    var passwordChanges = 0
    private var draftSerial = 100L

    override suspend fun send(session: String, message: WebCompose) {
        check(session)
        sendError?.let { throw it }
        sent += message
        message.draftUid?.let { drafts.remove(it) }
    }

    override suspend fun saveDraft(session: String, message: WebCompose): WebDraftSaved {
        check(session)
        message.draftUid?.let { drafts.remove(it) }
        val uid = ++draftSerial
        drafts[uid] = message
        return WebDraftSaved(uid, "Drafts")
    }

    override suspend fun deleteDraft(session: String, uid: Long) {
        check(session)
        drafts.remove(uid)
        deletedDrafts += uid
    }

    override suspend fun fromAddresses(session: String): List<String> {
        check(session)
        return fromList
    }

    override suspend fun suggest(session: String, query: String): List<WebSuggestion> {
        check(session)
        return suggestions.filter { it.email.contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun thread(session: String, folder: String, uid: Long): List<WebThreadItem> {
        check(session)
        return emptyList()
    }

    override suspend fun translate(session: String, html: String, target: String): WebTranslation {
        check(session)
        return WebTranslation("[$target] $html", "en")
    }

    override suspend fun usage(session: String): WebUsage {
        check(session)
        return WebUsage(usedBytes = 512L * 1024 * 1024, totalBytes = 5L * 1024 * 1024 * 1024, percent = 10, unlimited = false)
    }

    override suspend fun changePassword(session: String, current: String, new: String) {
        check(session)
        if (current != password) throw WebError.WrongPassword()
        if (new.length < 8) throw WebError.Policy("En az 8 karakter")
        password = new
        passwordChanges++
    }

    // Kasası olmayan sade kutu: uçtan uca şifreleme kapalı, giriş koruması yok.
    override suspend fun prelogin(email: String): Prelogin = Prelogin(hosted, null)
    override suspend fun vault(session: String): WebVault? = null
    override suspend fun rawMessage(session: String, folder: String, uid: Long): ByteArray = throw WebError.Server(404)
    override suspend fun keyDirectory(session: String, addresses: List<String>): Map<String, String> = emptyMap()
    override suspend fun sendEncrypted(session: String, message: EncryptedSend): List<String> = throw WebError.Server(500)
    override suspend fun saveEncryptedDraft(session: String, message: EncryptedDraft): WebDraftSaved = throw WebError.Server(500)
    override suspend fun changePasswordWithVault(
        session: String,
        currentSecret: String,
        newSecret: String,
        vault: JsonObject,
        target: String,
    ) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSyncTest {
    private val store = MemoryStore()
    private val engine = FakeEngine()
    private val web = FakeAccountApi()
    private val email = "store@b.co"

    private val clock = TestTimeSource()

    private fun controller(scope: TestScope) = AppController(
        engine,
        AccountRepository(store),
        TestScope(UnconfinedTestDispatcher(scope.testScheduler)),
        web,
        clock = clock,
    )

    private fun AppController.account() = state.value.accounts.first { it.email == email }

    @Test
    fun signInPullsNameLabelsAndSignatureFromTheWeb() = runTest {
        web.name = "Deniz Yel"
        web.labels += WebLabel("l1", "Teklif", "green", "MDLabc123")
        web.signatures += WebSignature("s0", "Eski", "<p>eski</p>", isDefault = false)
        web.signatures += WebSignature("s1", "Varsayılan", "<p>Deniz Yel</p><p>Satış &amp; Destek</p>", isDefault = true)
        web.prefs = WebPrefs(signatureOnNew = true, signatureOnReply = false)
        // Kutunun eski mailinden tahmin edilen ad, servisteki adın önüne geçmemeli.
        engine.ownerName = "DENIZ YEL | Online Store"

        val c = controller(this)
        c.addAccount(email, "dogru-sifre")
        val account = c.account()
        assertEquals(WebStatus.SYNCED, c.state.value.web[email])
        assertEquals("Deniz Yel", account.displayName)
        assertTrue(account.webLinked)
        assertEquals("Deniz Yel\nSatış & Destek", account.signature)
        assertEquals("s1", account.signatureId)
        assertFalse(account.signatureRich)
        assertFalse(account.signatureOnReply)

        // Etiketler servistekilerdir: ad, renk ve iletideki anahtar sözcük oradan gelir.
        val label = c.state.value.labels.single()
        assertEquals("Teklif", label.name)
        assertEquals(MailLabel("MDLabc123"), label.label)
        assertEquals(LabelPalette.argb("green"), label.color)

        // Yeniden açılışta kayıtlı oturum kullanılır: yeniden giriş yapılmaz, etiketler hazırdır.
        val again = controller(this)
        assertEquals(1, web.logins)
        assertEquals("Teklif", again.state.value.labels.single().name)
        assertEquals("Deniz Yel", again.state.value.active?.displayName)
    }

    @Test
    fun emptyServerNameKeepsTheLocalOneAndMissingPrefsDefaultToOn() = runTest {
        web.prefsMissing = true
        engine.ownerName = "Yerel Ad"
        val c = controller(this)
        c.addAccount(email, "dogru-sifre")
        assertEquals(WebStatus.SYNCED, c.state.value.web[email])
        assertEquals("Yerel Ad", c.account().displayName)
        assertTrue(c.account().signatureOnNew && c.account().signatureOnReply)
        // Eşleşmiş hesapta etiket listesi servistekidir (boşsa boş).
        assertTrue(c.state.value.labels.isEmpty())
    }

    @Test
    fun expiredSessionSignsInAgainOnce() = runTest {
        val c = controller(this)
        c.addAccount(email, "dogru-sifre")
        assertEquals(1, web.logins)
        web.sessions.clear() // sunucuda oturumun süresi doldu
        web.name = "Yeni Ad"
        c.syncWeb(email, force = true)
        assertEquals(2, web.logins)
        assertEquals("Yeni Ad", c.account().displayName)
        assertEquals(WebStatus.SYNCED, c.state.value.web[email])
    }

    @Test
    fun rejectedSignInIsNeverRetriedAutomatically() = runTest {
        // Reddedilen giriş sunucuda başarısız bir posta girişi sayılır (IP yasağı):
        // kendiliğinden tekrarlanmamalı, hesap kaydedilmemeli.
        web.password = "baska"
        val c = controller(this)
        c.addAccount(email, "dogru-sifre")
        assertEquals(ErrorKind.AUTH, c.state.value.formError)
        assertTrue(c.state.value.accounts.isEmpty())
        repeat(4) { c.refresh() }
        assertEquals(1, web.logins)

        // Kullanıcı kendisi yeniden denerse bir kez daha denenir.
        web.password = "dogru-sifre"
        c.addAccount(email, "dogru-sifre")
        assertEquals(2, web.logins)
        assertEquals(WebStatus.SYNCED, c.state.value.web[email])
    }

    @Test
    fun passwordChangedElsewhereIsNotRetriedUntilTheUserEntersIt() = runTest {
        // Şifre başka cihazda değişti (ör. web postada şifreleme açıldı): oturum düşer.
        // Sonraki her istek (avatarlar, eşitleme, yenileme) eski şifreyle bir başarısız
        // giriş daha yapmamalı; tek deneme, sonra kullanıcı yeni şifreyi girene kadar dur.
        val c = controller(this)
        c.addAccount(email, "dogru-sifre")
        assertEquals(1, web.logins)
        web.password = "yeni-sifre"
        web.sessions.clear()
        c.syncWeb(email, force = true)
        assertEquals(2, web.logins)
        assertEquals(WebStatus.AUTH_FAILED, c.state.value.web[email])
        repeat(5) {
            c.syncWeb(email, force = true)
            c.refresh()
            runCatching { c.avatar("biri@maildots.com") }
        }
        assertEquals(2, web.logins)

        c.updateStoredPassword(email, "yeni-sifre")
        assertEquals(3, web.logins)
        assertEquals(WebStatus.SYNCED, c.state.value.web[email])
    }

    @Test
    fun rateLimitedSignInBacksOffInsteadOfRetryingOnEveryRequest() = runTest {
        val c = controller(this)
        c.addAccount(email, "dogru-sifre")
        assertEquals(1, web.logins)
        web.sessions.clear()
        web.loginFail = WebError.RateLimited()
        repeat(6) {
            runCatching { c.syncWeb(email, force = true) }
            runCatching { c.avatar("biri@maildots.com") }
        }
        assertEquals(2, web.logins) // tek deneme, sonra 30 sn beklenir

        clock += 31.seconds
        web.loginFail = null
        c.syncWeb(email, force = true)
        assertEquals(3, web.logins)
        assertEquals(WebStatus.SYNCED, c.state.value.web[email])
    }

    @Test
    fun networkFailureLeavesLocalSettingsAndCanBeRetried() = runTest {
        web.fail = WebError.Network()
        val c = controller(this)
        c.addAccount(email, "dogru-sifre")
        assertEquals(WebStatus.OFFLINE, c.state.value.web[email])
        assertEquals(MailLabel.BUILTIN, c.state.value.labels.map { it.label })
        web.fail = null
        c.syncWeb(email, force = true)
        assertEquals(WebStatus.SYNCED, c.state.value.web[email])
        assertEquals(1, web.logins)
    }

    @Test
    fun twoStepVerificationIsCompletedWithACode() = runTest {
        web.twoFactorCode = "123456"
        web.name = "Deniz Yel"
        val c = controller(this)
        c.addAccount(email, "dogru-sifre")
        assertEquals(WebStatus.NEEDS_CODE, c.state.value.web[email])
        assertEquals("", c.account().webSession)

        c.submitWebCode(email, "12")
        assertEquals(WebProblem.BAD_CODE, c.state.value.webProblem)
        c.submitWebCode(email, "000000")
        assertEquals(WebProblem.BAD_CODE, c.state.value.webProblem)
        assertEquals(WebStatus.NEEDS_CODE, c.state.value.web[email])

        c.submitWebCode(email, " 123 456 ")
        assertNull(c.state.value.webProblem)
        assertEquals(WebStatus.SYNCED, c.state.value.web[email])
        assertEquals("Deniz Yel", c.account().displayName)
        assertEquals(1, web.logins)
    }

    @Test
    fun nameChangeIsWrittenToTheWebAndRevertedOnFailure() = runTest {
        web.name = "Deniz Yel"
        val c = controller(this)
        c.addAccount(email, "dogru-sifre")
        c.setDisplayName(email, "  Deniz Yel A.Ş. ")
        assertEquals("Deniz Yel A.Ş.", web.name)
        assertEquals("Deniz Yel A.Ş.", c.account().displayName)

        web.fail = WebError.Network()
        c.setDisplayName(email, "Kaydedilemeyen")
        assertEquals("Deniz Yel A.Ş.", c.account().displayName)
        assertEquals("Deniz Yel A.Ş.", web.name)
        assertEquals(WebProblem.NETWORK, c.state.value.webProblem)
    }

    @Test
    fun labelsAreCreatedRecolouredAndDeletedOnTheWeb() = runTest {
        engine.inbox += summary(7)
        val c = controller(this)
        c.addAccount(email, "dogru-sifre")

        c.createLabel("  Müşteri  ", LabelPalette.argb("teal"), applyTo = summary(7))
        val created = web.labels.single()
        assertEquals("Müşteri", created.name)
        assertEquals("teal", created.color)
        val info = c.state.value.labels.single()
        assertEquals(MailLabel(created.keyword), info.label)
        // İletiye servisin verdiği anahtar sözcük yazılır (webmail aynı etiketi görür).
        assertEquals(listOf(Triple(7L, info.label, true)), engine.labelCalls)

        c.createLabel("Müşteri", LabelPalette.argb("red"))
        assertEquals(WebProblem.NAME_TAKEN, c.state.value.webProblem)
        assertEquals(1, web.labels.size)

        c.setLabelColor(info.label, LabelPalette.argb("pink"))
        assertEquals("pink", web.labels.single().color)
        assertNull(c.state.value.webProblem)

        web.fail = WebError.Network()
        c.setLabelColor(info.label, LabelPalette.argb("blue"))
        assertEquals(LabelPalette.argb("pink"), c.state.value.labels.single().color)
        c.deleteLabel(info.label)
        assertEquals(1, c.state.value.labels.size)
        web.fail = null

        c.deleteLabel(info.label)
        assertTrue(web.labels.isEmpty())
        assertTrue(c.state.value.labels.isEmpty())
        // Servis iletilerden de kaldırır; cihaz ayrıca tüm klasörleri taramaz.
        assertTrue(engine.cleared.isEmpty())
        assertTrue(controller(this).state.value.labels.isEmpty())
    }

    @Test
    fun plainSignatureIsSavedButFormattedOneIsNotOverwritten() = runTest {
        val c = controller(this)
        c.addAccount(email, "dogru-sifre")
        c.setSignature(email, "Deniz Yel\nSatış <ekibi>")
        assertEquals("Deniz Yel<br>Satış &lt;ekibi&gt;", web.signatures.single().html)
        assertEquals(web.signatures.single().id, c.account().signatureId)

        c.setSignature(email, "Yeni imza")
        assertEquals(1, web.signatures.size)
        assertEquals("Yeni imza", web.signatures.single().html)

        c.setSignature(email, "   ")
        assertTrue(web.signatures.isEmpty())
        assertEquals("", c.account().signatureId)

        // Web'de biçimlendirilmiş imza: mobilde metni görünür ama düz metinle ezilmez.
        web.signatures += WebSignature("zengin", "Kurumsal", "<p><strong>Deniz Yel</strong></p><p><a href=\"https://ornek.example\">ornek.example</a></p>", isDefault = true)
        c.syncWeb(email, force = true)
        assertTrue(c.account().signatureRich)
        assertEquals("Deniz Yel\nornek.example", c.account().signature)
        c.setSignature(email, "ezme denemesi")
        assertTrue(web.signatures.single().html.contains("<strong>"))
        assertEquals("Deniz Yel\nornek.example", c.account().signature)
    }

    @Test
    fun webSettingsOpenWithoutHandingOverTheSessionAndResyncOnReturn() = runTest {
        val c = controller(this)
        c.addAccount(email, "dogru-sifre")
        c.openSettings()
        c.openAccountSettings(email)
        val logins = web.logins
        c.openWebSettings(email)
        assertEquals(Screen.WebSettings(email), c.state.value.screen)
        // Sayfa için oturum açılmaz/yenilenmez: kullanıcı web'e kendisi giriş yapar.
        assertEquals(logins, web.logins)
        // Web'de ad değişti: geri dönünce uygulama da onu gösterir.
        web.name = "Web'de Değişen"
        c.back()
        assertEquals(Screen.AccountSettings(email), c.state.value.screen)
        assertEquals("Web'de Değişen", c.account().displayName)
    }

    @Test
    fun logoutClosesTheWebSessionToo() = runTest {
        val c = controller(this)
        c.addAccount(email, "dogru-sifre")
        val session = c.account().webSession
        assertTrue(session.isNotEmpty())
        // Oturum değeri hesap dökümüne (günlüklere) girmez.
        assertFalse(c.account().toString().contains(session))
        c.removeAccount(email)
        assertEquals(listOf(session), web.loggedOut)
        assertTrue(store.map.keys.none { it.contains(email) })
    }

    @Test
    fun signatureTextConversion() {
        assertEquals("Ad Soyad\nŞirket & Ortakları", SignatureText.toText("<div>Ad Soyad</div><div>Şirket &amp; Ortakları</div>"))
        assertEquals("a\n\nb", SignatureText.toText("<p>a</p><p></p><p></p><p>b</p>"))
        assertEquals("görünen", SignatureText.toText("<style>p{color:red}</style><p>görünen</p><script>alert(1)</script>"))
        assertFalse(SignatureText.isRich("<p>düz</p><div>metin<br/>satır</div>"))
        assertTrue(SignatureText.isRich("<p style=\"color:red\">renkli</p>"))
        assertTrue(SignatureText.isRich("<p><b>kalın</b></p>"))
        assertTrue(SignatureText.isRich("<img src=\"x\">"))
        assertEquals("1 &lt; 2 &amp; 3<br>son", SignatureText.toHtml(" 1 < 2 & 3\r\nson \n"))
        // Gidiş-dönüş: düz metin imza aynen geri gelir.
        assertEquals("Ad\nUnvan <x>", SignatureText.toText(SignatureText.toHtml("Ad\nUnvan <x>")))
    }

    @Test
    fun systemKeywordsAreNotLabels() {
        assertNull(MailLabel.fromKeyword("\$Forwarded"))
        assertNull(MailLabel.fromKeyword("NonJunk"))
        assertNull(MailLabel.fromKeyword("\$MDNSent"))
        assertEquals(MailLabel.WORK, MailLabel.fromKeyword("\$Label2"))
        // Servisin etiketleri sunucunun bildirdiği yazımla korunur.
        assertEquals(MailLabel("MDLabc123"), MailLabel.fromKeyword("MDLabc123"))
    }

    @Test
    fun deviceIsRegisteredForPushOncePerAccountAndUnregisteredOnLogout() = runTest {
        val c = controller(this)
        // Belirtec hesap eklenmeden once gelmis olabilir.
        c.onPushToken("belirtec-1")
        assertTrue(web.pushRegistered.isEmpty())
        c.addAccount(email, "dogru-sifre")
        val session = c.account().webSession
        assertEquals(listOf(session to "belirtec-1"), web.pushRegistered)

        // Yeniden eslesme ve ayni belirtec: yeniden kayit yapilmaz.
        c.syncWeb(email, force = true)
        c.onPushToken("belirtec-1")
        assertEquals(1, web.pushRegistered.size)

        // Belirtec degisirse yeni belirtecle kaydedilir.
        c.onPushToken("belirtec-2")
        assertEquals(session to "belirtec-2", web.pushRegistered.last())

        // Cikista once bildirim kaydi silinir, sonra oturum kapatilir.
        c.removeAccount(email)
        assertEquals(listOf(session to "belirtec-2"), web.pushUnregistered)
        assertEquals(listOf(session), web.loggedOut)
    }

    @Test
    fun failedPushRegistrationIsRetriedOnTheNextSync() = runTest {
        web.pushFails = true
        val c = controller(this)
        c.onPushToken("belirtec-1")
        c.addAccount(email, "dogru-sifre")
        assertTrue(web.pushRegistered.isEmpty())
        // Posta ve eslesme etkilenmez.
        assertEquals(WebStatus.SYNCED, c.state.value.web[email])
        web.pushFails = false
        c.syncWeb(email, force = true)
        assertEquals(1, web.pushRegistered.size)
    }

    @Test
    fun notificationTapOpensTheMessageInTheRightAccount() = runTest {
        engine.inbox += summary(42)
        val c = controller(this)
        c.addAccount(email, "dogru-sifre")
        c.openAddAccount()
        c.addAccount("ikinci@b.co", "dogru-sifre")
        assertEquals("ikinci@b.co", c.state.value.active?.email)

        c.openFromNotification(email.uppercase(), "INBOX", 42)
        assertEquals(email, c.state.value.active?.email)
        assertEquals(Screen.Reader("INBOX", 42), c.state.value.screen)
        // Bu cihazda olmayan hesap icin hicbir sey yapilmaz.
        c.openFromNotification("yok@b.co", "INBOX", 1)
        assertEquals(email, c.state.value.active?.email)
    }
}
