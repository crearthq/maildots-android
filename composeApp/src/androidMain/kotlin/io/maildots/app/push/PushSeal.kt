package io.maildots.app.push

import io.maildots.app.data.AccountRepository
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * T6 — bildirim içeriği Google'dan (Firebase) düz geçmez. Bu kurulum kendi 256 bit
 * anahtarını üretir, bildirim kaydında yalnız bizim sunucumuza verir; sunucu gönderen,
 * konu, hesap ve okunmamış sayısını bu anahtarla (AES-256-GCM) mühürler. Firebase
 * yalnızca okunamayan bir blob taşır; burada açılır.
 *
 * Biçim: base64( iv[12] | şifreli metin | etiket[16] ), AAD "maildots/push/v1".
 */
object PushSeal {
    private const val AAD = "maildots/push/v1"

    /** Kurulumun anahtarı (yoksa üretilip şifreli depoya yazılır). */
    fun ensureKey(repository: AccountRepository): String =
        repository.pushKey ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.getEncoder().encodeToString(it) }
            .also { repository.pushKey = it }

    /** Mühürlü veriyi açar; açılamazsa (yanlış anahtar, bozuk veri) null. */
    fun open(keyB64: String, sealed: String): Map<String, String>? = runCatching {
        val key = Base64.getDecoder().decode(keyB64)
        val raw = Base64.getDecoder().decode(sealed)
        if (key.size != 32 || raw.size < 12 + 16) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(raw, 12, raw.size - 12)
        val o: JsonObject = Json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonObject
        o.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }
    }.getOrNull()
}
