package io.maildots.app

import io.maildots.app.widget.pushItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WidgetPushItemTest {
    private val data = mapOf(
        "type" to "new_mail",
        "account" to "A@B.co",
        "folder" to "INBOX",
        "uid" to "77",
        "sender" to "\"Ayşe Yılmaz\" <ayse@x.test>",
        "subject" to "Toplantı",
        "snippet" to "gizli-onizleme",
        "unseen" to "3",
    )

    @Test
    fun pushBecomesARowWithoutThePreview() {
        val item = pushItem("a@b.co", data, now = 1234)!!
        assertEquals(77L, item.uid)
        assertEquals("Ayşe Yılmaz", item.from)
        assertEquals("Toplantı", item.subject)
        assertEquals(1234L, item.date)
        assertFalse(item.seen)
        assertFalse(item.toString().contains("gizli"))
        assertEquals("ayse@x.test", pushItem("a@b.co", data + ("sender" to "ayse@x.test"), 0)!!.from)
    }

    @Test
    fun otherAccountsFoldersAndBadUidsAreIgnored() {
        assertNull(pushItem("c@d.co", data, 0))
        assertNull(pushItem("a@b.co", data + ("folder" to "Spam"), 0))
        assertNull(pushItem("a@b.co", data + ("uid" to "x"), 0))
        assertNull(pushItem("a@b.co", data - "uid", 0))
    }
}
