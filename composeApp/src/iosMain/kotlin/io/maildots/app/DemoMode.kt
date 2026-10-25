package io.maildots.app

import io.maildots.app.data.AccountRepository
import io.maildots.app.data.SecureStore
import io.maildots.app.mail.Account
import io.maildots.app.mail.Address
import io.maildots.app.mail.Attachment
import io.maildots.app.mail.AttachmentData
import io.maildots.app.mail.Folder
import io.maildots.app.mail.FolderRole
import io.maildots.app.mail.MailEngine
import io.maildots.app.mail.MailFilter
import io.maildots.app.mail.MailLabel
import io.maildots.app.mail.MessageDetail
import io.maildots.app.mail.MessageSummary
import io.maildots.app.mail.OutgoingMessage
import io.maildots.app.mail.SearchQuery
import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.preferredLanguages
import platform.Foundation.timeIntervalSince1970
import kotlin.experimental.ExperimentalNativeApi

/**
 * Mağaza ekran görüntüleri için demo modu: yalnızca hata ayıklama derlemesinde ve uygulama
 * `-MDDemo` argümanıyla açıldığında. Gerçek hesap, Keychain ve ağ kullanılmaz; bellekte
 * örnek bir hesap ve kurgusal iletiler gösterilir (gerçek kişi ya da müşteri verisi yok).
 */
@OptIn(ExperimentalNativeApi::class)
internal val demoMode: Boolean by lazy {
    Platform.isDebugBinary && NSProcessInfo.processInfo.arguments.contains("-MDDemo")
}

/** Yalnızca bellekte duran depo (demo modu). */
internal class MemorySecureStore : SecureStore {
    private val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) {
        map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
}

/** Demo hesabı hazır, karşılama görülmüş bir depo. */
internal fun demoRepository(): AccountRepository = AccountRepository(MemorySecureStore()).apply {
    val demo = DemoData.current
    save(listOf(Account(demo.email, "demo", displayName = demo.owner)))
    activeEmail = demo.email
    welcomeSeen = true
}

/** Kurgusal içerik; dil cihazdan gelir (uygulamadaki gibi Türkçe ya da İngilizce). */
private class DemoData(
    val email: String,
    val owner: String,
    val folders: List<Folder>,
    val messages: List<MessageSummary>,
    val bodies: Map<Long, String>,
    val attachments: Map<Long, List<Attachment>>,
    /** Taslaklar klasöründeki tek taslak (yazma ekranı görseli için). */
    val draft: MessageSummary,
    val draftText: String,
) {
    companion object {
        val current: DemoData by lazy {
            val tr = (NSLocale.preferredLanguages.firstOrNull() as? String)?.startsWith("tr") != false
            if (tr) turkish() else english()
        }

        /** "Şimdi": bugün 09:41 (mağaza görsellerindeki durum çubuğu saatiyle aynı). */
        private val now: Long get() {
            val today = NSCalendar.currentCalendar.startOfDayForDate(NSDate())
            return ((today.timeIntervalSince1970 + 9 * 3600 + 41 * 60) * 1000).toLong()
        }
        private const val MIN = 60_000L
        private const val HOUR = 60 * MIN
        private const val DAY = 24 * HOUR

        private fun msg(
            uid: Long, from: String, email: String, subject: String, snippet: String, ago: Long,
            seen: Boolean = true, flagged: Boolean = false, attachment: Boolean = false,
            labels: List<MailLabel> = emptyList(), owner: Address,
        ) = MessageSummary(
            uid = uid, subject = subject, from = Address(from, email), to = listOf(owner),
            dateMillis = now - ago, seen = seen, flagged = flagged, hasAttachment = attachment,
            labels = labels, snippet = snippet,
        )

        private fun turkish(): DemoData {
            val owner = Address("Deniz Yılmaz", "deniz@kuzey.example")
            val messages = listOf(
                msg(110, "Elif Kaya", "elif@lumen-mimarlik.example", "Yeni ofis için teklif",
                    "Merhaba Deniz, konuştuğumuz teklifi ekte gönderiyorum. Takvim ve bütçe…", 18 * MIN,
                    seen = false, attachment = true, labels = listOf(MailLabel.WORK), owner = owner),
                msg(109, "Can Demir", "can@kuzey.example", "Cuma toplantısı 14:00'te",
                    "Gündemi paylaştım; tasarım ekibinden de katılım olacak.", 52 * MIN,
                    seen = false, flagged = true, labels = listOf(MailLabel.IMPORTANT), owner = owner),
                msg(108, "Zeynep Arslan", "zeynep@kuzey.example", "Tasarım taslakları hazır",
                    "Ana sayfa ve mobil görünüm için iki seçenek hazırladım.", 3 * HOUR,
                    seen = false, attachment = true, owner = owner),
                msg(107, "MailDots", "merhaba@maildots.com", "Alan adınızla e-posta hazır",
                    "Kutunuz kuruldu. Telefonunuzdan, bilgisayarınızdan her yerden ulaşın.", 5 * HOUR, owner = owner),
                msg(106, "Mert Yıldız", "mert@atlasmuhasebe.example", "Ekim ayı faturası",
                    "Ekim dönemine ait faturanız ektedir. Son ödeme tarihi 30 Ekim.", DAY + 2 * HOUR,
                    attachment = true, labels = listOf(MailLabel.TODO), owner = owner),
                msg(105, "Selin Aydın", "selin@kuzey.example", "Hafta sonu ekip yemeği",
                    "Cumartesi akşamı için iki mekân önerim var, hangisi uygun?", DAY + 5 * HOUR,
                    labels = listOf(MailLabel.PERSONAL), owner = owner),
                msg(104, "Ofis Yönetimi", "ofis@kuzey.example", "Bina bakım duyurusu",
                    "Perşembe günü 09:00–11:00 arası asansör bakımda olacak.", 2 * DAY, owner = owner),
                msg(103, "Burak Şahin", "burak@novaajans.example", "Sunum notları",
                    "Dünkü sunumun notlarını derledim; geri bildirimleriniz için teşekkürler.", 2 * DAY + 3 * HOUR,
                    owner = owner),
                msg(102, "Ece Koç", "ece@kuzey.example", "Yeni müşteri görüşmesi",
                    "Salı sabahı için uygun musun? Kısa bir tanışma toplantısı planlıyoruz.", 3 * DAY, owner = owner),
                msg(101, "Kuzey Studio", "bulten@kuzey.example", "Haftalık bülten",
                    "Bu hafta: yeni projeler, ekipten haberler ve takvim.", 4 * DAY, owner = owner),
            )
            return DemoData(
                email = owner.email,
                owner = owner.name,
                folders = listOf(
                    Folder("INBOX", "INBOX", FolderRole.INBOX, unread = 3, total = 128),
                    Folder("Projeler", "Projeler", FolderRole.OTHER, unread = 0, total = 42),
                    Folder("Müşteriler", "Müşteriler", FolderRole.OTHER, unread = 0, total = 27),
                    Folder("Sent", "Sent", FolderRole.SENT, unread = 0, total = 64),
                    Folder("Drafts", "Drafts", FolderRole.DRAFTS, unread = 0, total = 1),
                    Folder("Archive", "Archive", FolderRole.ARCHIVE, unread = 0, total = 312),
                    Folder("Junk", "Junk", FolderRole.JUNK, unread = 0, total = 0),
                    Folder("Trash", "Trash", FolderRole.TRASH, unread = 0, total = 5),
                ),
                messages = messages,
                bodies = mapOf(110L to proposalTr, 109L to meetingTr),
                attachments = mapOf(
                    110L to listOf(Attachment("Teklif-Kuzey-Ofis.pdf", "application/pdf", 482_000)),
                    108L to listOf(Attachment("Anasayfa-v2.png", "image/png", 1_240_000)),
                    106L to listOf(Attachment("Fatura-Ekim.pdf", "application/pdf", 96_000)),
                ),
                draft = MessageSummary(
                    uid = 201, folderPath = "Drafts", subject = "Teklif hakkında",
                    from = owner, to = listOf(Address("Elif Kaya", "elif@lumen-mimarlik.example")),
                    dateMillis = now - 5 * MIN, seen = true, flagged = false, hasAttachment = false,
                ),
                draftText = "Merhaba Elif,\n\nTeklif için teşekkürler, takvim bizim için de uygun görünüyor. " +
                    "Perşembe 10:00'da kısa bir görüşme yapabilir miyiz?\n\nSevgiler,\nDeniz",
            )
        }

        private fun english(): DemoData {
            val owner = Address("Alex Morgan", "alex@north.example")
            val messages = listOf(
                msg(110, "Emma Clarke", "emma@lumen-architects.example", "Proposal for the new office",
                    "Hi Alex, attached is the proposal we discussed. Timeline and budget…", 18 * MIN,
                    seen = false, attachment = true, labels = listOf(MailLabel.WORK), owner = owner),
                msg(109, "Daniel Reed", "daniel@north.example", "Friday meeting at 2 pm",
                    "I shared the agenda; the design team will join as well.", 52 * MIN,
                    seen = false, flagged = true, labels = listOf(MailLabel.IMPORTANT), owner = owner),
                msg(108, "Sophie Turner", "sophie@north.example", "Design drafts are ready",
                    "Two options for the home page and the mobile layout.", 3 * HOUR,
                    seen = false, attachment = true, owner = owner),
                msg(107, "MailDots", "hello@maildots.com", "Your branded email is ready",
                    "Your mailbox is set up. Reach it from your phone or computer, anywhere.", 5 * HOUR, owner = owner),
                msg(106, "Oliver Hayes", "oliver@atlasaccounting.example", "October invoice",
                    "Your invoice for October is attached. Due date: October 30.", DAY + 2 * HOUR,
                    attachment = true, labels = listOf(MailLabel.TODO), owner = owner),
                msg(105, "Mia Collins", "mia@north.example", "Team dinner this weekend",
                    "Two places in mind for Saturday evening — which works for you?", DAY + 5 * HOUR,
                    labels = listOf(MailLabel.PERSONAL), owner = owner),
                msg(104, "Office Team", "office@north.example", "Building maintenance notice",
                    "The elevator will be under maintenance on Thursday, 9–11 am.", 2 * DAY, owner = owner),
                msg(103, "Lucas Bennett", "lucas@novaagency.example", "Presentation notes",
                    "I put together the notes from yesterday's presentation. Thanks for the feedback.", 2 * DAY + 3 * HOUR,
                    owner = owner),
                msg(102, "Grace Walker", "grace@north.example", "New client meeting",
                    "Are you free Tuesday morning? A short intro call with the new client.", 3 * DAY, owner = owner),
                msg(101, "North Studio", "news@north.example", "Weekly newsletter",
                    "This week: new projects, team news and the calendar.", 4 * DAY, owner = owner),
            )
            return DemoData(
                email = owner.email,
                owner = owner.name,
                folders = listOf(
                    Folder("INBOX", "INBOX", FolderRole.INBOX, unread = 3, total = 128),
                    Folder("Projects", "Projects", FolderRole.OTHER, unread = 0, total = 42),
                    Folder("Clients", "Clients", FolderRole.OTHER, unread = 0, total = 27),
                    Folder("Sent", "Sent", FolderRole.SENT, unread = 0, total = 64),
                    Folder("Drafts", "Drafts", FolderRole.DRAFTS, unread = 0, total = 1),
                    Folder("Archive", "Archive", FolderRole.ARCHIVE, unread = 0, total = 312),
                    Folder("Junk", "Junk", FolderRole.JUNK, unread = 0, total = 0),
                    Folder("Trash", "Trash", FolderRole.TRASH, unread = 0, total = 5),
                ),
                messages = messages,
                bodies = mapOf(110L to proposalEn, 109L to meetingEn),
                attachments = mapOf(
                    110L to listOf(Attachment("Proposal-North-Office.pdf", "application/pdf", 482_000)),
                    108L to listOf(Attachment("Homepage-v2.png", "image/png", 1_240_000)),
                    106L to listOf(Attachment("Invoice-October.pdf", "application/pdf", 96_000)),
                ),
                draft = MessageSummary(
                    uid = 201, folderPath = "Drafts", subject = "About the proposal",
                    from = owner, to = listOf(Address("Emma Clarke", "emma@lumen-architects.example")),
                    dateMillis = now - 5 * MIN, seen = true, flagged = false, hasAttachment = false,
                ),
                draftText = "Hi Emma,\n\nThanks for the proposal — the timeline works for us too. " +
                    "Could we have a quick call on Thursday at 10 am?\n\nBest,\nAlex",
            )
        }

        private val proposalTr = """
            <p>Merhaba Deniz,</p>
            <p>Geçen hafta konuştuğumuz yeni ofis projesi için teklifimizi ekte gönderiyorum. Kısaca özetlemek gerekirse:</p>
            <table style="width:100%;border-collapse:collapse;margin:12px 0">
              <tr><td style="padding:8px 0;border-bottom:1px solid #e4e2da">Keşif ve proje</td><td style="padding:8px 0;border-bottom:1px solid #e4e2da;text-align:right">2 hafta</td></tr>
              <tr><td style="padding:8px 0;border-bottom:1px solid #e4e2da">Uygulama</td><td style="padding:8px 0;border-bottom:1px solid #e4e2da;text-align:right">6 hafta</td></tr>
              <tr><td style="padding:8px 0">Teslim</td><td style="padding:8px 0;text-align:right">1 Aralık</td></tr>
            </table>
            <p>Uygun olursan bu hafta kısa bir görüşmeyle detayların üzerinden geçebiliriz.</p>
            <p>İyi çalışmalar,<br><b>Elif Kaya</b><br>Lumen Mimarlık</p>
        """.trimIndent()

        private val meetingTr = """
            <p>Herkese merhaba,</p>
            <p>Cuma 14:00'teki toplantının gündemi:</p>
            <ul><li>Yeni ofis projesi</li><li>Web sitesi tasarımı</li><li>Ekim hedefleri</li></ul>
            <p>Görüşmek üzere,<br><b>Can</b></p>
        """.trimIndent()

        private val proposalEn = """
            <p>Hi Alex,</p>
            <p>Attached is our proposal for the new office project we discussed last week. In short:</p>
            <table style="width:100%;border-collapse:collapse;margin:12px 0">
              <tr><td style="padding:8px 0;border-bottom:1px solid #e4e2da">Discovery and design</td><td style="padding:8px 0;border-bottom:1px solid #e4e2da;text-align:right">2 weeks</td></tr>
              <tr><td style="padding:8px 0;border-bottom:1px solid #e4e2da">Build</td><td style="padding:8px 0;border-bottom:1px solid #e4e2da;text-align:right">6 weeks</td></tr>
              <tr><td style="padding:8px 0">Handover</td><td style="padding:8px 0;text-align:right">December 1</td></tr>
            </table>
            <p>If it works for you, we could go over the details in a short call this week.</p>
            <p>Best regards,<br><b>Emma Clarke</b><br>Lumen Architects</p>
        """.trimIndent()

        private val meetingEn = """
            <p>Hi everyone,</p>
            <p>Agenda for Friday's 2 pm meeting:</p>
            <ul><li>New office project</li><li>Website design</li><li>October goals</li></ul>
            <p>See you there,<br><b>Daniel</b></p>
        """.trimIndent()
    }
}

/** Demo posta motoru: tüm işlemler bellekte, ağ yok. */
internal class DemoMailEngine : MailEngine {
    private val data = DemoData.current
    private val messages = data.messages.toMutableList()

    private fun update(uid: Long, change: (MessageSummary) -> MessageSummary) {
        val i = messages.indexOfFirst { it.uid == uid }
        if (i >= 0) messages[i] = change(messages[i])
    }

    override suspend fun verify(account: Account) = Unit
    override suspend fun folders(account: Account): List<Folder> =
        data.folders.map { if (it.role == FolderRole.INBOX) it.copy(unread = messages.count { m -> !m.seen }) else it }
    override suspend fun inboxUnread(account: Account): Int = messages.count { !it.seen }
    override suspend fun displayName(account: Account): String = data.owner

    override suspend fun messages(
        account: Account, folderPath: String, offset: Int, limit: Int, filter: MailFilter, label: MailLabel?,
    ): List<MessageSummary> {
        if (label == null && folderPath == "Drafts") return if (offset == 0) listOf(data.draft) else emptyList()
        if (folderPath != "INBOX" && label == null) return emptyList()
        return messages
            .filter { label == null || label in it.labels }
            .filter {
                when (filter) {
                    MailFilter.ALL -> true
                    MailFilter.UNREAD -> !it.seen
                    MailFilter.FLAGGED -> it.flagged
                    MailFilter.ATTACHMENTS -> it.hasAttachment
                }
            }
            .drop(offset).take(limit)
    }

    override suspend fun search(account: Account, query: SearchQuery, limit: Int): List<MessageSummary> {
        val text = query.text.trim()
        return messages.filter {
            text.isEmpty() || it.subject.contains(text, ignoreCase = true) || it.from.label.contains(text, ignoreCase = true) ||
                it.snippet.contains(text, ignoreCase = true)
        }.filter { !query.unread || !it.seen }.take(limit)
    }

    override suspend fun setLabel(account: Account, folderPath: String, uid: Long, label: MailLabel, on: Boolean) =
        update(uid) { m -> m.copy(labels = if (on) (m.labels + label).distinct() else m.labels - label) }
    override suspend fun clearLabel(account: Account, label: MailLabel) = Unit
    override suspend fun createFolder(account: Account, name: String): String = name.trim()
    override suspend fun renameFolder(account: Account, path: String, newName: String): String = newName.trim()
    override suspend fun deleteFolder(account: Account, path: String) = Unit

    override suspend fun message(account: Account, folderPath: String, uid: Long): MessageDetail {
        if (uid == data.draft.uid) return MessageDetail(data.draft, emptyList(), data.draftText, null, emptyList())
        val summary = messages.first { it.uid == uid }
        val html = data.bodies[uid] ?: "<p>${summary.snippet}</p>"
        return MessageDetail(summary, emptyList(), null, html, data.attachments[uid].orEmpty())
    }

    override suspend fun setSeen(account: Account, folderPath: String, uid: Long, seen: Boolean) = update(uid) { it.copy(seen = seen) }
    override suspend fun setFlagged(account: Account, folderPath: String, uid: Long, flagged: Boolean) =
        update(uid) { it.copy(flagged = flagged) }
    override suspend fun delete(account: Account, folderPath: String, uid: Long) {
        messages.removeAll { it.uid == uid }
    }
    override suspend fun move(account: Account, folderPath: String, uid: Long, targetPath: String) {
        messages.removeAll { it.uid == uid }
    }
    override suspend fun folderFor(account: Account, role: FolderRole): String? = data.folders.firstOrNull { it.role == role }?.path
    override suspend fun attachment(account: Account, folderPath: String, uid: Long, index: Int): AttachmentData {
        val a = data.attachments[uid]?.getOrNull(index) ?: throw io.maildots.app.mail.MailError.Unknown()
        return AttachmentData(a.name, a.mimeType, ByteArray(0))
    }
    override suspend fun send(account: Account, message: OutgoingMessage) = Unit
    override suspend fun disconnect(account: Account) = Unit
}
