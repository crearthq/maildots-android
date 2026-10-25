package io.maildots.app

import io.maildots.app.mail.FolderRole
import io.maildots.app.mail.MailFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Ana ekran widget'ının gösterdiği gelen kutusu özeti (iOS ve Android ortak).
 * Yalnızca gösterim bilgisi: gönderen, konu, tarih, okundu, şifreli. Gövde, önizleme,
 * şifre ya da oturum burada YOKTUR; şifreli iletide de liste gibi yalnızca gönderen ve konu.
 */
data class InboxSnapshot(val account: String, val unread: Int, val items: List<Item>) {
    data class Item(
        val uid: Long,
        val folder: String,
        val from: String,
        val subject: String,
        /** Epoch milisaniye. */
        val date: Long,
        val seen: Boolean,
        /** Uçtan uca şifreli (widget kilit simgesi gösterir; içerik gösterilmez). */
        val encrypted: Boolean = false,
    )

    /**
     * JSON (iOS widget'ının çözdüğü biçim). `encrypted` yalnızca doğruysa yazılır:
     * iOS tarafı bilmediği anahtarı yok sayar, görüntü öncekiyle aynı kalır.
     */
    fun toJson(): String = buildJsonObject {
        put("account", account)
        put("unread", unread)
        putJsonArray("items") {
            items.forEach { m ->
                addJsonObject {
                    put("uid", m.uid)
                    put("folder", m.folder)
                    put("from", m.from)
                    put("subject", m.subject)
                    put("date", m.date)
                    put("seen", m.seen)
                    if (m.encrypted) put("encrypted", true)
                }
            }
        }
    }.toString()

    /**
     * Anlık bildirimle gelen yeni ileti (uygulama açılmadan widget güncel kalsın).
     * Aynı uid varsa yenilenmez; en yeni [limit] ileti tutulur.
     */
    fun withNew(item: Item, unread: Int?, limit: Int): InboxSnapshot {
        if (items.any { it.uid == item.uid && it.folder == item.folder }) return this
        val merged = (items + item).sortedByDescending { it.date }.take(limit)
        return copy(unread = unread ?: (this.unread + if (item.seen) 0 else 1), items = merged)
    }

    companion object {
        /** Bozuk ya da eksik kayıt: null (widget "uygulamayı açın" gösterir). */
        fun fromJson(json: String): InboxSnapshot? = runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            InboxSnapshot(
                account = root.getValue("account").jsonPrimitive.content,
                unread = root.getValue("unread").jsonPrimitive.int,
                items = root.getValue("items").jsonArray.map { e ->
                    val o: JsonObject = e.jsonObject
                    Item(
                        uid = o.getValue("uid").jsonPrimitive.long,
                        folder = o.getValue("folder").jsonPrimitive.content,
                        from = o.getValue("from").jsonPrimitive.content,
                        subject = o.getValue("subject").jsonPrimitive.content,
                        date = o.getValue("date").jsonPrimitive.long,
                        seen = o.getValue("seen").jsonPrimitive.boolean,
                        encrypted = o["encrypted"]?.jsonPrimitive?.boolean ?: false,
                    )
                },
            )
        }.getOrNull()
    }
}

/** Widget'a ne yapılacağı. */
sealed interface WidgetUpdate {
    /** Hesap kalmadı: widget boşaltılır ("giriş yapın"). */
    data object Clear : WidgetUpdate
    data class Show(val snapshot: InboxSnapshot) : WidgetUpdate
}

/** iOS widget'ının tuttuğu ileti sayısı (gösterdiği 4). */
const val WIDGET_ITEMS_IOS = 5

/** Android widget'ının gösterdiği (ve tuttuğu) ileti sayısı. */
const val WIDGET_ITEMS_ANDROID = 4

/**
 * Etkin hesabın gelen kutusundaki son [limit] ileti, widget için.
 * Dönüş: null = değişiklik yok (gelen kutusu ekranda değil ya da yükleniyor).
 */
internal fun widgetUpdate(state: AppState, limit: Int): WidgetUpdate? {
    val account = state.active ?: return if (state.accounts.isEmpty()) WidgetUpdate.Clear else null
    val box = state.mailbox
    if (box.label != null || box.filter != MailFilter.ALL) return null
    val role = box.folders.firstOrNull { it.path == box.folderPath }?.role
    if (role != FolderRole.INBOX && !(role == null && box.folderPath == "INBOX")) return null
    // Yüklenirken boş görünen liste widget'ı boşaltmasın.
    if (box.messages.isEmpty() && (box.loading || box.error != null)) return null
    val items = box.messages.sortedByDescending { it.dateMillis }.take(limit).map { m ->
        InboxSnapshot.Item(
            uid = m.uid,
            folder = m.folderPath,
            from = m.from.label,
            subject = m.subject,
            date = m.dateMillis,
            seen = m.seen,
            encrypted = m.encrypted,
        )
    }
    return WidgetUpdate.Show(
        InboxSnapshot(
            account = account.email,
            unread = state.unreadByAccount[account.email] ?: box.messages.count { !it.seen },
            items = items,
        ),
    )
}

/** Durum değiştikçe widget güncellemeleri; aynı görüntü yinelenmez. */
internal fun widgetUpdates(state: Flow<AppState>, limit: Int): Flow<WidgetUpdate> =
    state.map { widgetUpdate(it, limit) }.filterNotNull().distinctUntilChanged()

/**
 * Widget başlığına dokunulunca: hesabın gelen kutusu listesi açılır. Açık ekranlardan geri
 * dönülür; yazma ekranındaysa taslak her zamanki gibi kaydedilir. Bilinmeyen hesap yok sayılır.
 */
fun AppController.openInbox(account: String) {
    val target = state.value.accounts.firstOrNull { it.email.equals(account, ignoreCase = true) } ?: return
    // Hesap değişince liste o hesabın gelen kutusuyla açılır.
    if (state.value.active?.email != target.email) {
        switchAccount(target.email)
        return
    }
    repeat(4) { if (state.value.screen != Screen.Mailbox) back() }
    val box = state.value.mailbox
    val inbox = box.folders.firstOrNull { it.role == FolderRole.INBOX }?.path ?: "INBOX"
    if (box.folderPath != inbox || box.filter != MailFilter.ALL || box.label != null) selectFolder(inbox)
}

/** Widget ya da bildirimden gelen "şunu aç" isteği (doğrulanmış). */
data class OpenRequest(val account: String, val folder: String, val uid: Long, val inbox: Boolean)

/**
 * Dışarıdan gelen açma isteğini doğrular (etkinlik dışa açıktır; başka uygulamalar da
 * aynı anahtarlarla çağırabilir). Yalnızca gezinme yapılır:
 * - hesap bu cihazda kayıtlı olmalı (büyük/küçük harf duyarsız; kayıtlı yazımı döner);
 * - uid sayı olmalı (Long ya da yalnızca rakamlardan oluşan metin), 0 = yalnızca hesap;
 * - klasör adı kısa ve denetim karakteri içermemeli (boş = INBOX).
 */
fun parseOpenRequest(
    account: String?,
    folder: String?,
    uid: Any?,
    inbox: Boolean,
    knownAccounts: List<String>,
): OpenRequest? {
    val raw = account?.trim().orEmpty()
    if (raw.isEmpty() || raw.length > 320) return null
    val email = knownAccounts.firstOrNull { it.equals(raw, ignoreCase = true) } ?: return null
    val id: Long = when (uid) {
        null -> 0L
        is Long -> uid
        is Int -> uid.toLong()
        is String -> if (uid.isNotEmpty() && uid.length <= 19 && uid.all { it in '0'..'9' }) uid.toLongOrNull() ?: return null else return null
        else -> return null
    }
    if (id < 0) return null
    val path = folder.orEmpty().ifBlank { "INBOX" }
    if (path.length > 512 || path.any { it.isISOControl() }) return null
    // uid yoksa (0) yalnız hesaba geçilir; gelen kutusuna dönüş yalnız açık istekle (widget başlığı).
    return OpenRequest(email, path, id, inbox)
}
