package io.maildots.app.mail

/** Kullanıcının seçtiği, henüz denetlenmemiş dosya. [bytes] null ise okunamadı ya da sınırı aştı. */
class PickedFile(val name: String, val mimeType: String?, val bytes: ByteArray?, val tooLarge: Boolean = false)

/** Gönderilecek iletiye eklenmiş, denetimden geçmiş dosya (içerik denetleyicide tutulur). */
data class DraftAttachment(val id: Int, val name: String, val mimeType: String, val sizeBytes: Long)

enum class AttachProblem { BLOCKED_TYPE, TOO_LARGE, TOO_MANY, EMPTY, UNREADABLE }

/**
 * Giden eklerin güvenlik kuralları.
 *
 * - Dosya adı dışarıdan gelen veridir: yol ayırıcıları, denetim karakterleri ve satır
 *   sonları atılır (başlık enjeksiyonu ve dizin dışına yazma olmasın).
 * - İçerik türü yalnızca "tür/alt-tür" biçimindeyse kullanılır; değilse genel ikili tür.
 * - Çalıştırılabilir ve betik dosyaları eklenemez. Karar SON uzantıya göre verilir
 *   ("fatura.pdf.exe" engellenir).
 * - Sayı ve toplam boyut sınırlıdır.
 */
object AttachmentPolicy {
    const val MAX_FILES = 10
    /** Giden posta Postmark üzerinden aktarılır (kodlanmış en çok 20 480 000 bayt); base64 ile ~1.37 kat. */
    const val MAX_TOTAL_BYTES = 13L * 1024 * 1024
    const val MAX_NAME_LENGTH = 120
    const val FALLBACK_NAME = "dosya"
    const val BINARY = "application/octet-stream"

    /** Açıldığında kod çalıştırabilen dosya türleri (alıcıyı korumak için eklenmez). */
    private val BLOCKED = setOf(
        "exe", "com", "bat", "cmd", "scr", "pif", "msi", "msp", "mst", "msix", "appx", "cpl", "dll", "sys", "drv",
        "js", "jse", "vbs", "vbe", "vb", "wsf", "wsh", "wsc", "ws", "hta", "ps1", "psm1", "psd1", "ps1xml",
        "jar", "jnlp", "apk", "xapk", "aab", "ipa", "app", "dmg", "pkg", "deb", "rpm", "sh", "bash", "command",
        "lnk", "url", "scf", "reg", "inf", "gadget", "chm", "hlp", "iso", "img", "vhd", "vhdx",
        "docm", "dotm", "xlsm", "xltm", "xlam", "pptm", "potm", "ppam", "ppsm", "sldm",
    )

    private val MIME = Regex("^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}$")

    fun safeName(raw: String): String {
        val cleaned = buildString {
            for (ch in raw.substringAfterLast('/').substringAfterLast('\\')) {
                when {
                    ch.code < 0x20 || ch.code == 0x7F -> Unit
                    // Yön değiştiren görünmez karakterler adı olduğundan farklı gösterir ("exe.pdf" hilesi).
                    ch.code in 0x200B..0x200F || ch.code in 0x202A..0x202E || ch.code in 0x2066..0x2069 || ch.code == 0xFEFF -> Unit
                    ch in "\"<>:|?*" -> append('_')
                    else -> append(ch)
                }
            }
        }.trim().trim('.').trim()
        if (cleaned.isEmpty()) return FALLBACK_NAME
        if (cleaned.length <= MAX_NAME_LENGTH) return cleaned
        // Uzantı korunarak kısaltılır.
        val ext = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..10 && cleaned.contains('.') }
        return if (ext == null) cleaned.take(MAX_NAME_LENGTH)
        else cleaned.take(MAX_NAME_LENGTH - ext.length - 1).trimEnd('.', ' ') + "." + ext
    }

    fun extension(name: String): String = name.substringAfterLast('.', "").trim().lowercase()

    fun isBlocked(name: String): Boolean = extension(name) in BLOCKED

    fun safeMimeType(raw: String?): String {
        val value = raw?.substringBefore(';')?.trim().orEmpty()
        return if (MIME.matches(value)) value.lowercase() else BINARY
    }

    class Outcome(val accepted: List<Pair<DraftAttachment, ByteArray>>, val problem: AttachProblem?, val problemName: String?)

    /**
     * Seçilen dosyaları denetler. Kurala uyanlar eklenir; uymayan ilk dosyanın nedeni
     * (ve adı) döndürülür. [existing] hâlihazırda ekli olanlardır.
     */
    fun admit(existing: List<DraftAttachment>, picked: List<PickedFile>, nextId: Int): Outcome {
        val accepted = ArrayList<Pair<DraftAttachment, ByteArray>>()
        var problem: AttachProblem? = null
        var problemName: String? = null
        var count = existing.size
        var total = existing.sumOf { it.sizeBytes }
        var id = nextId
        fun reject(kind: AttachProblem, name: String) {
            if (problem == null) {
                problem = kind
                problemName = name
            }
        }
        for (file in picked) {
            val name = safeName(file.name)
            val bytes = file.bytes
            when {
                isBlocked(name) -> reject(AttachProblem.BLOCKED_TYPE, name)
                file.tooLarge -> reject(AttachProblem.TOO_LARGE, name)
                bytes == null -> reject(AttachProblem.UNREADABLE, name)
                bytes.isEmpty() -> reject(AttachProblem.EMPTY, name)
                count >= MAX_FILES -> reject(AttachProblem.TOO_MANY, name)
                total + bytes.size > MAX_TOTAL_BYTES -> reject(AttachProblem.TOO_LARGE, name)
                else -> {
                    accepted += DraftAttachment(id++, name, safeMimeType(file.mimeType), bytes.size.toLong()) to bytes
                    count++
                    total += bytes.size
                }
            }
        }
        return Outcome(accepted, problem, problemName)
    }
}
