package io.maildots.app

import kotlin.time.TimeSource
import kotlin.time.TimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration
import io.maildots.app.data.AccountRepository
import io.maildots.app.data.MailboxCache
import io.maildots.app.mail.Account
import io.maildots.app.mail.PickedFile
import io.maildots.app.mail.DraftAttachment
import io.maildots.app.mail.AttachmentPolicy
import io.maildots.app.mail.AttachProblem
import io.maildots.app.mail.Address
import io.maildots.app.mail.AttachmentData
import io.maildots.app.mail.Folder
import io.maildots.app.mail.FolderRole
import io.maildots.app.mail.MailEngine
import io.maildots.app.mail.MailError
import io.maildots.app.mail.MailFilter
import io.maildots.app.mail.MailLabel
import io.maildots.app.mail.MessageDetail
import io.maildots.app.mail.MessageSummary
import io.maildots.app.mail.OutgoingMessage
import io.maildots.app.mail.SearchQuery
import io.maildots.app.mail.ComposeRules
import io.maildots.app.mail.toWebCompose
import io.maildots.app.web.AccountApi
import io.maildots.app.web.AccountService
import io.maildots.app.web.MailApi
import io.maildots.app.web.WebCompose
import io.maildots.app.web.WebSuggestion
import io.maildots.app.web.WebThreadItem
import io.maildots.app.web.WebUsage
import io.maildots.app.web.LabelPalette
import io.maildots.app.web.SignatureText
import io.maildots.app.web.WebError
import io.maildots.app.web.WebLogin
import io.maildots.app.web.WebPrefs
import io.maildots.app.web.E2EApi
import io.maildots.app.web.EncryptedDraft
import io.maildots.app.web.EncryptedSend
import io.maildots.app.web.WebDraftSaved
import io.maildots.app.web.WebVault
import io.maildots.app.e2e.E2EError
import io.maildots.app.e2e.E2EService
import io.maildots.app.e2e.SendPlan
import io.maildots.app.mail.Attachment
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Kullanıcıya gösterilecek hata türü (metni arayüz çevirir). */
enum class ErrorKind {
    AUTH, NETWORK, SECURITY, INVALID_EMAIL, EMPTY_PASSWORD, DUPLICATE, NO_RECIPIENT, TOO_LARGE, RATE_LIMITED, UNKNOWN,

    /** Hesapta uçtan uca şifreleme açık ama bu cihaz (henüz) desteklemiyor. */
    E2E_UNSUPPORTED,

    /** Şifreli gönderim için kasanın bu cihazda açılması gerekiyor. */
    E2E_LOCKED,

    /** Alıcı anahtarları denetlenemedi: hiçbir şey gönderilmedi (şifresize düşülmez). */
    E2E_CHECK_FAILED,
}

/** Hesapta uçtan uca şifreleme var, bu cihazda çekirdek yok. */
class E2EUnsupported : Exception("e2e_unsupported")

/** Şifreli gönderim/taslak için kasa bu cihazda açılmalı. */
class E2ELocked : Exception("e2e_locked")

/** Alıcı anahtarları ya da kasa durumu denetlenemedi (fail-closed: gönderilmedi). */
class E2ECheckFailed(cause: Throwable? = null) : Exception("e2e_check_failed", cause)

/** Giriş korumalı hesapta sunucu düz şifre istedi: şifre gönderilmedi. */
class LoginDowngrade : Exception("login_downgrade")

/**
 * Şifreleme zorunlu tutuldu (masaüstü yazma penceresinde "Şifrele") ama [addresses] için anahtar
 * yok ya da kutuda şifreleme kapalı: hiçbir şey gönderilmedi (şifresize düşülmez).
 */
class E2ERequired(val addresses: List<String>) : Exception("e2e_required")

/** Gönderimin sonucu (durumdan bağımsız: çoklu yazma penceresi de kullanır). */
sealed interface SendOutcome {
    /** Gitti. [degraded]: sunucu önem / okundu bilgisi / satır içi görseli tanımadı, onlarsız gitti. */
    data class Sent(val encrypted: Boolean, val failedBcc: List<String>, val degraded: Boolean = false) : SendOutcome

    /** Kullanıcı onayı gerekiyor (şifresiz gönderim / değişmiş anahtar); hiçbir şey gitmedi. */
    data class NeedsConsent(val consent: SendConsent) : SendOutcome
}

/** Yazarken şifreleme önizlemesi: kutunun durumu ve anahtarı olmayan alıcılar (son karar gönderimde). */
data class EncryptionPreview(val status: E2EStatus, val missing: List<String>)

fun Throwable.toErrorKind(): ErrorKind = when (this) {
    is E2EUnsupported -> ErrorKind.E2E_UNSUPPORTED
    is E2ELocked -> ErrorKind.E2E_LOCKED
    is E2ECheckFailed -> ErrorKind.E2E_CHECK_FAILED
    is MailError.Auth -> ErrorKind.AUTH
    is MailError.Network -> ErrorKind.NETWORK
    is MailError.Security -> ErrorKind.SECURITY
    is MailError.TooLarge -> ErrorKind.TOO_LARGE
    is WebError.Network -> ErrorKind.NETWORK
    is WebError.Unauthorized -> ErrorKind.AUTH
    is WebError.TooLarge -> ErrorKind.TOO_LARGE
    is WebError.RateLimited -> ErrorKind.RATE_LIMITED
    else -> ErrorKind.UNKNOWN
}

/** Listede bir iletiyi kaydırınca yapılacak işlem. */
enum class SwipeAction { TOGGLE_READ, DELETE, ARCHIVE, STAR, SPAM, NONE }

/** Uygulama teması: sistemi izle ya da zorla açık/koyu. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val swipeRight: SwipeAction = SwipeAction.TOGGLE_READ,
    val swipeLeft: SwipeAction = SwipeAction.DELETE,
    val theme: ThemeMode = ThemeMode.SYSTEM,
)

enum class ComposeMode { NEW, REPLY, REPLY_ALL, FORWARD, DRAFT }

sealed interface Screen {
    /** Hesap ekleme (ilk açılış ya da ek hesap). */
    data class AddAccount(val canCancel: Boolean) : Screen
    data object Mailbox : Screen
    data object Search : Screen
    data class Reader(val folderPath: String, val uid: Long, val fromSearch: Boolean = false) : Screen
    data class Composer(val mode: ComposeMode = ComposeMode.NEW, val source: MessageDetail? = null) : Screen
    data object Settings : Screen

    /** Tek hesabın ayarları (ad, imza, klasörler, etiketler, çıkış). */
    data class AccountSettings(val email: String) : Screen
    data class AccountFolders(val email: String) : Screen
    data class AccountLabels(val email: String) : Screen

    /** Hesabın web ayarları (webmail), uygulama içinde oturum açık olarak. */
    data class WebSettings(val email: String) : Screen
}

/**
 * Etiket tanımı: etiket + rengi (ARGB). [name] ve [id] hesap servisinden gelen
 * etiketlerde doludur; hazır etiketlerde ad dil dosyasından gelir.
 */
@Serializable
data class LabelInfo(val label: MailLabel, val color: Long, val name: String = "", val id: String = "")

/** Hesabın, hesap servisiyle (webmail) eşleşme durumu. */
enum class WebStatus { SYNCING, SYNCED, NEEDS_CODE, AUTH_FAILED, OFFLINE }

/** Hesap ayarlarında gösterilecek sorun (metni arayüz çevirir). */
enum class WebProblem { NETWORK, NAME_TAKEN, INVALID, BAD_CODE, CODE_EXPIRED, RATE_LIMITED, UNKNOWN }

private fun Throwable.toWebProblem(): WebProblem = when (this) {
    is WebError.Network -> WebProblem.NETWORK
    is WebError.NameTaken -> WebProblem.NAME_TAKEN
    is WebError.Invalid -> WebProblem.INVALID
    is WebError.BadCode -> if (expired) WebProblem.CODE_EXPIRED else WebProblem.BAD_CODE
    is WebError.RateLimited -> WebProblem.RATE_LIMITED
    else -> WebProblem.UNKNOWN
}

/** Hazır etiketlerin varsayılan renkleri ve yeni etiket için renk seçenekleri. */
object LabelColors {
    /** Hesap servisinin (webmail) renkleriyle aynı palet: iki tarafta aynı renk görünür. */
    val PALETTE = LabelPalette.COLORS.map { it.second }
    val DEFAULTS = listOf(
        LabelInfo(MailLabel.IMPORTANT, LabelPalette.argb("red")),
        LabelInfo(MailLabel.WORK, LabelPalette.argb("orange")),
        LabelInfo(MailLabel.PERSONAL, LabelPalette.argb("green")),
        LabelInfo(MailLabel.TODO, LabelPalette.argb("blue")),
        LabelInfo(MailLabel.LATER, LabelPalette.argb("purple")),
    )

    /** Bu cihazda tanımı olmayan (başka cihazda oluşturulmuş) etiket için sabit bir renk. */
    fun fallback(label: MailLabel): Long =
        PALETTE[(label.keyword.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7fffffff }) % PALETTE.size]

    /** Kayıtlı tanımları varsayılanlarla birleştirir: hazır beş etiket her zaman vardır. */
    fun merge(stored: List<LabelInfo>): List<LabelInfo> =
        DEFAULTS.map { d -> stored.firstOrNull { it.label == d.label } ?: d } +
            stored.filter { !it.label.isBuiltin && it.label.customName != null }.distinctBy { it.label }
}

data class MailboxState(
    val folders: List<Folder> = emptyList(),
    val folderPath: String = "INBOX",
    /** Klasör içi süzme (Okunmamış / Yıldızlı / Ekli görünümleri). */
    val filter: MailFilter = MailFilter.ALL,
    /** Etiket görünümü (yoksa null): tüm klasörlerde o etiketi taşıyan iletiler. */
    val label: MailLabel? = null,
    val messages: List<MessageSummary> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    /** Sonraki sayfa yüklenemedi (altta "tekrar dene" gösterilir). */
    val moreFailed: Boolean = false,
    val error: ErrorKind? = null,
) {
    /** Hesap ilk kez açılıyor: henüz hiçbir şey yüklenmedi (geçiş ekranı gösterilir). */
    val opening: Boolean get() = loading && folders.isEmpty() && messages.isEmpty()
}

data class SearchState(
    val query: SearchQuery = SearchQuery(),
    val results: List<MessageSummary> = emptyList(),
    val loading: Boolean = false,
    /** En az bir arama yapıldı mı ("sonuç yok" ile "henüz aranmadı" ayrımı). */
    val searched: Boolean = false,
    val error: ErrorKind? = null,
)

data class ReaderState(
    val loading: Boolean = false,
    val detail: MessageDetail? = null,
    val error: ErrorKind? = null,
    /** İndirilmekte olan ekin sırası (yoksa null). */
    val openingAttachment: Int? = null,
    val attachmentError: ErrorKind? = null,
    /** Aynı konuşmadaki iletiler (en eskiden yeniye; tek iletiyse boş). */
    val thread: List<WebThreadItem> = emptyList(),
    val translating: Boolean = false,
    /** Çevrilmiş gövde (gösteriliyorsa) ve kaynak dili. */
    val translatedHtml: String? = null,
    val translatedFrom: String = "",
    val translateFailed: Boolean = false,
    /** Uçtan uca şifreli ileti açılamadı (nedenini arayüz gösterir; kilitliyse şifre istenir). */
    val e2eProblem: E2EProblem? = null,
    val unlocking: Boolean = false,
)

/** Şifreli iletinin açılamama nedeni. */
enum class E2EProblem {
    /** Kasa bu cihazda açık değil: şifreyle açılır. */
    LOCKED,

    /** Şifre kasayı açmadı. */
    WRONG_PASSWORD,

    /** İleti bu anahtara şifrelenmemiş (başka bir anahtar) ya da bozuk. */
    NOT_FOR_THIS_KEY,

    /** Bu cihazda şifreleme desteklenmiyor. */
    UNSUPPORTED,

    NETWORK,
    FAILED,
}

/** Hesabın uçtan uca şifreleme durumu (bu cihazda). */
enum class E2EStatus {
    /** Kasa yok: posta sıradan (şifresiz) çalışır. */
    OFF,

    /** Kasa var ve anahtar bu cihazda açık. */
    READY,

    /** Kasa var ama anahtar bu cihazda yok: şifreli posta için şifreyle açılmalı. */
    LOCKED,
}

/** Gönderimde kullanıcı onayı gereken durum (şifresiz gönderim ya da değişmiş anahtar). */
sealed interface SendConsent {
    data class Plain(val addresses: List<String>, val quotesEncrypted: Boolean) : SendConsent
    data class KeyChanged(val addresses: List<String>, val fingerprints: Map<String, String>) : SendConsent
}

/** Yazma ekranının, alanların dışındaki durumu. */
data class ComposeState(
    /** Hesap servisinde kayıtlı taslak (her kayıtta yenisiyle değişir; gönderilince silinir). */
    val draftUid: Long? = null,
    /** Gönderen olarak seçilebilecek adresler (kutu + takma adlar; boşsa yalnızca kutu). */
    val fromOptions: List<String> = emptyList(),
    val from: String? = null,
    /** İletilen / taslaktaki ekler indiriliyor. */
    val preparing: Boolean = false,
    val savingDraft: Boolean = false,
    /** En son değişiklikler taslağa kaydedildi. */
    val draftSaved: Boolean = false,
    /** Yazılan alıcı için öneriler (adres defteri, son yazılanlar, şirket). */
    val suggestions: List<WebSuggestion> = emptyList(),
    /** Gönderim onay bekliyor (şifresiz gönderim / değişmiş alıcı anahtarı). */
    val consent: SendConsent? = null,
    /** Son gönderim uçtan uca şifreli gitti. */
    val sentEncrypted: Boolean = false,
    /** Ulaştırılamayan gizli alıcılar (şifreli gönderimde). */
    val failedBcc: List<String> = emptyList(),
)

/** Yazma ekranındaki alanlar (taslak kaydı ve gönderim için). */
data class ComposeFields(
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
) {
    val isBlank: Boolean get() = to.isBlank() && cc.isBlank() && bcc.isBlank() && subject.isBlank() && body.isBlank()
}

/** Toplu seçimde bir ileti. */
data class MessageKey(val folder: String, val uid: Long)

/** Toplu işlemler (liste seçim çubuğu). */
enum class BulkAction { READ, UNREAD, STAR, UNSTAR, ARCHIVE, DELETE, SPAM, NOT_SPAM }

/** Şifre işlemlerinde gösterilecek sorun. */
enum class PasswordProblem {
    WRONG_CURRENT, POLICY, AUTH, NETWORK, UNKNOWN,

    /** Giriş korumalı hesapta sunucu korumayı kapalı gösterdi: şifre gönderilmedi. */
    SECURITY,
}

data class AppState(
    val accounts: List<Account> = emptyList(),
    val active: Account? = null,
    val screen: Screen = Screen.AddAccount(canCancel = false),
    val mailbox: MailboxState = MailboxState(),
    val search: SearchState = SearchState(),
    val recentSearches: List<String> = emptyList(),
    /**
     * Etkin hesabın etiket tanımları. Hesap servisiyle eşleşmişse oradaki etiketler,
     * değilse hazır beş etiket + bu cihazda oluşturulanlar.
     */
    val labels: List<LabelInfo> = LabelColors.DEFAULTS,
    /** Hesap başına hesap servisi (webmail) eşleşme durumu. */
    val web: Map<String, WebStatus> = emptyMap(),
    /** Hesap ayarlarında gösterilecek son sorun. */
    val webProblem: WebProblem? = null,
    val reader: ReaderState = ReaderState(),
    val settings: Settings = Settings(),
    /** Hesap başına gelen kutusundaki okunmamış sayısı (menüdeki rozetler). */
    val unreadByAccount: Map<String, Int> = emptyMap(),
    /** Hesap ekleme / gönderme gibi tek seferlik işlemler. */
    val busy: Boolean = false,
    val formError: ErrorKind? = null,
    /** Kısa bilgi mesajı (ör. "gönderildi"); gösterildikten sonra temizlenir. */
    val notice: Notice? = null,
    /** İlk açılış: giriş ekranından önce karşılama sayfaları gösterilir. */
    val welcome: Boolean = false,
    /** İleti az önce gönderildi: yazma ekranı kapanmadan önce onay gösterilir (alıcı özeti). */
    val sentTo: String? = null,
    /** Yazılan iletiye eklenmiş dosyalar (içerikleri denetleyicide tutulur). */
    val drafts: List<DraftAttachment> = emptyList(),
    /** Son eklenemeyen dosyanın nedeni ve adı. */
    val attachProblem: AttachProblem? = null,
    val attachProblemName: String? = null,
    val compose: ComposeState = ComposeState(),
    /** Listede seçili iletiler (boşsa seçim kipi kapalı). */
    val selection: Set<MessageKey> = emptySet(),
    /** Hesap başına depolama kullanımı (hesap ayarlarında). */
    val usage: Map<String, WebUsage> = emptyMap(),
    val passwordProblem: PasswordProblem? = null,
    /** Şifre kuralı açıklaması (sunucudan; [PasswordProblem.POLICY] ile). */
    val passwordPolicy: String = "",
    val passwordBusy: Boolean = false,
    /** Hesap başına uçtan uca şifreleme durumu (yalnızca yeni sistemdeki hesaplar). */
    val e2e: Map<String, E2EStatus> = emptyMap(),
    /** Hesap başına görülmemiş şifreleme kurcalaması (T3; kırmızı bant). */
    val encryptionAlerts: Map<String, io.maildots.app.web.EncryptionAlert> = emptyMap(),
    /** Hesap başına şifrelemenin açıldığı an (sonrasında düz saklanan ileti işaretlenir). */
    val protectedSince: Map<String, Long> = emptyMap(),
    /** Şifreleme kurulumu bekleyen (yeni) hesaplar (T2; kurulum web postada). */
    val onboardingPending: Set<String> = emptySet(),
)

enum class Notice {
    SENT, DELETED, ARCHIVED, MOVED, SPAM, FOLDER_CREATED, FOLDER_RENAMED, FOLDER_DELETED,
    DRAFT_SAVED, DRAFT_DISCARDED, NOT_SPAM, FOLDER_EMPTIED, PASSWORD_CHANGED, PASSWORD_UPDATED, MARKED,
}

private fun MessageSummary.sameAs(other: MessageSummary) = uid == other.uid && folderPath == other.folderPath

/**
 * Uygulama durumu ve işlemler. Arayüz yalnızca [state] okur ve buradaki
 * fonksiyonları çağırır; posta motoruna doğrudan dokunmaz.
 */
private val LOGIN_BACKOFF_START = 30.seconds
private val LOGIN_BACKOFF_MAX = 5.minutes

/** Şifreleme denetimi (T3) hesap başına en çok bu sıklıkta. */
private val ENCRYPTION_CHECK_EVERY = 60.seconds

class AppController(
    private val engine: MailEngine,
    private val repository: AccountRepository,
    private val scope: CoroutineScope,
    /** Hesap servisi (webmail.maildots.com): giriş, ayarlar, bildirim, uçtan uca şifreleme. */
    private val api: AccountApi? = null,
    /** Webmail'in posta işlemleri (toplu işlem, spam değil, klasör boşaltma, konuşma). */
    private val mailApi: MailApi? = api as? MailApi,
    /** Uçtan uca şifreleme (platformun kripto çekirdeği); null = bu cihazda yok. */
    private val e2e: E2EService? = null,
    /** Giriş geri çekilmesi için saat (testlerde değiştirilebilir). */
    private val clock: TimeSource = TimeSource.Monotonic,
    /**
     * Açılan ileti hemen okundu yapılsın mı (Android/iOS: evet). Masaüstü false verir: okundu
     * yapma zamanını kendisi seçer (hemen / N saniye sonra / seçim değişince) ve [markSeen] çağırır.
     */
    private val markReadOnOpen: Boolean = true,
    /**
     * Teşhis satırı (yalnız hata sınıfı/durum kodu; içerik yok). Android/iOS: println (bugünkü
     * davranış). Masaüstü yalnız -Dmaildots.debug=true ile yazar.
     */
    private val diagnostic: (String) -> Unit = { println(it) },
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var typingJob: Job? = null

    /** Bu oturumda adı aranmış hesaplar (her yenilemede yeniden aranmasın). */
    private val nameLookedUp = HashSet<String>()

    /** Bu oturumda hesap servisiyle eşleşmesi denenmiş hesaplar. */
    private val webTried = HashSet<String>()

    /** İki adımlı doğrulama bekleyen girişler (yalnızca bellekte, kısa ömürlü). */
    private val pendingCodes = HashMap<String, String>()

    /** Hesabın etiket tanımları: eşleşmişse servisteki liste, değilse hazır + yerel etiketler. */
    private fun labelsFor(account: Account): List<LabelInfo> {
        val stored = repository.loadLabels(account.email)
        return if (account.webLinked) stored else LabelColors.merge(stored)
    }

    init {
        // Yalnızca maildots.com hesapları: eski sistemden (servis adresi boş) kalan kayıtlar bırakılır.
        val stored = repository.load()
        val accounts = stored.filter { it.service.isNotEmpty() }
        if (accounts.size != stored.size) repository.save(accounts)
        val active = accounts.firstOrNull { it.email == repository.activeEmail } ?: accounts.firstOrNull()
        _state.value = AppState(
            accounts = accounts,
            active = active,
            screen = if (active == null) Screen.AddAccount(canCancel = false) else Screen.Mailbox,
            // Hesabı olan (eski) kullanıcıya karşılama gösterilmez.
            welcome = active == null && !repository.welcomeSeen,
            // Son görülen gelen kutusu hemen gösterilir; sunucudan gelen güncel hâl üstüne yazılır.
            mailbox = active?.let { cachedMailbox(it.email) } ?: MailboxState(),
            settings = repository.loadSettings(),
            recentSearches = repository.loadRecentSearches(),
            labels = active?.let { labelsFor(it) } ?: LabelColors.DEFAULTS,
        )
        // İlk yenileme sınıfın sonundaki init'te: aşağıda tanımlı alanlar henüz ilklenmedi.
    }

    /** Karşılama tamamlandı ya da atlandı: bir daha gösterilmez. */
    fun finishWelcome() {
        repository.welcomeSeen = true
        // Hesap varken (önizleme) kutuya dönülür.
        _state.update { it.copy(welcome = false, screen = if (it.active != null) Screen.Mailbox else it.screen) }
    }

    /** Karşılamayı yeniden gösterir (yalnızca geliştirme derlemesinde, tasarım denemesi için çağrılır). */
    fun previewWelcome() = _state.update { it.copy(welcome = true, screen = Screen.AddAccount(canCancel = false)) }

    // ---- hesaplar

    fun addAccount(emailRaw: String, password: String) {
        val email = emailRaw.trim().lowercase()
        val current = _state.value
        val error = when {
            !EMAIL_REGEX.matches(email) -> ErrorKind.INVALID_EMAIL
            password.isEmpty() -> ErrorKind.EMPTY_PASSWORD
            current.accounts.any { it.email == email } -> ErrorKind.DUPLICATE
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(formError = error) }
            return
        }
        _state.update { it.copy(busy = true, formError = null) }
        scope.launch {
            try {
                // Kutu sunucuda mı ve giriş koruması açık mı? Sunucu söyler (şifre gönderilmeden).
                val pre = discover(email) ?: throw MailError.Auth()
                val signedIn = signInModern(email, password, pre)
                val account = signedIn.account
                val accounts = _state.value.accounts + account
                repository.save(accounts)
                repository.activeEmail = account.email
                _state.update {
                    it.copy(
                        accounts = accounts,
                        active = account,
                        busy = false,
                        screen = Screen.Mailbox,
                        mailbox = MailboxState(),
                        search = SearchState(),
                        labels = labelsFor(account),
                    )
                }
                // Kasa, girişte türetilen kilit anahtarıyla açılır (şifre ikinci kez sorulmaz).
                if (account.webSession.isNotEmpty()) {
                    launchUnlockAfterSignIn(email, account.webSession, signedIn.password, signedIn.lockKey)
                }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, formError = e.toErrorKind()) }
            }
        }
    }

    /** Yeni sistemde girişin sonucu: hesap ve kasayı açmak için gerekenler (yalnızca bellekte). */
    private class ModernSignIn(val account: Account, val password: String?, val lockKey: String?)

    /**
     * Giriş öncesi keşif (şifre gönderilmeden): kutu sunucuda mı, giriş koruması açık mı?
     * Kutu yoksa null. Sunucunun verdiği hata ya da anlaşılmayan yanıt girişi durdurur.
     */
    private suspend fun discover(email: String): io.maildots.app.web.Prelogin? {
        val api = api as? E2EApi ?: throw MailError.Unknown()
        val pre = try {
            api.prelogin(email)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebError.Network) {
            throw MailError.Network(e)
        } catch (e: WebError.RateLimited) {
            throw e
        } catch (e: Throwable) {
            throw MailError.Network(e)
        }
        return pre.takeIf { it.hosted }
    }

    /**
     * Giriş. Giriş koruması açıksa şifre cihazda giriş anahtarına çevrilir;
     * sunucuya ve kayda yalnızca o gider (Proton'daki gibi şifre cihazdan çıkmaz).
     */
    private suspend fun signInModern(email: String, password: String, pre: io.maildots.app.web.Prelogin): ModernSignIn {
        val service = api ?: throw MailError.Unknown()
        val secret = if (pre.kdf == null) io.maildots.app.e2e.SignInSecret(password, null, protected = false)
        else (e2e ?: throw E2EUnsupported()).signInSecret(pre, password)
        val result = try {
            service.login(email, secret.secret)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebError.Network) {
            throw MailError.Network(e)
        }
        val base = Account(
            email = email,
            password = secret.secret,
            service = AccountService.BASE_URL,
            loginProtected = secret.protected,
        )
        // Korumalı kutuda şifre tutulmaz; korumasız kutuda kasa (varsa) şifreyle açılır.
        val keepPassword = if (secret.protected) null else password
        return when (result) {
            WebLogin.Invalid -> throw MailError.Auth()
            is WebLogin.NeedsCode -> {
                pendingCodes[email] = result.pending
                pendingUnlock[email] = keepPassword to secret.lockKey
                ModernSignIn(base, keepPassword, secret.lockKey)
            }
            is WebLogin.Ok -> ModernSignIn(base.copy(webSession = result.session), keepPassword, secret.lockKey)
        }
    }

    fun switchAccount(email: String) {
        val account = _state.value.accounts.firstOrNull { it.email == email } ?: return
        repository.activeEmail = account.email
        _state.update {
            it.copy(
                active = account,
                screen = Screen.Mailbox,
                mailbox = cachedMailbox(account.email),
                search = SearchState(),
                labels = labelsFor(account),
            )
        }
        refresh()
    }

    fun removeAccount(email: String) {
        val removed = _state.value.accounts.firstOrNull { it.email == email } ?: return
        val accounts = _state.value.accounts - removed
        repository.save(accounts)
        val active = if (_state.value.active?.email == email) accounts.firstOrNull() else _state.value.active
        repository.activeEmail = active?.email
        repository.removeMailbox(email)
        scope.launch { runCatching { engine.disconnect(removed) } }
        // Hesap servisindeki oturumu da kapat (sunucuda açık oturum kalmasın).
        pushRegistered.remove(email)
        val service = apiOf(removed)
        if (service != null && removed.webSession.isNotEmpty()) {
            val token = repository.pushToken
            scope.launch {
                // Sıra önemli: bildirim kaydı oturum açıkken silinir (cihaz artık bu kutunun postasını almasın).
                if (token != null) runCatching { service.unregisterPush(removed.webSession, token) }
                runCatching { service.logout(removed.webSession) }
            }
        }
        // Bu cihazda açılmış kasa anahtarı hesapla birlikte gider.
        e2e?.forget(email)
        pendingUnlock.remove(email)
        decryptedParts.keys.removeAll { it.startsWith("$email|") }
        _state.update { it.copy(e2e = it.e2e - email) }
        repository.removeLabels(email)
        pendingCodes.remove(email)
        _state.update {
            it.copy(
                accounts = accounts,
                active = active,
                screen = when {
                    active == null -> Screen.AddAccount(canCancel = false)
                    it.screen is Screen.AccountSettings || it.screen == Screen.Settings -> Screen.Settings
                    else -> Screen.Mailbox
                },
                mailbox = MailboxState(),
                search = SearchState(),
                labels = active?.let { a -> labelsFor(a) } ?: LabelColors.DEFAULTS,
                web = it.web - email,
                unreadByAccount = it.unreadByAccount - email,
            )
        }
        if (active != null) refresh()
    }

    // ---- ayarlar

    fun setTheme(theme: ThemeMode) {
        val settings = _state.value.settings.copy(theme = theme)
        repository.saveSettings(settings)
        _state.update { it.copy(settings = settings) }
    }

    fun setSwipeActions(right: SwipeAction, left: SwipeAction) {
        val settings = _state.value.settings.copy(swipeRight = right, swipeLeft = left)
        repository.saveSettings(settings)
        _state.update { it.copy(settings = settings) }
    }

    // ---- gezinme

    fun openAddAccount() = _state.update { it.copy(screen = Screen.AddAccount(canCancel = true), formError = null) }

    fun openSettings() = _state.update { it.copy(screen = Screen.Settings) }

    fun openAccountSettings(email: String) {
        if (_state.value.accounts.none { it.email == email }) return
        _state.update { it.copy(screen = Screen.AccountSettings(email), webProblem = null) }
        syncWeb(email)
        loadUsage(email)
    }

    /** Etiketler hesaba özgüdür: başka bir hesabın etiketleri açılırken o hesaba geçilir. */
    fun openAccountLabels(email: String) {
        if (_state.value.accounts.none { it.email == email }) return
        if (_state.value.active?.email != email) switchAccount(email)
        _state.update { it.copy(screen = Screen.AccountLabels(email), webProblem = null) }
    }

    /** Hesabın web ayarlarını uygulama içinde açar (oturum yoksa önce kurar). */
    fun openWebSettings(email: String) {
        if (apiFor(email) == null) return
        // Uygulamanın oturumu sayfaya aktarılmaz; kullanıcı web'e sayfada kendisi giriş yapar.
        _state.update { it.copy(screen = Screen.WebSettings(email), webProblem = null) }
    }

    // ---- hesap servisi (webmail) eşleşmesi

    private fun account(email: String): Account? = _state.value.accounts.firstOrNull { it.email == email }

    /** Hesap servisi (kayıtlı hesap için). */
    private fun apiFor(email: String): AccountApi? = apiOf(account(email))

    private fun apiOf(account: Account?): AccountApi? = if (account == null) null else api

    private fun mailApiFor(email: String): MailApi? = if (account(email) == null) null else mailApi

    /** Uçtan uca şifreleme uçları. */
    private fun e2eApiFor(email: String): E2EApi? = if (account(email) == null) null else api as? E2EApi

    private fun updateAccount(email: String, transform: (Account) -> Account) {
        val accounts = _state.value.accounts.map { if (it.email == email) transform(it) else it }
        if (accounts.none { it.email == email }) return
        repository.save(accounts)
        _state.update {
            it.copy(accounts = accounts, active = accounts.firstOrNull { a -> a.email == it.active?.email } ?: it.active)
        }
    }

    private fun setWeb(email: String, status: WebStatus) = _state.update { it.copy(web = it.web + (email to status)) }

    /**
     * Hesap servisine giriş. ÖNEMLİ: reddedilen giriş, sunucu tarafında başarısız bir
     * posta girişi demektir; bu yüzden reddedilince KENDİLİĞİNDEN yeniden denenmez.
     */
    private suspend fun webLogin(email: String): String? {
        val service = apiFor(email) ?: return null
        val current = account(email) ?: return null
        val result = try {
            service.login(current.email, current.password)
        } catch (e: WebError) {
            // Deneme sınırı, ağ ya da sunucu hatası: bir süre (30 sn'den başlayıp katlanarak,
            // en çok 5 dk) kendiliğinden yeniden giriş denenmez; her istek bir giriş daha yapmasın.
            if (e !is WebError.Unauthorized) {
                val delay = loginBackoff[email]?.second?.let { minOf(it * 2, LOGIN_BACKOFF_MAX) } ?: LOGIN_BACKOFF_START
                loginBackoff[email] = (clock.markNow() + delay) to delay
            }
            throw e
        }
        return when (result) {
            is WebLogin.Ok -> {
                pendingCodes.remove(email)
                rejectedLogins.remove(email)
                loginBackoff.remove(email)
                updateAccount(email) { it.copy(webSession = result.session) }
                result.session
            }
            is WebLogin.NeedsCode -> {
                pendingCodes[email] = result.pending
                setWeb(email, WebStatus.NEEDS_CODE)
                null
            }
            WebLogin.Invalid -> {
                rejectedLogins.add(email)
                setWeb(email, WebStatus.AUTH_FAILED)
                null
            }
        }
    }

    /**
     * Şifresi sunucuca reddedilen hesaplar: kullanıcı yeni şifreyi girene kadar hiçbir istek
     * kendiliğinden giriş denemez (durum alanı eşitlemede değişebildiği için ayrı tutulur).
     */
    private val rejectedLogins = HashSet<String>()

    /** Geçici giriş hatasından sonra bir sonraki kendiliğinden denemenin zamanı ve son bekleme. */
    private val loginBackoff = HashMap<String, Pair<TimeMark, Duration>>()

    /** Hesap başına giriş kilidi: aynı anda gelen istekler tek bir girişte birleşir (deneme sınırı). */
    private val loginLocks = HashMap<String, Mutex>()

    /**
     * Kutunun oturumunu verir; yoksa giriş yapar. [stale] verilirse o oturum geçersiz sayılır
     * (başka bir istek bu arada yenilediyse yeni oturum döner, ikinci giriş yapılmaz).
     */
    private suspend fun ensureSession(email: String, stale: String? = null): String? {
        val lock = loginLocks.getOrPut(email) { Mutex() }
        return lock.withLock {
            val current = account(email)?.webSession?.takeIf { it.isNotEmpty() }
            when {
                current != null && current != stale -> current
                // İki adımlı doğrulama kodu bekleniyor: yeniden giriş denenmez (deneme sınırı).
                pendingCodes.containsKey(email) -> {
                    setWeb(email, WebStatus.NEEDS_CODE)
                    null
                }
                // Sunucu bu cihazdaki şifreyi reddetti: kullanıcı yeni şifreyi girene kadar
                // (updateStoredPassword) hiçbir istek kendiliğinden giriş denemez. Yoksa her
                // çağrı kayıtlı eski şifreyle bir başarısız giriş daha yapar (deneme sınırı,
                // sunucu tarafında IP engeli).
                email in rejectedLogins -> {
                    if (current != null) updateAccount(email) { it.copy(webSession = "") }
                    setWeb(email, WebStatus.AUTH_FAILED)
                    null
                }
                loginBackoff[email]?.first?.hasNotPassedNow() == true -> null
                else -> {
                    if (current != null) updateAccount(email) { it.copy(webSession = "") }
                    webLogin(email)
                }
            }
        }
    }

    /** Oturumla bir çağrı yapar; oturumun süresi dolmuşsa bir kez yeniden giriş yapıp tekrar dener. */
    private suspend fun <T> web(email: String, block: suspend (AccountApi, String) -> T): T? {
        val service = apiFor(email) ?: return null
        val session = ensureSession(email) ?: return null
        return try {
            block(service, session)
        } catch (e: WebError.Unauthorized) {
            val fresh = ensureSession(email, stale = session) ?: return null
            block(service, fresh)
        }
    }

    /**
     * Bir adresin avatar resmi (etkin hesabın oturumuyla). null = sunucuya göre resmi yok;
     * alınamadıysa (oturum yok, ağ hatası) FIRLATIR: çağıran "resmi yok" diye saklamaz.
     */
    suspend fun avatar(email: String): ByteArray? {
        val active = _state.value.active?.email ?: throw MailError.Unknown()
        // Liste sarmalı: "resmi yok" (null) ile "oturum yok" (web() null) ayrışsın.
        val answer = web(active) { api, session -> listOf(api.avatar(session, email)) } ?: throw MailError.Unknown()
        return answer.single()
    }

    /**
     * Webmail API'si üzerinden çalışan posta motoru için oturum ([io.maildots.app.mail.WebSessions]).
     * iOS'ta motor bununla kurulur: `WebMailEngine(api, api) { a, renew -> controller.webSession(a, renew) }`.
     */
    suspend fun webSession(account: Account, renew: Boolean): String? {
        if (apiFor(account.email) == null) return null
        val stale = if (renew) account(account.email)?.webSession?.takeIf { it.isNotEmpty() } else null
        val session = ensureSession(account.email, stale)
        if (session != null && _state.value.web[account.email] != WebStatus.SYNCED) syncWeb(account.email)
        return session
    }

    /**
     * Ad, etiketler ve imzayı hesap servisinden alır. Oturum başına hesap için bir kez
     * kendiliğinden çalışır; [force] ile (kullanıcı isteğiyle) yeniden çalıştırılır.
     */
    fun syncWeb(email: String, force: Boolean = false) {
        if (apiFor(email) == null) return
        if (!webTried.add(email) && !force) return
        if (_state.value.web[email] == WebStatus.SYNCING) return
        scope.launch {
            setWeb(email, WebStatus.SYNCING)
            try {
                val done = web(email) { service, session ->
                    val profile = service.profile(session)
                    val labels = service.labels(session)
                    val signatures = service.signatures(session)
                    // Tercihler eski sunucu sürümünde olmayabilir: yoksa varsayılan (imza eklenir).
                    val prefs = try {
                        service.prefs(session)
                    } catch (e: WebError.Unauthorized) {
                        throw e
                    } catch (e: WebError) {
                        WebPrefs()
                    }
                    val signature = signatures.firstOrNull { it.isDefault } ?: signatures.firstOrNull()
                    val infos = labels.map { LabelInfo(MailLabel(it.keyword), LabelPalette.argb(it.color), it.name, it.id) }
                    repository.saveLabels(email, infos)
                    nameLookedUp += email
                    updateAccount(email) {
                        it.copy(
                            webLinked = true,
                            // Servisteki ad esastır; orada boşsa cihazdaki ad korunur.
                            displayName = profile.displayName.ifBlank { it.displayName },
                            signature = signature?.let { s -> SignatureText.toText(s.html) }.orEmpty(),
                            signatureId = signature?.id.orEmpty(),
                            signatureRich = signature?.let { s -> SignatureText.isRich(s.html) } ?: false,
                            signatureOnNew = prefs.signatureOnNew,
                            signatureOnReply = prefs.signatureOnReply,
                        )
                    }
                    if (_state.value.active?.email == email) _state.update { it.copy(labels = infos) }
                    true
                }
                // null: giriş tamamlanamadı; durum webLogin içinde (kod gerekli / reddedildi) yazıldı.
                // Yazılmadıysa (geri çekilme, oturum yok) "eşitleniyor"da kalmasın: yoksa sonraki
                // her eşitleme hiç başlamaz.
                if (done == true) {
                    setWeb(email, WebStatus.SYNCED)
                    registerPush(email)
                    refreshE2E(email)
                } else if (_state.value.web[email] == WebStatus.SYNCING) {
                    setWeb(email, WebStatus.OFFLINE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Teşhis için yalnızca hata sınıfı (ve varsa durum kodu); içerik yazılmaz.
                diagnostic("MailDotsWeb: sync failed: ${e::class.simpleName} ${(e as? WebError.Server)?.status ?: ""}")
                setWeb(email, WebStatus.OFFLINE)
            }
        }
    }

    // ---- uçtan uca şifreleme

    /** Girişi iki adımlı kod bekleyen hesabın kasa açılışı için şifre / kilit anahtarı (yalnızca bellekte). */
    private val pendingUnlock = HashMap<String, Pair<String?, String?>>()

    /** Cihazda açılmış şifreli iletilerin ekleri ("hesap|klasör|uid"); yalnızca bellekte, sınırlı. */
    private val decryptedParts = LinkedHashMap<String, List<AttachmentData>>()

    private fun partsKey(email: String, folder: String, uid: Long) = "$email|$folder|$uid"

    private fun rememberParts(key: String, parts: List<AttachmentData>) {
        decryptedParts.remove(key)
        decryptedParts[key] = parts
        while (decryptedParts.size > MAX_DECRYPTED_CACHED) decryptedParts.remove(decryptedParts.keys.first())
    }

    private fun setE2E(email: String, status: E2EStatus) {
        val first = _state.value.e2e[email] == null
        _state.update { it.copy(e2e = it.e2e + (email to status)) }
        // Kasası olduğu ilk kez anlaşılan hesap hemen denetlenir (T3).
        if (first && status != E2EStatus.OFF) checkEncryption(email)
    }

    /** web() sonucu ile "kasa yok" ayrımı (web() oturum açılamazsa null döner). */
    private class VaultAnswer(val vault: WebVault?)

    private suspend fun fetchVault(email: String): VaultAnswer? {
        val api = e2eApiFor(email) ?: return null
        return web(email) { _, session -> VaultAnswer(api.vault(session)) }
    }

    /**
     * Kasa durumu: yoksa OFF; anahtar bu cihazda ve aynı sertifikaysa READY; değilse LOCKED.
     * null = çelişki: bu cihaz bu hesapta kasa gördü (ya da anahtarı duruyor) ama sunucu "kasa
     * yok" diyor. Buna güvenilmez — şifreleme sunucunun bir yanıtıyla sessizce kapanamaz.
     */
    private fun statusOf(email: String, vault: WebVault?): E2EStatus? {
        val service = e2e
        if (vault == null) {
            val seen = service != null && (service.key(email) != null || service.wasEnabled(email))
            return if (seen) null else E2EStatus.OFF
        }
        service?.markEnabled(email)
        val key = e2e?.key(email)
        if (key != null && key.certificatePem.replace(WS, "") != vault.certificatePem.replace(WS, "")) {
            // Kasa yenilenmiş (web'de yeniden kurulmuş): eski anahtar artık işe yaramaz.
            e2e?.forget(email)
            return E2EStatus.LOCKED
        }
        return if (key != null) E2EStatus.READY else E2EStatus.LOCKED
    }

    /** Yeni sistemdeki hesabın kasa durumunu sunucudan okur. */
    private fun refreshE2E(email: String) {
        if (e2eApiFor(email) == null) return
        scope.launch {
            val answer = try {
                fetchVault(email)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            } ?: return@launch
            statusOf(email, answer.vault)?.let { setE2E(email, it) }
        }
    }

    /**
     * Kasa durumu, bilinmiyorsa sunucudan okunarak. Okunamazsa [E2ECheckFailed]: belirsizlikte
     * gönderim ya da taslak kaydı durur (şifresiz yola düşülmez).
     */
    private suspend fun requireE2EStatus(email: String, fresh: Boolean = false): E2EStatus {
        if (account(email) == null) return E2EStatus.OFF
        if (!fresh) _state.value.e2e[email]?.let { return it }
        val answer = try {
            fetchVault(email)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw E2ECheckFailed(e)
        } ?: throw E2ECheckFailed()
        return (statusOf(email, answer.vault) ?: throw E2ECheckFailed()).also { setE2E(email, it) }
    }

    private fun launchUnlockAfterSignIn(email: String, session: String, password: String?, lockKey: String?) {
        scope.launch { unlockAfterSignIn(email, session, password, lockKey) }
    }

    /**
     * Giriş öncesi keşif "düz şifre" dedi ama kasa giriş korumalı: sunucu tutarsız ya da
     * şifreyi almak için korumayı gizledi. Kayıtlı şifre silinir, oturum kapatılır ve
     * kullanıcı uyarılır; kasa bu şifreyle açılmaz.
     */
    private suspend fun rejectDowngrade(email: String, session: String) {
        runCatching { apiFor(email)?.logout(session) }
        updateAccount(email) { it.copy(password = "", webSession = "") }
        setWeb(email, WebStatus.AUTH_FAILED)
        setE2E(email, E2EStatus.LOCKED)
        _state.update { it.copy(mailbox = it.mailbox.copy(error = ErrorKind.SECURITY)) }
    }

    /** Girişten hemen sonra kasayı açar (kilit anahtarı girişte türetildi; şifre ikinci kez sorulmaz). */
    private suspend fun unlockAfterSignIn(email: String, session: String, password: String?, lockKey: String?) {
        val api = e2eApiFor(email) ?: return
        try {
            val vault = api.vault(session)
            if (vault == null) {
                setE2E(email, E2EStatus.OFF)
                return
            }
            val service = e2e
            if (service == null) {
                setE2E(email, E2EStatus.LOCKED)
                return
            }
            if (lockKey == null && vault.loginMode != "password") {
                rejectDowngrade(email, session)
                return
            }
            if (password == null && lockKey == null) {
                statusOf(email, vault)?.let { setE2E(email, it) }
                return
            }
            service.unlock(vault, email, password, lockKey)
            setE2E(email, E2EStatus.READY)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Açılamadı (ör. şifre yönetici tarafından sıfırlandı): okuma ekranı şifre ister.
            setE2E(email, E2EStatus.LOCKED)
        }
    }

    /**
     * Kasayı bu cihazda şifreyle açar (okuma ekranındaki kilit ya da hesap ayarları).
     * Açılırsa okuma ekranındaki ileti yeniden açılır.
     */
    fun unlockE2E(email: String, password: String) {
        if (password.isEmpty() || _state.value.reader.unlocking) return
        val service = e2e ?: run {
            _state.update { it.copy(reader = it.reader.copy(e2eProblem = E2EProblem.UNSUPPORTED)) }
            return
        }
        _state.update { it.copy(reader = it.reader.copy(unlocking = true)) }
        scope.launch {
            val problem = try {
                val answer = fetchVault(email) ?: throw MailError.Auth()
                val vault = answer.vault ?: throw E2EError("no_vault")
                service.unlock(vault, email, password, null)
                setE2E(email, E2EStatus.READY)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: E2EError) {
                if (e.code == "unlock_failed") E2EProblem.WRONG_PASSWORD else E2EProblem.FAILED
            } catch (e: Throwable) {
                if (e.toErrorKind() == ErrorKind.NETWORK) E2EProblem.NETWORK else E2EProblem.FAILED
            }
            _state.update { it.copy(reader = it.reader.copy(unlocking = false, e2eProblem = problem ?: it.reader.e2eProblem)) }
            if (problem == null) {
                val screen = _state.value.screen as? Screen.Reader
                val summary = _state.value.reader.detail?.summary
                if (screen != null && summary != null) openMessage(summary)
            }
        }
    }

    private fun E2EError.toProblem(): E2EProblem = when (code) {
        "locked" -> E2EProblem.LOCKED
        "not_for_this_key", "decrypt_failed", "not_encrypted" -> E2EProblem.NOT_FOR_THIS_KEY
        else -> E2EProblem.FAILED
    }

    /**
     * Şifreli iletiyi cihazda açar. Açılamazsa iletinin kendisi (gövdesiz) ve nedeni döner;
     * gövde hiçbir zaman sunucudan düz metin olarak istenmez.
     */
    private suspend fun decryptDetail(account: Account, detail: MessageDetail, asDraft: Boolean): Pair<MessageDetail, E2EProblem?> {
        val service = e2e ?: return detail to E2EProblem.UNSUPPORTED
        val api = e2eApiFor(account.email) ?: return detail to E2EProblem.UNSUPPORTED
        if (service.key(account.email) == null) return detail to E2EProblem.LOCKED
        val folder = detail.summary.folderPath
        val uid = detail.summary.uid
        return try {
            if (asDraft) {
                val draft = web(account.email) { _, session -> service.openDraft(api, session, account.email, folder, uid) }
                    ?: return detail to E2EProblem.NETWORK
                val parts = draft.attachments.map { AttachmentData(it.filename, it.contentType, Base64.decode(it.contentBase64)) }
                rememberParts(partsKey(account.email, folder, uid), parts)
                detail.copy(
                    html = draft.html,
                    text = null,
                    attachments = parts.map { Attachment(it.name, it.mimeType, it.bytes.size.toLong()) },
                    htmlBaseUrl = null,
                    decrypted = true,
                ) to null
            } else {
                val mail = web(account.email) { _, session -> service.open(api, session, account.email, folder, uid) }
                    ?: return detail to E2EProblem.NETWORK
                val parts = mail.attachments.map { AttachmentData(it.filename, it.contentType, Base64.decode(it.data)) }
                rememberParts(partsKey(account.email, folder, uid), parts)
                // T4: imzalıysa, imzalayan gönderenin dizindeki anahtarı mı?
                val sender = detail.summary.from.email
                val listed = runCatching {
                    web(account.email) { _, session -> api.keyDirectory(session, listOf(sender)) }
                }.getOrNull()?.get(sender.lowercase())
                // Hüküm verilemezse ileti yine açılır; yalnızca "doğrulanamadı" yazar.
                val verdict = runCatching {
                    service.judge(account.email, mail.signature, sender, listed, inLog = { fp ->
                        web(account.email) { _, session -> service.logCheck(api, session, mapOf(sender to fp)) }
                            ?: io.maildots.app.e2e.LogCheck.UNAVAILABLE
                    }, subject = detail.summary.subject)
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    io.maildots.app.e2e.SignatureVerdict.UNKNOWN_KEY
                }
                detail.copy(
                    html = mail.html,
                    text = null,
                    attachments = parts.map { Attachment(it.name, it.mimeType, it.bytes.size.toLong()) },
                    htmlBaseUrl = null,
                    decrypted = true,
                    signature = verdict,
                ) to null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: E2EError) {
            detail to e.toProblem()
        } catch (e: Throwable) {
            detail to if (e.toErrorKind() == ErrorKind.NETWORK) E2EProblem.NETWORK else E2EProblem.FAILED
        }
    }

    /** Ekin içeriği: cihazda açılmış iletide bellekten, değilse sunucudan. */
    private suspend fun attachmentData(account: Account, summary: MessageSummary, index: Int): AttachmentData =
        decryptedParts[partsKey(account.email, summary.folderPath, summary.uid)]?.getOrNull(index)
            ?: if (summary.encrypted) throw MailError.Unknown() else engine.attachment(account, summary.folderPath, summary.uid, index)

    /**
     * Yeni sistemde gönderim. Kasası olan kutuda: herkesin anahtarı varsa uçtan uca şifreli
     * gider; anahtarı olmayan ya da anahtarı değişen alıcı varsa kullanıcıya sorulur
     * ([SendConsent]); belirsizlikte (dizin/anahtar denetimi başarısız) hiçbir şey gitmez.
     * [requireEncryption]: şifresiz gönderim hiç önerilmez; şifrelenemiyorsa [E2ERequired].
     */
    private suspend fun sendModern(
        account: Account,
        message: OutgoingMessage,
        plainFor: Set<String>,
        trusted: Map<String, String>,
        quotesEncrypted: Boolean,
        requireEncryption: Boolean = false,
    ): SendOutcome {
        val email = account.email
        // Her gönderimde sunucudan yeniden okunur (önbellekteki eski "kapalı" bilgisine güvenilmez).
        val status = requireE2EStatus(email, fresh = true)
        if (status == E2EStatus.OFF) {
            if (requireEncryption) throw E2ERequired((message.to + message.cc + message.bcc).distinct())
            val degraded = web(email) { service, session -> plainSend(service, session, message) } ?: throw MailError.Auth()
            return SendOutcome.Sent(encrypted = false, failedBcc = emptyList(), degraded = degraded)
        }
        if (status == E2EStatus.LOCKED) throw E2ELocked()
        val service = e2e ?: throw E2EUnsupported()
        val api = e2eApiFor(email) ?: throw E2EUnsupported()
        val plan = try {
            web(email) { _, session -> service.plan(api, session, email, message.to, message.cc, message.bcc, trusted) }
                ?: throw MailError.Auth()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MailError.Auth) {
            throw e
        } catch (e: E2EError) {
            if (e.code == "locked") throw E2ELocked() else throw E2ECheckFailed(e)
        } catch (e: Throwable) {
            throw E2ECheckFailed(e)
        }
        when (plan) {
            is SendPlan.KeyChanged -> return SendOutcome.NeedsConsent(SendConsent.KeyChanged(plan.addresses, plan.fingerprints))
            is SendPlan.Missing -> {
                if (requireEncryption) throw E2ERequired(plan.addresses)
                if (!plainFor.containsAll(plan.addresses)) return SendOutcome.NeedsConsent(SendConsent.Plain(plan.addresses, quotesEncrypted))
                val degraded = web(email) { s, session -> plainSend(s, session, message) } ?: throw MailError.Auth()
                return SendOutcome.Sent(encrypted = false, failedBcc = emptyList(), degraded = degraded)
            }
            is SendPlan.Encrypted -> {
                if (message.attachments.sumOf { it.bytes.size.toLong() } > MAX_ENCRYPTED_ATTACHMENT_BYTES) throw MailError.TooLarge()
                // Satır içi görseller şifreli gövdeye gömülür (çekirdek Content-ID desteklemez).
                val (html, attachments) = io.maildots.app.mail.InlineImages.forEncryption(
                    message.html ?: io.maildots.app.mail.plainToHtml(message.body),
                    message.attachments,
                )
                val visible = message.to + message.cc
                val main = service.encrypt(account.email, html, attachments, plan.main, visible, message.subject)
                val bccCopies = plan.bcc.map { (address, pem) ->
                    // Kendi imzası, bu gizli alıcıyı adıyla anar: orada "size" sayılır.
                    address to service.encrypt(account.email, html, attachments, listOf(pem), visible + address, message.subject)
                }
                val (failed, degraded) = web(email) { _, session ->
                    val sealed = EncryptedSend(
                        to = message.to,
                        cc = message.cc,
                        from = message.from,
                        subject = message.subject,
                        inReplyTo = message.inReplyTo,
                        references = message.references,
                        draftUid = message.draftUid,
                        ciphertext = main,
                        bccCopies = bccCopies,
                        importance = message.importance,
                        readReceipt = message.readReceipt,
                    )
                    // Ek başlık yoksa eskisi gibi; varsa reddedilirse onlarsız bir kez daha.
                    if (message.importance == null && !message.readReceipt) api.sendEncrypted(session, sealed) to false
                    else api.sendEncryptedCompat(session, sealed)
                } ?: throw MailError.Auth()
                service.pin(email, plan.pins)
                return SendOutcome.Sent(encrypted = true, failedBcc = failed, degraded = degraded)
            }
        }
    }

    /** Sunucudan (şifresiz) gönderim; ek alan yoksa eskisi gibi [AccountApi.send]. */
    private suspend fun plainSend(service: AccountApi, session: String, message: OutgoingMessage): Boolean {
        val web = message.toWebCompose()
        if (!web.hasExtras) {
            service.send(session, web)
            return false
        }
        return service.sendCompat(session, web)
    }

    /**
     * Taslak kaydı. Kasası olan kutunun taslağı cihazda kendi anahtarına şifrelenir; kasa
     * kilitliyse ya da durum bilinmiyorsa taslak KAYDEDİLMEZ (düz metin sunucuya gitmez).
     */
    /**
     * [fresh]: taslak cihazda açılmış şifreli içerik taşıyabilir: durum sunucudan taze okunur;
     * okunamazsa kaydedilmez (düz metin sunucuya gitmez).
     */
    private suspend fun storeDraft(email: String, message: WebCompose, fresh: Boolean = false): WebDraftSaved? {
        val status = try {
            requireE2EStatus(email, fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return null
        }
        if (status == E2EStatus.OFF) return web(email) { service, session -> service.saveDraft(session, message) }
        if (status != E2EStatus.READY) return null
        val service = e2e ?: return null
        val api = e2eApiFor(email) ?: return null
        val (html, attachments) = io.maildots.app.mail.InlineImages.forEncryption(message.html, message.attachments)
        val sealed = service.sealForSelf(email, html, attachments)
        return web(email) { _, session ->
            api.saveEncryptedDraft(
                session,
                EncryptedDraft(
                    to = message.to,
                    cc = message.cc,
                    bcc = message.bcc,
                    from = message.from,
                    subject = message.subject,
                    inReplyTo = message.inReplyTo,
                    references = message.references,
                    draftUid = message.draftUid,
                    ciphertext = sealed,
                ),
            )
        }
    }

    /** İki adımlı doğrulama kodunu gönderir; başarılıysa eşleşmeyi tamamlar. */
    fun submitWebCode(email: String, code: String) {
        val service = apiFor(email) ?: return
        val clean = code.trim().replace(" ", "")
        if (clean.length < 6) {
            _state.update { it.copy(webProblem = WebProblem.BAD_CODE) }
            return
        }
        scope.launch {
            try {
                // Bekleyen giriş yoksa (uygulama yeniden açıldıysa ya da süresi dolduysa) yeniden başlat.
                val pending = pendingCodes[email] ?: run {
                    webLogin(email)?.let {
                        // Kod istenmeden oturum açıldı (2FA bu arada kapatılmış).
                        _state.update { st -> st.copy(webProblem = null) }
                        syncWeb(email, force = true)
                        return@launch
                    }
                    pendingCodes[email] ?: return@launch
                }
                val session = service.verifyCode(pending, clean)
                pendingCodes.remove(email)
                updateAccount(email) { it.copy(webSession = session) }
                // Girişte bekletilen kasa açılışı (iki adımlı kod istenmişti).
                pendingUnlock.remove(email)?.let { (password, lock) -> unlockAfterSignIn(email, session, password, lock) }
                _state.update { it.copy(webProblem = null) }
                syncWeb(email, force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e is WebError.BadCode && e.expired) pendingCodes.remove(email)
                _state.update { it.copy(webProblem = e.toWebProblem()) }
            }
        }
    }

    fun clearWebProblem() = _state.update { it.copy(webProblem = null) }

    // ---- anlık bildirim

    /** Bu oturumda bildirime kaydedilmiş hesaplar (belirteç değişince sıfırlanır). */
    private val pushRegistered = HashSet<String>()

    /**
     * İşletim sisteminin verdiği bildirim belirteci (açılışta ve değiştiğinde çağrılır).
     * Hesap servisiyle eşleşmiş her hesap bu belirteçle bildirime kaydedilir.
     */
    fun onPushToken(token: String) {
        if (token.isBlank()) return
        if (repository.pushToken != token) {
            repository.pushToken = token
            pushRegistered.clear()
        }
        _state.value.accounts.filter { it.webLinked }.forEach { registerPush(it.email) }
    }

    private fun registerPush(email: String) {
        if (apiFor(email) == null) return
        val token = repository.pushToken ?: return
        if (!pushRegistered.add(email)) return
        scope.launch {
            try {
                if (web(email) { service, session -> service.registerPush(session, token) } == null) pushRegistered.remove(email)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Kayıt başarısızsa bir sonraki eşleşmede yeniden denenir; posta etkilenmez.
                pushRegistered.remove(email)
            }
        }
    }

    /** Bildirime dokunulunca: hesaba geçer ve iletiyi açar. */
    fun openFromNotification(email: String, folderPath: String, uid: Long) {
        val target = _state.value.accounts.firstOrNull { it.email.equals(email, ignoreCase = true) } ?: return
        if (_state.value.active?.email != target.email) switchAccount(target.email)
        if (uid <= 0) return
        openMessage(
            MessageSummary(
                uid = uid,
                folderPath = folderPath.ifBlank { "INBOX" },
                subject = "",
                from = Address("", ""),
                to = emptyList(),
                dateMillis = 0,
                seen = false,
                flagged = false,
                hasAttachment = false,
            ),
        )
    }

    private fun linked(email: String): Boolean = apiFor(email) != null && account(email)?.webLinked == true

    /** Klasörler hesaba özgüdür: başka bir hesabın klasörleri açılırken o hesaba geçilir. */
    fun openAccountFolders(email: String) {
        if (_state.value.accounts.none { it.email == email }) return
        if (_state.value.active?.email != email) switchAccount(email)
        _state.update { it.copy(screen = Screen.AccountFolders(email)) }
    }

    /** Hesabın imzasını kaydeder (satır sonları korunur). */
    fun setSignature(email: String, signature: String) {
        val clean = signature.replace("\r", "").trim().take(1000)
        val before = account(email) ?: return
        // Biçimlendirilmiş imza düz metinle ezilmez (web'de düzenlenir).
        if (linked(email) && before.signatureRich) return
        updateAccount(email) { it.copy(signature = clean) }
        if (!linked(email) || clean == before.signature) return
        scope.launch {
            try {
                web(email) { service, session ->
                    val existing = service.signatures(session).firstOrNull { it.id == before.signatureId }
                    if (clean.isEmpty()) {
                        if (existing != null) service.deleteSignature(session, existing.id)
                        updateAccount(email) { it.copy(signatureId = "") }
                    } else {
                        val saved = service.saveSignature(session, existing, SignatureText.toHtml(clean))
                        updateAccount(email) { it.copy(signatureId = saved.id) }
                    }
                }
                _state.update { it.copy(webProblem = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                updateAccount(email) { it.copy(signature = before.signature, signatureId = before.signatureId) }
                _state.update { it.copy(webProblem = e.toWebProblem()) }
            }
        }
    }

    // ---- masaüstü: her ileti kendi yazma penceresinde (birden çok pencere; alanlar pencerede)

    /**
     * Masaüstü yazma pencereleri: verilirse [openComposer] yazma ekranını açmaz, isteği buraya
     * verir (true = karşılandı). Android ve iOS'ta null: davranış değişmez.
     */
    var composeHandler: ((ComposeMode, MessageDetail?) -> Boolean)? = null

    /** Kutu hesap servisine bağlı mı (taslak, öneri, gönderen adresleri, imzalar için gerekir). */
    fun composeLinked(email: String): Boolean = linked(email)

    /**
     * Pencereden gönderim: [send] ile AYNI yol (sendModern: şifreleme planı, onaylar, fail-closed);
     * yalnız durum pencerede tutulur. [requireEncryption]: şifresize hiç düşülmez ([E2ERequired]).
     */
    suspend fun sendDetached(
        email: String,
        message: OutgoingMessage,
        plainFor: Set<String>,
        trusted: Map<String, String>,
        quotesEncrypted: Boolean,
        requireEncryption: Boolean,
    ): SendOutcome {
        val account = account(email) ?: throw MailError.Auth()
        // Cihazda açılmış şifreli içerik alıntılanıyorsa şifre zorunludur (arayüzden bağımsız).
        return sendModern(account, message, plainFor, trusted, quotesEncrypted, requireEncryption || quotesEncrypted)
    }

    /** Kutunun şifreleme durumu sunucudan taze (hata = null: bilinmiyor). */
    suspend fun e2eStatusFresh(email: String): E2EStatus? = try {
        requireE2EStatus(email, fresh = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    /**
     * Pencereden taslak kaydı: [saveDraft] ile aynı kurallar (kasası olan kutuda kendine şifreli;
     * kasa kilitli ya da durum bilinmiyorsa KAYDEDİLMEZ, null). Ağ hatasında null.
     */
    suspend fun saveDraftDetached(email: String, message: WebCompose, fresh: Boolean = false): WebDraftSaved? = withContext(NonCancellable) {
        try {
            storeDraft(email, message, fresh)
        } catch (e: Throwable) {
            null
        }
    }

    /** Taslağı siler (başarısızsa false: Taslaklar'da kalır). */
    suspend fun deleteDraftDetached(email: String, uid: Long): Boolean = try {
        web(email) { service, session -> service.deleteDraft(session, uid) } != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        false
    }

    /** Gönderen olarak seçilebilecek adresler (kutu + takma adlar). */
    suspend fun fromAddressesOf(email: String): List<String> = try {
        web(email) { service, session -> service.fromAddresses(session) }.orEmpty().filter { EMAIL_REGEX.matches(it) }.distinct()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        emptyList()
    }

    /** Alıcı önerileri (adres defteri, son yazılanlar, şirket dizini). */
    suspend fun suggestFor(email: String, term: String): List<WebSuggestion> {
        if (term.trim().length < 2 || !linked(email)) return emptyList()
        return try {
            web(email) { service, session -> service.suggest(session, term.trim()) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /** Hesabın imzaları (webmail'deki liste). */
    suspend fun signaturesOf(email: String): List<io.maildots.app.web.WebSignature> = try {
        web(email) { service, session -> service.signatures(session) }.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        emptyList()
    }

    /**
     * Yazarken şifreleme önizlemesi: yalnız dizinde anahtarı olmayan alıcılar (anahtar denetimi,
     * şeffaflık kaydı ve onaylar gönderimde [sendModern]'de yapılır; önizleme kararı değiştirmez).
     */
    suspend fun encryptionPreview(email: String, addresses: List<String>): EncryptionPreview {
        val status = requireE2EStatus(email)
        if (status != E2EStatus.READY) return EncryptionPreview(status, emptyList())
        val api = e2eApiFor(email) ?: return EncryptionPreview(status, emptyList())
        val others = addresses.map { it.trim().lowercase() }.filter { EMAIL_REGEX.matches(it) && it != email.lowercase() }.distinct()
        if (others.isEmpty()) return EncryptionPreview(status, emptyList())
        val directory = web(email) { _, session -> api.keyDirectory(session, others) } ?: throw MailError.Auth()
        return EncryptionPreview(status, others.filter { it !in directory.keys })
    }

    /** İletilen / taslaktaki iletinin eki (şifreli iletide cihazda açılmış hâli; bellekte). */
    suspend fun composeAttachment(email: String, source: MessageDetail, index: Int): AttachmentData {
        val account = account(email) ?: throw MailError.Auth()
        return attachmentData(account, source.summary, index)
    }

    /** Pencereden gönderildi / taslak kaydedildi / atıldı: kısa bildirim; Taslaklar ya da Gönderilenler açıksa yenilenir. */
    fun composeFinished(notice: Notice?) {
        if (notice != null) _state.update { it.copy(notice = notice) }
        val box = _state.value.mailbox
        val role = box.folders.firstOrNull { f -> f.path == box.folderPath }?.role
        if (role == FolderRole.DRAFTS || role == FolderRole.SENT) refresh()
    }

    fun openComposer(mode: ComposeMode = ComposeMode.NEW, source: MessageDetail? = null) {
        if (composeHandler?.invoke(mode, source) == true) {
            // Yazma kendi penceresinde: okuma ekranında açılan taslak salt okunur gösterilir.
            if (mode == ComposeMode.DRAFT && source != null) {
                _state.update { s ->
                    val sc = s.screen as? Screen.Reader
                    if (sc != null && sc.uid == source.summary.uid && sc.folderPath == source.summary.folderPath) {
                        s.copy(reader = ReaderState(detail = source))
                    } else s
                }
            }
            return
        }
        clearDrafts()
        draftJob?.cancel()
        composeFields = null
        lastSavedDraft = null
        lastSavedFinal = null
        latestDraftUid = if (mode == ComposeMode.DRAFT) source?.summary?.uid else null
        val email = _state.value.active?.email
        _state.update {
            it.copy(
                screen = Screen.Composer(mode, source),
                formError = null,
                compose = ComposeState(draftUid = if (mode == ComposeMode.DRAFT) source?.summary?.uid else null),
            )
        }
        if (email != null && linked(email)) loadFromAddresses(email)
        // İletilen iletinin ve açılan taslağın ekleri cihaza alınır: yeni eklerle aynı denetimden geçer.
        if (source != null && source.attachments.isNotEmpty() && (mode == ComposeMode.FORWARD || mode == ComposeMode.DRAFT)) {
            carryAttachments(source)
        }
    }

    /** Gönderen adresleri (kutu + takma adlar); yalnızca birden fazlaysa seçim gösterilir. */
    private fun loadFromAddresses(email: String) {
        scope.launch {
            val list = try {
                web(email) { service, session -> service.fromAddresses(session) }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()
            }
            _state.update {
                if (it.screen !is Screen.Composer) it
                else it.copy(compose = it.compose.copy(fromOptions = list.filter { a -> EMAIL_REGEX.matches(a) }.distinct()))
            }
        }
    }

    private fun carryAttachments(source: MessageDetail) {
        val account = _state.value.active ?: return
        val screen = _state.value.screen
        _state.update { it.copy(compose = it.compose.copy(preparing = true)) }
        scope.launch {
            var budget = AttachmentPolicy.MAX_TOTAL_BYTES
            val picked = source.attachments.mapIndexed { index, meta ->
                if (meta.sizeBytes > budget) return@mapIndexed PickedFile(meta.name, meta.mimeType, null, tooLarge = true)
                val data = try {
                    attachmentData(account, source.summary, index)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                } ?: return@mapIndexed PickedFile(meta.name, meta.mimeType, null)
                budget -= data.bytes.size
                PickedFile(meta.name, meta.mimeType, data.bytes)
            }
            if (_state.value.screen != screen) return@launch
            _state.update { it.copy(compose = it.compose.copy(preparing = false)) }
            addAttachments(picked)
        }
    }

    /** Seçenekler arasından gönderen adresi seçer. */
    fun setComposeFrom(address: String) {
        if (address !in _state.value.compose.fromOptions) return
        _state.update { it.copy(compose = it.compose.copy(from = address)) }
        markComposeDirty()
    }

    // ---- taslak (hesap servisinde; webmail ile aynı Taslaklar klasörü)

    private var composeFields: ComposeFields? = null

    /** Sunucudaki en son taslak (ekran kapansa da izlenir: son kayıt onun yerini alır). */
    private var latestDraftUid: Long? = null
    private var lastSavedDraft: String? = null
    private var draftJob: Job? = null
    private val draftLock = Mutex()
    private var suggestJob: Job? = null

    private fun markComposeDirty() {
        composeFields?.let { draftChanged(it) }
    }

    /** Yazma ekranındaki alanlar değişti: kısa bir duraklamadan sonra taslak kaydedilir. */
    fun draftChanged(fields: ComposeFields) {
        composeFields = fields
        val email = _state.value.active?.email ?: return
        if (_state.value.compose.draftSaved) _state.update { it.copy(compose = it.compose.copy(draftSaved = false)) }
        if (!linked(email)) return
        draftJob?.cancel()
        draftJob = scope.launch {
            delay(DRAFT_DELAY_MS)
            saveDraft(email, final = false)
        }
    }

    /** Taslağın içeriği (değişmediyse yeniden kaydedilmesin diye karşılaştırılır). */
    private fun draftKey(fields: ComposeFields, withAttachments: Boolean): String =
        fields.hashCode().toString() + "|" + _state.value.compose.from + "|" +
            (if (withAttachments) _state.value.drafts.joinToString(",") { it.id.toString() } else "-")

    private fun lenientRecipients(raw: String): List<String> =
        raw.split(',', ';', ' ', '\n').map { it.trim().lowercase() }.filter { EMAIL_REGEX.matches(it) }.distinct()

    /**
     * Taslağı kaydeder. Ara kayıtlar ekleri yalnızca küçükse taşır (mobil veri için);
     * ekrandan çıkarken yapılan son kayıt hepsini taşır. Kayıt, öncekinin yerini alır.
     */
    private suspend fun saveDraft(email: String, final: Boolean): Boolean = draftLock.withLock {
        val fields = composeFields ?: return@withLock false
        val compose = _state.value.compose
        val drafts = _state.value.drafts
        if (fields.isBlank && drafts.isEmpty()) {
            // Boşaltılmış taslak sunucuda kalmasın.
            val uid = compose.draftUid
            if (final && uid != null) {
                try {
                    web(email) { service, session -> service.deleteDraft(session, uid) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Silinemezse Taslaklar'da kalır; kullanıcı oradan silebilir.
                }
            }
            return@withLock false
        }
        val bytes = drafts.sumOf { it.sizeBytes }
        val withAttachments = final || bytes <= DRAFT_INLINE_ATTACHMENT_BYTES
        val key = draftKey(fields, withAttachments)
        val textKey = key.substringBeforeLast('|')
        // Değişmediyse kaydetme; ara kayıtta ekler dışarıda kaldıysa metin aynıysa da kaydetme.
        if (key == lastSavedDraft || (!withAttachments && lastSavedDraft?.substringBeforeLast('|') == textKey)) {
            return@withLock true
        }
        _state.update { it.copy(compose = it.compose.copy(savingDraft = true)) }
        val attachments = if (withAttachments) {
            drafts.mapNotNull { d -> draftBytes[d.id]?.let { AttachmentData(d.name, d.mimeType, it) } }
        } else emptyList()
        val message = WebCompose(
            to = lenientRecipients(fields.to),
            cc = lenientRecipients(fields.cc),
            bcc = lenientRecipients(fields.bcc),
            from = compose.from,
            subject = fields.subject.trim(),
            html = io.maildots.app.mail.plainToHtml(fields.body),
            attachments = attachments,
            draftUid = latestDraftUid,
        )
        // Başlamış kayıt yarıda kesilmez: kesilirse sunucuda sahipsiz bir taslak kalabilir.
        val saved = withContext(NonCancellable) {
            try {
                storeDraft(email, message, fresh = composerQuotesEncrypted())
            } catch (e: Throwable) {
                null
            }
        }
        if (saved?.uid != null) latestDraftUid = saved.uid
        _state.update {
            it.copy(
                compose = it.compose.copy(
                    savingDraft = false,
                    draftUid = saved?.uid ?: it.compose.draftUid,
                    draftSaved = saved != null,
                ),
            )
        }
        if (saved != null) lastSavedDraft = key
        saved != null
    }

    /**
     * Ekrandan çıkarken son kayıt: alanlar ve ekler kopyalanmış olarak gelir (ekran kapandığı
     * için durumdaki ekler silinmiştir). Değişmemiş içerik yeniden kaydedilmez.
     */
    private suspend fun finalDraftSave(
        email: String,
        fields: ComposeFields,
        compose: ComposeState,
        attachments: List<AttachmentData>,
    ): Boolean = draftLock.withLock {
        val uid = latestDraftUid
        if (fields.isBlank && attachments.isEmpty()) {
            if (uid != null) {
                try {
                    web(email) { service, session -> service.deleteDraft(session, uid) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Taslaklar'da kalır.
                }
            }
            return@withLock false
        }
        val key = fields.hashCode().toString() + "|" + compose.from + "|" + attachments.joinToString(",") { it.name + ":" + it.bytes.size }
        if (key == lastSavedFinal && uid != null) return@withLock false
        val message = WebCompose(
            to = lenientRecipients(fields.to),
            cc = lenientRecipients(fields.cc),
            bcc = lenientRecipients(fields.bcc),
            from = compose.from,
            subject = fields.subject.trim(),
            html = io.maildots.app.mail.plainToHtml(fields.body),
            attachments = attachments,
            draftUid = uid,
        )
        val saved = withContext(NonCancellable) {
            try {
                storeDraft(email, message, fresh = composerQuotesEncrypted())
            } catch (e: Throwable) {
                null
            }
        }
        if (saved != null) {
            lastSavedFinal = key
            latestDraftUid = saved.uid
        }
        saved != null
    }

    private var lastSavedFinal: String? = null

    /** Yazma ekranının kaynağı cihazda açılmış şifreli ileti mi (taslak kaydında durum taze okunur). */
    private fun composerQuotesEncrypted(): Boolean {
        val source = (_state.value.screen as? Screen.Composer)?.source ?: return false
        return source.decrypted || source.summary.encrypted
    }

    /** Taslağı siler ve yazma ekranını kapatır. */
    fun discardDraft() {
        val email = _state.value.active?.email ?: return
        val uid = latestDraftUid
        latestDraftUid = null
        draftJob?.cancel()
        composeFields = null
        clearDrafts()
        val inDrafts = _state.value.mailbox.folders.firstOrNull { it.path == _state.value.mailbox.folderPath }?.role == FolderRole.DRAFTS
        _state.update { it.copy(screen = Screen.Mailbox, formError = null, compose = ComposeState(), notice = Notice.DRAFT_DISCARDED) }
        if (uid != null && linked(email)) {
            scope.launch {
                draftLock.withLock {
                    try {
                        web(email) { service, session -> service.deleteDraft(session, uid) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // Silinemezse Taslaklar'da kalır.
                    }
                }
                if (inDrafts) refresh()
            }
        }
    }

    /** Yazarken alıcı önerileri: [text] alandaki metin; son virgülden sonraki parça aranır. */
    fun suggestRecipients(text: String) {
        val email = _state.value.active?.email ?: return
        suggestJob?.cancel()
        val term = text.substringAfterLast(',').substringAfterLast(';').trim()
        if (term.length < 2 || !linked(email)) {
            if (_state.value.compose.suggestions.isNotEmpty()) _state.update { it.copy(compose = it.compose.copy(suggestions = emptyList())) }
            return
        }
        suggestJob = scope.launch {
            delay(SUGGEST_DELAY_MS)
            val found = try {
                web(email) { service, session -> service.suggest(session, term) }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()
            }
            _state.update { if (it.screen is Screen.Composer) it.copy(compose = it.compose.copy(suggestions = found.take(6))) else it }
        }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        _state.update { it.copy(compose = it.compose.copy(suggestions = emptyList())) }
    }

    // ---- ekler (yazma)

    /** Ek içerikleri: durum nesnesine konmaz (büyük veri, günlüğe/kopyaya girmesin). */
    private val draftBytes = HashMap<Int, ByteArray>()
    private var draftSerial = 0

    private fun clearDrafts() {
        draftBytes.clear()
        _state.update { it.copy(drafts = emptyList(), attachProblem = null, attachProblemName = null) }
    }

    /** Kullanıcının seçtiği dosyaları denetleyip iletiye ekler. */
    fun addAttachments(picked: List<PickedFile>) {
        if (_state.value.screen !is Screen.Composer || picked.isEmpty()) return
        val outcome = AttachmentPolicy.admit(_state.value.drafts, picked, draftSerial + 1)
        outcome.accepted.forEach { (draft, bytes) ->
            draftBytes[draft.id] = bytes
            draftSerial = draft.id
        }
        _state.update {
            it.copy(
                drafts = it.drafts + outcome.accepted.map { pair -> pair.first },
                attachProblem = outcome.problem,
                attachProblemName = outcome.problemName,
            )
        }
    }

    fun removeAttachment(id: Int) {
        draftBytes.remove(id)
        _state.update { it.copy(drafts = it.drafts.filterNot { d -> d.id == id }, attachProblem = null, attachProblemName = null) }
    }

    /** Geri: bir üst ekrana döner. Kök ekrandaysa false (uygulama kapanabilir). */
    fun back(): Boolean {
        val s = _state.value
        return when (val screen = s.screen) {
            is Screen.Reader -> {
                _state.update { it.copy(screen = if (screen.fromSearch) Screen.Search else Screen.Mailbox) }
                true
            }
            Screen.Settings, Screen.Search -> {
                _state.update { it.copy(screen = Screen.Mailbox, formError = null) }
                true
            }
            is Screen.AccountSettings -> {
                _state.update { it.copy(screen = Screen.Settings) }
                true
            }
            is Screen.AccountFolders -> {
                _state.update { it.copy(screen = Screen.AccountSettings(screen.email)) }
                true
            }
            is Screen.AccountLabels -> {
                _state.update { it.copy(screen = Screen.AccountSettings(screen.email), webProblem = null) }
                true
            }
            is Screen.WebSettings -> {
                _state.update { it.copy(screen = Screen.AccountSettings(screen.email)) }
                // Web'de yapılan değişiklikleri hemen al.
                syncWeb(screen.email, force = true)
                true
            }
            is Screen.Composer -> {
                // Yanıt/ilet ekranından okunan iletiye geri dön.
                val source = screen.source?.summary
                val shown = s.reader.detail?.summary
                val back = if (source != null && shown != null && shown.sameAs(source)) {
                    Screen.Reader(source.folderPath, source.uid, fromSearch = s.search.results.any { it.sameAs(source) })
                } else Screen.Mailbox
                // Yazılanlar kaybolmasın: hesap servisine taslak olarak kaydedilir (ekler dahil).
                val email = s.active?.email
                draftJob?.cancel()
                if (s.sentTo == null && email != null && linked(email) && composeFields != null) {
                    val pending = s.drafts.mapNotNull { d -> draftBytes[d.id]?.let { AttachmentData(d.name, d.mimeType, it) } }
                    val fields = composeFields
                    val compose = s.compose
                    scope.launch {
                        val saved = finalDraftSave(email, fields!!, compose, pending)
                        if (saved) {
                            _state.update { it.copy(notice = Notice.DRAFT_SAVED) }
                            val box = _state.value.mailbox
                            if (box.folders.firstOrNull { f -> f.path == box.folderPath }?.role == FolderRole.DRAFTS) refresh()
                        }
                    }
                }
                composeFields = null
                clearDrafts()
                // Gönderim onayı gösterilirken geri basılırsa doğrudan kutuya dönülür.
                _state.update { it.copy(screen = if (it.sentTo != null) Screen.Mailbox else back, formError = null, sentTo = null) }
                true
            }
            is Screen.AddAccount -> if (screen.canCancel && s.active != null) {
                _state.update { it.copy(screen = Screen.Mailbox, formError = null) }
                true
            } else false
            Screen.Mailbox -> false
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    // ---- posta kutusu

    fun selectFolder(path: String, filter: MailFilter = MailFilter.ALL, label: MailLabel? = null) {
        _state.update {
            it.copy(
                mailbox = it.mailbox.copy(
                    folderPath = path,
                    filter = filter,
                    label = label,
                    messages = emptyList(),
                    endReached = false,
                    moreFailed = false,
                ),
            )
        }
        load(full = false)
    }

    /** Bulunulan klasörde süzgeci değiştirir (üst çubuktaki süzgeç menüsü). */
    fun setFilter(filter: MailFilter) = selectFolder(_state.value.mailbox.folderPath, filter, null)

    /** Tam yenileme: klasörler, sayılar, iletiler, diğer hesaplar ve hesap ayarları. */
    fun refresh() {
        load(full = true)
        _state.value.active?.email?.let {
            checkEncryption(it)
            checkOnboarding(it)
        }
    }

    /** T2: yeni kutu şifrelemeyi henüz kurmadıysa listede bilgi bandı (kurulum web postada). */
    private fun checkOnboarding(email: String) {
        if (_state.value.e2e[email] != null && _state.value.e2e[email] != E2EStatus.OFF) {
            _state.update { it.copy(onboardingPending = it.onboardingPending - email) }
            return
        }
        scope.launch {
            val pending = runCatching {
                web(email) { api, session -> (api as? E2EApi)?.onboardingRequired(session) }
            }.getOrNull() ?: return@launch
            _state.update {
                it.copy(onboardingPending = if (pending) it.onboardingPending + email else it.onboardingPending - email)
            }
        }
    }

    /** Son şifreleme denetimi (hesap başına): sunucu zaten dakikada birden sık bakmaz. */
    private val encryptionChecked = HashMap<String, TimeMark>()

    /**
     * T3: kayıtlı posta şifrelemesi hâlâ bu kutunun anahtarına açık mı? Değilse sunucu
     * yeniden açar; uygulama kırmızı bandı ve "şifrelenmeden saklandı" işaretini gösterir.
     * Yalnız kasası olan (şifreleme açık) hesaplarda, en çok dakikada bir.
     */
    fun checkEncryption(email: String) {
        if (_state.value.e2e[email] == null || _state.value.e2e[email] == E2EStatus.OFF) return
        val last = encryptionChecked[email]
        if (last != null && last.elapsedNow() < ENCRYPTION_CHECK_EVERY) return
        encryptionChecked[email] = clock.markNow()
        scope.launch {
            val guard = runCatching {
                web(email) { api, session -> (api as? E2EApi)?.encryptionGuard(session) }
            }.getOrNull() ?: return@launch
            _state.update { st ->
                st.copy(
                    encryptionAlerts = guard.alert?.let { st.encryptionAlerts + (email to it) }
                        ?: (st.encryptionAlerts - email),
                    protectedSince = guard.protectedSinceMillis?.let { st.protectedSince + (email to it) }
                        ?: (st.protectedSince - email),
                )
            }
        }
    }

    /** Uygulama öne geldi: etkin hesabın şifrelemesi yeniden denetlenir (T3). */
    fun onForeground() {
        _state.value.active?.email?.let { checkEncryption(it) }
    }

    /** Kurcalama uyarısı görüldü (sunucu yalnız şifreleme yeniden açıldıysa kabul eder). */
    fun acknowledgeEncryptionAlert(email: String) {
        scope.launch {
            val ok = runCatching {
                web(email) { api, session -> (api as? E2EApi)?.acknowledgeEncryptionAlert(session); true }
            }.getOrNull() == true
            if (ok) _state.update { it.copy(encryptionAlerts = it.encryptionAlerts - email) }
        }
    }

    /**
     * [full] değilse (klasör ya da süzgeç değişimi) yalnızca iletiler yüklenir: klasör
     * listesi zaten bellidir ve her dokunuşta tüm klasörleri yeniden saymak beklemeyi
     * uzatır. Sayılar, iletiler geldikten sonra arka planda tazelenir.
     */
    /** Diskteki son gelen kutusu (yoksa boş): açılışta ve hesap geçişinde beklemeden gösterilir. */
    private fun cachedMailbox(email: String): MailboxState {
        val cache = repository.loadMailbox(email) ?: return MailboxState()
        val inbox = cache.folders.firstOrNull { it.role == FolderRole.INBOX }?.path ?: "INBOX"
        return MailboxState(folders = cache.folders, folderPath = inbox, messages = cache.messages)
    }

    private fun load(full: Boolean) {
        val account = _state.value.active ?: return
        val box = _state.value.mailbox
        val quick = !full && box.folders.any { it.path == box.folderPath }
        loadJob?.cancel()
        _state.update { it.copy(mailbox = it.mailbox.copy(loading = true, error = null)) }
        loadJob = scope.launch {
            try {
                val folders = if (quick) box.folders else engine.folders(account)
                val path = if (folders.any { it.path == box.folderPath }) box.folderPath
                else folders.firstOrNull { it.role == FolderRole.INBOX }?.path ?: box.folderPath
                // Etiket görünümü klasöre bağlı değildir: tüm klasörlerde aranır, sayfalanmaz.
                val messages = if (box.label != null) engine.search(account, SearchQuery(label = box.label), SEARCH_LIMIT)
                else engine.messages(account, path, 0, PAGE_SIZE, box.filter)
                val inboxUnread = folders.firstOrNull { it.role == FolderRole.INBOX }?.unread ?: 0
                _state.update {
                    if (it.active?.email != account.email) it
                    else it.copy(
                        unreadByAccount = it.unreadByAccount + (account.email to inboxUnread),
                        mailbox = it.mailbox.copy(
                            folders = folders,
                            folderPath = path,
                            messages = messages,
                            loading = false,
                            endReached = box.label != null || messages.size < PAGE_SIZE,
                        ),
                    )
                }
                // Gelen kutusunun süzülmemiş ilk sayfası bir sonraki açılış için saklanır.
                if (box.label == null && box.filter == MailFilter.ALL &&
                    folders.firstOrNull { it.path == path }?.role == FolderRole.INBOX
                ) {
                    repository.saveMailbox(account.email, MailboxCache(folders, messages))
                }
                if (quick) refreshFolderCounts(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(mailbox = it.mailbox.copy(loading = false, error = e.toErrorKind())) }
            }
        }
        if (!full) return
        refreshOtherAccounts(account)
        resolveDisplayName(account)
        syncWeb(account.email)
    }

    /** Klasör sayılarını arka planda tazeler (liste beklemez; hata yutulur). */
    private fun refreshFolderCounts(account: Account) {
        scope.launch {
            val folders = runCatching { engine.folders(account) }.getOrNull() ?: return@launch
            val inboxUnread = folders.firstOrNull { it.role == FolderRole.INBOX }?.unread ?: 0
            _state.update {
                if (it.active?.email != account.email) it
                else it.copy(
                    unreadByAccount = it.unreadByAccount + (account.email to inboxUnread),
                    mailbox = it.mailbox.copy(folders = folders),
                )
            }
        }
    }

    /** Görünen adı elle değiştirir (boş bırakılırsa adres gösterilir). Giden iletilerde de kullanılır. */
    fun setDisplayName(email: String, name: String) {
        val clean = name.trim().replace(Regex("[\\r\\n\\t]+"), " ").take(60)
        val before = account(email)?.displayName ?: return
        nameLookedUp += email
        updateAccount(email) { it.copy(displayName = clean) }
        if (!linked(email) || clean == before) return
        // Hesap servisine de yaz (webmail ve paneldeki ad aynı kalsın); olmazsa geri al.
        scope.launch {
            try {
                web(email) { service, session -> service.setDisplayName(session, clean) }
                _state.update { it.copy(webProblem = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                updateAccount(email) { it.copy(displayName = before) }
                _state.update { it.copy(webProblem = e.toWebProblem()) }
            }
        }
    }

    /** Hesabın adı bilinmiyorsa sunucudaki iletilerden bulur ve kaydeder. */
    private fun resolveDisplayName(account: Account) {
        if (account.displayName.isNotBlank() || !nameLookedUp.add(account.email)) return
        scope.launch {
            val name = runCatching { engine.displayName(account) }.getOrNull()?.trim().orEmpty()
            if (name.isEmpty()) return@launch
            // Bu arada ad başka yoldan (hesap servisi, kullanıcı) geldiyse ona dokunma.
            if (account(account.email)?.displayName?.isNotBlank() != false) return@launch
            updateAccount(account.email) { it.copy(displayName = name) }
        }
    }

    /** Diğer hesapların okunmamış sayısını arka planda tazeler (rozetler için; hata yutulur). */
    private fun refreshOtherAccounts(active: Account) {
        _state.value.accounts.filter { it.email != active.email }.forEach { other ->
            scope.launch {
                runCatching { engine.inboxUnread(other) }
                    .onSuccess { count ->
                        _state.update { it.copy(unreadByAccount = it.unreadByAccount + (other.email to count)) }
                    }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val account = s.active ?: return
        val box = s.mailbox
        if (box.loading || box.loadingMore || box.endReached) return
        _state.update { it.copy(mailbox = it.mailbox.copy(loadingMore = true, moreFailed = false)) }
        scope.launch {
            try {
                val more = engine.messages(account, box.folderPath, box.messages.size, PAGE_SIZE, box.filter)
                _state.update {
                    val now = it.mailbox
                    if (it.active?.email != account.email || now.folderPath != box.folderPath ||
                        now.filter != box.filter || now.label != box.label
                    ) it
                    else it.copy(
                        mailbox = now.copy(
                            messages = (now.messages + more).distinctBy { m -> m.folderPath to m.uid },
                            loadingMore = false,
                            endReached = more.size < PAGE_SIZE,
                        ),
                    )
                }
            } catch (e: Throwable) {
                _state.update { it.copy(mailbox = it.mailbox.copy(loadingMore = false, moreFailed = true)) }
            }
        }
    }

    /** Yeni klasör oluşturur; [moveHere] verilirse iletiyi oraya taşır. */
    fun createFolder(name: String, moveHere: MessageSummary? = null) {
        val account = _state.value.active ?: return
        if (name.isBlank()) return
        scope.launch {
            runCatching { engine.createFolder(account, name) }
                .onSuccess { path ->
                    if (moveHere != null) moveMessage(moveHere, path)
                    else {
                        _state.update { it.copy(notice = Notice.FOLDER_CREATED) }
                        refresh()
                    }
                }
                .onFailure { e -> _state.update { it.copy(mailbox = it.mailbox.copy(error = e.toErrorKind())) } }
        }
    }

    fun renameFolder(path: String, newName: String) {
        val account = _state.value.active ?: return
        if (newName.isBlank()) return
        scope.launch {
            runCatching { engine.renameFolder(account, path, newName) }
                .onSuccess { newPath ->
                    _state.update {
                        it.copy(
                            notice = Notice.FOLDER_RENAMED,
                            mailbox = if (it.mailbox.folderPath == path) it.mailbox.copy(folderPath = newPath) else it.mailbox,
                        )
                    }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(mailbox = it.mailbox.copy(error = e.toErrorKind())) } }
        }
    }

    /** Klasörü siler; içindeki iletiler çöp klasörüne taşınır. */
    fun deleteFolder(path: String) {
        val account = _state.value.active ?: return
        scope.launch {
            runCatching { engine.deleteFolder(account, path) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            notice = Notice.FOLDER_DELETED,
                            mailbox = if (it.mailbox.folderPath == path) {
                                it.mailbox.copy(folderPath = "INBOX", filter = MailFilter.ALL, label = null, messages = emptyList())
                            } else it.mailbox,
                        )
                    }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(mailbox = it.mailbox.copy(error = e.toErrorKind())) } }
        }
    }

    // ---- etiket tanımları

    private fun saveLabels(labels: List<LabelInfo>) {
        _state.value.active?.let { repository.saveLabels(it.email, labels) }
        _state.update { it.copy(labels = labels) }
    }

    /**
     * Yeni etiket oluşturur; [applyTo] verilirse iletiye de uygular. Hesap servisiyle
     * eşleşmiş hesapta etiket orada oluşturulur (webmail'de de görünür).
     */
    fun createLabel(name: String, color: Long, applyTo: MessageSummary? = null) {
        val active = _state.value.active ?: return
        val clean = name.trim().replace(Regex("\\s+"), " ").take(MailLabel.MAX_NAME_LENGTH)
        if (clean.isEmpty()) return
        if (linked(active.email)) {
            scope.launch {
                try {
                    val created = web(active.email) { service, session ->
                        service.createLabel(session, clean, LabelPalette.token(color))
                    } ?: return@launch
                    val info = LabelInfo(MailLabel(created.keyword), LabelPalette.argb(created.color), created.name, created.id)
                    if (_state.value.active?.email == active.email) {
                        saveLabels(_state.value.labels.filterNot { it.id == info.id } + info)
                        _state.update { it.copy(webProblem = null) }
                        if (applyTo != null) setLabel(applyTo, info.label, true)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _state.update { it.copy(webProblem = e.toWebProblem()) }
                }
            }
            return
        }
        val label = MailLabel.custom(clean) ?: return
        val current = _state.value.labels
        saveLabels(
            if (current.any { it.label == label }) current.map { if (it.label == label) it.copy(color = color) else it }
            else current + LabelInfo(label, color),
        )
        if (applyTo != null) setLabel(applyTo, label, true)
    }

    fun setLabelColor(label: MailLabel, color: Long) {
        val active = _state.value.active ?: return
        val before = _state.value.labels
        val info = before.firstOrNull { it.label == label } ?: return
        if (info.color == color) return
        saveLabels(before.map { if (it.label == label) it.copy(color = color) else it })
        if (!linked(active.email) || info.id.isEmpty()) return
        scope.launch {
            try {
                web(active.email) { service, session -> service.updateLabel(session, info.id, null, LabelPalette.token(color)) }
                _state.update { it.copy(webProblem = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (_state.value.active?.email == active.email) saveLabels(before)
                _state.update { it.copy(webProblem = e.toWebProblem()) }
            }
        }
    }

    /** Kullanıcının oluşturduğu etiketi siler ve iletilerden kaldırır. Hazır etiketler silinmez. */
    fun deleteLabel(label: MailLabel) {
        val active = _state.value.active ?: return
        val before = _state.value.labels
        val info = before.firstOrNull { it.label == label }
        val onService = linked(active.email) && info != null && info.id.isNotEmpty()
        if (label.isBuiltin && !onService) return
        saveLabels(before.filterNot { it.label == label })
        val strip = { list: List<MessageSummary> -> list.map { if (label in it.labels) it.copy(labels = it.labels - label) else it } }
        _state.update {
            it.copy(
                mailbox = it.mailbox.copy(messages = strip(it.mailbox.messages)),
                search = it.search.copy(results = strip(it.search.results)),
                reader = it.reader.detail?.let { d -> it.reader.copy(detail = d.copy(summary = strip(listOf(d.summary)).single())) } ?: it.reader,
            )
        }
        if (_state.value.mailbox.label == label) selectFolder("INBOX")
        scope.launch {
            if (onService) {
                try {
                    // Servis etiketi siler ve iletilerden de kaldırır.
                    web(active.email) { service, session -> service.deleteLabel(session, info!!.id) }
                    _state.update { it.copy(webProblem = null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (_state.value.active?.email == active.email) saveLabels(before)
                    _state.update { it.copy(webProblem = e.toWebProblem()) }
                }
            } else {
                runCatching { engine.clearLabel(active, label) }
            }
        }
    }

    // ---- arama

    /** Arama ekranını açar; varsayılan olarak bulunulan klasörde arar. */
    fun openSearch() = _state.update {
        it.copy(screen = Screen.Search, search = SearchState(query = SearchQuery(folderPath = it.mailbox.folderPath)))
    }

    /** Ölçütleri günceller; en az bir ölçüt varsa hemen arar. */
    fun setQuery(query: SearchQuery, run: Boolean = true) {
        _state.update { it.copy(search = it.search.copy(query = query)) }
        if (run) runSearch()
    }

    /** Yazarken çağrılır: kısa bir duraklamadan sonra arar; ara sözcükler son aramalara yazılmaz. */
    fun typeQuery(text: String) {
        _state.update { it.copy(search = it.search.copy(query = it.search.query.copy(text = text))) }
        typingJob?.cancel()
        typingJob = scope.launch {
            delay(TYPING_DELAY_MS)
            // Tek harf çok fazla sonuç verir; başka ölçüt yoksa bekle.
            val q = _state.value.search.query
            if (q.text.trim().length == 1 && !q.copy(text = "").hasCriteria) return@launch
            search(remember = false)
        }
    }

    fun runSearch() {
        typingJob?.cancel()
        search(remember = true)
    }

    private fun rememberSearch(text: String) {
        if (text.isEmpty()) return
        val recent = (listOf(text) + _state.value.recentSearches.filterNot { it.equals(text, ignoreCase = true) })
            .take(MAX_RECENT)
        repository.saveRecentSearches(recent)
        _state.update { it.copy(recentSearches = recent) }
    }

    private fun search(remember: Boolean) {
        val account = _state.value.active ?: return
        val query = _state.value.search.query
        searchJob?.cancel()
        if (!query.hasCriteria) {
            _state.update { it.copy(search = it.search.copy(results = emptyList(), loading = false, searched = false, error = null)) }
            return
        }
        if (remember) rememberSearch(query.text.trim())
        _state.update { it.copy(search = it.search.copy(loading = true, error = null)) }
        searchJob = scope.launch {
            try {
                val results = engine.search(account, query, SEARCH_LIMIT)
                _state.update {
                    if (it.search.query != query) it
                    else it.copy(search = it.search.copy(results = results, loading = false, searched = true))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(search = it.search.copy(loading = false, searched = true, error = e.toErrorKind())) }
            }
        }
    }

    fun clearRecentSearches() {
        repository.saveRecentSearches(emptyList())
        _state.update { it.copy(recentSearches = emptyList()) }
    }

    // ---- okuma

    fun openMessage(m: MessageSummary) {
        val account = _state.value.active ?: return
        val fromSearch = _state.value.screen == Screen.Search
        // Yazarken bulunan bir sonuç açıldıysa o sözcük artık "aranmış" sayılır.
        if (fromSearch) rememberSearch(_state.value.search.query.text.trim())
        val screen = Screen.Reader(m.folderPath, m.uid, fromSearch)
        val isDraft = _state.value.mailbox.folders.firstOrNull { it.path == m.folderPath }?.role == FolderRole.DRAFTS
        _state.update { it.copy(screen = screen, reader = ReaderState(loading = true)) }
        scope.launch {
            try {
                var detail = engine.message(account, m.folderPath, m.uid)
                var problem: E2EProblem? = null
                // Uçtan uca şifreli: gövde ve ekler cihazda açılır (sunucuda yok).
                if (detail.summary.encrypted) {
                    val opened = decryptDetail(account, detail, asDraft = isDraft)
                    detail = opened.first
                    problem = opened.second
                }
                // Taslak, okuma yerine yazma ekranında açılır (webmail'deki gibi). Açılamayan
                // şifreli taslak okuma ekranında kalır (kilidi açılınca düzenlenir).
                if (isDraft && problem == null && _state.value.screen == screen) {
                    openComposer(ComposeMode.DRAFT, detail)
                    return@launch
                }
                _state.update { s ->
                    if (s.screen != screen) s
                    else s.copy(reader = ReaderState(detail = detail, e2eProblem = problem))
                }
                // Webmail gövdeyi getirirken iletiyi zaten okundu yapar (ayrıntı seen = true döner);
                // liste yine de güncellenmeli, yoksa "okunmadı yap" ilk dokunuşta iletiyi okundu yapar.
                if (markReadOnOpen) {
                    updateMessage(m) { it.copy(seen = true) }
                    if (!detail.summary.seen) runCatching { engine.setSeen(account, m.folderPath, m.uid, true) }
                } else if (detail.summary.seen && current(m)?.seen == false) {
                    // Sunucu gövdeyi verirken okundu yaptı (peek yok): yalnız yerel liste ve sayılar.
                    applySeen(listOf(m), true)
                }
                enrichFromWeb(account.email, screen, detail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { s -> if (s.screen != screen) s else s.copy(reader = ReaderState(error = e.toErrorKind())) }
            }
        }
    }

    /**
     * Hesap servisiyle eşleşmiş kutuda iletiyi zenginleştirir: uzak görseller webmail'in
     * gizlilik vekilinden (gönderen okurun IP'sini ve açtığını öğrenemez) ve konuşmanın diğer iletileri.
     */
    private fun enrichFromWeb(email: String, screen: Screen.Reader, detail: MessageDetail) {
        if (!linked(email)) return
        val folder = detail.summary.folderPath
        val uid = detail.summary.uid
        val mailApi = mailApiFor(email)
        // Cihazda açılmış şifreli ileti sunucudan yeniden alınmaz (sunucuda gövdesi yok, ağ kapalı kalır).
        if (!detail.decrypted && detail.htmlBaseUrl == null && mailApi != null && REMOTE_IMAGE.containsMatchIn(detail.html.orEmpty())) {
            scope.launch {
                val web = try {
                    web(email) { _, session -> mailApi.message(session, folder, uid) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                } ?: return@launch
                if (web.html.isBlank()) return@launch
                _state.update { s ->
                    val d = s.reader.detail
                    if (s.screen != screen || d == null || d.summary.uid != uid) s
                    else s.copy(
                        reader = s.reader.copy(
                            detail = d.copy(
                                html = web.html,
                                htmlBaseUrl = AccountService.BASE_URL,
                                messageId = d.messageId ?: web.messageId,
                                references = d.references.ifEmpty { web.references },
                                replyTo = d.replyTo.ifEmpty { web.replyTo },
                                replyToAddress = web.replyToAddress ?: d.replyToAddress,
                            ),
                        ),
                    )
                }
            }
        }
        scope.launch {
            val thread = try {
                web(email) { service, session -> service.thread(session, folder, uid) }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()
            }
            if (thread.size < 2) return@launch
            _state.update { s -> if (s.screen != screen) s else s.copy(reader = s.reader.copy(thread = thread)) }
        }
    }

    /** Konuşmadaki başka bir iletiyi açar. */
    fun openThreadMessage(item: WebThreadItem) {
        val current = _state.value.reader.detail?.summary ?: return
        if (item.uid == current.uid && item.folder == current.folderPath) return
        openMessage(
            MessageSummary(
                uid = item.uid,
                folderPath = item.folder,
                subject = item.subject,
                from = item.from ?: Address("", ""),
                to = emptyList(),
                dateMillis = item.dateMillis,
                seen = true,
                flagged = false,
                hasAttachment = false,
            ),
        )
    }

    /** Gövdeyi cihaz diline çevirir (webmail'in çeviri servisi). */
    fun translate(target: String) {
        val email = _state.value.active?.email ?: return
        val detail = _state.value.reader.detail ?: return
        // Cihazda açılmış şifreli ileti çeviri servisine (sunucuya) gönderilmez.
        if (detail.decrypted || detail.summary.encrypted) return
        if (!linked(email) || _state.value.reader.translating) return
        val html = detail.html?.takeIf { it.isNotBlank() } ?: io.maildots.app.mail.plainToHtml(detail.text.orEmpty())
        val uid = detail.summary.uid
        _state.update { it.copy(reader = it.reader.copy(translating = true, translateFailed = false)) }
        scope.launch {
            val result = try {
                web(email) { service, session -> service.translate(session, html, target) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            _state.update {
                if (it.reader.detail?.summary?.uid != uid) it
                else it.copy(
                    reader = it.reader.copy(
                        translating = false,
                        translatedHtml = result?.html,
                        translatedFrom = result?.sourceLanguage.orEmpty(),
                        translateFailed = result == null,
                    ),
                )
            }
        }
    }

    fun showOriginal() = _state.update { it.copy(reader = it.reader.copy(translatedHtml = null, translateFailed = false)) }

    /** Eki indirir ve hazır olunca [onReady] ile platforma (önizleme) verir. */
    fun openAttachment(index: Int, onReady: (AttachmentData) -> Unit) {
        val s = _state.value
        val account = s.active ?: return
        val m = s.reader.detail?.summary ?: return
        if (s.reader.openingAttachment != null) return
        _state.update { it.copy(reader = it.reader.copy(openingAttachment = index, attachmentError = null)) }
        scope.launch {
            try {
                val data = attachmentData(account, m, index)
                _state.update { it.copy(reader = it.reader.copy(openingAttachment = null)) }
                onReady(data)
            } catch (e: Throwable) {
                _state.update {
                    it.copy(reader = it.reader.copy(openingAttachment = null, attachmentError = e.toErrorKind()))
                }
            }
        }
    }

    fun toggleFlag(m: MessageSummary) {
        val account = _state.value.active ?: return
        val flagged = !(current(m) ?: m).flagged
        updateMessage(m) { it.copy(flagged = flagged) }
        scope.launch {
            // Sunucu reddederse yerel durumu geri al.
            runCatching { engine.setFlagged(account, m.folderPath, m.uid, flagged) }
                .onFailure { updateMessage(m) { it.copy(flagged = !flagged) } }
        }
    }

    /** Okundu / okunmadı. Okuma ekranından "okunmadı" yapılırsa listeye dönülür. */
    fun toggleSeen(m: MessageSummary) {
        val account = _state.value.active ?: return
        val seen = !(current(m) ?: m).seen
        updateMessage(m) { it.copy(seen = seen) }
        if (!seen && _state.value.screen is Screen.Reader) back()
        scope.launch {
            runCatching { engine.setSeen(account, m.folderPath, m.uid, seen) }
                .onFailure { updateMessage(m) { it.copy(seen = !seen) } }
        }
    }

    /**
     * İletileri okundu/okunmadı yapar (masaüstü: okuma bölmesi, Ctrl+Q/U, bağlam menüsü). Liste
     * satırı, klasörün okunmamış sayısı ve hesap rozeti HEMEN değişir; sunucu reddederse hepsi
     * geri alınır. Başarılıysa sayılar sunucudan tazelenir.
     */
    fun markSeen(messages: List<MessageSummary>, seen: Boolean) {
        val account = _state.value.active ?: return
        val changed = messages.mapNotNull { m -> (current(m) ?: m).takeIf { it.seen != seen } }
            .distinctBy { it.folderPath to it.uid }
        if (changed.isEmpty()) return
        applySeen(changed, seen)
        scope.launch {
            try {
                changed.groupBy { it.folderPath }.forEach { (folder, list) ->
                    val api = mailApiFor(account.email)
                    val done = api != null && linked(account.email) &&
                        web(account.email) { _, session -> api.setFlags(session, folder, list.map { it.uid }, seen = seen) } != null
                    if (!done) list.forEach { engine.setSeen(account, folder, it.uid, seen) }
                }
                refreshFolderCounts(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                applySeen(changed, !seen)
            }
        }
    }

    /** Yerel: satırların okundu bilgisi ve klasör/hesap okunmamış sayıları (sunucuya gitmez). */
    private fun applySeen(messages: List<MessageSummary>, seen: Boolean) {
        val email = _state.value.active?.email ?: return
        val delta = messages.groupingBy { it.folderPath }.eachCount().mapValues { (_, n) -> if (seen) -n else n }
        messages.forEach { m -> updateMessage(m) { it.copy(seen = seen) } }
        _state.update { s ->
            val folders = s.mailbox.folders.map { f -> delta[f.path]?.let { d -> f.copy(unread = (f.unread + d).coerceAtLeast(0)) } ?: f }
            val inbox = folders.firstOrNull { it.role == FolderRole.INBOX }
            val inboxDelta = inbox?.let { delta[it.path] }
            s.copy(
                mailbox = s.mailbox.copy(folders = folders),
                unreadByAccount = if (inboxDelta == null) s.unreadByAccount
                else s.unreadByAccount + (email to ((s.unreadByAccount[email] ?: 0) + inboxDelta).coerceAtLeast(0)),
            )
        }
    }

    fun setLabel(m: MessageSummary, label: MailLabel, on: Boolean) {
        val account = _state.value.active ?: return
        val apply = { value: Boolean ->
            updateMessage(m) { it.copy(labels = if (value) (it.labels + label).distinct().sortedBy { l -> l.keyword } else it.labels - label) }
        }
        apply(on)
        scope.launch {
            runCatching { engine.setLabel(account, m.folderPath, m.uid, label, on) }.onFailure { apply(!on) }
        }
    }

    fun deleteMessage(m: MessageSummary) = relocate(m, Notice.DELETED) { account -> engine.delete(account, m.folderPath, m.uid) }

    fun archiveMessage(m: MessageSummary) = relocate(m, Notice.ARCHIVED) { account ->
        val target = engine.folderFor(account, FolderRole.ARCHIVE) ?: throw MailError.Unknown()
        engine.move(account, m.folderPath, m.uid, target)
    }

    fun spamMessage(m: MessageSummary) = relocate(m, Notice.SPAM) { account ->
        val target = engine.folderFor(account, FolderRole.JUNK) ?: throw MailError.Unknown()
        engine.move(account, m.folderPath, m.uid, target)
    }

    fun moveMessage(m: MessageSummary, targetPath: String) = relocate(m, Notice.MOVED) { account ->
        engine.move(account, m.folderPath, m.uid, targetPath)
    }

    /** Gereksiz klasöründeki iletiyi gelen kutusuna geri alır. */
    fun notSpam(m: MessageSummary) = relocate(m, Notice.NOT_SPAM) { account -> notSpamAll(account, m.folderPath, listOf(m.uid)) }

    private suspend fun notSpamAll(account: Account, folder: String, uids: List<Long>) {
        val api = mailApiFor(account.email)
        if (api != null && linked(account.email) &&
            web(account.email) { _, session -> api.spam(session, folder, uids, notSpam = true) } != null
        ) return
        val inbox = engine.folderFor(account, FolderRole.INBOX) ?: "INBOX"
        uids.forEach { engine.move(account, folder, it, inbox) }
    }

    /** Klasörü boşaltır (çöp ve gereksiz için; geri alınamaz, arayüz onay ister). */
    fun emptyFolder(path: String) {
        val account = _state.value.active ?: return
        val api = mailApiFor(account.email) ?: return
        if (!linked(account.email)) return
        val before = _state.value.mailbox.messages
        if (_state.value.mailbox.folderPath == path) _state.update { it.copy(mailbox = it.mailbox.copy(messages = emptyList())) }
        scope.launch {
            try {
                web(account.email) { _, session -> api.emptyFolder(session, path) } ?: throw MailError.Auth()
                _state.update { it.copy(notice = Notice.FOLDER_EMPTIED) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(mailbox = it.mailbox.copy(messages = before, error = e.toErrorKind())) }
            }
        }
    }

    /** Hesap servisi bu kutuda boşaltmayı destekliyor mu (arayüz düğmeyi buna göre gösterir). */
    fun canEmptyFolders(): Boolean = _state.value.active?.let { mailApiFor(it.email) != null && linked(it.email) } == true

    // ---- toplu seçim

    fun toggleSelection(m: MessageSummary) = toggleSelection(MessageKey(m.folderPath, m.uid))

    fun toggleSelection(key: MessageKey) {
        _state.update { it.copy(selection = if (key in it.selection) it.selection - key else it.selection + key) }
    }

    /** Avatarla sürükleyerek seçim: seçimi verilen kümeyle değiştirir. */
    fun setSelection(keys: Set<MessageKey>) = _state.update { it.copy(selection = keys) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun selectAllVisible() = _state.update { s ->
        s.copy(selection = s.mailbox.messages.map { MessageKey(it.folderPath, it.uid) }.toSet())
    }

    private fun selectedMessages(): List<MessageSummary> {
        val s = _state.value
        return (s.mailbox.messages + s.search.results).filter { MessageKey(it.folderPath, it.uid) in s.selection }
            .distinctBy { it.folderPath to it.uid }
    }

    /** Seçili iletilere toplu işlem uygular (hesap servisiyle eşleşmişse tek istekte). */
    fun bulk(action: BulkAction) {
        val account = _state.value.active ?: return
        val chosen = selectedMessages()
        if (chosen.isEmpty()) return
        clearSelection()
        when (action) {
            BulkAction.READ, BulkAction.UNREAD, BulkAction.STAR, BulkAction.UNSTAR -> {
                val transform: (MessageSummary) -> MessageSummary = when (action) {
                    BulkAction.READ -> { m -> m.copy(seen = true) }
                    BulkAction.UNREAD -> { m -> m.copy(seen = false) }
                    BulkAction.STAR -> { m -> m.copy(flagged = true) }
                    else -> { m -> m.copy(flagged = false) }
                }
                val before = chosen.associateBy { it.folderPath to it.uid }
                chosen.forEach { m -> updateMessage(m, transform) }
                scope.launch {
                    try {
                        chosen.groupBy { it.folderPath }.forEach { (folder, list) ->
                            val uids = list.map { it.uid }
                            val api = mailApiFor(account.email)
                            val done = api != null && linked(account.email) && web(account.email) { _, session ->
                                when (action) {
                                    BulkAction.READ -> api.setFlags(session, folder, uids, seen = true)
                                    BulkAction.UNREAD -> api.setFlags(session, folder, uids, seen = false)
                                    BulkAction.STAR -> api.setFlags(session, folder, uids, flagged = true)
                                    else -> api.setFlags(session, folder, uids, flagged = false)
                                }
                            } != null
                            if (!done) list.forEach { m ->
                                when (action) {
                                    BulkAction.READ -> engine.setSeen(account, folder, m.uid, true)
                                    BulkAction.UNREAD -> engine.setSeen(account, folder, m.uid, false)
                                    BulkAction.STAR -> engine.setFlagged(account, folder, m.uid, true)
                                    else -> engine.setFlagged(account, folder, m.uid, false)
                                }
                            }
                        }
                        refreshFolderCounts(account)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        chosen.forEach { m -> before[m.folderPath to m.uid]?.let { old -> updateMessage(m) { old } } }
                        _state.update { it.copy(mailbox = it.mailbox.copy(error = e.toErrorKind())) }
                    }
                }
            }
            BulkAction.ARCHIVE -> relocateAll(chosen, Notice.ARCHIVED) { folder, uids ->
                val target = engine.folderFor(account, FolderRole.ARCHIVE) ?: throw MailError.Unknown()
                moveAll(account, folder, uids, target)
            }
            BulkAction.SPAM -> relocateAll(chosen, Notice.SPAM) { folder, uids ->
                val target = engine.folderFor(account, FolderRole.JUNK) ?: throw MailError.Unknown()
                moveAll(account, folder, uids, target)
            }
            BulkAction.NOT_SPAM -> relocateAll(chosen, Notice.NOT_SPAM) { folder, uids -> notSpamAll(account, folder, uids) }
            BulkAction.DELETE -> relocateAll(chosen, Notice.DELETED) { folder, uids ->
                val api = mailApiFor(account.email)
                val inTrash = _state.value.mailbox.folders.firstOrNull { it.path == folder }?.role == FolderRole.TRASH
                val done = api != null && linked(account.email) && web(account.email) { _, session ->
                    if (inTrash) api.deleteForever(session, folder, uids) else api.trash(session, folder, uids)
                } != null
                if (!done) uids.forEach { engine.delete(account, folder, it) }
            }
        }
    }

    /** Seçili iletileri bir klasöre taşır. */
    fun bulkMove(targetPath: String) {
        val account = _state.value.active ?: return
        val chosen = selectedMessages().filter { it.folderPath != targetPath }
        clearSelection()
        if (chosen.isEmpty()) return
        relocateAll(chosen, Notice.MOVED) { folder, uids -> moveAll(account, folder, uids, targetPath) }
    }

    private suspend fun moveAll(account: Account, folder: String, uids: List<Long>, target: String) {
        val api = mailApiFor(account.email)
        if (api != null && linked(account.email) &&
            web(account.email) { _, session -> api.move(session, folder, uids, target) } != null
        ) return
        uids.forEach { engine.move(account, folder, it, target) }
    }

    /** [relocate]'in toplu hâli: iletiler hemen listeden çıkar, hata olursa geri gelir. */
    private fun relocateAll(chosen: List<MessageSummary>, notice: Notice, operation: suspend (String, List<Long>) -> Unit) {
        val boxBefore = _state.value.mailbox.messages
        val searchBefore = _state.value.search.results
        val keys = chosen.map { it.folderPath to it.uid }.toSet()
        _state.update {
            it.copy(
                mailbox = it.mailbox.copy(messages = boxBefore.filterNot { m -> (m.folderPath to m.uid) in keys }),
                search = it.search.copy(results = searchBefore.filterNot { m -> (m.folderPath to m.uid) in keys }),
            )
        }
        scope.launch {
            try {
                chosen.groupBy { it.folderPath }.forEach { (folder, list) -> operation(folder, list.map { it.uid }) }
                _state.update { it.copy(notice = notice) }
                _state.value.active?.let { refreshFolderCounts(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        mailbox = it.mailbox.copy(messages = boxBefore, error = e.toErrorKind()),
                        search = it.search.copy(results = searchBefore),
                    )
                }
            }
        }
    }

    // ---- hesap: kota ve şifre

    /** Depolama kullanımını hesap servisinden alır (hesap ayarları açılınca). */
    fun loadUsage(email: String) {
        if (!linked(email)) return
        scope.launch {
            val usage = try {
                web(email) { service, session -> service.usage(session) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            } ?: return@launch
            _state.update { it.copy(usage = it.usage + (email to usage)) }
        }
    }

    fun clearPasswordProblem() = _state.update { it.copy(passwordProblem = null, passwordPolicy = "") }

    /**
     * Şifreyi değiştirir (hesap servisinde; posta sunucusundaki şifre de değişir). Bu cihazdaki
     * kayıt yeni şifreyle güncellenir; sunucu diğer cihazlardaki oturumları kapatır.
     */
    fun changePassword(email: String, current: String, new: String) {
        if (!linked(email) || _state.value.passwordBusy) return
        if (new.length < 8) {
            _state.update { it.copy(passwordProblem = PasswordProblem.POLICY, passwordPolicy = "") }
            return
        }
        _state.update { it.copy(passwordBusy = true, passwordProblem = null, passwordPolicy = "") }
        scope.launch {
            try {
                // Kasası olan kutuda anahtar yeni şifreye cihazda taşınır; giriş korumasında
                // sunucuya şifre değil yalnızca giriş anahtarları gider.
                val vault = fetchVault(email)?.vault
                // Korumalı hesapta kasa yoksa ya da "düz şifre" kipindeyse şifre sunucuya gitmez.
                if (account(email)?.loginProtected == true && (vault == null || vault.loginMode == "password")) {
                    throw LoginDowngrade()
                }
                if (vault != null) {
                    val service = e2e ?: throw E2EUnsupported()
                    val api = e2eApiFor(email) ?: throw E2EUnsupported()
                    if (vault.loginMode != "password" && !strongPassword(new, current)) throw WebError.Policy("")
                    val plan = try {
                        service.passwordChange(vault, email, current, new)
                    } catch (e: E2EError) {
                        if (e.code == "locked") throw WebError.WrongPassword() else throw e
                    }
                    web(email) { _, session ->
                        api.changePasswordWithVault(session, plan.currentSecret, plan.newSecret, plan.vault, plan.target)
                    } ?: throw WebError.Unauthorized()
                    updateAccount(email) { it.copy(password = plan.newSecret, loginProtected = plan.target == "derived") }
                    _state.update { it.copy(passwordBusy = false, notice = Notice.PASSWORD_CHANGED) }
                    return@launch
                }
                web(email) { service, session -> service.changePassword(session, current, new) } ?: throw WebError.Unauthorized()
                updateAccount(email) { it.copy(password = new) }
                _state.update { it.copy(passwordBusy = false, notice = Notice.PASSWORD_CHANGED) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        passwordBusy = false,
                        passwordProblem = when (e) {
                            is LoginDowngrade -> PasswordProblem.SECURITY
                            is WebError.WrongPassword -> PasswordProblem.WRONG_CURRENT
                            is WebError.Policy -> PasswordProblem.POLICY
                            is WebError.Network -> PasswordProblem.NETWORK
                            is WebError.Unauthorized -> PasswordProblem.AUTH
                            else -> PasswordProblem.UNKNOWN
                        },
                        passwordPolicy = (e as? WebError.Policy)?.detail.orEmpty(),
                    )
                }
            }
        }
    }

    /**
     * Şifre başka yerde (webmail, panel) değiştirildiyse bu cihazdaki kaydı günceller.
     * Yeni şifre önce posta sunucusunda doğrulanır; yanlışsa kayıt değişmez.
     */
    fun updateStoredPassword(email: String, password: String) {
        val before = account(email) ?: return
        if (password.isEmpty() || _state.value.passwordBusy) return
        _state.update { it.copy(passwordBusy = true, passwordProblem = null) }
        scope.launch {
            try {
                // Giriş korumasında şifre yine cihazda giriş anahtarına çevrilir.
                val pre = (api as? E2EApi)?.prelogin(email) ?: throw MailError.Unknown()
                // Korumalı hesapta sunucu birden "düz şifre" derse şifre GÖNDERİLMEZ
                // (yönetici sıfırlaması ancak web postada, açık uyarıyla kabul edilir).
                if (!pre.hosted || (before.loginProtected && pre.kdf == null)) throw LoginDowngrade()
                val signed = signInModern(email, password, pre)
                updateAccount(email) {
                    it.copy(
                        password = signed.account.password,
                        loginProtected = signed.account.loginProtected,
                        webSession = signed.account.webSession,
                    )
                }
                if (signed.account.webSession.isNotEmpty()) {
                    pendingCodes.remove(email)
                    rejectedLogins.remove(email)
                    unlockAfterSignIn(email, signed.account.webSession, signed.password, signed.lockKey)
                }
                _state.update { it.copy(passwordBusy = false, notice = Notice.PASSWORD_UPDATED, web = it.web - email) }
                syncWeb(email, force = true)
                if (_state.value.active?.email == email) refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        passwordBusy = false,
                        passwordProblem = when (e) {
                            is LoginDowngrade -> PasswordProblem.SECURITY
                            is MailError.Auth -> PasswordProblem.AUTH
                            is MailError.Network -> PasswordProblem.NETWORK
                            else -> PasswordProblem.UNKNOWN
                        },
                    )
                }
            }
        }
    }

    /** Listede kaydırma işlemini uygular. İleti listeden çıkıyorsa true döner. */
    fun performSwipe(m: MessageSummary, action: SwipeAction): Boolean = when (action) {
        SwipeAction.TOGGLE_READ -> { toggleSeen(m); false }
        SwipeAction.STAR -> { toggleFlag(m); false }
        SwipeAction.DELETE -> { deleteMessage(m); true }
        SwipeAction.ARCHIVE -> { archiveMessage(m); true }
        SwipeAction.SPAM -> { spamMessage(m); true }
        SwipeAction.NONE -> false
    }

    /**
     * İletiyi listelerden hemen çıkarır, sunucu işlemini arkada yapar; işlem başarısız
     * olursa listeleri geri getirir ve hatayı gösterir (ileti sessizce kaybolmaz).
     */
    private fun relocate(m: MessageSummary, notice: Notice, operation: suspend (Account) -> Unit) {
        val account = _state.value.active ?: return
        val boxBefore = _state.value.mailbox.messages
        val searchBefore = _state.value.search.results
        if (_state.value.screen is Screen.Reader) back()
        _state.update {
            it.copy(
                mailbox = it.mailbox.copy(messages = boxBefore.filterNot { x -> x.sameAs(m) }),
                search = it.search.copy(results = searchBefore.filterNot { x -> x.sameAs(m) }),
            )
        }
        scope.launch {
            runCatching { operation(account) }
                .onSuccess { _state.update { it.copy(notice = notice) } }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            mailbox = it.mailbox.copy(messages = boxBefore, error = e.toErrorKind()),
                            search = it.search.copy(results = searchBefore),
                        )
                    }
                }
        }
    }

    /** İletinin durumdaki güncel hâli (liste, arama sonuçları ya da okuma ekranı). */
    private fun current(m: MessageSummary): MessageSummary? {
        val s = _state.value
        return s.mailbox.messages.firstOrNull { it.sameAs(m) }
            ?: s.search.results.firstOrNull { it.sameAs(m) }
            ?: s.reader.detail?.summary?.takeIf { it.sameAs(m) }
    }

    private fun updateMessage(m: MessageSummary, transform: (MessageSummary) -> MessageSummary) {
        _state.update { s ->
            s.copy(
                mailbox = s.mailbox.copy(messages = s.mailbox.messages.map { if (it.sameAs(m)) transform(it) else it }),
                search = s.search.copy(results = s.search.results.map { if (it.sameAs(m)) transform(it) else it }),
                reader = s.reader.detail?.takeIf { it.summary.sameAs(m) }
                    ?.let { s.reader.copy(detail = it.copy(summary = transform(it.summary))) } ?: s.reader,
            )
        }
    }

    // ---- yazma

    /**
     * Onay bekleyen gönderimin alanları. Onay, kullanıcının gördüğü adreslere (şifresiz) ve
     * parmak izlerine (değişen anahtar) ve hesaba bağlıdır: yeniden denemede liste büyürse
     * yeniden sorulur.
     */
    private class SendArgs(
        val email: String,
        val to: String,
        val cc: String,
        val subject: String,
        val body: String,
        val bcc: String,
        val plainFor: Set<String>,
        val trusted: Map<String, String>,
    )

    private var pendingSend: SendArgs? = null

    /** Gönderim onayına yanıt: kabulse aynı ileti, onaylanan kapsamla yeniden gönderilir. */
    fun answerConsent(accept: Boolean) {
        val consent = _state.value.compose.consent ?: return
        val args = pendingSend
        pendingSend = null
        _state.update { it.copy(compose = it.compose.copy(consent = null)) }
        if (!accept || args == null || _state.value.active?.email != args.email) return
        when (consent) {
            is SendConsent.Plain ->
                send(args.to, args.cc, args.subject, args.body, args.bcc, plainFor = args.plainFor + consent.addresses, trusted = args.trusted)
            is SendConsent.KeyChanged ->
                send(args.to, args.cc, args.subject, args.body, args.bcc, plainFor = args.plainFor, trusted = args.trusted + consent.fingerprints)
        }
    }

    fun send(
        toRaw: String,
        ccRaw: String,
        subject: String,
        body: String,
        bccRaw: String = "",
        plainFor: Set<String> = emptySet(),
        trusted: Map<String, String> = emptyMap(),
    ) {
        if (_state.value.busy || _state.value.sentTo != null) return
        val account = _state.value.active ?: return
        val to = parseRecipients(toRaw)
        val cc = parseRecipients(ccRaw)
        val bcc = parseRecipients(bccRaw)
        val error = when {
            to == null || cc == null || bcc == null -> ErrorKind.INVALID_EMAIL
            to.isEmpty() -> ErrorKind.NO_RECIPIENT
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(formError = error) }
            return
        }
        _state.update { it.copy(busy = true, formError = null, compose = it.compose.copy(consent = null)) }
        scope.launch {
            try {
                val attachments = _state.value.drafts.mapNotNull { d ->
                    draftBytes[d.id]?.let { bytes -> AttachmentData(d.name, d.mimeType, bytes) }
                }
                draftJob?.cancel()
                val composer = _state.value.screen as? Screen.Composer
                val source = composer?.source
                val replying = composer?.mode == ComposeMode.REPLY || composer?.mode == ComposeMode.REPLY_ALL
                // Süren taslak kaydı bitsin: gönderim en son taslağı siler (Taslaklar'da kopya kalmaz).
                val draftUid = draftLock.withLock { latestDraftUid }
                val message = OutgoingMessage(
                    to = to!!,
                    cc = cc!!,
                    bcc = bcc!!,
                    subject = subject.trim(),
                    body = body,
                    attachments = attachments,
                    from = _state.value.compose.from,
                    inReplyTo = if (replying) source?.messageId else null,
                    references = if (replying && source != null) ComposeRules.references(source) else emptyList(),
                    draftUid = draftUid,
                )
                // Kasası olan kutuda uçtan uca şifreli; onay gerekirse beklenir.
                val quotesEncrypted = source?.decrypted == true || source?.summary?.encrypted == true
                when (val outcome = sendModern(account, message, plainFor, trusted, quotesEncrypted)) {
                    is SendOutcome.NeedsConsent -> {
                        pendingSend = SendArgs(account.email, toRaw, ccRaw, subject, body, bccRaw, plainFor, trusted)
                        _state.update { it.copy(busy = false, compose = it.compose.copy(consent = outcome.consent)) }
                        return@launch
                    }
                    is SendOutcome.Sent -> _state.update {
                        it.copy(compose = it.compose.copy(sentEncrypted = outcome.encrypted, failedBcc = outcome.failedBcc))
                    }
                }
                composeFields = null
                latestDraftUid = null
                clearDrafts()
                // Kime gittiği de söylenir: ilk alıcı ve varsa diğerlerinin sayısı.
                val all = to + cc + bcc
                val sentTo = all.first() + if (all.size > 1) " +" + (all.size - 1) else ""
                // Yazma ekranı hemen kapanmaz: önce ortada "Gönderildi" görünür, sonra kutuya dönülür.
                _state.update { it.copy(busy = false, sentTo = sentTo) }
                delay(SENT_CONFIRM_MS)
                _state.update {
                    if (it.sentTo != null && it.screen is Screen.Composer) it.copy(screen = Screen.Mailbox, sentTo = null)
                    else it.copy(sentTo = null)
                }
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, formError = e.toErrorKind()) }
            }
        }
    }

    /** Giriş korumalı kutuda şifre kuralı cihazda denetlenir (sunucu şifreyi görmez): webmail ile aynı. */
    private fun strongPassword(new: String, current: String): Boolean =
        new.length >= 12 && new.length <= 256 && new.any { it.isLetter() } && new.any { it.isDigit() } && new != current

    /** Virgül / noktalı virgül / boşlukla ayrılmış adresler. Geçersiz adres varsa null. */
    private fun parseRecipients(raw: String): List<String>? {
        val parts = raw.split(',', ';', ' ', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return if (parts.all { EMAIL_REGEX.matches(it) }) parts.distinct() else null
    }

    /**
     * İlk yenileme, bütün alanlar ilklendikten sonra çalışır. Kapsam hemen çalıştırıyorsa
     * (Main.immediate) yenileme kurucunun içinde başlar; daha önce çağrılırsa henüz
     * ilklenmemiş alanlara (ör. loginLocks) dokunur: JVM'de NPE, iOS'ta çöküş.
     */
    init {
        if (_state.value.active != null) refresh()
    }

    companion object {
        const val PAGE_SIZE = 30
        const val SEARCH_LIMIT = 100
        const val TYPING_DELAY_MS = 350L
        const val SENT_CONFIRM_MS = 1100L
        const val DRAFT_DELAY_MS = 3000L
        const val SUGGEST_DELAY_MS = 250L
        /** Ara taslak kayıtlarında ekler yalnızca toplamı bundan küçükse gönderilir (mobil veri). */
        const val DRAFT_INLINE_ATTACHMENT_BYTES = 1L * 1024 * 1024
        /** Gövdede uzak görsel var mı (webmail'in vekilinden yeniden alınır). */
        private val REMOTE_IMAGE = Regex("<img[^>]+src\\s*=\\s*[\"']?https?://", RegexOption.IGNORE_CASE)
        const val MAX_RECENT = 10
        /** Bellekte tutulan, cihazda açılmış ileti eki kümesi sayısı. */
        const val MAX_DECRYPTED_CACHED = 8
        /** Uçtan uca şifreli gönderimde ek sınırı (webmail ile aynı: çift base64 + sunucu sınırı). */
        const val MAX_ENCRYPTED_ATTACHMENT_BYTES = 9L * 1024 * 1024
        private val WS = Regex("\\s+")
        val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}
