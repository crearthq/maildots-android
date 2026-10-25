package io.maildots.app.widget

import android.content.Context
import io.maildots.app.InboxSnapshot
import io.maildots.app.WIDGET_ITEMS_ANDROID
import io.maildots.app.data.KeystoreSecureStore

/** Widget'ın ne göstereceği. */
sealed interface WidgetContent {
    /** Hesap yok: "giriş yapın". */
    data object SignedOut : WidgetContent

    /** Uygulama henüz gelen kutusunu yazmadı (yeni kurulum/güncelleme): "uygulamayı açın". */
    data object NotYet : WidgetContent

    data class Inbox(val snapshot: InboxSnapshot) : WidgetContent
}

/**
 * Widget verisi: şifreli depoda (AndroidKeyStore, [KeystoreSecureStore]); düz ayar dosyasına
 * yazılmaz. Yalnızca gelen kutusu özeti ([InboxSnapshot]: gönderen, konu, tarih, okundu) tutulur;
 * gövde, önizleme, şifre ya da oturum yok. Yazan tek taraf uygulama sürecidir; widget yalnızca okur.
 */
object WidgetStore {
    private const val INBOX = "widget.inbox"
    private const val SIGNED_OUT = "widget.signedOut"
    private val lock = Any()

    private fun store(context: Context) = KeystoreSecureStore(context)

    fun read(context: Context): WidgetContent = synchronized(lock) {
        val s = store(context)
        s.get(INBOX)?.let(InboxSnapshot::fromJson)?.let { return WidgetContent.Inbox(it) }
        if (s.get(SIGNED_OUT) != null) WidgetContent.SignedOut else WidgetContent.NotYet
    }

    fun write(context: Context, snapshot: InboxSnapshot) = synchronized(lock) {
        val s = store(context)
        s.put(INBOX, snapshot.toJson())
        s.remove(SIGNED_OUT)
    }

    /** Hesap kalmadı: özet silinir, widget "giriş yapın" der. */
    fun clear(context: Context) = synchronized(lock) {
        val s = store(context)
        s.remove(INBOX)
        s.put(SIGNED_OUT, "1")
    }

    /**
     * Özet artık cihazda olmayan bir hesaba aitse silinir (hesap kaldırıldı, başka hesap kaldı).
     * Dönüş: silindi mi.
     */
    fun dropUnlessAccountIn(context: Context, accounts: Set<String>): Boolean = synchronized(lock) {
        val s = store(context)
        val snapshot = s.get(INBOX)?.let(InboxSnapshot::fromJson) ?: return false
        if (accounts.any { it.equals(snapshot.account, ignoreCase = true) }) return false
        s.remove(INBOX)
        if (accounts.isEmpty()) s.put(SIGNED_OUT, "1")
        true
    }

    /**
     * Anlık bildirimle gelen yeni ileti, uygulama açılmadan widget'a eklenir (ağa çıkılmaz).
     * Yalnızca özetin hesabı ve gelen kutusu için; bildirimin önizlemesi (snippet) ALINMAZ.
     * Dönüş: özet değişti mi.
     */
    fun addFromPush(context: Context, data: Map<String, String>, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            val s = store(context)
            val snapshot = s.get(INBOX)?.let(InboxSnapshot::fromJson) ?: return false
            val item = pushItem(snapshot.account, data, now) ?: return false
            val unread = data["unseen"]?.toIntOrNull()?.takeIf { it >= 0 }
            val next = snapshot.withNew(item, unread, WIDGET_ITEMS_ANDROID)
            if (next == snapshot) return false
            s.put(INBOX, next.toJson())
            true
        }
}

/**
 * Bildirim verisinden widget satırı; özetin hesabına ve gelen kutusuna ait değilse null.
 * Gönderen "Ad Soyad <adres>" biçimindedir (bildirimdeki gibi görünen ad, yoksa adres).
 */
internal fun pushItem(account: String, data: Map<String, String>, now: Long): InboxSnapshot.Item? {
    if (!data["account"].orEmpty().equals(account, ignoreCase = true)) return null
    val folder = data["folder"].orEmpty().ifBlank { "INBOX" }
    if (!folder.equals("INBOX", ignoreCase = true)) return null
    val uid = data["uid"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val raw = data["sender"].orEmpty()
    val address = raw.substringAfter('<', raw).substringBefore('>').trim()
    val name = raw.substringBefore('<').trim().trim('"')
    return InboxSnapshot.Item(
        uid = uid,
        folder = folder,
        from = name.ifBlank { address },
        subject = data["subject"].orEmpty(),
        date = now,
        seen = false,
        encrypted = data["encrypted"] == "1" || data["encrypted"] == "true",
    )
}
