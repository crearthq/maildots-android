package io.maildots.app.mail

import io.maildots.app.web.SignatureText

/**
 * Yanıt / iletme kuralları (webmail'deki `replyState` / `forwardState` ile aynı).
 * Saf fonksiyonlar: arayüzden bağımsız sınanır.
 */
object ComposeRules {
    /**
     * Yanıtın alıcıları.
     * - Birincil alıcı: kendi Gönderilenler kopyamızsa asıl alıcı; değilse gönderenin istediği
     *   yanıt adresi (Reply-To), yoksa gönderen.
     * - Tümünü yanıtla: özgün Kime listesi Kime'ye, Bilgi listesi Bilgi'ye eklenir.
     * - Kendi adresimiz ve tekrarlar çıkarılır (büyük/küçük harf duyarsız).
     */
    fun replyRecipients(detail: MessageDetail, self: String, all: Boolean): Pair<List<String>, List<String>> {
        val me = self.trim().lowercase()
        val fromSelf = detail.summary.from.email.equals(me, ignoreCase = true)
        val primary = detail.replyToAddress?.takeIf { it.isNotBlank() }
            ?: detail.replyTo.firstOrNull { it.email.isNotBlank() }?.email
            ?: if (fromSelf) detail.summary.to.firstOrNull()?.email else detail.summary.from.email
        val seen = HashSet<String>().apply { add(me) }
        fun take(list: List<String>): List<String> = list.map { it.trim().lowercase() }.filter { it.isNotEmpty() && seen.add(it) }
        val to = take(listOfNotNull(primary) + if (all) detail.summary.to.map { it.email } else emptyList())
        val cc = if (all) take(detail.cc.map { it.email }) else emptyList()
        return to to cc
    }

    /** Yanıtın References zinciri: özgün zincir + yanıtlanan iletinin kimliği (en çok 100). */
    fun references(detail: MessageDetail): List<String> =
        (detail.references + listOfNotNull(detail.messageId)).filter { it.isNotBlank() }.distinct().takeLast(100)

    /** Alıntılanacak düz metin: düz metin gövde yoksa HTML gövdeden çıkarılır. */
    fun plainText(detail: MessageDetail): String =
        detail.text?.takeIf { it.isNotBlank() } ?: detail.html?.let { SignatureText.toText(it) }.orEmpty()

    /** "Ynt:" / "İlt:" önekini bir kez ekler (zaten varsa ya da [alt] önekiyle başlıyorsa eklemez). */
    fun prefixed(subject: String, prefix: String, alt: String): String {
        val s = subject.trim()
        return if (s.startsWith(prefix.trim(), ignoreCase = true) || s.startsWith(alt, ignoreCase = true)) s else prefix + s
    }
}
