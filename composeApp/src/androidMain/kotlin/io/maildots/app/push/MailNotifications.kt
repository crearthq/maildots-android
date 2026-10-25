package io.maildots.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.maildots.app.MailDotsApp
import io.maildots.app.MainActivity
import io.maildots.app.R
import io.maildots.app.mail.AvatarStyle
import java.util.Locale
import org.json.JSONObject

/** Bildirime ya da widget'a dokununca açılacak iletiyi taşıyan anahtarlar (MainActivity doğrular). */
object PushExtras {
    const val ACCOUNT = "maildots.push.account"
    const val FOLDER = "maildots.push.folder"
    const val UID = "maildots.push.uid"
    /** Widget başlığı: iletiyi değil hesabın gelen kutusu listesini aç. */
    const val INBOX = "maildots.push.inbox"
}

/**
 * Yeni posta bildirimleri.
 *
 * Sunucu yalnızca VERİ gönderir (gönderen, konu, kısa özet, klasör, uid); bildirimi
 * uygulama oluşturur. Böylece hesaba göre gruplanır, kilit ekranında içerik gizlenir
 * ve dokununca doğrudan ilgili ileti açılır.
 */
object MailNotifications {
    private const val CHANNEL = "new_mail"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val turkish = Locale.getDefault().language == "tr"
        val channel = NotificationChannel(
            CHANNEL,
            if (turkish) "Yeni e-postalar" else "New emails",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = if (turkish) "Gelen kutunuza yeni e-posta geldiğinde" else "When new email arrives in your inbox"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Simge rozeti: hesabın gelen kutusundaki okunmamış sayısı. Başlatıcılar rozet
     * sayısını bildirimlerden toplar (her bildirim `number`, yoksa 1). Bu yüzden
     * hesabın EN YENİ bildirimi "okunmamış − diğer açık bildirimler" sayısını taşır;
     * toplam, bütün hesapların gelen kutusu okunmamışına eşit olur. Okunmamış
     * kalmayan hesabın bildirimleri kaldırılır. (Yalnız nokta gösteren başlatıcılarda
     * sayı görünmez; bu Android'in sınırı.)
     */
    private fun prefs(context: Context) = context.getSharedPreferences("maildots_badge", Context.MODE_PRIVATE)

    /**
     * Son bildirimin içeriği (gönderen, konu, önizleme) yalnız şifreli depoda durur;
     * düz ayar dosyasında yalnız sayılar kalır. Eski sürümün düz kopyası ilk erişimde silinir.
     */
    private fun secure(context: Context) = io.maildots.app.data.KeystoreSecureStore(context)

    private fun lastKey(account: String) = "notif.last:" + account.lowercase()

    private fun dropPlainLast(context: Context) {
        val p = prefs(context)
        val stale = p.all.keys.filter { it.startsWith("last:") }
        if (stale.isNotEmpty()) p.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    /** Cihazdan kaldırılan hesapların bildirim izlerini (sayı + son bildirim) siler. */
    fun forgetAccountsExcept(context: Context, keep: Set<String>) {
        dropPlainLast(context)
        val p = prefs(context)
        val wanted = keep.map { it.lowercase() }.toSet()
        val gone = p.all.keys.filter { it.startsWith("unread:") }.map { it.removePrefix("unread:") }
            .filter { it.lowercase() !in wanted }
        if (gone.isEmpty()) return
        p.edit().apply { gone.forEach { remove("unread:$it") } }.apply()
        val store = secure(context)
        gone.forEach { runCatching { store.remove(lastKey(it)) } }
    }

    private fun unreadOf(context: Context, account: String): Int? =
        prefs(context).getInt("unread:$account", -1).takeIf { it >= 0 }

    /** Hesabın açık (özet olmayan) bildirim kimlikleri. */
    private fun activeChildren(context: Context, account: String): List<Int> =
        runCatching {
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .filter { it.notification.group == account && it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }
                .map { it.id }
        }.getOrDefault(emptyList())

    /**
     * Uygulama içindeki okunmamış sayıları rozete yansıtır (gelen kutusu yenilenince,
     * ileti okununca). Yalnız haritadaki hesaplara dokunur.
     */
    fun syncUnread(context: Context, unreadByAccount: Map<String, Int>) {
        val p = prefs(context)
        val manager = NotificationManagerCompat.from(context)
        for ((account, unread) in unreadByAccount) {
            val before = unreadOf(context, account)
            p.edit().putInt("unread:$account", unread).apply()
            if (before == unread) continue
            val children = activeChildren(context, account)
            if (children.isEmpty()) continue
            if (unread == 0) {
                // Hepsi okundu: bu hesabın bildirimleri artık eskidi.
                runCatching {
                    children.forEach { manager.cancel(it) }
                    manager.cancel(account.hashCode())
                }
                continue
            }
            // En yeni bildirimi yeni sayıyla sessizce yeniden yayınla.
            val last = runCatching { secure(context).get(lastKey(account)) }.getOrNull() ?: continue
            val data = runCatching {
                val json = JSONObject(last)
                json.keys().asSequence().associateWith { json.getString(it) }
            }.getOrNull() ?: continue
            if (notificationId(data) in children) show(context, data, silent = true)
        }
    }

    private fun notificationId(data: Map<String, String>): Int =
        (data["account"].orEmpty() + "/" + data["folder"].orEmpty().ifBlank { "INBOX" } + "/" + (data["uid"]?.toLongOrNull() ?: 0L)).hashCode()

    fun show(context: Context, data: Map<String, String>, silent: Boolean = false) {
        val account = data["account"]?.takeIf { it.isNotBlank() } ?: return
        if (!canNotify(context)) return
        if (!silent) {
            data["unseen"]?.toIntOrNull()?.let { prefs(context).edit().putInt("unread:$account", it).apply() }
            dropPlainLast(context)
            runCatching { secure(context).put(lastKey(account), JSONObject(data).toString()) }
        }
        ensureChannel(context)
        val turkish = Locale.getDefault().language == "tr"
        val folder = data["folder"].orEmpty().ifBlank { "INBOX" }
        val uid = data["uid"]?.toLongOrNull() ?: 0L
        // "Ad Soyad <adres>" biçiminden görünen ad ve adres.
        val raw = data["sender"].orEmpty()
        val address = raw.substringAfter('<', raw).substringBefore('>').trim().lowercase()
        val name = raw.substringBefore('<').trim().trim('"')
        val from = name.ifBlank { address }.ifBlank { if (turkish) "Yeni e-posta" else "New email" }
        val subject = data["subject"].orEmpty().ifBlank { if (turkish) "(konu yok)" else "(no subject)" }
        val snippet = data["snippet"].orEmpty()

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(PushExtras.ACCOUNT, account)
            putExtra(PushExtras.FOLDER, folder)
            putExtra(PushExtras.UID, uid)
        }
        val id = notificationId(data)
        // Rozet: bu bildirim, hesabın diğer açık bildirimleri dışında kalan okunmamışları taşır.
        val number = unreadOf(context, account)?.let { unread ->
            maxOf(1, unread - activeChildren(context, account).count { it != id })
        }
        val pending = PendingIntent.getActivity(
            context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val color = 0xFF2A38EC.toInt()
        // Sağda gönderenin avatarı (uygulamadaki gelen kutusuyla aynı renk ve baş harfler).
        val avatar = senderAvatar(context, name, address.ifBlank { from })
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_maildots)
            .setLargeIcon(avatar)
            .setColor(color)
            .setContentTitle(from)
            .setContentText(subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (snippet.isBlank()) subject else subject + "\n" + snippet))
            .setSubText(account)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Kilit ekranında içerik gösterilmez; yalnızca "yeni e-posta" ve hesap görünür.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_maildots)
                    .setColor(color)
                    .setContentTitle(if (turkish) "Yeni e-posta" else "New email")
                    .setContentText(account)
                    .build(),
            )
            .setGroup(account)
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .apply { if (number != null) setNumber(number) }
            .setContentIntent(pending)
            .build()
        val summary = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_maildots)
            .setColor(color)
            .setSubText(account)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(account))
            .setGroup(account)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setSilent(silent)
            .build()
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.notify(id, notification)
            manager.notify(account.hashCode(), summary)
        }
    }
}

/** Gönderen avatarı bit eşlemi: renkli daire üstünde beyaz baş harfler ([AvatarStyle]). */
private fun senderAvatar(context: Context, name: String, key: String): Bitmap {
    val size = (64 * context.resources.displayMetrics.density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AvatarStyle.colorFor(key).toInt() }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, fill)
    val text = AvatarStyle.initials(name, key)
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = size * if (text.length > 1) 0.36f else 0.42f
    }
    val y = size / 2f - (ink.descent() + ink.ascent()) / 2f
    canvas.drawText(text, size / 2f, y, ink)
    return bitmap
}

/** Firebase'den gelen iletileri ve kayıt belirteci değişikliklerini karşılar. */
class MailPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        (application as? MailDotsApp)?.controller?.onPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val raw = message.data
        if (raw["type"] != "new_mail") return
        val app = application as? MailDotsApp ?: return
        val controller = app.controller
        val accounts = controller.state.value.accounts
        val data: Map<String, String> = when {
            // Mühürlü (T6): içerik yalnız bu kurulumun anahtarıyla açılır.
            raw["enc"] != null -> {
                val key = app.repository.pushKey ?: return
                PushSeal.open(key, raw["enc"]!!) ?: return
            }
            // İçeriksiz bildirim (anahtar henüz kayıtlı değil): genel "yeni e-posta".
            else -> mapOf("type" to "new_mail", "account" to (accounts.firstOrNull()?.email ?: return))
        }
        // Bu cihazda artık bulunmayan bir hesap için bildirim gösterilmez.
        val account = data["account"] ?: return
        if (accounts.none { it.email.equals(account, ignoreCase = true) }) return
        MailNotifications.show(this, data)
        // Ana ekran widget'ı: yeni ileti (gönderen + konu) uygulama açılmadan eklenir; ağ yok.
        runCatching {
            if (io.maildots.app.widget.WidgetStore.addFromPush(this, data)) io.maildots.app.widget.InboxWidget.refresh(this)
        }
    }
}
