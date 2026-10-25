import Foundation
import Security

/// Widget'ın gösterdiği gelen kutusu özeti (uygulama yazar, widget okur).
struct InboxSnapshot: Codable {
    struct Item: Codable, Identifiable {
        let uid: Int64
        let folder: String
        let from: String
        let subject: String
        /// Epoch milisaniye.
        let date: Int64
        let seen: Bool
        var id: Int64 { uid }
    }

    let account: String
    let unread: Int
    let items: [Item]
}

/// Uygulama ile widget arasındaki paylaşılan depo: Keychain'de, yalnızca ikisinin erişebildiği
/// ayrı bir grupta (MDSharedKeychainGroup). Uygulamanın şifre ve oturum kayıtları kendi grubunda
/// kalır; widget onlara erişemez. Erişim: ilk kilit açılışından sonra, yalnızca bu cihazda.
enum WidgetStore {
    private static let service = "io.maildots.widget"
    private static let key = "inbox"

    private static var accessGroup: String? {
        Bundle.main.object(forInfoDictionaryKey: "MDSharedKeychainGroup") as? String
    }

    private static func query() -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        if let group = accessGroup { q[kSecAttrAccessGroup as String] = group }
        return q
    }

    static func read() -> InboxSnapshot? {
        var q = query()
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(InboxSnapshot.self, from: data)
    }

    static func write(_ json: String) {
        let data = Data(json.utf8)
        // Bozuk görüntü yazılmasın.
        guard (try? JSONDecoder().decode(InboxSnapshot.self, from: data)) != nil else { return }
        let update: [String: Any] = [kSecValueData as String: data]
        if SecItemUpdate(query() as CFDictionary, update as CFDictionary) == errSecItemNotFound {
            var add = query()
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    static func clear() {
        SecItemDelete(query() as CFDictionary)
    }
}

/// Widget satırından uygulamaya bağlantı: maildots://open?account=&folder=&uid=
enum WidgetLink {
    static let scheme = "maildots"

    static func message(account: String, folder: String, uid: Int64) -> URL? {
        var c = URLComponents()
        c.scheme = scheme
        c.host = "open"
        c.queryItems = [
            URLQueryItem(name: "account", value: account),
            URLQueryItem(name: "folder", value: folder),
            URLQueryItem(name: "uid", value: String(uid)),
        ]
        return c.url
    }

    /// Gelen kutusu listesi: maildots://inbox?account=
    static func inbox(account: String?) -> URL? {
        var c = URLComponents()
        c.scheme = scheme
        c.host = "inbox"
        if let account { c.queryItems = [URLQueryItem(name: "account", value: account)] }
        return c.url
    }
}
