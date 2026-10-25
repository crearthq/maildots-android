package io.maildots.app.data

import io.maildots.app.LabelInfo
import io.maildots.app.Settings
import io.maildots.app.SwipeAction
import io.maildots.app.ThemeMode
import io.maildots.app.mail.Account
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Şifreli anahtar-değer deposu. Android'de değerler AndroidKeyStore'daki dışa
 * aktarılamayan bir AES-256-GCM anahtarıyla şifrelenir; iOS'ta Keychain kullanılacak.
 * Düz metin diske ASLA yazılmaz.
 */
interface SecureStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** Hesap listesi: tek şifreli kayıt olarak saklanır. */
/** Gelen kutusunun diske yazılan son hâli. */
@kotlinx.serialization.Serializable
data class MailboxCache(
    val folders: List<io.maildots.app.mail.Folder>,
    val messages: List<io.maildots.app.mail.MessageSummary>,
)

class AccountRepository(private val store: SecureStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Account.serializer())

    fun load(): List<Account> {
        val raw = store.get(KEY_ACCOUNTS) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun save(accounts: List<Account>) {
        if (accounts.isEmpty()) store.remove(KEY_ACCOUNTS)
        else store.put(KEY_ACCOUNTS, json.encodeToString(serializer, accounts))
    }

    var activeEmail: String?
        get() = store.get(KEY_ACTIVE)
        set(value) {
            if (value == null) store.remove(KEY_ACTIVE) else store.put(KEY_ACTIVE, value)
        }

    fun loadSettings(): Settings {
        fun action(key: String, default: SwipeAction) =
            store.get(key)?.let { name -> SwipeAction.entries.firstOrNull { it.name == name } } ?: default
        val defaults = Settings()
        return Settings(
            swipeRight = action(KEY_SWIPE_RIGHT, defaults.swipeRight),
            swipeLeft = action(KEY_SWIPE_LEFT, defaults.swipeLeft),
            theme = store.get(KEY_THEME)?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: defaults.theme,
        )
    }

    fun saveSettings(settings: Settings) {
        store.put(KEY_SWIPE_RIGHT, settings.swipeRight.name)
        store.put(KEY_SWIPE_LEFT, settings.swipeLeft.name)
        store.put(KEY_THEME, settings.theme.name)
    }

    /** Son aramalar (kişisel veri: şifreli depoda tutulur). */
    fun loadRecentSearches(): List<String> {
        val raw = store.get(KEY_RECENT) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrDefault(emptyList())
    }

    fun saveRecentSearches(items: List<String>) {
        if (items.isEmpty()) store.remove(KEY_RECENT)
        else store.put(KEY_RECENT, json.encodeToString(ListSerializer(String.serializer()), items))
    }

    /** Hesabın etiket tanımları (ad, renk). Hiç kaydedilmediyse boş liste. */
    fun loadLabels(email: String): List<LabelInfo> {
        val raw = store.get(KEY_LABELS + email) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(LabelInfo.serializer()), raw) }.getOrDefault(emptyList())
    }

    fun saveLabels(email: String, labels: List<LabelInfo>) {
        store.put(KEY_LABELS + email, json.encodeToString(ListSerializer(LabelInfo.serializer()), labels))
    }

    fun removeLabels(email: String) = store.remove(KEY_LABELS + email)

    /**
     * Gelen kutusunun son görülen hâli (klasörler + ilk sayfa). Uygulama açılırken
     * sunucu beklenmeden gösterilir; şifreli depoda durur, hesap çıkınca silinir.
     */
    fun loadMailbox(email: String): MailboxCache? {
        val raw = store.get(KEY_MAILBOX + email) ?: return null
        return runCatching { json.decodeFromString(MailboxCache.serializer(), raw) }.getOrNull()
    }

    fun saveMailbox(email: String, cache: MailboxCache) {
        runCatching { store.put(KEY_MAILBOX + email, json.encodeToString(MailboxCache.serializer(), cache)) }
    }

    fun removeMailbox(email: String) = store.remove(KEY_MAILBOX + email)

    /** Bu cihazın bildirim belirteci (son bilinen). */
    var pushToken: String?
        get() = store.get(KEY_PUSH_TOKEN)
        set(value) {
            if (value == null) store.remove(KEY_PUSH_TOKEN) else store.put(KEY_PUSH_TOKEN, value)
        }

    /** Bu kurulumun bildirim mühür anahtarı (T6; base64, 32 bayt). Yalnız bizim sunucumuza verilir. */
    var pushKey: String?
        get() = store.get(KEY_PUSH_KEY)
        set(value) {
            if (value == null) store.remove(KEY_PUSH_KEY) else store.put(KEY_PUSH_KEY, value)
        }

    /** Karşılama sayfaları bu cihazda bir kez gösterildi mi? */
    var welcomeSeen: Boolean
        get() = store.get(KEY_WELCOME) == "1"
        set(value) {
            if (value) store.put(KEY_WELCOME, "1") else store.remove(KEY_WELCOME)
        }

    private companion object {
        const val KEY_WELCOME = "welcome.seen.v1"
        const val KEY_MAILBOX = "mailbox.cache.v1."
        const val KEY_PUSH_TOKEN = "push.token.v1"
        const val KEY_PUSH_KEY = "push.key.v1"
        const val KEY_LABELS = "labels.v2."
        const val KEY_RECENT = "search.recent.v1"
        const val KEY_ACCOUNTS = "accounts.v1"
        const val KEY_ACTIVE = "active.v1"
        const val KEY_SWIPE_RIGHT = "swipe.right.v1"
        const val KEY_SWIPE_LEFT = "swipe.left.v1"
        const val KEY_THEME = "theme.v1"
    }
}
