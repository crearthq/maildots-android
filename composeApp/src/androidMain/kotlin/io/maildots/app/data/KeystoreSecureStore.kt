package io.maildots.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore destekli şifreli depo.
 *
 * - Anahtar: AES-256-GCM, AndroidKeyStore içinde üretilir ve dışa AKTARILAMAZ
 *   (uygulama bile ham anahtarı okuyamaz; destekleyen cihazlarda donanımda durur).
 * - Her değer yeni bir rastgele IV ile şifrelenir; diske yalnızca IV + şifreli metin
 *   yazılır. Kayıt adı ek doğrulama verisi (AAD) olarak bağlanır: bir kaydın şifreli
 *   metni başka bir kayda kopyalanırsa çözülmez.
 * - Yedeklemeye dahil değildir (manifest: allowBackup=false + dataExtractionRules).
 */
class KeystoreSecureStore(context: Context) : SecureStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // Anahtar geçersizleşmiş ya da veri bozulmuş: kaydı at (kullanıcı yeniden girer).
            prefs.edit().remove(key).apply()
            null
        }
    }

    /**
     * Anahtar kalıcı olarak geçersizleştiyse (işletim sistemi anahtar deposunu sıfırladı,
     * cihaz geri yüklendi) eskisi silinir, yenisi üretilir. Eski kayıtlar zaten çözülemez:
     * hepsi atılır, kullanıcı yeniden giriş yapar (uygulama çökmez).
     */
    private fun encryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key())
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
            prefs.edit().clear().apply()
            cipher.init(Cipher.ENCRYPT_MODE, key())
        }
        return cipher
    }

    override fun put(key: String, value: String) {
        val cipher = encryptCipher()
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val out = cipher.iv + encrypted
        prefs.edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val PREFS = "maildots.secure"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "maildots.store.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
