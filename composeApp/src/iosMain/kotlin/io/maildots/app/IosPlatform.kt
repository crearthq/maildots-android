@file:OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.maildots.app

import io.maildots.app.mail.AttachmentData
import io.maildots.app.mail.AttachmentPolicy
import io.maildots.app.mail.PickedFile
import io.maildots.app.ui.Platform
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSCalendar
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterLongStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSFileManager
import platform.Foundation.NSInputStream
import platform.Foundation.NSLocale
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.preferredLanguages
import platform.Foundation.writeToFile
import platform.QuickLook.QLPreviewController
import platform.QuickLook.QLPreviewControllerDataSourceProtocol
import platform.QuickLook.QLPreviewItemProtocol
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSInteger
import platform.darwin.NSObject

/** Cihaz dili: Strings yalnızca "tr" ve "en" bilir. */
internal fun deviceLanguage(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String)?.take(2)?.lowercase() ?: "en"

private fun isTurkish() = deviceLanguage() == "tr"

private val timeFormatter by lazy { NSDateFormatter().apply { dateStyle = NSDateFormatterNoStyle; timeStyle = NSDateFormatterShortStyle } }
private val dayFormatter by lazy { NSDateFormatter().apply { setLocalizedDateFormatFromTemplate("dMMM") } }
private val fullFormatter by lazy { NSDateFormatter().apply { dateStyle = NSDateFormatterLongStyle; timeStyle = NSDateFormatterShortStyle } }

private fun nsDate(millis: Long) = NSDate.dateWithTimeIntervalSince1970(millis / 1000.0)

/** Bugünse saat, değilse gün + kısa ay (cihaz dili). */
internal fun formatShortDate(millis: Long): String {
    val date = nsDate(millis)
    return if (NSCalendar.currentCalendar.isDateInToday(date)) timeFormatter.stringFromDate(date) else dayFormatter.stringFromDate(date)
}

internal fun formatFullDate(millis: Long): String = fullFormatter.stringFromDate(nsDate(millis))

/** Ekranın en üstündeki görünüm denetleyicisi (sunum için). */
internal fun topViewController(): UIViewController? {
    val windows = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { it.windows.filterIsInstance<UIWindow>() }
    var vc = (windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull())?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

private fun showMessage(message: String) {
    val alert = UIAlertController.alertControllerWithTitle(null, message, UIAlertControllerStyleAlert)
    alert.addAction(UIAlertAction.actionWithTitle(if (isTurkish()) "Tamam" else "OK", UIAlertActionStyleDefault, null))
    topViewController()?.presentViewController(alert, animated = true, completion = null)
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

private fun attachmentDir(): String = NSTemporaryDirectory().trimEnd('/') + "/attachments"

/** Önceki oturumdan kalan indirilmiş ekleri siler (diskte birikmesin). */
internal fun clearAttachmentCache() {
    runCatching { NSFileManager.defaultManager.removeItemAtPath(attachmentDir(), null) }
}

/**
 * QuickLook öğesi. NSURL protokolü ObjC'de uygular ama Kotlin türünde görünmez;
 * `url as QLPreviewItemProtocol` dönüşümü çalışma anında başarısız olur.
 */
private class PreviewItem(private val url: NSURL, private val title: String) : NSObject(), QLPreviewItemProtocol {
    override fun previewItemURL(): NSURL = url
    override fun previewItemTitle(): String = title
}

/** QuickLook kaynağı (denetleyici kaynağını zayıf tuttuğu için burada saklanır). */
private class PreviewSource(private val item: PreviewItem) : NSObject(), QLPreviewControllerDataSourceProtocol {
    override fun numberOfPreviewItemsInPreviewController(controller: QLPreviewController): NSInteger = 1
    override fun previewController(controller: QLPreviewController, previewItemAtIndex: NSInteger): QLPreviewItemProtocol = item
}

private var previewSource: PreviewSource? = null

/**
 * Eki uygulamanın geçici dizinine yazar ve QuickLook ile gösterir. Dosya adı yol
 * ayraçlarından ve denetim karakterlerinden arındırılır (dizin dışına yazılamaz).
 */
internal fun openAttachment(data: AttachmentData) {
    try {
        val safeName = data.name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120).ifBlank { "ek" }
        val dir = attachmentDir()
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val path = "$dir/$safeName"
        if (!data.bytes.toNSData().writeToFile(path, atomically = true)) error("write")
        val source = PreviewSource(PreviewItem(NSURL.fileURLWithPath(path), data.name))
        previewSource = source
        val preview = QLPreviewController().apply { dataSource = source }
        topViewController()?.presentViewController(preview, animated = true, completion = null) ?: error("no vc")
    } catch (e: Throwable) {
        showMessage(if (isTurkish()) "Ek açılamadı." else "Could not open the attachment.")
    }
}

/** Sistem dosya seçicisi; yalnızca kullanıcının seçtiği dosyalar (kopya olarak) okunur. */
private class PickerDelegate(
    private val limit: Long,
    private val onPicked: (List<PickedFile>) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    private val scope: CoroutineScope = MainScope()

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val urls = didPickDocumentsAtURLs.filterIsInstance<NSURL>()
        pickerDelegate = null
        if (urls.isEmpty()) return
        scope.launch {
            val files = withContext(Dispatchers.Default) { readPicked(urls, limit) }
            onPicked(files)
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        pickerDelegate = null
    }
}

private var pickerDelegate: PickerDelegate? = null

internal fun pickAttachments(maxBytes: Long, onPicked: (List<PickedFile>) -> Unit) {
    val delegate = PickerDelegate(maxBytes, onPicked)
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true).apply {
        allowsMultipleSelection = true
        this.delegate = delegate
    }
    val top = topViewController() ?: return
    pickerDelegate = delegate
    top.presentViewController(picker, animated = true, completion = null)
}

/**
 * Seçilen dosyaları okur. Toplamda [limit] bayttan fazlası belleğe alınmaz
 * (dosyanın bildirdiği boyuta güvenilmez; okurken sayılır).
 */
@OptIn(ExperimentalForeignApi::class)
private fun readPicked(urls: List<NSURL>, limit: Long): List<PickedFile> {
    var remaining = limit
    // Sayı sınırı denetleyicide uygulanır; burada yalnızca gereksiz okuma önlenir.
    return urls.take(AttachmentPolicy.MAX_FILES + 1).map { url ->
        val name = url.lastPathComponent?.takeIf { it.isNotBlank() } ?: AttachmentPolicy.FALLBACK_NAME
        val type = url.pathExtension?.takeIf { it.isNotEmpty() }?.let { UTType.typeWithFilenameExtension(it)?.preferredMIMEType }
        if (!url.isFileURL()) return@map PickedFile(name, type, null)
        if (remaining <= 0) return@map PickedFile(name, type, null, tooLarge = true)
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            val stream = NSInputStream(uRL = url)
            stream.open()
            try {
                val chunks = mutableListOf<ByteArray>()
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                var tooLarge = false
                while (true) {
                    val n = buffer.usePinned<ByteArray, Long> { stream.read(it.addressOf(0).reinterpret<UByteVar>(), buffer.size.convert<ULong>()) }.toInt()
                    if (n < 0) return@map PickedFile(name, type, null)
                    if (n == 0) break
                    if (total + n > remaining) {
                        tooLarge = true
                        break
                    }
                    chunks += buffer.copyOf(n)
                    total += n
                }
                if (tooLarge) return@map PickedFile(name, type, null, tooLarge = true)
                val data = ByteArray(total.toInt())
                var offset = 0
                chunks.forEach { it.copyInto(data, offset); offset += it.size }
                remaining -= data.size
                PickedFile(name, type, data)
            } finally {
                stream.close()
            }
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }
}

/** Uygulamanın tema seçimini pencerelere uygular (durum çubuğu ve sistem ekranları da uyar). */
internal fun applyThemeMode(mode: ThemeMode) {
    val style = when (mode) {
        ThemeMode.SYSTEM -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
        ThemeMode.LIGHT -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
        ThemeMode.DARK -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
    }
    UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { it.windows.filterIsInstance<UIWindow>() }
        .forEach { it.overrideUserInterfaceStyle = style }
}

internal fun iosPlatform() = Platform(
    language = deviceLanguage(),
    formatShortDate = ::formatShortDate,
    formatFullDate = ::formatFullDate,
    htmlBody = { html, light, baseUrl, modifier -> SafeHtml(html, light, baseUrl, modifier) },
    openAttachment = ::openAttachment,
    print = MailPrinter::print,
    webPage = { url, modifier -> AccountWebPage(url, modifier) },
    pickAttachments = ::pickAttachments,
    onThemeChanged = ::applyStatusBar,
    onThemeMode = ::applyThemeMode,
    composeButton = if (glassAvailable) { label, onClick -> GlassComposeButton(label, onClick) } else null,
)
