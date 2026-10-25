import SwiftUI
import WidgetKit

/// Ana ekran widget'ı: etkin hesabın gelen kutusundaki son 5 ileti, tam genişlikte ve kısa
/// (orta boy). Veriyi uygulama yazar (WidgetStore); widget ağa çıkmaz.
@main
struct MailDotsWidgetBundle: WidgetBundle {
    var body: some Widget { RecentMailWidget() }
}

struct RecentMailWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "RecentMail", provider: RecentMailProvider()) { entry in
            RecentMailView(entry: entry)
        }
        .configurationDisplayName(Texts.current.galleryTitle)
        .description(Texts.current.galleryDescription)
        .supportedFamilies([.systemMedium])
    }
}

struct RecentMailEntry: TimelineEntry {
    let date: Date
    /// nil: uygulama henüz açılmadı ya da hesap yok.
    let snapshot: InboxSnapshot?
}

struct RecentMailProvider: TimelineProvider {
    func placeholder(in context: Context) -> RecentMailEntry {
        RecentMailEntry(date: Date(), snapshot: .sample)
    }

    func getSnapshot(in context: Context, completion: @escaping (RecentMailEntry) -> Void) {
        let stored = WidgetStore.read()
        completion(RecentMailEntry(date: Date(), snapshot: context.isPreview ? (stored ?? .sample) : stored))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<RecentMailEntry>) -> Void) {
        // Uygulama gelen kutusu değişince widget'ı kendisi yeniler.
        completion(Timeline(entries: [RecentMailEntry(date: Date(), snapshot: WidgetStore.read())], policy: .never))
    }
}

// MARK: - Görünüm

/// Widget'ta gösterilen ileti sayısı (ferah kalsın diye 4).
private let visibleItems = 4

struct RecentMailView: View {
    let entry: RecentMailEntry
    private let t = Texts.current
    /// Tam renk dışındaki modlarda (iOS 26 Şeffaf/Liquid Glass, Renkli) sistem zemini kaldırır ve
    /// içeriği tek renge çevirir: vurgu öğeleri sistemin rengine geçer, dolgu kullanılmaz.
    @Environment(\.widgetRenderingMode) private var renderingMode
    private var fullColor: Bool { renderingMode == .fullColor }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            Spacer(minLength: 8)
            if let snapshot = entry.snapshot {
                if snapshot.items.isEmpty {
                    message(t.emptyInbox)
                } else {
                    // Satırlar kalan yüksekliğe eşit dağılır (altta boşluk birikmez).
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(snapshot.items.prefix(visibleItems).enumerated()), id: \.element.id) { index, item in
                            if index > 0 { Spacer(minLength: 4) }
                            if let url = WidgetLink.message(account: snapshot.account, folder: item.folder, uid: item.uid) {
                                Link(destination: url) { MailRow(item: item, fullColor: fullColor) }
                            } else {
                                MailRow(item: item, fullColor: fullColor)
                            }
                        }
                    }
                }
            } else {
                message(t.openApp)
            }
        }
        .foregroundStyle(fullColor ? Color.white : Color.primary)
        .widgetURL(WidgetLink.inbox(account: entry.snapshot?.account))
        .widgetBackground(Palette.gradient)
    }

    /// Başlık: dokununca gelen kutusu listesi açılır.
    @ViewBuilder
    private var header: some View {
        let row = HStack(spacing: 5) {
            Image(systemName: "tray.fill")
                .font(.system(size: 11, weight: .semibold))
                .widgetAccentable()
            Text(t.inbox)
                .font(.system(size: 12, weight: .semibold))
            if let account = entry.snapshot?.account {
                Text(account)
                    .font(.system(size: 11))
                    .opacity(0.7)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 4)
            if let unread = entry.snapshot?.unread, unread > 0 {
                let count = Text(unread > 999 ? "999+" : "\(unread)").font(.system(size: 10, weight: .bold))
                if fullColor {
                    count
                        .foregroundStyle(Palette.brandSolid)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 1)
                        .background(Capsule().fill(Color.white))
                } else {
                    // Camda dolgulu rozet okunmaz: yalnızca vurgu renginde sayı.
                    count.font(.system(size: 12, weight: .bold)).widgetAccentable()
                }
            }
        }
        .contentShape(Rectangle())
        if let url = WidgetLink.inbox(account: entry.snapshot?.account) {
            Link(destination: url) { row }
        } else {
            row
        }
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12))
            .opacity(0.8)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Tek satırlık ileti: okunmamış noktası, gönderen, konu, zaman.
struct MailRow: View {
    let item: InboxSnapshot.Item
    var fullColor = true

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(item.seen ? Color.clear : (fullColor ? Color.white : Color.primary))
                .frame(width: 6, height: 6)
                .widgetAccentable()
            Text(item.from)
                .font(.system(size: 12, weight: item.seen ? .regular : .semibold))
                .lineLimit(1)
                .frame(width: 92, alignment: .leading)
            Text(item.subject.isEmpty ? Texts.current.noSubject : item.subject)
                .font(.system(size: 12))
                .opacity(0.75)
                .lineLimit(1)
            Spacer(minLength: 4)
            Text(Self.time(item.date))
                .font(.system(size: 11, weight: item.seen ? .regular : .semibold))
                .opacity(item.seen ? 0.7 : 1)
                .lineLimit(1)
                .widgetAccentable(!item.seen)
        }
        .frame(height: 18)
    }

    /// Bugünse saat, değilse gün + kısa ay (uygulamadaki listeyle aynı).
    static func time(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
        let f = DateFormatter()
        f.locale = Locale(identifier: Texts.isTurkish ? "tr_TR" : "en_US")
        if Calendar.current.isDateInToday(date) {
            f.timeStyle = .short
            f.dateStyle = .none
        } else {
            f.setLocalizedDateFormatFromTemplate("dMMM")
        }
        return f.string(from: date)
    }
}

// MARK: - Renkler ve metinler

enum Palette {
    static let brandSolid = Color(red: 0x2A / 255, green: 0x38 / 255, blue: 0xEC / 255)
    /// Marka mavisi degrade (sol üstte açık, sağ altta koyu); koyu temada bir ton derin.
    static let gradient = LinearGradient(
        colors: [
            Color(UIColor { $0.userInterfaceStyle == .dark
                ? UIColor(red: 0x2F / 255, green: 0x3D / 255, blue: 0xE0 / 255, alpha: 1)
                : UIColor(red: 0x4B / 255, green: 0x58 / 255, blue: 0xF5 / 255, alpha: 1) }),
            Color(UIColor { $0.userInterfaceStyle == .dark
                ? UIColor(red: 0x16 / 255, green: 0x1E / 255, blue: 0x8C / 255, alpha: 1)
                : UIColor(red: 0x1F / 255, green: 0x2B / 255, blue: 0xCF / 255, alpha: 1) }),
        ],
        startPoint: .topLeading,
        endPoint: .bottomTrailing,
    )
}

/// Arayüz dili cihazdan gelir (uygulamadaki gibi yalnızca Türkçe ve İngilizce).
struct Texts {
    let inbox: String
    let galleryTitle: String
    let galleryDescription: String
    let emptyInbox: String
    let openApp: String
    let noSubject: String

    static var isTurkish: Bool { (Locale.preferredLanguages.first ?? "").hasPrefix("tr") }

    static var current: Texts {
        isTurkish
            ? Texts(inbox: "Gelen Kutusu", galleryTitle: "Son e-postalar",
                    galleryDescription: "Gelen kutunuzdaki son 4 e-posta.",
                    emptyInbox: "Gelen kutusu boş.", openApp: "E-postalarınızı görmek için MailDots'u açın.",
                    noSubject: "(Konu yok)")
            : Texts(inbox: "Inbox", galleryTitle: "Recent emails",
                    galleryDescription: "The last 4 emails in your inbox.",
                    emptyInbox: "Your inbox is empty.", openApp: "Open MailDots to see your emails.",
                    noSubject: "(No subject)")
    }
}

extension InboxSnapshot {
    /// Galeri önizlemesi (gerçek veri değil).
    static var sample: InboxSnapshot {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let tr = Texts.isTurkish
        return InboxSnapshot(account: "ad@sirketiniz.com", unread: 2, items: [
            .init(uid: 5, folder: "INBOX", from: "Ayşe Yılmaz", subject: tr ? "Toplantı notları" : "Meeting notes", date: now - 600_000, seen: false),
            .init(uid: 4, folder: "INBOX", from: "Mehmet Kaya", subject: tr ? "Teklif hakkında" : "About the offer", date: now - 3_600_000, seen: false),
            .init(uid: 3, folder: "INBOX", from: "Destek", subject: tr ? "Talebiniz yanıtlandı" : "Your request was answered", date: now - 7_200_000, seen: true),
            .init(uid: 2, folder: "INBOX", from: "Elif Demir", subject: tr ? "Fatura" : "Invoice", date: now - 90_000_000, seen: true),
            .init(uid: 1, folder: "INBOX", from: "Can Öztürk", subject: tr ? "Haftalık rapor" : "Weekly report", date: now - 180_000_000, seen: true),
        ])
    }
}

extension View {
    /// iOS 17+'da sistem zemini ve kenar boşlukları; iOS 16'da elle.
    @ViewBuilder
    func widgetBackground<S: ShapeStyle>(_ style: S) -> some View {
        if #available(iOSApplicationExtension 17.0, *) {
            containerBackground(style, for: .widget)
        } else {
            padding(14).background(style)
        }
    }
}
