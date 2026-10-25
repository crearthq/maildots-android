package io.maildots.app.ui

import io.maildots.app.PasswordProblem

/** Webmail ile eşitleme için eklenen arayüz metinleri (Türkçe / İngilizce). */
data class MoreStrings(
    val replyAll: String,
    val bcc: String,
    val showCcBcc: String,
    val from: String,
    val discardDraft: String,
    val discardDraftBody: String,
    val draftSaving: String,
    val draftSaved: String,
    val preparingAttachments: String,
    val draftLabel: String,
    val notSpam: String,
    val translate: String,
    val translating: String,
    val translatedFrom: (String) -> String,
    val translateFailed: String,
    val showOriginal: String,
    val conversation: (Int) -> String,
    val you: String,
    val selected: (Int) -> String,
    val selectAll: String,
    val markRead: String,
    val markUnread: String,
    val star: String,
    val unstar: String,
    val deleteForever: String,
    val deleteForeverBody: (Int) -> String,
    val emptyTrash: String,
    val emptyJunk: String,
    val emptyFolderBody: String,
    val emptyAction: String,
    val storage: String,
    val storageUsed: (String, String, Int) -> String,
    val storageUnlimited: (String) -> String,
    val changePassword: String,
    val changePasswordOnWeb: String,
    val newPassword: String,
    val updatePassword: String,
    val updatePasswordBody: String,
    val signInAgainTitle: String,
    val signInAgainBody: (String) -> String,
    val signInAgainAction: String,
    val passwordProblems: Map<PasswordProblem, String>,
    val suggestionSource: (String) -> String,
    /** T3: şifreleme kurcalama bandı. */
    val tamperTitle: (kind: String) -> String,
    val tamperRestored: String,
    val tamperRestoredMismatch: String,
    val tamperBroken: String,
    val tamperAck: String,
    val storedUnencrypted: String,
    /** T2: yeni kutuda şifreleme kurulumu bekliyor. */
    val onboardingTitle: String,
    val onboardingBody: String,
) {
    companion object {
        val TR = MoreStrings(
            replyAll = "Tümünü yanıtla",
            bcc = "Gizli",
            showCcBcc = "Bilgi / Gizli ekle",
            from = "Gönderen",
            discardDraft = "Taslağı sil",
            discardDraftBody = "Bu taslak silinecek. Bu işlem geri alınamaz.",
            draftSaving = "Kaydediliyor…",
            draftSaved = "Taslak kaydedildi",
            preparingAttachments = "Ekler hazırlanıyor…",
            draftLabel = "Taslak",
            notSpam = "Gereksiz değil",
            translate = "Çevir",
            translating = "Çevriliyor…",
            translatedFrom = { lang -> if (lang.isBlank()) "Çevrildi" else "${lang.uppercase()} dilinden çevrildi" },
            translateFailed = "Çeviri şu an yapılamadı.",
            showOriginal = "Orijinali göster",
            conversation = { n -> "Bu konuşmada $n ileti" },
            you = "Siz",
            selected = { n -> "$n seçili" },
            selectAll = "Tümünü seç",
            markRead = "Okundu yap",
            markUnread = "Okunmadı yap",
            star = "Yıldızla",
            unstar = "Yıldızı kaldır",
            deleteForever = "Kalıcı olarak sil",
            deleteForeverBody = { n -> if (n == 1) "Bu e-posta kalıcı olarak silinecek. Bu işlem geri alınamaz." else "$n e-posta kalıcı olarak silinecek. Bu işlem geri alınamaz." },
            emptyTrash = "Çöp kutusunu boşalt",
            emptyJunk = "Gereksiz klasörünü boşalt",
            emptyFolderBody = "Bu klasördeki tüm e-postalar kalıcı olarak silinecek. Bu işlem geri alınamaz.",
            emptyAction = "Boşalt",
            storage = "Depolama",
            storageUsed = { used, total, pct -> "$used / $total kullanılıyor (%$pct)" },
            storageUnlimited = { used -> "$used kullanılıyor" },
            changePassword = "Şifreyi değiştir",
            changePasswordOnWeb = "Tarayıcıda, web'e giriş yaparak",
            newPassword = "Yeni şifre",
            updatePassword = "Şifreyi güncelle",
            updatePasswordBody = "Şifrenizi başka bir yerde (webmail ya da yönetim paneli) değiştirdiyseniz yeni şifreyi buraya girin.",
            signInAgainTitle = "Şifreniz değişmiş",
            signInAgainBody = { email -> "$email için bu cihazdaki şifre artık kabul edilmiyor. Postalarınızı almaya devam etmek için yeni şifrenizi girin." },
            signInAgainAction = "Şifreyi gir",
            passwordProblems = mapOf(
                PasswordProblem.WRONG_CURRENT to "Mevcut şifre yanlış.",
                PasswordProblem.POLICY to "Yeni şifre kurallara uymuyor.",
                PasswordProblem.AUTH to "Şifre kabul edilmedi.",
                PasswordProblem.NETWORK to "Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edin.",
                PasswordProblem.UNKNOWN to "İşlem tamamlanamadı. Lütfen yeniden deneyin.",
                PasswordProblem.SECURITY to "Bu hesapta giriş koruması açıktı ama sunucu kapalı gösteriyor. Güvenliğiniz için şifreniz gönderilmedi. Şifrenizi unuttuysanız web postada \"Şifremi unuttum\" ile kurtarma ifadenizi kullanın; beklemiyorsanız destekle iletişime geçin.",
            ),
            tamperTitle = { kind ->
                when (kind) {
                    "mismatch" -> "Posta şifrelemeniz sunucuda başka bir anahtara çevrilmiş bulundu"
                    "weakened" -> "Gönderilen kopyaların ve taslakların şifrelenmesi sunucuda kapatılmış bulundu"
                    else -> "Kayıtlı posta şifrelemeniz sunucuda kapatılmış bulundu"
                }
            },
            tamperRestored = "Sizin anahtarınıza yeniden açıldı. Bu arada saklanan iletiler şifrelenmeden saklanmış olabilir; listede işaretlidirler. Bunu siz ya da yöneticiniz yapmadıysa destekle iletişime geçin.",
            tamperRestoredMismatch = "Sizin anahtarınıza yeniden çevrildi. Bu arada gelen iletiler başka bir anahtara şifrelenmiş olabilir; onları açamazsınız ve listede ayrıca işaretlenmezler. Hemen destekle iletişime geçin.",
            tamperBroken = "Yeniden açılamadı: yeni gelen postanız şu an şifrelenmeden saklanıyor. Hemen destekle iletişime geçin.",
            tamperAck = "Anladım",
            storedUnencrypted = "Şifrelenmeden saklandı",
            onboardingTitle = "Uçtan uca şifrelemeyi kurun",
            onboardingBody = "Bu yeni kutuda şifreleme henüz kurulmadı. Bilgisayarınızda web postaya giriş yapın; kurulum ilk girişte açılır ve bir dakika sürer.",
            suggestionSource = { source ->
                when (source) {
                    "contact" -> "Kişi"
                    "directory" -> "Şirket"
                    else -> "Son yazışma"
                }
            },
        )

        val EN = MoreStrings(
            replyAll = "Reply all",
            bcc = "Bcc",
            showCcBcc = "Add Cc / Bcc",
            from = "From",
            discardDraft = "Delete draft",
            discardDraftBody = "This draft will be deleted. This cannot be undone.",
            draftSaving = "Saving…",
            draftSaved = "Draft saved",
            preparingAttachments = "Preparing attachments…",
            draftLabel = "Draft",
            notSpam = "Not junk",
            translate = "Translate",
            translating = "Translating…",
            translatedFrom = { lang -> if (lang.isBlank()) "Translated" else "Translated from ${lang.uppercase()}" },
            translateFailed = "Translation is not available right now.",
            showOriginal = "Show original",
            conversation = { n -> "$n messages in this conversation" },
            you = "You",
            selected = { n -> "$n selected" },
            selectAll = "Select all",
            markRead = "Mark as read",
            markUnread = "Mark as unread",
            star = "Star",
            unstar = "Remove star",
            deleteForever = "Delete forever",
            deleteForeverBody = { n -> if (n == 1) "This email will be deleted forever. This cannot be undone." else "$n emails will be deleted forever. This cannot be undone." },
            emptyTrash = "Empty trash",
            emptyJunk = "Empty junk",
            emptyFolderBody = "All emails in this folder will be deleted forever. This cannot be undone.",
            emptyAction = "Empty",
            storage = "Storage",
            storageUsed = { used, total, pct -> "$used of $total used ($pct%)" },
            storageUnlimited = { used -> "$used used" },
            changePassword = "Change password",
            changePasswordOnWeb = "In your browser, after signing in",
            newPassword = "New password",
            updatePassword = "Update password",
            updatePasswordBody = "If you changed your password elsewhere (webmail or the admin panel), enter the new one here.",
            signInAgainTitle = "Your password has changed",
            signInAgainBody = { email -> "The password saved on this device for $email is no longer accepted. Enter your new password to keep receiving mail." },
            signInAgainAction = "Enter password",
            passwordProblems = mapOf(
                PasswordProblem.WRONG_CURRENT to "The current password is incorrect.",
                PasswordProblem.POLICY to "The new password does not meet the rules.",
                PasswordProblem.AUTH to "The password was not accepted.",
                PasswordProblem.NETWORK to "Could not reach the server. Check your internet connection.",
                PasswordProblem.UNKNOWN to "The operation could not be completed. Please try again.",
                PasswordProblem.SECURITY to "Login protection was on for this account, but the server now shows it off. For your safety your password was not sent. If an administrator reset your password, sign in on webmail first; if you did not expect this, contact support.",
            ),
            tamperTitle = { kind ->
                when (kind) {
                    "mismatch" -> "Your mail encryption was found pointed at another key on the server"
                    "weakened" -> "Encryption of sent copies and drafts was found switched off on the server"
                    else -> "Encryption of your stored mail was found switched off on the server"
                }
            },
            tamperRestored = "It was turned back on to your key. Messages stored meanwhile may be stored unencrypted; the list marks them. If neither you nor your administrator did this, contact support.",
            tamperRestoredMismatch = "It was pointed back at your key. Messages that arrived meanwhile may be encrypted to another key; you cannot open them and the list does not mark them. Contact support at once.",
            tamperBroken = "It could not be turned back on: new mail is being stored unencrypted right now. Contact support at once.",
            tamperAck = "Got it",
            storedUnencrypted = "Stored unencrypted",
            onboardingTitle = "Set up end-to-end encryption",
            onboardingBody = "Encryption is not set up yet for this new mailbox. Sign in to webmail on your computer; setup opens at the first sign-in and takes a minute.",
            suggestionSource = { source ->
                when (source) {
                    "contact" -> "Contact"
                    "directory" -> "Company"
                    else -> "Recent"
                }
            },
        )
    }
}

/** Bayt sayısını okunur boyuta çevirir (ör. "1,2 GB"). */
fun humanBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val rounded = if (unit == 0 || value >= 100) value.toLong().toString() else ((value * 10).toLong() / 10.0).toString()
    return "$rounded ${units[unit]}"
}
