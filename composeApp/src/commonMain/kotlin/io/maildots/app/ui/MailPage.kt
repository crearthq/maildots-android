package io.maildots.app.ui

/**
 * Posta gövdesini saran HTML sayfası (Android ve masaüstü ortak; Android'deki eski hâliyle
 * birebir aynı çıktı). CSP betiği, çerçeveyi ve ağı yasaklar; [imageOrigin] verilirse yalnızca
 * o kökenin görselleri (webmail görsel vekili) yüklenebilir.
 */
internal fun mailPage(
    body: String,
    light: Boolean,
    heading: String? = null,
    imageOrigin: String? = null,
    /** Uygulamanın zemin rengi (#RRGGBB): gövde ekranla aynı zemine oturur. Yoksa saf beyaz / koyu. */
    background: String? = null,
): String {
    val fg = if (light) "#090c1d" else "#ededee"
    val bg = background ?: if (light) "#ffffff" else "#18181a"
    val link = if (light) "#2a38ec" else "#8f98ff"
    val rule = if (light) "#cfccc1" else "#45454a"
    val title = heading?.let { "<h2 style=\"font-weight:500;margin:0 0 16px\">${escapeHtml(it)}</h2>" }.orEmpty()
    return """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:${imageOrigin?.let { " $it" }.orEmpty()}; font-src data:">
        <style>
          html,body{margin:0;padding:0;background:$bg;color:$fg;font:16px/1.55 sans-serif;word-wrap:break-word;overflow-wrap:anywhere}
          body{padding:16px 20px}
          a{color:$link} img{max-width:100%;height:auto} table{max-width:100%}
          blockquote{margin:0 0 0 8px;padding-left:12px;border-left:2px solid $rule}
          pre{white-space:pre-wrap;font:inherit}
        </style></head><body>$title$body</body></html>
    """.trimIndent()
}

/** Android'in TextUtils.htmlEncode'u ile aynı kaçışlar. */
internal fun escapeHtml(text: String): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '\'' -> append("&#39;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}
