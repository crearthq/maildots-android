import UIKit
import UserNotifications
import ComposeApp
#if canImport(FirebaseMessaging)
import FirebaseCore
import FirebaseMessaging
#endif

/// Pencere: ortak Compose ekranları doğrudan kök denetleyicidir
/// (durum çubuğu stilini uygulamanın teması belirlesin diye SwiftUI sarmalayıcısı yok).
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = MainViewControllerKt.MainViewController()
        window.makeKeyAndVisible()
        self.window = window
        // Widget satırına dokunularak açıldıysa ilgili ileti açılır.
        connectionOptions.urlContexts.forEach { WidgetPublisher.open($0.url) }
        // Statik açılış ekranının ardından logo kendini çizerek belirir (Android'deki gibi).
        SplashView(frame: window.bounds, dark: windowScene.traitCollection.userInterfaceStyle == .dark).play(in: window)
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        URLContexts.forEach { WidgetPublisher.open($0.url) }
    }
}

/// Bildirim: APNs belirteci Firebase'e verilir, Firebase belirteci ortak koda gider.
@main
final class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    /// GoogleService-Info.plist kullanıcı tarafından yerelde konur; yoksa bildirim kapalıdır.
    private var firebaseReady = false

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        #if canImport(FirebaseMessaging)
        if Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil {
            FirebaseApp.configure()
            Messaging.messaging().delegate = self
            firebaseReady = true
        }
        #endif
        if !firebaseReady { NSLog("MailDotsPush Firebase yapılandırması yok; bildirim kapalı") }
        UNUserNotificationCenter.current().delegate = self
        WidgetPublisher.install()
        return true
    }

    // Saklanmış eski sahne oturumları da bu pencere kurucusuyla açılsın.
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        return config
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        #if canImport(FirebaseMessaging)
        if firebaseReady { Messaging.messaging().apnsToken = deviceToken }
        #endif
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        NSLog("MailDotsPush APNs kaydı başarısız: %@", error.localizedDescription)
    }

    // Uygulama öndeyken de bildirim gösterilir.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    // Bildirime dokununca ilgili hesap ve ileti açılır.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo
        if let account = info["account"] as? String {
            let folder = info["folder"] as? String ?? ""
            let uid = Int64(info["uid"] as? String ?? "") ?? (info["uid"] as? NSNumber)?.int64Value ?? 0
            DispatchQueue.main.async {
                MainViewControllerKt.openFromNotification(account: account, folder: folder, uid: uid)
            }
        }
        completionHandler()
    }
}

#if canImport(FirebaseMessaging)
extension AppDelegate: MessagingDelegate {
    // Belirteç ASLA günlüğe yazılmaz; doğrudan ortak koda (Keychain) gider.
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let token = fcmToken, !token.isEmpty else { return }
        DispatchQueue.main.async { MainViewControllerKt.onPushToken(token: token) }
    }
}
#endif
