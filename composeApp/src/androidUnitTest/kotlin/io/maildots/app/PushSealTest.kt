package io.maildots.app

import io.maildots.app.push.PushSeal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** T6: sunucunun (Node, seal.ts) mühürlediği bildirim burada açılır; başka anahtarla açılmaz. */
class PushSealTest {
    private val key = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
    private val sealed =
        "CQkJCQkJCQkJCQkJXKfw7c6V41uCDK5PuYfCtriST9FVjZSvGsAyhO9Wah+hgIqDjzNDfBvc0WVqV4y5Xni852f2xCsGGyOtdEIf5QAW7iylBJ/GtoSsBp3TprRkLFRzizul/R+RNelhpSFAdh+n9qwzqTMA8rV1V7H/bBpuu1/rES0o19gjXQ=="

    @Test
    fun opensWhatTheServerSealed() {
        val data = PushSeal.open(key, sealed)!!
        assertEquals("kerem@maildots.com", data["account"])
        assertEquals("Teklif", data["subject"])
        assertEquals("Ayşe <a@b.co>", data["sender"])
        assertEquals("3", data["unseen"])
    }

    @Test
    fun anotherKeyOrATamperedBlobOpensNothing() {
        assertNull(PushSeal.open("CAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg=", sealed))
        val tampered = sealed.replaceRange(20, 21, if (sealed[20] == 'A') "B" else "A")
        assertNull(PushSeal.open(key, tampered))
    }
}
