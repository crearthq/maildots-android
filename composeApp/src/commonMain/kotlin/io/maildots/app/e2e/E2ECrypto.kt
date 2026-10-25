package io.maildots.app.e2e

import io.maildots.app.mail.AttachmentData
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Argon2id parametreleri (kasanın tuzu; giriş korumasında giriş anahtarı da bununla türer). */
@Serializable
data class KdfParams(
    val alg: String,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val salt: String,
)

/**
 * Şifreden türeyen iki anahtar: [loginKey] sunucuya giden giriş anahtarı (`md1_…`),
 * [lockKey] kasayı açan kilit anahtarı (base64; cihazdan çıkmaz, işi bitince atılır).
 */
@Serializable
data class DerivedSecrets(val loginKey: String, val lockKey: String) {
    override fun toString(): String = "DerivedSecrets(***)"
}

@Serializable
data class DecryptedPart(val filename: String, val contentType: String, val size: Long, val data: String)

/**
 * İletinin S/MIME imzası (T4; kripto çekirdeğinin bildirdiği hâliyle). Geçerli imza
 * yalnızca "bu sertifikanın sahibi imzaladı" der; gönderen sayılması için sertifikanın
 * gönderenin MailDots dizinindeki anahtarı olması gerekir (bkz. E2EService.judge).
 */
@Serializable
data class MailSignature(
    val signed: Boolean = false,
    val valid: Boolean = false,
    val signerEmails: List<String> = emptyList(),
    val signerFingerprint: String? = null,
    val signedAt: String? = null,
    /** İmzanın İÇİNDEKİ başlıklar (gönderen, alıcılar, konu, gönderenin kayıt başı). */
    val protected: ProtectedHeaders? = null,
)

/** İmzalı parçanın içindeki başlıklar (T4). */
@Serializable
data class ProtectedHeaders(
    val from: String = "",
    val to: List<String> = emptyList(),
    val subject: String = "",
    val keyLog: KeyLogHead? = null,
)

@Serializable
data class KeyLogHead(val seq: Int, val hash: String)

/** Cihazda açılmış ileti: temizlenmiş HTML (görseller data: olarak gömülü) ve ekler. */
@Serializable
data class DecryptedMail(
    val html: String,
    val hadRemoteContent: Boolean = false,
    val attachments: List<DecryptedPart> = emptyList(),
    val signature: MailSignature = MailSignature(),
)

/** Kayıt sayfası uygulandı mı; yeni durum ve kayıttakiyle uyuşmayan adresler. */
data class KeyLogResult(
    val ok: Boolean,
    val state: kotlinx.serialization.json.JsonObject? = null,
    val complete: Boolean = false,
    val mismatched: List<String> = emptyList(),
)

@Serializable
data class DraftPart(val filename: String, val contentType: String, val contentBase64: String, val size: Long)

@Serializable
data class OpenedDraft(val html: String, val attachments: List<DraftPart> = emptyList())

@Serializable
data class CertCheck(val ok: Boolean, val fingerprint: String? = null)

/** Şifre değişiminde sunucuya gidecekler (giriş korumasında yalnızca giriş anahtarları). */
@Serializable
data class PasswordChangePlan(
    val target: String,
    val currentSecret: String,
    val newSecret: String,
    val vault: JsonObject,
) {
    override fun toString(): String = "PasswordChangePlan(target=$target)"
}

/** Kripto çekirdeğinin hata kodu (içerik ya da anahtar taşımaz). */
class E2EError(val code: String) : Exception(code)

/**
 * Uçtan uca şifreleme işlemleri. Uygulamada webmail'in tarayıcıdaki kodunun aynısı
 * (tek dosyalık "kripto çekirdeği") ağı kapalı gizli bir web görünümünde çalışır; Proton
 * da her platformda aynı kripto kitaplığını kullanır. Hiçbir işlem ağa çıkmaz.
 */
interface E2ECrypto {
    suspend fun deriveSecrets(password: String, kdf: KdfParams): DerivedSecrets

    /** Kasanın parola kopyasını kilit anahtarıyla açar; özel anahtarı (PKCS#8, base64) döndürür. */
    suspend fun unlock(vault: JsonObject, email: String, lockKey: String): String

    suspend fun decrypt(raw: ByteArray, pkcs8: String, certificatePem: String): DecryptedMail

    suspend fun openDraft(raw: ByteArray, pkcs8: String, certificatePem: String): OpenedDraft

    /** MIME gövdesini kurar ve verilen sertifikaların her birine şifreler (base64 S/MIME). */
    /** [signer] verilirse ileti önce gönderenin kendi anahtarıyla imzalanır (T4). */
    suspend fun encrypt(
        html: String,
        attachments: List<AttachmentData>,
        certificates: List<String>,
        signer: StoredKey? = null,
        headers: ProtectedHeaders? = null,
    ): String

    suspend fun checkCertificate(certificatePem: String, address: String): CertCheck

    /**
     * T4: bir kayıt sayfasını cihazın doğruladığı duruma uygular (zincir, karma, geri
     * gitmeme) ve [served] anahtarların kayıttaki güncel anahtar olup olmadığını söyler.
     */
    suspend fun applyKeyLog(
        state: kotlinx.serialization.json.JsonObject?,
        page: kotlinx.serialization.json.JsonObject,
        served: Map<String, String>,
    ): KeyLogResult = throw E2EError("unsupported")

    suspend fun passwordChange(
        email: String,
        currentPassword: String,
        newPassword: String,
        loginMode: String,
        vault: JsonObject,
    ): PasswordChangePlan
}

/**
 * [E2ECrypto]'nun köprü üzerinden ortak uygulaması: bağımsız değişkenler JSON olarak
 * çekirdeğe gider, yanıt JSON olarak döner. Platform yalnızca [invoke]'u sağlar.
 */
abstract class BridgedE2ECrypto : E2ECrypto {
    protected val json = Json { ignoreUnknownKeys = true }

    /** Çekirdekte [method]'u çalıştırır; başarısızlıkta [E2EError] fırlatır. */
    protected abstract suspend fun invoke(method: String, args: String): String

    private suspend fun run(method: String, args: JsonObject): JsonElement =
        json.parseToJsonElement(invoke(method, args.toString()))

    override suspend fun deriveSecrets(password: String, kdf: KdfParams): DerivedSecrets =
        json.decodeFromJsonElement(
            DerivedSecrets.serializer(),
            run("deriveSecrets", buildJsonObject {
                put("password", password)
                put("kdf", json.encodeToJsonElement(KdfParams.serializer(), kdf))
            }),
        )

    override suspend fun unlock(vault: JsonObject, email: String, lockKey: String): String {
        val out = run("unlock", buildJsonObject {
            put("bundle", vault)
            put("email", email)
            put("lockKey", lockKey)
        }) as? JsonObject ?: throw E2EError("core_error")
        return (out["pkcs8"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: throw E2EError("core_error")
    }

    override suspend fun decrypt(raw: ByteArray, pkcs8: String, certificatePem: String): DecryptedMail =
        json.decodeFromJsonElement(
            DecryptedMail.serializer(),
            run("decrypt", buildJsonObject {
                put("raw", Base64.encode(raw))
                put("pkcs8", pkcs8)
                put("certificatePem", certificatePem)
            }),
        )

    override suspend fun openDraft(raw: ByteArray, pkcs8: String, certificatePem: String): OpenedDraft =
        json.decodeFromJsonElement(
            OpenedDraft.serializer(),
            run("openDraft", buildJsonObject {
                put("raw", Base64.encode(raw))
                put("pkcs8", pkcs8)
                put("certificatePem", certificatePem)
            }),
        )

    override suspend fun encrypt(
        html: String,
        attachments: List<AttachmentData>,
        certificates: List<String>,
        signer: StoredKey?,
        headers: ProtectedHeaders?,
    ): String {
        val out = run("encrypt", buildJsonObject {
            signer?.let {
                put("signer", buildJsonObject {
                    put("pkcs8", it.pkcs8)
                    put("certificatePem", it.certificatePem)
                })
            }
            headers?.let { h ->
                put("headers", buildJsonObject {
                    put("from", h.from)
                    putJsonArray("to") { h.to.forEach { add(it) } }
                    put("subject", h.subject)
                    h.keyLog?.let { k ->
                        put("keyLog", buildJsonObject {
                            put("seq", k.seq)
                            put("hash", k.hash)
                        })
                    }
                })
            }
            put("html", html)
            putJsonArray("attachments") {
                attachments.forEach { a ->
                    add(buildJsonObject {
                        put("filename", a.name)
                        put("contentType", a.mimeType)
                        put("contentBase64", Base64.encode(a.bytes))
                    })
                }
            }
            putJsonArray("certificates") { certificates.forEach { add(it) } }
        }) as? JsonObject ?: throw E2EError("core_error")
        return (out["ciphertext"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: throw E2EError("core_error")
    }

    override suspend fun applyKeyLog(
        state: kotlinx.serialization.json.JsonObject?,
        page: kotlinx.serialization.json.JsonObject,
        served: Map<String, String>,
    ): KeyLogResult {
        val out = run("keyLog", buildJsonObject {
            put("state", state ?: kotlinx.serialization.json.JsonNull)
            put("page", page)
            put("served", buildJsonObject { served.forEach { (k, v) -> put(k, v) } })
        }) as? JsonObject ?: return KeyLogResult(false)
        val ok = (out["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        if (!ok) return KeyLogResult(false)
        return KeyLogResult(
            ok = true,
            state = out["state"] as? JsonObject,
            complete = (out["complete"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
            mismatched = (out["mismatched"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
        )
    }

    override suspend fun checkCertificate(certificatePem: String, address: String): CertCheck =
        json.decodeFromJsonElement(
            CertCheck.serializer(),
            run("checkCertificate", buildJsonObject {
                put("certificatePem", certificatePem)
                put("address", address)
            }),
        )

    override suspend fun passwordChange(
        email: String,
        currentPassword: String,
        newPassword: String,
        loginMode: String,
        vault: JsonObject,
    ): PasswordChangePlan = json.decodeFromJsonElement(
        PasswordChangePlan.serializer(),
        run("passwordChange", buildJsonObject {
            put("email", email)
            put("currentPassword", currentPassword)
            put("newPassword", newPassword)
            put("loginMode", loginMode)
            put("bundle", vault)
        }),
    )
}
