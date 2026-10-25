package io.maildots.app

import android.content.Intent

/** Yayın derlemesi: sunucu değiştirilemez; her zaman canlı MailDots, her zaman TLS. */
internal object DevServer {
    val local: Boolean = false
    val webBase: String? = null
    fun configure(intent: Intent?) = Unit
    fun webApi(): io.maildots.app.web.HttpAccountApi? = null
    val widgetDemoActive: Boolean = false
    fun widgetDemo(context: android.content.Context, intent: Intent?) = Unit
}
