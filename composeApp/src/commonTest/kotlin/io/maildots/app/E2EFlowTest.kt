package io.maildots.app

import io.maildots.app.data.AccountRepository
import io.maildots.app.e2e.CertCheck
import io.maildots.app.e2e.DecryptedMail
import io.maildots.app.e2e.DerivedSecrets
import io.maildots.app.e2e.E2ECrypto
import io.maildots.app.e2e.E2EError
import io.maildots.app.e2e.E2EKeyStore
import io.maildots.app.e2e.E2EService
import io.maildots.app.e2e.KdfParams
import io.maildots.app.e2e.OpenedDraft
import io.maildots.app.e2e.PasswordChangePlan
import io.maildots.app.mail.Address
import io.maildots.app.mail.AttachmentData
import io.maildots.app.mail.MessageDetail
import io.maildots.app.mail.MessageSummary
import io.maildots.app.web.AccountApi
import io.maildots.app.web.AccountService
import io.maildots.app.web.E2EApi
import io.maildots.app.web.EncryptedDraft
import io.maildots.app.web.EncryptedSend
import io.maildots.app.web.Prelogin
import io.maildots.app.web.WebCompose
import io.maildots.app.web.WebDraftSaved
import io.maildots.app.web.WebError
import io.maildots.app.web.WebLogin
import io.maildots.app.web.WebVault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val OWNER = "ali@maildots.com"
private const val PASSWORD = "Gizli-parola-2026"
private val KDF = KdfParams("argon2id", 65_536, 3, 1, "c2FsdHNhbHRzYWx0c2FsdA==")

/** Deterministik sahte çekirdek: gerçek kripto yerine izlenebilir dönüşümler. */
private class FakeCrypto : E2ECrypto {
    override suspend fun deriveSecrets(password: String, kdf: KdfParams) =
        DerivedSecrets(loginKey = "md1_" + password.hashCode().toString().padEnd(43, 'x').take(43), lockKey = "lock:$password")

    override suspend fun unlock(vault: JsonObject, email: String, lockKey: String): String =
        if (lockKey == "lock:$PASSWORD") "PKCS8" else throw E2EError("unlock_failed")

    override suspend fun decrypt(raw: ByteArray, pkcs8: String, certificatePem: String): DecryptedMail {
        val text = raw.decodeToString()
        if (!text.startsWith("ENC:")) throw E2EError("not_for_this_key")
        return DecryptedMail(html = "<p>${text.removePrefix("ENC:")}</p>")
    }

    override suspend fun openDraft(raw: ByteArray, pkcs8: String, certificatePem: String) =
        OpenedDraft(html = "<p>${raw.decodeToString().removePrefix("ENC:")}</p>")

    override suspend fun encrypt(
        html: String,
        attachments: List<AttachmentData>,
        certificates: List<String>,
        signer: io.maildots.app.e2e.StoredKey?,
        headers: io.maildots.app.e2e.ProtectedHeaders?,
    ) = "CT[" + certificates.sorted().joinToString(",") + "]" + html

    override suspend fun applyKeyLog(
        state: kotlinx.serialization.json.JsonObject?,
        page: kotlinx.serialization.json.JsonObject,
        served: Map<String, String>,
    ): io.maildots.app.e2e.KeyLogResult {
        val logged = page["logged"] as kotlinx.serialization.json.JsonObject
        val mismatched = served.filter { (a, fp) -> (logged[a] as? kotlinx.serialization.json.JsonPrimitive)?.content != fp }.keys.toList()
        return io.maildots.app.e2e.KeyLogResult(ok = true, state = kotlinx.serialization.json.buildJsonObject { put("seq", kotlinx.serialization.json.JsonPrimitive(1)) }, complete = true, mismatched = mismatched)
    }

    override suspend fun checkCertificate(certificatePem: String, address: String) =
        if (certificatePem.startsWith("CERT:$address")) CertCheck(true, "fp:$certificatePem") else CertCheck(false)

    override suspend fun passwordChange(email: String, currentPassword: String, newPassword: String, loginMode: String, vault: JsonObject) =
        PasswordChangePlan(loginMode, "cur", "new", JsonObject(emptyMap()))
}

/** Yeni sistemin sahte hesap servisi: webmail'in e2e uçları ve gönderim/taslak kaydı. */
private class FakeModernApi(private val base: FakeAccountApi = FakeAccountApi()) : AccountApi by base, E2EApi {
    var hosted = true
    var protected = true
    var preloginFails = false
    var hasVault = true
    /** Kasanın bildirdiği giriş kipi (null = [protected]'a göre). */
    var vaultMode: String? = null
    var preloginError: WebError? = null
    val loginSecrets = ArrayList<String>()
    val directory = HashMap<String, String>()
    val encryptedSends = ArrayList<EncryptedSend>()
    val plainSends = ArrayList<WebCompose>()
    val encryptedDrafts = ArrayList<EncryptedDraft>()
    val plainDrafts = ArrayList<WebCompose>()
    val raw = HashMap<Long, String>()
    private var session = 0

    var secret = ""

    override suspend fun login(email: String, password: String): WebLogin {
        loginSecrets += password
        if (password != secret) return WebLogin.Invalid
        val id = "s-${++session}"
        base.sessions += id
        return WebLogin.Ok(id)
    }

    override suspend fun send(session: String, message: WebCompose) {
        plainSends += message
    }

    override suspend fun saveDraft(session: String, message: WebCompose): WebDraftSaved {
        plainDrafts += message
        return WebDraftSaved(1, "Drafts")
    }

    override suspend fun prelogin(email: String): Prelogin {
        if (preloginFails) throw WebError.Network()
        preloginError?.let { throw it }
        return if (!hosted) Prelogin(false, null) else Prelogin(true, if (protected) KDF else null)
    }

    override suspend fun vault(session: String): WebVault? = if (!hasVault) null else WebVault(
        owner = OWNER,
        vault = JsonObject(mapOf("certificatePem" to JsonPrimitive("CERT:$OWNER"))),
        certificatePem = "CERT:$OWNER",
        kdf = KDF,
        loginMode = vaultMode ?: if (protected) "derived" else "password",
        encryptionEnabled = true,
    )

    override suspend fun rawMessage(session: String, folder: String, uid: Long): ByteArray =
        (raw[uid] ?: throw WebError.Server(404)).encodeToByteArray()

    override suspend fun keyDirectory(session: String, addresses: List<String>) =
        directory.filterKeys { it in addresses }

    /** T4: adresler kayda yazılmadan dizinde verilirse (sahte anahtar) burada yer almaz. */
    val unlogged = HashSet<String>()

    /** Dürüst sunucu: dizindeki her anahtar kayıtta (sahte kripto bu sayfayı "kayıt" sayar). */
    override suspend fun keyLog(session: String, after: Int): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("logged", kotlinx.serialization.json.buildJsonObject {
                directory.filterKeys { it !in unlogged }.forEach { (a, pem) -> put(a, kotlinx.serialization.json.JsonPrimitive("fp:$pem")) }
            })
        }

    override suspend fun sendEncrypted(session: String, message: EncryptedSend): List<String> {
        encryptedSends += message
        return emptyList()
    }

    override suspend fun saveEncryptedDraft(session: String, message: EncryptedDraft): WebDraftSaved {
        encryptedDrafts += message
        return WebDraftSaved(2, "Drafts")
    }

    override suspend fun changePasswordWithVault(session: String, currentSecret: String, newSecret: String, vault: JsonObject, target: String) = Unit
}

private fun encryptedSummary(uid: Long) = MessageSummary(
    uid = uid, subject = "gizli $uid", from = Address("Veli", "veli@maildots.com"), to = emptyList(), dateMillis = 0,
    seen = false, flagged = false, hasAttachment = false, encrypted = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class E2EFlowTest {
    private val store = MemoryStore()
    private val engine = FakeEngine()
    private val modern = FakeModernApi()
    private val crypto = FakeCrypto()

    @Test
    fun signatureVerdictFollowsTheDirectoryKeyAndThePin() = runTest {
        val keys = E2EKeyStore(store)
        val service = E2EService(crypto, keys)
        val owner = "ben@maildots.com"
        val from = "ali@maildots.com"
        val dir = "CERT:$from"
        val ok = io.maildots.app.e2e.MailSignature(signed = true, valid = true, signerEmails = listOf(from), signerFingerprint = "fp:$dir")
        // An older signature (no protected headers) only vouches for the body.
        assertEquals(io.maildots.app.e2e.SignatureVerdict.VERIFIED_BODY_ONLY, service.judge(owner, ok, from, dir))
        assertEquals(io.maildots.app.e2e.SignatureVerdict.UNSIGNED, service.judge(owner, io.maildots.app.e2e.MailSignature(), from, dir))
        assertEquals(io.maildots.app.e2e.SignatureVerdict.NONE, service.judge(owner, io.maildots.app.e2e.MailSignature(), from, null))
        val wrapped = ok.copy(protected = io.maildots.app.e2e.ProtectedHeaders(from = from, to = listOf(owner), subject = "Öğle"))
        assertEquals(io.maildots.app.e2e.SignatureVerdict.HEADERS_CHANGED, service.judge(owner, wrapped, from, dir, subject = "Transfer"))
        assertEquals(io.maildots.app.e2e.SignatureVerdict.VERIFIED, service.judge(owner, wrapped, from, dir, subject = "  Öğle "))
        val empty = ok.copy(protected = io.maildots.app.e2e.ProtectedHeaders(from = from, to = listOf(owner), subject = "(no subject)"))
        assertEquals(io.maildots.app.e2e.SignatureVerdict.VERIFIED, service.judge(owner, empty, from, dir, subject = ""))
        // My log is complete (inLog OK) yet behind the sender's head: entries are withheld from me.
        keys.saveKeyLogState(kotlinx.serialization.json.buildJsonObject {
            put("seq", kotlinx.serialization.json.JsonPrimitive(1))
            put("hash", kotlinx.serialization.json.JsonPrimitive("a".repeat(64)))
        })
        val ahead = wrapped.copy(protected = wrapped.protected!!.copy(keyLog = io.maildots.app.e2e.KeyLogHead(5, "c".repeat(64))))
        assertEquals(io.maildots.app.e2e.SignatureVerdict.LOG_FORK, service.judge(owner, ahead, from, dir, inLog = { io.maildots.app.e2e.LogCheck.OK }, subject = "Öğle"))
        assertEquals(io.maildots.app.e2e.SignatureVerdict.VERIFIED, service.judge(owner, ahead, from, dir, subject = "Öğle"))
        keys.saveKeyLogState(kotlinx.serialization.json.buildJsonObject {})
        assertEquals(io.maildots.app.e2e.SignatureVerdict.NOT_ADDRESSED, service.judge(owner, wrapped.copy(protected = wrapped.protected!!.copy(to = listOf("c@x.co"))), from, dir, subject = "Öğle"))
        assertEquals(io.maildots.app.e2e.SignatureVerdict.LOG_REWRITTEN, service.judge(owner, ok, from, dir, inLog = { io.maildots.app.e2e.LogCheck.REWRITTEN }))
        assertEquals(io.maildots.app.e2e.SignatureVerdict.BROKEN, service.judge(owner, ok.copy(valid = false), from, dir))
        assertEquals(io.maildots.app.e2e.SignatureVerdict.WRONG_ADDRESS, service.judge(owner, ok.copy(signerEmails = listOf("x@y.co")), from, dir))
        assertEquals(io.maildots.app.e2e.SignatureVerdict.WRONG_KEY, service.judge(owner, ok.copy(signerFingerprint = "fp:baska"), from, dir))
        assertEquals(io.maildots.app.e2e.SignatureVerdict.UNKNOWN_KEY, service.judge(owner, ok, from, null))
        // A different key pinned on this device for the sender wins over the directory.
        service.pin(owner, mapOf(from to "fp:eski"))
        assertEquals(io.maildots.app.e2e.SignatureVerdict.WRONG_KEY, service.judge(owner, ok, from, dir))
    }

    @Test
    fun subjectsAreComparedInOneForm() {
        assertEquals("(no subject)", io.maildots.app.e2e.normalizeSubject(" \n\u00A0"))
        assertEquals("Teklif ve fiyat", io.maildots.app.e2e.normalizeSubject("Teklif\n  ve\u2003fiyat "))
    }

    private fun controller(scope: TestScope, withCrypto: Boolean = true) = AppController(
        engine = engine,
        repository = AccountRepository(store),
        scope = TestScope(UnconfinedTestDispatcher(scope.testScheduler)),
        api = modern,
        e2e = if (withCrypto) E2EService(crypto, E2EKeyStore(store)) else null,
    )

    private suspend fun loginKey() = crypto.deriveSecrets(PASSWORD, KDF).loginKey

    private suspend fun signedIn(scope: TestScope): AppController {
        modern.secret = loginKey()
        engine.validPassword = modern.secret
        return controller(scope).also { it.addAccount(OWNER, PASSWORD) }
    }

    @Test
    fun protectedSignInSendsOnlyTheLoginKeyAndOpensTheVault() = runTest {
        val c = signedIn(this)
        val account = c.state.value.active
        assertNotNull(account)
        assertEquals(AccountService.BASE_URL, account.service)
        assertTrue(account.loginProtected)
        assertEquals(listOf(loginKey()), modern.loginSecrets)
        assertEquals(loginKey(), account.password)
        assertEquals(E2EStatus.READY, c.state.value.e2e[OWNER])
        // Şifrenin kendisi hiçbir yere yazılmadı.
        assertTrue(store.map.values.none { it.contains(PASSWORD) })
    }

    @Test
    fun addressNotOnTheServiceIsRejectedWithoutSendingThePassword() = runTest {
        modern.hosted = false
        val c = controller(this)
        c.addAccount("a@firma.com", "dogru-sifre")
        assertEquals(ErrorKind.AUTH, c.state.value.formError)
        assertNull(c.state.value.active)
        assertTrue(modern.loginSecrets.isEmpty())
    }

    @Test
    fun discoveryOutageStopsSignInForEveryDomain() = runTest {
        modern.preloginFails = true
        val c = controller(this)
        c.addAccount(OWNER, PASSWORD)
        assertEquals(ErrorKind.NETWORK, c.state.value.formError)
        assertNull(c.state.value.active)
        c.addAccount("a@firma.com", "dogru-sifre")
        assertEquals(ErrorKind.NETWORK, c.state.value.formError)
        assertNull(c.state.value.active)
        assertTrue(modern.loginSecrets.isEmpty())
    }

    @Test
    fun protectedAccountNeedsTheCryptoCore() = runTest {
        val c = controller(this, withCrypto = false)
        c.addAccount(OWNER, PASSWORD)
        assertEquals(ErrorKind.E2E_UNSUPPORTED, c.state.value.formError)
        assertTrue(modern.loginSecrets.isEmpty())
    }

    @Test
    fun encryptedMessageOpensOnTheDeviceAndIsNeverTranslated() = runTest {
        val c = signedIn(this)
        engine.inbox += encryptedSummary(5)
        engine.details[5] = MessageDetail(encryptedSummary(5), emptyList(), null, "", emptyList())
        modern.raw[5] = "ENC:merhaba"
        c.openMessage(encryptedSummary(5))
        val detail = c.state.value.reader.detail
        assertNotNull(detail)
        assertTrue(detail.decrypted)
        assertEquals("<p>merhaba</p>", detail.html)
        assertNull(detail.htmlBaseUrl)
        c.translate("en")
        assertFalse(c.state.value.reader.translating)
    }

    @Test
    fun lockedKeyIsUnlockedWithThePasswordOnTheDevice() = runTest {
        val c = signedIn(this)
        E2EKeyStore(store).remove(OWNER) // anahtar bu cihazda yok
        engine.details[6] = MessageDetail(encryptedSummary(6), emptyList(), null, "", emptyList())
        modern.raw[6] = "ENC:kilitli"
        c.openMessage(encryptedSummary(6))
        assertEquals(E2EProblem.LOCKED, c.state.value.reader.e2eProblem)
        c.unlockE2E(OWNER, "yanlis")
        assertEquals(E2EProblem.WRONG_PASSWORD, c.state.value.reader.e2eProblem)
        c.unlockE2E(OWNER, PASSWORD)
        assertEquals(E2EStatus.READY, c.state.value.e2e[OWNER])
        assertEquals("<p>kilitli</p>", c.state.value.reader.detail?.html)
        assertNull(c.state.value.reader.e2eProblem)
    }

    @Test
    fun sendsEncryptedWhenEveryRecipientHasAKeyWithSeparateBccCopies() = runTest {
        val c = signedIn(this)
        modern.directory["veli@maildots.com"] = "CERT:veli@maildots.com"
        modern.directory["gizli@maildots.com"] = "CERT:gizli@maildots.com"
        c.openComposer()
        c.send("veli@maildots.com", "", "Konu", "Merhaba", "gizli@maildots.com")
        val sent = modern.encryptedSends.single()
        assertTrue(modern.plainSends.isEmpty())
        assertTrue(sent.ciphertext.startsWith("CT[CERT:$OWNER,CERT:veli@maildots.com]"))
        assertEquals(listOf("gizli@maildots.com"), sent.bccCopies.map { it.first })
        assertTrue(sent.bccCopies.single().second.startsWith("CT[CERT:gizli@maildots.com]"))
        assertTrue(c.state.value.compose.sentEncrypted)
    }

    @Test
    fun aKeyMissingFromTheTransparencyLogStopsTheSend() = runTest {
        val c = signedIn(this)
        modern.directory["veli@maildots.com"] = "CERT:veli@maildots.com"
        modern.unlogged.add("veli@maildots.com") // served, but never logged (swapped)
        c.openComposer()
        c.send("veli@maildots.com", "", "Konu", "Merhaba")
        assertTrue(modern.encryptedSends.isEmpty() && modern.plainSends.isEmpty())
    }

    @Test
    fun recipientWithoutKeyNeedsExplicitConsentForPlainSending() = runTest {
        val c = signedIn(this)
        c.openComposer()
        c.send("disari@gmail.com", "", "Konu", "Merhaba")
        val consent = c.state.value.compose.consent
        assertIs<SendConsent.Plain>(consent)
        assertEquals(listOf("disari@gmail.com"), consent.addresses)
        assertTrue(modern.plainSends.isEmpty() && modern.encryptedSends.isEmpty())
        c.answerConsent(true)
        assertEquals(1, modern.plainSends.size)
        assertTrue(modern.encryptedSends.isEmpty())
    }

    @Test
    fun windowSendUsesTheSamePlanAndNeverFallsBackWhenEncryptionIsRequired() = runTest {
        val c = signedIn(this)
        modern.directory["veli@maildots.com"] = "CERT:veli@maildots.com"
        fun msg(vararg to: String) = io.maildots.app.mail.OutgoingMessage(to = to.toList(), subject = "Konu", body = "", html = "<p>Merhaba</p>")
        // Anahtarı olmayan alıcı: şifre zorunluysa hiçbir şey gitmez.
        val required = runCatching { c.sendDetached(OWNER, msg("disari@gmail.com"), emptySet(), emptyMap(), false, requireEncryption = true) }
        assertEquals(listOf("disari@gmail.com"), (required.exceptionOrNull() as E2ERequired).addresses)
        // Zorunlu değilse aynı onay istenir; onaylanınca şifresiz gider.
        val asked = c.sendDetached(OWNER, msg("disari@gmail.com"), emptySet(), emptyMap(), false, requireEncryption = false)
        assertIs<SendConsent.Plain>((asked as SendOutcome.NeedsConsent).consent)
        assertTrue(modern.plainSends.isEmpty() && modern.encryptedSends.isEmpty())
        val sent = c.sendDetached(OWNER, msg("disari@gmail.com"), setOf("disari@gmail.com"), emptyMap(), false, requireEncryption = false)
        assertEquals(SendOutcome.Sent(encrypted = false, failedBcc = emptyList()), sent)
        assertEquals(1, modern.plainSends.size)
        // Herkesin anahtarı varsa şifreli gider (zorunlu olsun olmasın).
        val encrypted = c.sendDetached(OWNER, msg("veli@maildots.com"), emptySet(), emptyMap(), false, requireEncryption = true)
        assertEquals(true, (encrypted as SendOutcome.Sent).encrypted)
        assertEquals(1, modern.encryptedSends.size)
        // Cihazda açılmış şifreli içerik alıntılanıyorsa şifresiz gönderim hiç önerilmez (arayüz ne derse desin).
        val quoted = runCatching { c.sendDetached(OWNER, msg("disari@gmail.com"), emptySet(), emptyMap(), quotesEncrypted = true, requireEncryption = false) }
        assertIs<E2ERequired>(quoted.exceptionOrNull())
        assertEquals(1, modern.plainSends.size)
        // Pencerenin durumu denetleyicinin yazma durumuna dokunmaz.
        assertNull(c.state.value.compose.consent)
    }

    @Test
    fun changedRecipientKeyIsNotUsedSilently() = runTest {
        val c = signedIn(this)
        modern.directory["veli@maildots.com"] = "CERT:veli@maildots.com#1"
        c.openComposer()
        c.send("veli@maildots.com", "", "1", "bir")
        assertEquals(1, modern.encryptedSends.size)
        advanceUntilIdle() // "Gönderildi" onayı kapanır
        modern.directory["veli@maildots.com"] = "CERT:veli@maildots.com#2"
        c.openComposer()
        c.send("veli@maildots.com", "", "2", "iki")
        assertIs<SendConsent.KeyChanged>(c.state.value.compose.consent)
        assertEquals(1, modern.encryptedSends.size)
        c.answerConsent(true)
        assertEquals(2, modern.encryptedSends.size)
        assertTrue(modern.encryptedSends.last().ciphertext.contains("#2"))
    }

    @Test
    fun lockedVaultBlocksSendingInsteadOfFallingBackToPlain() = runTest {
        val c = signedIn(this)
        E2EKeyStore(store).remove(OWNER)
        val fresh = controller(this) // yeni açılış: durum sunucudan okunur, anahtar yok
        fresh.openComposer()
        fresh.send("disari@gmail.com", "", "Konu", "Merhaba")
        assertEquals(ErrorKind.E2E_LOCKED, fresh.state.value.formError)
        assertTrue(modern.plainSends.isEmpty() && modern.encryptedSends.isEmpty())
        assertNotNull(c)
    }

    @Test
    fun accountWithoutVaultSendsAsBefore() = runTest {
        modern.hasVault = false
        val c = signedIn(this)
        assertEquals(E2EStatus.OFF, c.state.value.e2e[OWNER])
        c.openComposer()
        c.send("disari@gmail.com", "", "Konu", "Merhaba")
        assertEquals(1, modern.plainSends.size)
        assertNull(c.state.value.compose.consent)
    }

    @Test
    fun serverClaimingNoVaultDoesNotSilentlyTurnEncryptionOff() = runTest {
        val c = signedIn(this)
        modern.hasVault = false // sunucu birden "kasa yok" diyor; anahtar cihazda duruyor
        c.openComposer()
        c.send("disari@gmail.com", "", "Konu", "Merhaba")
        assertEquals(ErrorKind.E2E_CHECK_FAILED, c.state.value.formError)
        assertTrue(modern.plainSends.isEmpty() && modern.encryptedSends.isEmpty())
        // Anahtar silinse bile bu cihaz kasayı gördüğünü hatırlar.
        E2EKeyStore(store).get(OWNER)?.let { E2EKeyStore(store).put(OWNER, it) }
        val fresh = controller(this)
        fresh.openComposer()
        fresh.send("disari@gmail.com", "", "Konu", "Merhaba")
        assertTrue(modern.plainSends.isEmpty())
    }

    @Test
    fun reenteringThePasswordNeverSendsItWhenTheServerHidesProtection() = runTest {
        val c = signedIn(this)
        val logins = modern.loginSecrets.size
        modern.protected = false // keşif artık "düz şifre" diyor
        c.updateStoredPassword(OWNER, PASSWORD)
        assertEquals(PasswordProblem.SECURITY, c.state.value.passwordProblem)
        assertEquals(logins, modern.loginSecrets.size)
        assertTrue(modern.loginSecrets.none { it == PASSWORD })
    }

    @Test
    fun plainSignInAgainstAProtectedVaultIsRejected() = runTest {
        modern.protected = false
        modern.vaultMode = "derived" // keşif "düz şifre" dedi ama kasa korumalı: tutarsız
        modern.secret = PASSWORD
        engine.validPassword = PASSWORD
        val c = controller(this)
        c.addAccount(OWNER, PASSWORD)
        assertEquals("", c.state.value.accounts.single().password)
        assertEquals(ErrorKind.SECURITY, c.state.value.mailbox.error)
        assertNull(E2EKeyStore(store).get(OWNER))
    }

    @Test
    fun plainConsentCoversOnlyTheAddressesTheUserSaw() = runTest {
        val c = signedIn(this)
        modern.directory["veli@maildots.com"] = "CERT:veli@maildots.com"
        c.openComposer()
        c.send("disari@gmail.com, veli@maildots.com", "", "Konu", "Merhaba")
        assertEquals(listOf("disari@gmail.com"), (c.state.value.compose.consent as SendConsent.Plain).addresses)
        modern.directory.remove("veli@maildots.com") // onaydan sonra sunucu veli'nin anahtarını gizliyor
        c.answerConsent(true)
        assertTrue(modern.plainSends.isEmpty())
        assertNotNull(c.state.value.compose.consent)
    }

    @Test
    fun aPinnedKeyThatDisappearsIsTreatedAsChanged() = runTest {
        val c = signedIn(this)
        modern.directory["veli@maildots.com"] = "CERT:veli@maildots.com"
        c.openComposer()
        c.send("veli@maildots.com", "", "1", "bir")
        assertEquals(1, modern.encryptedSends.size)
        advanceUntilIdle()
        modern.directory.clear()
        c.openComposer()
        c.send("veli@maildots.com", "", "2", "iki")
        assertIs<SendConsent.KeyChanged>(c.state.value.compose.consent)
        assertTrue(modern.plainSends.isEmpty())
    }

    @Test
    fun aDiscoveryErrorDoesNotFallBackToTheOldSystem() = runTest {
        modern.preloginError = WebError.Server(502)
        val c = controller(this)
        c.addAccount("a@firma.com", "dogru-sifre")
        assertEquals(ErrorKind.NETWORK, c.state.value.formError)
        assertNull(c.state.value.active)
    }

    @Test
    fun removingTheAccountForgetsTheKey() = runTest {
        val c = signedIn(this)
        assertNotNull(E2EKeyStore(store).get(OWNER))
        c.removeAccount(OWNER)
        assertNull(E2EKeyStore(store).get(OWNER))
    }
}
