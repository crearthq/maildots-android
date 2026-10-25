package io.maildots.app.mail

/**
 * Gönderen avatarının kuralı (uygulama listesi ve bildirim aynı kuralı kullanır):
 * renk adresten türetilir, aynı gönderen her yerde aynı rengi alır; yazı baş harflerdir.
 */
object AvatarStyle {
    /** ARGB renkler (marka mavisi ilk sırada). */
    val COLORS: List<Long> = listOf(0xFF2A38EC, 0xFF0E9F8E, 0xFFD9822B, 0xFFC2416B, 0xFF6D4AE0, 0xFF2E7DD7)

    fun colorFor(key: String): Long =
        COLORS[(key.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7fffffff }) % COLORS.size]

    /** İki kelimelik adda iki baş harf, tek kelimede bir; ad yoksa adresin ilk harfi. */
    fun initials(name: String, email: String): String {
        val words = name.trim().split(' ', '.', '_', '-').filter { it.isNotEmpty() }
        return when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}"
            words.size == 1 -> words[0].take(1)
            else -> email.take(1)
        }.uppercase().ifEmpty { "?" }
    }
}
