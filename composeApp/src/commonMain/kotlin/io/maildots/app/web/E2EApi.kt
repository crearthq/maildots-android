package io.maildots.app.web

import io.maildots.app.e2e.KdfParams
import kotlinx.serialization.json.JsonObject

/** Giriş öncesi keşif: adres bu sunucuda mı, şifre nasıl gönderilecek ([kdf] varsa giriş koruması açık). */
data class Prelogin(val hosted: Boolean, val kdf: KdfParams?)

/**
 * Kutunun anahtar kasası (sunucuda yalnızca açık sertifika ve kilitli özel anahtar kopyaları durur).
 * [vault] çekirdeğe olduğu gibi verilen JSON'dur.
 */
data class WebVault(
    val owner: String,
    val vault: JsonObject,
    val certificatePem: String,
    val kdf: KdfParams,
    /** "password" | "derived" | "switching" */
    val loginMode: String,
    val encryptionEnabled: Boolean,
)

/** Cihazda şifrelenmiş gönderim: sunucuya yalnızca şifreli veri gider. */
data class EncryptedSend(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val from: String? = null,
    val subject: String = "",
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val draftUid: Long? = null,
    /** To + Cc + gönderen için şifreli (Gönderilenler kopyası aynı baytlardır). */
    val ciphertext: String,
    /** Her gizli alıcıya, yalnızca onun açabileceği ayrı kopya. */
    val bccCopies: List<Pair<String, String>> = emptyList(),
    /** Dış başlıklar (şifreli içeriğin dışında): önem ve okundu bilgisi isteği. */
    val importance: String? = null,
    val readReceipt: Boolean = false,
) {
    override fun toString(): String = "EncryptedSend(to=${to.size})"
}

/** Kendine şifreli taslak (yalnızca gönderen açabilir). */
data class EncryptedDraft(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val from: String? = null,
    val subject: String = "",
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val draftUid: Long? = null,
    val ciphertext: String,
) {
    override fun toString(): String = "EncryptedDraft(to=${to.size})"
}

/** Kayıtlı posta şifrelemesi sunucuda kapatılmış / zayıflatılmış / başka anahtara çevrilmiş bulundu (T3). */
data class EncryptionAlert(val atMillis: Long, val kind: String, val restored: Boolean)

/**
 * Şifreleme denetiminin yanıtı: [protectedSinceMillis] şifrelemenin bu kasanın anahtarına
 * açıldığı an (sonrasında düz saklanan ileti işaretlenir), [alert] görülmemiş kurcalama.
 */
data class EncryptionGuard(val protectedSinceMillis: Long?, val alert: EncryptionAlert?)

/**
 * Uçtan uca şifrelemenin hesap servisi uçları (yalnızca yeni posta sistemi, maildots.com).
 * Hepsi kutunun oturumuyla çalışır; sunucu düz metni hiç görmez.
 */
interface E2EApi {
    /** Oturumsuz keşif; ağ hatasında [WebError.Network]. */
    suspend fun prelogin(email: String): Prelogin

    /** Kutunun kasası; kasa yoksa (ya da bu sunucuda şifreleme yoksa) null. */
    suspend fun vault(session: String): WebVault?

    /**
     * Kayıtlı posta şifrelemesi hâlâ bu kutunun anahtarına açık mı? Değilse sunucu
     * oturumun sırrıyla yeniden açar (webmail'deki T3 denetimi). Desteklenmiyorsa null.
     */
    suspend fun encryptionGuard(session: String): EncryptionGuard? = null

    /** Kullanıcı kurcalama uyarısını gördü (yalnız şifreleme yeniden açıldıysa kabul edilir). */
    suspend fun acknowledgeEncryptionAlert(session: String) {}

    /**
     * T2: bu YENİ kutu uçtan uca şifrelemeyi henüz kurmadı mı (kurulum yalnız web postada;
     * kullanıcı bilerek atlayabilir)? Mevcut kutular için her zaman false.
     */
    suspend fun onboardingRequired(session: String): Boolean = false

    /** T4: anahtar şeffaflığı kaydının [after] sonrasındaki sayfası (herkese açık); yoksa null. */
    suspend fun keyLog(session: String, after: Int): kotlinx.serialization.json.JsonObject? = null

    /** İletinin sunucudaki ham (şifreli) hâli. */
    suspend fun rawMessage(session: String, folder: String, uid: Long): ByteArray

    /** Şifrelemesi açık MailDots adreslerinin sertifikaları (adres → PEM); olmayanlar listede yoktur. */
    suspend fun keyDirectory(session: String, addresses: List<String>): Map<String, String>

    /** Gönderir; ulaştırılamayan gizli alıcıları döndürür. Anahtar uyuşmazlığında [WebError.Invalid]. */
    suspend fun sendEncrypted(session: String, message: EncryptedSend): List<String>

    /** [sendEncrypted]; ek başlıklar reddedilirse onlarsız bir kez daha (ikinci değer true). */
    suspend fun sendEncryptedCompat(session: String, message: EncryptedSend): Pair<List<String>, Boolean> =
        sendEncrypted(session, message) to false

    suspend fun saveEncryptedDraft(session: String, message: EncryptedDraft): WebDraftSaved

    /** Kasası olan kutuda şifre değişimi (kilitli kopya cihazda yenilendi; bkz. E2ECrypto.passwordChange). */
    suspend fun changePasswordWithVault(
        session: String,
        currentSecret: String,
        newSecret: String,
        vault: JsonObject,
        target: String,
    )
}
