package io.maildots.app.e2e

import io.maildots.app.data.SecureStore
import io.maildots.app.mail.AttachmentData
import io.maildots.app.web.E2EApi
import io.maildots.app.web.Prelogin
import io.maildots.app.web.WebVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Bu cihazda açılmış kasa anahtarı (özel anahtar PKCS#8 base64 + kendi sertifikası). */
@Serializable
data class StoredKey(val pkcs8: String, val certificatePem: String) {
    override fun toString(): String = "StoredKey(***)"
}

/**
 * Açılmış özel anahtarın (StoredKey, JSON) tutulduğu yer. Varsayılan: şifreli depo
 * ([StoreKeyVault]: Android Keystore / iOS Keychain / Windows DPAPI). Masaüstündeki "yalnız
 * bellekte" seçeneği bu arayüzle takılır; ileride Windows Hello korumalı saklama da aynı yere
 * (ayrı bir uygulama olarak) gelir.
 */
interface UnlockedKeyVault {
    fun get(email: String): String?
    fun put(email: String, value: String)
    fun remove(email: String)

    companion object {
        /** Şifreli depodaki anahtar adı öneki (adres küçük harfle eklenir). */
        const val KEY_PREFIX = "e2e.key.v1."
    }
}

/** Varsayılan: anahtar şifreli depoda (Android/iOS davranışı; masaüstünde "hatırla" açıkken). */
class StoreKeyVault(private val store: SecureStore) : UnlockedKeyVault {
    override fun get(email: String): String? = store.get(UnlockedKeyVault.KEY_PREFIX + email.lowercase())
    override fun put(email: String, value: String) = store.put(UnlockedKeyVault.KEY_PREFIX + email.lowercase(), value)
    override fun remove(email: String) = store.remove(UnlockedKeyVault.KEY_PREFIX + email.lowercase())
}

/**
 * Açılmış anahtarlar ve alıcı anahtarı sabitlemeleri, şifreli depoda (Android Keystore /
 * iOS Keychain). Özel anahtar cihazdan çıkmaz, yedeğe girmez; hesap silinince silinir.
 * Anahtarın kendisi [vault]'ta (varsayılan aynı şifreli depo).
 */
class E2EKeyStore(private val store: SecureStore, private val vault: UnlockedKeyVault = StoreKeyVault(store)) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pinSerializer = MapSerializer(String.serializer(), String.serializer())

    fun get(email: String): StoredKey? =
        vault.get(email)?.let { runCatching { json.decodeFromString(StoredKey.serializer(), it) }.getOrNull() }

    fun put(email: String, key: StoredKey) = vault.put(email, json.encodeToString(StoredKey.serializer(), key))

    fun remove(email: String) {
        vault.remove(email)
        store.remove(PINS + email.lowercase())
        store.remove(ON + email.lowercase())
    }

    /**
     * Bu cihaz bu hesapta kasa gördü. Sonradan sunucu "kasa yok" derse buna güvenilmez
     * (şifreleme sessizce kapatılamaz); yalnızca hesabın cihazdan silinmesi işareti kaldırır.
     */
    fun markEnabled(email: String) = store.put(ON + email.lowercase(), "1")

    fun wasEnabled(email: String): Boolean = store.get(ON + email.lowercase()) == "1"

    /** T4: bu cihazın doğruladığı anahtar şeffaflığı kaydı durumu (baş + adres → anahtar). */
    fun keyLogState(): kotlinx.serialization.json.JsonObject? =
        store.get(KEY_LOG)?.let { runCatching { Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull() }

    fun saveKeyLogState(state: kotlinx.serialization.json.JsonObject) = store.put(KEY_LOG, state.toString())

    /** Alıcı adresi → ilk görülen sertifika parmak izi (TOFU). */
    fun pins(email: String): Map<String, String> =
        store.get(PINS + email.lowercase())?.let { runCatching { json.decodeFromString(pinSerializer, it) }.getOrNull() }.orEmpty()

    fun savePins(email: String, pins: Map<String, String>) {
        val trimmed = if (pins.size > MAX_PINS) pins.entries.toList().takeLast(MAX_PINS).associate { it.key to it.value } else pins
        store.put(PINS + email.lowercase(), json.encodeToString(pinSerializer, trimmed))
    }

    private companion object {
        const val PINS = "e2e.pins.v1."
        const val KEY_LOG = "e2e.keylog.v1"
        const val ON = "e2e.on.v1."
        const val MAX_PINS = 2_000
    }
}

/** Girişte sunucuya gidecek sır: şifre (sıradan kutu) ya da giriş anahtarı (+ kasayı açacak kilit anahtarı). */
data class SignInSecret(val secret: String, val lockKey: String?, val protected: Boolean) {
    override fun toString(): String = "SignInSecret(protected=$protected)"
}

/** Gönderimden önce verilen karar (fail-closed: belirsizlikte hiçbir şey gönderilmez). */
sealed interface SendPlan {
    /** Herkesin anahtarı var: [main] To + Cc + gönderene, [bcc] her gizli alıcıya ayrı. */
    data class Encrypted(val main: List<String>, val bcc: List<Pair<String, String>>, val pins: Map<String, String>) : SendPlan

    /** Bu alıcıların uçtan uca anahtarı yok: şifresiz gönderim ancak açık onayla. */
    data class Missing(val addresses: List<String>) : SendPlan

    /**
     * Bu alıcıların anahtarı daha önce görülenden farklı ya da hiç verilmedi (kaldırılmış):
     * sessizce kullanılmaz, onay istenir. [fingerprints] yeni parmak izleri ("" = anahtar yok).
     */
    data class KeyChanged(val addresses: List<String>, val fingerprints: Map<String, String>) : SendPlan
}

/**
 * Uçtan uca şifrelemenin uygulama tarafı: giriş anahtarı, kasanın cihazda açılması,
 * iletilerin açılması ve gönderim kararı. Kripto [crypto]'da (çekirdek), ağ [E2EApi]'de.
 */
/** İmza hükmü (T4). */
enum class SignatureVerdict {
    NONE, VERIFIED, VERIFIED_BODY_ONLY, UNSIGNED, UNKNOWN_KEY, NOT_ADDRESSED, WRONG_ADDRESS, WRONG_KEY,
    HEADERS_CHANGED, LOG_FORK, LOG_REWRITTEN, BROKEN,
}

/**
 * İmzalanan ve karşılaştırılan tek konu biçimi (webmail normalizeSubject ile aynı):
 * boşluk dizileri tek boşluk, kenarlar kırpılır, boş konu "(no subject)", en çok 2000 karakter.
 */
fun normalizeSubject(subject: String): String =
    subject.replace(JS_SPACE, " ").trim(' ').ifEmpty { "(no subject)" }.take(2000)

// JavaScript'in \s kümesi (Java'nın \s'i yalnızca ASCII).
private val JS_SPACE = Regex("[\t\n\u000B\u000C\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]+")

/** Anahtar kaydı denetiminin sonucu (T4). */
enum class LogCheck { OK, MISMATCH, REWRITTEN, UNAVAILABLE }

class E2EService(private val crypto: E2ECrypto, private val keys: E2EKeyStore) {

    fun key(email: String): StoredKey? = keys.get(email)

    fun forget(email: String) = keys.remove(email)

    /** Bu cihaz bu hesapta kasa gördü mü (sunucunun "kasa yok" yanıtına karşı; bkz. E2EKeyStore). */
    fun wasEnabled(email: String): Boolean = keys.wasEnabled(email)

    fun markEnabled(email: String) = keys.markEnabled(email)

    /** Yazılan şifreden girişte gönderilecek sırrı üretir (giriş korumasında şifre gönderilmez). */
    suspend fun signInSecret(prelogin: Prelogin, password: String): SignInSecret {
        val kdf = prelogin.kdf ?: return SignInSecret(password, null, protected = false)
        val derived = crypto.deriveSecrets(password, kdf)
        return SignInSecret(derived.loginKey, derived.lockKey, protected = true)
    }

    /**
     * Kasayı bu cihazda açar ve anahtarı saklar. Kilit anahtarı girişte türetildiyse
     * ([lockKey]) Argon2id yeniden çalışmaz; yoksa [password] ile kasanın tuzundan türetilir.
     * Açılamazsa [E2EError] ("unlock_failed": şifre bu kasayı açmıyor).
     */
    suspend fun unlock(vault: WebVault, email: String, password: String?, lockKey: String?) {
        val lock = lockKey ?: crypto.deriveSecrets(password ?: throw E2EError("unlock_failed"), vault.kdf).lockKey
        val pkcs8 = crypto.unlock(vault.vault, email, lock)
        keys.put(email, StoredKey(pkcs8, vault.certificatePem))
    }

    /** Şifreli iletiyi açar (ham hâli sunucudan, çözme cihazda). */
    suspend fun open(api: E2EApi, session: String, email: String, folder: String, uid: Long): DecryptedMail {
        val key = keys.get(email) ?: throw E2EError("locked")
        val raw = api.rawMessage(session, folder, uid)
        return crypto.decrypt(raw, key.pkcs8, key.certificatePem)
    }

    suspend fun openDraft(api: E2EApi, session: String, email: String, folder: String, uid: Long): OpenedDraft {
        val key = keys.get(email) ?: throw E2EError("locked")
        val raw = api.rawMessage(session, folder, uid)
        return crypto.openDraft(raw, key.pkcs8, key.certificatePem)
    }

    /**
     * Gönderim kararı. Dizin sorgusu ya da sertifika denetimi başarısızsa hata fırlatır
     * (gönderim durur, şifresiz gönderime düşülmez). [trusted]: kullanıcının onayladığı
     * değişmiş anahtarlar (adres → parmak izi); yalnızca birebir aynısı kabul edilir.
     */
    suspend fun plan(
        api: E2EApi,
        session: String,
        owner: String,
        to: List<String>,
        cc: List<String>,
        bcc: List<String>,
        trusted: Map<String, String> = emptyMap(),
    ): SendPlan {
        val self = keys.get(owner) ?: throw E2EError("locked")
        val everyone = (to + cc + bcc).map { it.lowercase() }.distinct()
        val others = everyone.filter { it != owner.lowercase() }
        val directory = if (others.isEmpty()) emptyMap() else api.keyDirectory(session, others)
        val certs = HashMap<String, String>()
        val fingerprints = HashMap<String, String>()
        for ((address, pem) in directory) {
            // Parmak izi çekirdekte yeniden hesaplanır; sunucunun söylediğine güvenilmez.
            val check = crypto.checkCertificate(pem, address)
            if (check.ok && check.fingerprint != null) {
                certs[address] = pem
                fingerprints[address] = check.fingerprint
            }
        }
        // T4: verilen her anahtar, anahtar şeffaflığı kaydındaki güncel anahtar olmalı;
        // değilse (ya da kayıt doğrulanamazsa) gönderim durur.
        if (fingerprints.isNotEmpty()) {
            when (logCheck(api, session, fingerprints)) {
                LogCheck.OK -> Unit
                LogCheck.REWRITTEN -> throw E2EError("transparency_rewritten")
                else -> throw E2EError("transparency")
            }
        }
        certs[owner.lowercase()] = self.certificatePem
        val pins = keys.pins(owner)
        // Değişen anahtar ve sabitlenmiş ama artık verilmeyen anahtar aynı ciddiyette sorulur.
        val changed = HashMap<String, String>()
        for (address in others) {
            val pinned = pins[address] ?: continue
            val now = fingerprints[address] ?: ""
            if (now != pinned && trusted[address] != now) changed[address] = now
        }
        if (changed.isNotEmpty()) return SendPlan.KeyChanged(changed.keys.sorted(), changed)
        val missing = everyone.filter { it !in certs }
        if (missing.isNotEmpty()) return SendPlan.Missing(missing)
        val mainAddresses = (to + cc).map { it.lowercase() }.distinct()
        val main = (mainAddresses.map { certs.getValue(it) } + self.certificatePem).distinct()
        val bccCopies = bcc.map { it.lowercase() }.distinct().map { it to certs.getValue(it) }
        return SendPlan.Encrypted(main, bccCopies, fingerprints)
    }

    /**
     * T4: [served] (adres → parmak izi) anahtar şeffaflığı kaydındaki güncel anahtarlar mı?
     * Kayıt yeni sayfalarla ilerletilir; zincir kopuk, geriye alınmış ya da erişilemiyorsa false.
     */
    suspend fun keysInLog(api: E2EApi, session: String, served: Map<String, String>): Boolean =
        logCheck(api, session, served) == LogCheck.OK

    /** Kaydı ilerletir ve [served] anahtarları denetler; geri gitmiş/çatallanmış kayıt ayrı bildirilir. */
    suspend fun logCheck(api: E2EApi, session: String, served: Map<String, String>): LogCheck {
        var state = keys.keyLogState()
        repeat(50) {
            val after = (state?.get("seq") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val page = runCatching { api.keyLog(session, after) }.getOrNull() ?: return LogCheck.UNAVAILABLE
            val result = crypto.applyKeyLog(state, page, served)
            if (!result.ok || result.state == null) return LogCheck.REWRITTEN
            state = result.state
            keys.saveKeyLogState(result.state)
            if (result.complete) return if (result.mismatched.isEmpty()) LogCheck.OK else LogCheck.MISMATCH
        }
        return LogCheck.UNAVAILABLE
    }

    /**
     * Başka bir kullanıcının (imzalı iletideki) kayıt başı bu cihazın gördüğüyle aynı mı?
     * null = bilinmiyor. [upToDate]: kayıt az önce sonuna kadar ilerletildi; o hâlde hâlâ
     * geride kalmak, sunucunun bu cihazdan kayıt sakladığı anlamına gelir (çatal).
     */
    fun witnessAgrees(head: KeyLogHead, upToDate: Boolean = false): Boolean? {
        val state = keys.keyLogState() ?: return null
        if (head.seq == 0) return head.hash == "0".repeat(64)
        val seq = (state["seq"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
        if (upToDate && seq < head.seq) return false
        val hashes = state["hashes"] as? kotlinx.serialization.json.JsonObject ?: return null
        val mine = (hashes[head.seq.toString()] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        return mine == head.hash
    }

    /** Gönderim başarılı olunca alıcı anahtarlarını sabitler. */
    fun pin(owner: String, fingerprints: Map<String, String>) {
        if (fingerprints.isEmpty()) return
        keys.savePins(owner, keys.pins(owner) + fingerprints)
    }

    /**
     * Gönderilecek ileti: gönderenin kendi anahtarıyla imzalanır, sonra şifrelenir (T4).
     * Gönderen, alıcılar (Gizli hariç), konu ve doğrulanmış kayıt başı imzanın içine girer.
     * Anahtar kilitliyse ileti imzasız GÖNDERİLMEZ.
     */
    suspend fun encrypt(
        owner: String,
        html: String,
        attachments: List<AttachmentData>,
        certificates: List<String>,
        to: List<String> = emptyList(),
        subject: String = "",
    ): String {
        val self = keys.get(owner) ?: throw E2EError("locked")
        val head = keys.keyLogState()?.let { st ->
            val seq = (st["seq"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            val hash = (st["hash"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (seq != null && hash != null && hash.length == 64) KeyLogHead(seq, hash) else null
        }
        return crypto.encrypt(
            html, attachments, certificates,
            signer = self,
            headers = ProtectedHeaders(from = owner.lowercase(), to = to.map { it.lowercase() }, subject = subject, keyLog = head),
        )
    }

    /**
     * İmzanın hükmü (T4), webmail'deki judgeSignature ile aynı: geçerli imza, gönderen
     * adresine ait ve o adresin MailDots dizinindeki (ve bu cihazda sabitlenmiş) anahtarıyla
     * atılmışsa "doğrulandı". [directoryPem] gönderenin dizindeki sertifikası (yoksa null).
     */
    suspend fun judge(
        owner: String,
        signature: MailSignature,
        from: String,
        directoryPem: String?,
        inLog: (suspend (String) -> LogCheck)? = null,
        subject: String? = null,
    ): SignatureVerdict {
        val sender = from.trim().lowercase()
        // Şifreli olması kimden geldiğini kanıtlamaz: MailDots anahtarı olan biri imzasız gönderdiyse işaretlenir.
        if (!signature.signed) return if (directoryPem != null) SignatureVerdict.UNSIGNED else SignatureVerdict.NONE
        if (!signature.valid || signature.signerFingerprint == null) return SignatureVerdict.BROKEN
        if (sender !in signature.signerEmails) return SignatureVerdict.WRONG_ADDRESS
        val signed = signature.protected
        if (signed != null && (signed.from != sender || (subject != null && normalizeSubject(signed.subject) != normalizeSubject(subject)))) {
            return SignatureVerdict.HEADERS_CHANGED
        }
        if (directoryPem == null) return SignatureVerdict.UNKNOWN_KEY
        val listed = crypto.checkCertificate(directoryPem, sender).fingerprint ?: return SignatureVerdict.UNKNOWN_KEY
        if (listed != signature.signerFingerprint) return SignatureVerdict.WRONG_KEY
        val pinned = keys.pins(owner)[sender]
        if (pinned != null && pinned != listed) return SignatureVerdict.WRONG_KEY
        // T4: ve anahtar kaydındaki güncel anahtarı olmalı.
        val logged = inLog?.invoke(listed)
        when (logged) {
            null, LogCheck.OK -> Unit
            LogCheck.MISMATCH -> return SignatureVerdict.WRONG_KEY
            LogCheck.REWRITTEN -> return SignatureVerdict.LOG_REWRITTEN
            LogCheck.UNAVAILABLE -> return SignatureVerdict.UNKNOWN_KEY
        }
        // Gönderenin gördüğü kayıt bu cihazınkiyle aynı olmalı (iki kişiye farklı kayıt).
        signed?.keyLog?.let { if (witnessAgrees(it, upToDate = logged == LogCheck.OK) == false) return SignatureVerdict.LOG_FORK }
        // Eski biçim imza: gövde imzalı, konu ve alıcılar bağlı değil.
        if (signed == null) return SignatureVerdict.VERIFIED_BODY_ONLY
        if (owner.lowercase() != sender && owner.lowercase() !in signed.to) {
            return SignatureVerdict.NOT_ADDRESSED
        }
        return SignatureVerdict.VERIFIED
    }

    /** Taslak: yalnızca gönderenin kendi anahtarına şifrelenir. */
    suspend fun sealForSelf(owner: String, html: String, attachments: List<AttachmentData>): String {
        val self = keys.get(owner) ?: throw E2EError("locked")
        return crypto.encrypt(html, attachments, listOf(self.certificatePem))
    }

    suspend fun passwordChange(vault: WebVault, email: String, current: String, new: String): PasswordChangePlan =
        crypto.passwordChange(email, current, new, vault.loginMode, vault.vault)
}
