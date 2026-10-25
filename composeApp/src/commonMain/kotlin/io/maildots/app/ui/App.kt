package io.maildots.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import io.maildots.app.ThemeMode
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import io.maildots.app.AppController
import io.maildots.app.Screen
import io.maildots.app.mail.AttachmentData

/** Platformun sağladığı parçalar (ortak kodda karşılığı olmayanlar). */
class Platform(
    /** Cihaz dili (ör. "tr"). */
    val language: String,
    /** Liste için kısa tarih (bugünse saat). */
    val formatShortDate: (Long) -> String,
    /** Okuma ekranı için tam tarih ve saat. */
    val formatFullDate: (Long) -> String,
    /**
     * HTML gövdeyi güvenli biçimde (betik ve uzak içerik kapalı) gösterir.
     * [light] null ise sistem teması, değilse zorla açık/koyu.
     * [baseUrl] verilirse yalnızca o adresin görsel vekili ("/api/img-proxy") yüklenebilir;
     * başka hiçbir ağ isteğine izin verilmez. null = ağ tamamen kapalı.
     */
    val htmlBody: @Composable (html: String, light: Boolean?, baseUrl: String?, modifier: Modifier) -> Unit,
    /** İndirilen eki önizleme için sistem görüntüleyicisinde açar. */
    val openAttachment: (AttachmentData) -> Unit,
    /** İletiyi yazdırır (HTML ya da düz metin). */
    val print: (title: String, html: String?, text: String?) -> Unit,
    /**
     * Hesabın web ayar sayfasını uygulama içinde gösterir. Uygulamanın oturumu aktarılmaz:
     * kullanıcı sayfada web'e kendisi giriş yapar; platform girişten sonra [url]'e döner.
     * Yalnızca hesap servisinin adresinde gezinilebilir; başka bağlantılar tarayıcıya gider.
     * Sayfa kapanınca çerezler atılır (her açılışta yeniden giriş istenir).
     */
    val webPage: @Composable (url: String, modifier: Modifier) -> Unit = { _, _ -> },
    /**
     * Sistem dosya seçicisini açar; kullanıcının seçtiği dosyaları (en çok [maxBytes] okunarak) döndürür.
     * Uygulama depolamaya erişim izni istemez: yalnızca kullanıcının tek tek seçtiği dosyalar okunur.
     */
    val pickAttachments: (maxBytes: Long, onPicked: (List<io.maildots.app.mail.PickedFile>) -> Unit) -> Unit = { _, _ -> },
    /** Uygulama teması değişince çağrılır (durum çubuğu simgelerini uydurmak için). */
    val onThemeChanged: (dark: Boolean) -> Unit = {},
    /** Tema seçimi değişince çağrılır: sistemin açılış ekranı da aynı temayı kullansın diye. */
    val onThemeMode: (ThemeMode) -> Unit = {},
    /**
     * Listede sağ alttaki "Yeni e-posta" düğmesinin platforma özgü hâli (iOS 26+: sistemin
     * Liquid Glass düğmesi). null ise Material düğmesi çizilir. Yerel düğme Compose'un üstünde
     * durduğundan menü, pencere ya da ekran geçişi sırasında ortak kod onu hiç çağırmaz.
     */
    val composeButton: (@Composable (label: String, onClick: () -> Unit) -> Unit)? = null,
)

/** Uygulamanın o anki teması (sistem değil; ayarlardaki seçim uygulanmış hâli). */
val LocalDarkTheme = staticCompositionLocalOf { false }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun App(controller: AppController, platform: Platform) {
    val state by controller.state.collectAsState()
    val strings = Strings.forLanguage(platform.language)

    val dark = when (state.settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    LaunchedEffect(dark) { platform.onThemeChanged(dark) }
    LaunchedEffect(state.settings.theme) { platform.onThemeMode(state.settings.theme) }

    val avatarLoader: suspend (String) -> ByteArray? = remember(controller) { { email -> controller.avatar(email) } }
    MailDotsTheme(dark = dark) {
        CompositionLocalProvider(
            LocalStrings provides strings,
            LocalDarkTheme provides dark,
            LocalLabels provides state.labels,
            LocalAvatarLoader provides avatarLoader,
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    ScreenHost(
                        screen = state.screen,
                        keepMailbox = state.active != null,
                        backEnabled = state.screen != Screen.Mailbox && !(state.screen is Screen.AddAccount && state.active == null),
                        onBack = { controller.back() },
                    ) { screen ->
                        when (screen) {
                            is Screen.AddAccount ->
                                if (state.welcome && !screen.canCancel) WelcomeScreen(onDone = controller::finishWelcome)
                                else AddAccountScreen(state, screen, controller)
                            Screen.Mailbox -> MailboxScreen(state, controller, platform)
                            is Screen.Reader -> ReaderScreen(state, controller, platform)
                            is Screen.Composer -> ComposerScreen(state, screen, controller, platform)
                            Screen.Settings -> SettingsScreen(state, controller)
                            is Screen.AccountSettings -> AccountSettingsScreen(state, screen, controller)
                            is Screen.AccountFolders -> AccountFoldersScreen(state, screen, controller)
                            is Screen.AccountLabels -> AccountLabelsScreen(state, controller)
                            is Screen.WebSettings -> WebSettingsScreen(state, screen, controller, platform)
                            Screen.Search -> SearchScreen(state, controller, platform)
                        }
                    }
                    // İşlem sonucu bildirimi: ekran değişse de görünür kalır.
                    NoticeToast(state, controller, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
                }
            }
        }
    }
}
