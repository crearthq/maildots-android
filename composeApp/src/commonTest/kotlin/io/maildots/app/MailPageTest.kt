package io.maildots.app

import io.maildots.app.ui.escapeHtml
import io.maildots.app.ui.mailPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailPageTest {
    @Test
    fun escapesLikeAndroidTextUtils() {
        assertEquals("&lt;b&gt; &amp; &#39;x&#39; &quot;y&quot;", escapeHtml("<b> & 'x' \"y\""))
    }

    @Test
    fun cspBlocksScriptsAndOnlyAllowsGivenImageOrigin() {
        val page = mailPage("<p>hi</p>", light = true, heading = "<s>", imageOrigin = "https://webmail.maildots.com")
        assertTrue("default-src 'none'; style-src 'unsafe-inline'; img-src data: https://webmail.maildots.com; font-src data:" in page)
        assertTrue("<h2 style=\"font-weight:500;margin:0 0 16px\">&lt;s&gt;</h2><p>hi</p>" in page)
        val closed = mailPage("x", light = false)
        assertTrue("img-src data:; font-src data:" in closed)
        assertFalse("script-src" in closed)
        assertTrue("background:#18181a" in closed)
    }
}
