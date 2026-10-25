package io.maildots.app.mail

import kotlin.io.encoding.Base64

/**
 * Satır içi görseller (masaüstü yazma penceresi). Sunucudan giden iletide görsel `cid:` ile
 * gösterilir ve [AttachmentData.contentId] taşıyan ek olur. Uçtan uca şifreli iletide (ve
 * şifreli taslakta) MIME'ı cihazdaki çekirdek kurar ve `Content-ID` desteklemez: orada görsel
 * gövdeye `data:` adresi olarak gömülür ve ekten çıkarılır. Satır içi görseli olmayan iletide
 * (Android, iOS) hiçbir şey değişmez.
 */
object InlineImages {
    private val CID_SRC = Regex("""(<img\b[^>]*?\bsrc\s*=\s*)(["'])cid:([^"'>\s]{1,200})\2""", RegexOption.IGNORE_CASE)

    /** Yalnız bu türler gömülür (çekirdekteki DOMPurify'ın izin verdiği görsel türleri). */
    val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")

    fun forEncryption(html: String, attachments: List<AttachmentData>): Pair<String, List<AttachmentData>> {
        val inline = attachments.filter { it.contentId != null }
        if (inline.isEmpty()) return html to attachments
        val byId = inline.associateBy { it.contentId!! }
        val embedded = HashSet<String>()
        val out = CID_SRC.replace(html) { m ->
            val image = byId[m.groupValues[3]]
            if (image == null || image.mimeType.lowercase() !in IMAGE_TYPES) {
                m.value
            } else {
                embedded += image.contentId!!
                m.groupValues[1] + m.groupValues[2] + "data:" + image.mimeType.lowercase() + ";base64," +
                    Base64.encode(image.bytes) + m.groupValues[2]
            }
        }
        // Gövdede gösterilmeyen satır içi görsel sıradan ek olarak kalır (kaybolmaz).
        val rest = attachments.filter { it.contentId == null || it.contentId !in embedded }
            .map { if (it.contentId != null) AttachmentData(it.name, it.mimeType, it.bytes) else it }
        return out to rest
    }
}
