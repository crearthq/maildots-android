import Foundation
import WidgetKit
import ComposeApp

/// Ortak koddan gelen gelen kutusu özetini widget deposuna yazar ve widget'ı yeniler.
enum WidgetPublisher {
    static func install() {
        WidgetBridge.shared.publish = { json in
            if let json { WidgetStore.write(json) } else { WidgetStore.clear() }
            WidgetCenter.shared.reloadAllTimelines()
        }
    }

    /// Widget bağlantısı ise ilgili hesabı açar: maildots://open?... iletiyi, maildots://inbox?...
    /// gelen kutusu listesini. Bağlantıyı başka uygulamalar da açabilir: yalnızca gezinme
    /// yapılır, bilinmeyen hesap yok sayılır.
    @discardableResult
    static func open(_ url: URL) -> Bool {
        guard url.scheme == WidgetLink.scheme, url.host == "open" || url.host == "inbox",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let account = items.first(where: { $0.name == "account" })?.value, account.contains("@")
        else { return false }
        let uid = Int64(items.first(where: { $0.name == "uid" })?.value ?? "") ?? 0
        if url.host == "inbox" || uid <= 0 {
            DispatchQueue.main.async { MainViewControllerKt.openInbox(account: account) }
            return true
        }
        let folder = items.first(where: { $0.name == "folder" })?.value ?? ""
        DispatchQueue.main.async {
            MainViewControllerKt.openFromNotification(account: account, folder: folder, uid: uid)
        }
        return true
    }
}
