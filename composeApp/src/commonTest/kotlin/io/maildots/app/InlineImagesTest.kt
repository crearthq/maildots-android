package io.maildots.app

import io.maildots.app.mail.AttachmentData
import io.maildots.app.mail.InlineImages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InlineImagesTest {
    @Test
    fun withoutInlineImagesNothingChanges() {
        val files = listOf(AttachmentData("a.pdf", "application/pdf", byteArrayOf(1)))
        val (html, rest) = InlineImages.forEncryption("<p>x</p>", files)
        assertEquals("<p>x</p>", html)
        assertSame(files, rest)
    }

    @Test
    fun cidImagesAreEmbeddedForTheEncryptedCore() {
        val image = AttachmentData("p.png", "image/png", byteArrayOf(1, 2, 3), contentId = "img1@maildots")
        val unused = AttachmentData("q.png", "image/png", byteArrayOf(4), contentId = "img2@maildots")
        val pdf = AttachmentData("a.pdf", "application/pdf", byteArrayOf(9))
        val (html, rest) = InlineImages.forEncryption(
            """<p>x</p><img alt="p" src="cid:img1@maildots"><img src='cid:nope'>""",
            listOf(image, unused, pdf),
        )
        assertTrue(html.contains("""src="data:image/png;base64,AQID""""), html)
        assertTrue(html.contains("src='cid:nope'"))
        // Gömülen görsel ekten çıkar; gövdede olmayan satır içi görsel sıradan ek olarak kalır.
        assertEquals(listOf("q.png", "a.pdf"), rest.map { it.name })
        assertNull(rest.first().contentId)
    }

    @Test
    fun onlyImageTypesAreEmbedded() {
        val bad = AttachmentData("x.svg", "image/svg+xml", byteArrayOf(1), contentId = "s@maildots")
        val (html, rest) = InlineImages.forEncryption("""<img src="cid:s@maildots">""", listOf(bad))
        assertEquals("""<img src="cid:s@maildots">""", html)
        assertEquals(1, rest.size)
    }
}
