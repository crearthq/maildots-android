package io.maildots.app.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS Keychain üzerinde şifreli anahtar-değer deposu.
 * - Öğe türü genel şifre: servis `io.maildots.app`, hesap = anahtar adı, değer UTF-8.
 * - Erişim: ilk kilit açılışından sonra, yalnızca bu cihazda. Bildirim gelince arka planda
 *   okunabilir; iCloud yedeğine ve başka cihaza geçmez.
 * - Hata olursa null döner ya da sessizce geçer. Değer ASLA günlüğe yazılmaz.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
class KeychainSecureStore(private val service: String = "io.maildots.app") : SecureStore {

    override fun get(key: String): String? = runCatching {
        withQuery(key, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) { query ->
            memScoped {
                val result = alloc<CFTypeRefVar>()
                if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@memScoped null
                val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
                NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
            }
        }
    }.getOrNull()

    override fun put(key: String, value: String) {
        runCatching {
            val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return
            val bridged = CFBridgingRetain(data)
            try {
                // Önce var olanı güncelle; yoksa ekle.
                val updated = withQuery(key) { query ->
                    val changes = dictionary(kSecValueData to bridged)
                    try {
                        SecItemUpdate(query, changes)
                    } finally {
                        CFRelease(changes)
                    }
                }
                if (updated == errSecItemNotFound) {
                    withQuery(key, kSecValueData to bridged, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) { query ->
                        SecItemAdd(query, null)
                    }
                }
            } finally {
                CFRelease(bridged)
            }
        }
    }

    override fun remove(key: String) {
        runCatching { withQuery(key) { query -> SecItemDelete(query) } }
    }

    /** Anahtarın sorgu sözlüğünü kurar, [block] bitince serbest bırakır. */
    private fun <T> withQuery(key: String, vararg extra: Pair<CFStringRef?, CFTypeRef?>, block: (CFMutableDictionaryRef?) -> T): T {
        val serviceRef = CFBridgingRetain(NSString.create(string = service))
        val accountRef = CFBridgingRetain(NSString.create(string = key))
        val query = dictionary(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to serviceRef,
            kSecAttrAccount to accountRef,
            *extra,
        )
        try {
            return block(query)
        } finally {
            CFRelease(query)
            CFRelease(serviceRef)
            CFRelease(accountRef)
        }
    }

    private fun dictionary(vararg pairs: Pair<CFStringRef?, CFTypeRef?>): CFMutableDictionaryRef? {
        val dict = CFDictionaryCreateMutable(null, pairs.size.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        pairs.forEach { (k, v) -> CFDictionaryAddValue(dict, k, v) }
        return dict
    }
}
