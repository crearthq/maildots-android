package io.maildots.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import io.maildots.app.InboxSnapshot
import io.maildots.app.MainActivity
import io.maildots.app.R
import io.maildots.app.push.PushExtras
import java.text.DateFormat
import java.util.Date

/**
 * Ana ekran widget'ı (klasik RemoteViews). Glance kullanılmadı: WorkManager'ı getirir, o da
 * manifeste yeni izinler (RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE...) ve arka plan işleri ekler.
 * Burada ek bağımlılık, izin, zamanlayıcı yok: widget yalnızca uygulamanın yazdığı şifreli özeti
 * okur ([WidgetStore]); ağa çıkmaz, kendiliğinden yenilenmez (updatePeriodMillis=0).
 *
 * Alıcı, AppWidget çatısı gereği dışa açıktır; yalnızca standart APPWIDGET_UPDATE süzgeci vardır
 * ve yalnızca bu sağlayıcıya ait widget kimlikleri çizilir.
 */
class InboxWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderAsync(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        renderAsync(context, manager, intArrayOf(id))
    }

    /** Keystore çözmesi ana iş parçacığını tutmasın: yayın açık tutulur, çizim arka planda yapılır. */
    private fun renderAsync(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                InboxWidget.render(app, manager, ids)
            } finally {
                pending.finish()
            }
        }.apply { name = "maildots-widget"; isDaemon = true }.start()
    }
}

object InboxWidget {
    /** Widget'tan açılan etkinlik isteklerinin eylemi (bildirim istekleriyle karışmasın). */
    const val ACTION_OPEN = "io.maildots.app.widget.OPEN"

    /** Bütün widget'ları yeniden çizer (uygulama özeti yazınca). Widget yoksa hiçbir şey yapmaz. */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, InboxWidgetProvider::class.java)) }.getOrNull()
        if (ids == null || ids.isEmpty()) return
        render(context, manager, ids)
    }

    internal fun render(context: Context, manager: AppWidgetManager, requested: IntArray) {
        // Dışarıdan gelen yayın başka kimlikler taşıyabilir: yalnızca bizim widget'larımız.
        val ours = runCatching { manager.getAppWidgetIds(ComponentName(context, InboxWidgetProvider::class.java)) }
            .getOrNull()?.toSet() ?: return
        val ids = requested.filter { it in ours }
        if (ids.isEmpty()) return
        val content = runCatching { WidgetStore.read(context) }.getOrDefault(WidgetContent.NotYet)
        for (id in ids) {
            val rows = rowsFor(runCatching { manager.getAppWidgetOptions(id) }.getOrNull())
            runCatching { manager.updateAppWidget(id, views(context, content, rows)) }
        }
    }

    /** Widget yüksekliğine sığan satır sayısı (en çok 4). */
    private fun rowsFor(options: Bundle?): Int {
        // Dikey konumda yükseklik MAX_HEIGHT'tır; bilinmiyorsa varsayılan boyut (4 satır).
        val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
        if (height <= 0) return MAX_ROWS
        return ((height - CHROME_DP) / ROW_DP).coerceIn(1, MAX_ROWS)
    }

    internal fun views(context: Context, content: WidgetContent, rows: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_inbox)
        views.removeAllViews(R.id.widget_rows)
        val snapshot = (content as? WidgetContent.Inbox)?.snapshot
        // Başlık (ve boş alan): hesabın gelen kutusu; özet yoksa uygulama açılır.
        val headerIntent = openIntent(context, REQUEST_HEADER, snapshot?.account, inbox = true)
        views.setOnClickPendingIntent(android.R.id.background, headerIntent)
        views.setOnClickPendingIntent(R.id.widget_header, headerIntent)
        views.setTextViewText(R.id.widget_account, snapshot?.account.orEmpty())
        val unread = snapshot?.unread ?: 0
        if (unread > 0) {
            views.setTextViewText(R.id.widget_unread, if (unread > 999) "999+" else unread.toString())
            views.setViewVisibility(R.id.widget_unread, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_unread, View.GONE)
        }
        val message = when {
            content is WidgetContent.SignedOut -> context.getString(R.string.widget_sign_in)
            snapshot == null -> context.getString(R.string.widget_open_app)
            snapshot.items.isEmpty() -> context.getString(R.string.widget_empty)
            else -> null
        }
        if (message != null || snapshot == null) {
            views.setTextViewText(R.id.widget_message, message.orEmpty())
            views.setViewVisibility(R.id.widget_message, View.VISIBLE)
            views.setViewVisibility(R.id.widget_rows, View.GONE)
            return views
        }
        views.setViewVisibility(R.id.widget_message, View.GONE)
        views.setViewVisibility(R.id.widget_rows, View.VISIBLE)
        snapshot.items.take(rows).forEachIndexed { index, item ->
            views.addView(R.id.widget_rows, row(context, snapshot.account, item, REQUEST_ROW + index))
        }
        return views
    }

    private fun row(context: Context, account: String, item: InboxSnapshot.Item, request: Int): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_inbox_row)
        val unread = !item.seen
        row.setViewVisibility(R.id.widget_row_dot, if (unread) View.VISIBLE else View.INVISIBLE)
        row.setTextViewText(R.id.widget_row_from, styled(item.from, bold = unread))
        // Şifreli iletide de liste gibi yalnızca konu (gövde ya da önizleme hiç tutulmaz).
        row.setViewVisibility(R.id.widget_row_lock, if (item.encrypted) View.VISIBLE else View.GONE)
        row.setTextViewText(R.id.widget_row_subject, item.subject.ifBlank { context.getString(R.string.widget_no_subject) })
        row.setTextViewText(R.id.widget_row_time, styled(time(context, item.date), bold = unread))
        row.setTextColor(R.id.widget_row_time, context.getColor(if (unread) R.color.widget_text else R.color.widget_text_secondary))
        row.setOnClickPendingIntent(R.id.widget_row, openIntent(context, request, account, inbox = false, item = item))
        return row
    }

    private fun styled(text: String, bold: Boolean): CharSequence {
        if (!bold || text.isEmpty()) return text
        return SpannableString(text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    /** Bugünse saat, değilse gün + kısa ay (uygulamadaki listeyle aynı). */
    private fun time(context: Context, millis: Long): String =
        if (DateUtils.isToday(millis)) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
        else DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

    /**
     * Uygulamayı açan istek: açık bileşen (yalnızca MainActivity), değiştirilemez (IMMUTABLE).
     * Ek veriler MainActivity'de yeniden doğrulanır (hesap kayıtlı mı, uid sayı mı).
     */
    private fun openIntent(
        context: Context,
        request: Int,
        account: String?,
        inbox: Boolean,
        item: InboxSnapshot.Item? = null,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (account != null) {
                putExtra(PushExtras.ACCOUNT, account)
                if (inbox || item == null) {
                    putExtra(PushExtras.INBOX, true)
                } else {
                    putExtra(PushExtras.FOLDER, item.folder)
                    putExtra(PushExtras.UID, item.uid)
                }
            }
        }
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private const val MAX_ROWS = 4
    /** Kenar boşlukları + başlık (dp). */
    private const val CHROME_DP = 2 * 14 + 24 + 6
    private const val ROW_DP = 24
    private const val REQUEST_HEADER = 0
    private const val REQUEST_ROW = 1
}
