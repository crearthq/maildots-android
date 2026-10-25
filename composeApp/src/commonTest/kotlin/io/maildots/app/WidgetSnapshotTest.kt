package io.maildots.app

import io.maildots.app.mail.Account
import io.maildots.app.mail.Folder
import io.maildots.app.mail.FolderRole
import io.maildots.app.mail.MailFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetSnapshotTest {
    private val account = Account("a@b.co", "gizli-sifre", webSession = "gizli-oturum")
    private val inbox = Folder("INBOX", "INBOX", FolderRole.INBOX, 0)
    private val sent = Folder("Sent", "Sent", FolderRole.SENT, 0)

    private fun state(
        folder: String = "INBOX",
        filter: MailFilter = MailFilter.ALL,
        loading: Boolean = false,
        messages: List<io.maildots.app.mail.MessageSummary> = (1L..7L).map {
            summary(it).copy(dateMillis = it * 1000, seen = it % 2 == 0L, snippet = "gizli-govde $it")
        },
    ) = AppState(
        accounts = listOf(account),
        active = account,
        mailbox = MailboxState(folders = listOf(inbox, sent), folderPath = folder, filter = filter, messages = messages, loading = loading),
        unreadByAccount = mapOf(account.email to 4),
    )

    private fun show(state: AppState, limit: Int = WIDGET_ITEMS_ANDROID): InboxSnapshot =
        assertIs<WidgetUpdate.Show>(widgetUpdate(state, limit)).snapshot

    @Test
    fun androidKeepsTheNewestFourWithOnlyDisplayFields() {
        val snapshot = show(state())
        assertEquals("a@b.co", snapshot.account)
        assertEquals(4, snapshot.unread)
        assertEquals(listOf(7L, 6L, 5L, 4L), snapshot.items.map { it.uid })
        val json = snapshot.toJson()
        // Şifre, oturum ve gövde önizlemesi widget'a gitmez.
        assertFalse(json.contains("gizli"))
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals(setOf("account", "unread", "items"), root.keys)
        val allowed = setOf("uid", "folder", "from", "subject", "date", "seen", "encrypted")
        root.getValue("items").jsonArray.forEach { assertTrue(allowed.containsAll(it.jsonObject.keys), "${it.jsonObject.keys}") }
    }

    @Test
    fun iosLimitStaysFive() {
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), show(state(), WIDGET_ITEMS_IOS).items.map { it.uid })
    }

    @Test
    fun encryptedMessageShowsSenderAndSubjectOnly() {
        val secret = summary(9).copy(subject = "Teklif", encrypted = true, snippet = "cihazda-acilmis-govde", dateMillis = 9_000)
        val snapshot = show(state(messages = listOf(secret)))
        val item = snapshot.items.single()
        assertTrue(item.encrypted)
        assertEquals("Teklif", item.subject)
        assertEquals("Gonderen", item.from)
        val json = snapshot.toJson()
        assertFalse(json.contains("cihazda-acilmis-govde"))
        assertTrue(json.contains("\"encrypted\":true"))
        // Şifresiz iletide anahtar yazılmaz (iOS görüntüsü değişmez).
        assertFalse(show(state()).toJson().contains("encrypted"))
    }

    @Test
    fun jsonRoundTrips() {
        val snapshot = show(state(messages = listOf(summary(3).copy(encrypted = true, dateMillis = 5))))
        assertEquals(snapshot, InboxSnapshot.fromJson(snapshot.toJson()))
        assertNull(InboxSnapshot.fromJson("{bozuk"))
        assertNull(InboxSnapshot.fromJson("""{"account":"a@b.co"}"""))
    }

    @Test
    fun otherViewsAndLoadingLeaveTheWidgetAlone() {
        assertNull(widgetUpdate(state(folder = "Sent"), WIDGET_ITEMS_ANDROID))
        assertNull(widgetUpdate(state(filter = MailFilter.UNREAD), WIDGET_ITEMS_ANDROID))
        assertNull(widgetUpdate(state(messages = emptyList(), loading = true), WIDGET_ITEMS_ANDROID))
        // Yüklenmiş boş gelen kutusu: boş liste gösterilir.
        assertTrue(show(state(messages = emptyList())).items.isEmpty())
    }

    @Test
    fun noAccountsClearsTheWidget() {
        assertEquals(WidgetUpdate.Clear, widgetUpdate(AppState(), WIDGET_ITEMS_ANDROID))
    }

    @Test
    fun pushedMailIsAddedOnceAndKeepsTheLimit() {
        val snapshot = show(state())
        val pushed = InboxSnapshot.Item(uid = 50, folder = "INBOX", from = "Yeni", subject = "Merhaba", date = 99_000, seen = false)
        val next = snapshot.withNew(pushed, unread = 5, limit = WIDGET_ITEMS_ANDROID)
        assertEquals(listOf(50L, 7L, 6L, 5L), next.items.map { it.uid })
        assertEquals(5, next.unread)
        assertEquals(next, next.withNew(pushed, unread = 5, limit = WIDGET_ITEMS_ANDROID))
        assertEquals(6, next.withNew(pushed.copy(uid = 51), unread = null, limit = 4).unread)
    }

    @Test
    fun openRequestAcceptsOnlyKnownAccountsAndNumericUids() {
        val known = listOf("Ayse@Sirket.com")
        val ok = assertNotNull(parseOpenRequest("ayse@sirket.com", "INBOX", 42L, false, known))
        assertEquals(OpenRequest("Ayse@Sirket.com", "INBOX", 42L, inbox = false), ok)
        assertEquals(42L, parseOpenRequest("ayse@sirket.com", null, "42", false, known)?.uid)
        assertEquals("INBOX", parseOpenRequest("ayse@sirket.com", "", 1L, false, known)?.folder)
        // Başlık ya da uid yok: gelen kutusu.
        assertEquals(false, parseOpenRequest("ayse@sirket.com", null, null, false, known)?.inbox)
        assertEquals(true, parseOpenRequest("ayse@sirket.com", null, null, true, known)?.inbox)
        // Bilinmeyen hesap, sayı olmayan ya da eksi uid, denetim karakterli klasör reddedilir.
        assertNull(parseOpenRequest("baska@x.com", "INBOX", 1L, false, known))
        assertNull(parseOpenRequest(null, "INBOX", 1L, false, known))
        assertNull(parseOpenRequest("ayse@sirket.com", "INBOX", "12a", false, known))
        assertNull(parseOpenRequest("ayse@sirket.com", "INBOX", "-1", false, known))
        assertNull(parseOpenRequest("ayse@sirket.com", "INBOX", -1L, false, known))
        assertNull(parseOpenRequest("ayse@sirket.com", "INBOX", 1.5, false, known))
        assertNull(parseOpenRequest("ayse@sirket.com", "IN\nBOX", 1L, false, known))
        assertNull(parseOpenRequest("ayse@sirket.com", "x".repeat(513), 1L, false, known))
        assertNull(parseOpenRequest("ayse@sirket.com", "INBOX", "99999999999999999999", false, known))
    }
}
