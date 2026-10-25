package io.maildots.app

import android.content.Intent

/**
 * YALNIZCA hata ayıklama derlemesi: uygulama, geliştirme makinesindeki yerel webmail'e
 * (düz bağlantı) yönlendirilebilir:
 *   adb shell am start -n io.maildots.app/.MainActivity --es devweb http://10.0.2.2:4319
 * Bu dosya yayın derlemesine GİRMEZ (androidRelease'teki karşılığı her zaman canlı sunucu, TLS).
 */
internal object DevServer {
    /** Yerel webmail, ör. http://10.0.2.2:4319. Yalnızca geliştirme makinesi kabul edilir. */
    @Volatile private var web: String? = null

    /** Yerel webmail'e yönlendirildi mi (bildirim kaydı yapılmaz). */
    val local: Boolean get() = web != null

    /** Yerel webmail adresi (görsel vekili ve bağlantılar için), yoksa null. */
    val webBase: String? get() = web

    fun configure(intent: Intent?) {
        intent?.getStringExtra("devweb")?.takeIf { it.startsWith("http://10.0.2.2:") }?.let { web = it }
    }

    fun webApi(): io.maildots.app.web.HttpAccountApi? = web?.let {
        io.maildots.app.web.HttpAccountApi(it, allowInsecureForTests = true, userAgent = "MailDots-Android-Dev")
    }

    /** Widget örnek verisi gösteriliyor: uygulama gerçek özeti yazmaz (ekran görüntüsü için). */
    @Volatile var widgetDemoActive = false
        private set

    /**
     * Widget'a örnek veri (gerçek posta değil), ekran görüntüsü için:
     *   adb shell am start -n io.maildots.app/.MainActivity --es widgetDemo inbox|empty|signedout
     */
    fun widgetDemo(context: android.content.Context, intent: Intent?) {
        val mode = intent?.getStringExtra("widgetDemo") ?: return
        widgetDemoActive = true
        val store = io.maildots.app.widget.WidgetStore
        val now = System.currentTimeMillis()
        val tr = java.util.Locale.getDefault().language == "tr"
        fun item(uid: Long, from: String, subject: String, ago: Long, seen: Boolean, encrypted: Boolean = false) =
            io.maildots.app.InboxSnapshot.Item(uid, "INBOX", from, subject, now - ago, seen, encrypted)
        when (mode) {
            "signedout" -> store.clear(context)
            "empty" -> store.write(context, io.maildots.app.InboxSnapshot("ad@sirketiniz.com", 0, emptyList()))
            else -> store.write(
                context,
                io.maildots.app.InboxSnapshot(
                    "ad@sirketiniz.com", 2,
                    listOf(
                        item(5, "Ayşe Yılmaz", if (tr) "Toplantı notları" else "Meeting notes", 600_000, seen = false),
                        item(4, "Mehmet Kaya", if (tr) "Teklif hakkında" else "About the offer", 3_600_000, seen = false, encrypted = true),
                        item(3, if (tr) "Destek" else "Support", if (tr) "Talebiniz yanıtlandı" else "Your request was answered", 7_200_000, seen = true),
                        item(2, "Elif Demir", if (tr) "Fatura" else "Invoice", 90_000_000, seen = true),
                    ),
                ),
            )
        }
        io.maildots.app.widget.InboxWidget.refresh(context)
    }
}
