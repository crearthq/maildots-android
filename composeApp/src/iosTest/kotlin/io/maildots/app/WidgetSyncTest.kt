package io.maildots.app

import io.maildots.app.mail.Account
import io.maildots.app.mail.Folder
import io.maildots.app.mail.FolderRole
import io.maildots.app.mail.MailFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WidgetSyncTest {
    private val account = Account("a@b.co", "gizli-sifre", webSession = "gizli-oturum")
    private val inbox = Folder("INBOX", "INBOX", FolderRole.INBOX, 0)
    private val sent = Folder("Sent", "Sent", FolderRole.SENT, 0)

    private fun state(folder: String = "INBOX", filter: MailFilter = MailFilter.ALL) = AppState(
        accounts = listOf(account),
        active = account,
        mailbox = MailboxState(
            folders = listOf(inbox, sent),
            folderPath = folder,
            filter = filter,
            messages = (1L..7L).map { summary(it).copy(dateMillis = it * 1000, seen = it % 2 == 0L) },
        ),
        unreadByAccount = mapOf(account.email to 4),
    )

    @Test
    fun inboxGivesTheNewestFiveWithoutSecrets() {
        val json = widgetSnapshot(state())!!
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals("a@b.co", root["account"]!!.jsonPrimitive.content)
        assertEquals(4, root["unread"]!!.jsonPrimitive.content.toInt())
        val uids = root["items"]!!.jsonArray.map { it.jsonObject["uid"]!!.jsonPrimitive.long }
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), uids)
        // Şifre ve oturum widget'a gitmez.
        assertFalse(json.contains("gizli"))
    }

    @Test
    fun otherViewsLeaveTheWidgetAlone() {
        assertNull(widgetSnapshot(state(folder = "Sent")))
        assertNull(widgetSnapshot(state(filter = MailFilter.UNREAD)))
    }

    @Test
    fun noAccountsClearsTheWidget() {
        assertEquals("", widgetSnapshot(AppState()))
    }
}
