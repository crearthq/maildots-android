package io.maildots.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Avatar baytlarını getiren işlev (denetleyiciden; yoksa resim aranmaz, baş harf gösterilir). */
val LocalAvatarLoader = staticCompositionLocalOf<(suspend (String) -> ByteArray?)?> { null }

/**
 * Avatar resimleri için bellek önbelleği: her adres bir kez sorulur ("yok" da saklanır),
 * aynı anda gelen istekler tek çağrıda birleşir. Uygulama ömrü boyunca sınırlı tutulur.
 */
private object AvatarCache {
    private const val MAX = 300
    private val mutex = Mutex()
    private val done = LinkedHashMap<String, ImageBitmap?>()
    private val pending = HashMap<String, CompletableDeferred<ImageBitmap?>>()

    fun peek(key: String): ImageBitmap? = done[key]

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(key: String, loader: suspend (String) -> ByteArray?): ImageBitmap? {
        val (wait, mine) = mutex.withLock {
            if (done.containsKey(key)) return done[key]
            pending[key]?.let { return@withLock it to false }
            CompletableDeferred<ImageBitmap?>().also { pending[key] = it } to true
        }
        if (!mine) return wait.await()
        // "Resmi yok" saklanır; alınamadıysa (oturum yok, ağ hatası) saklanmaz, sonra yeniden denenir.
        val fetched = try {
            Result.success(loader(key)?.decodeToImageBitmap())
        } catch (e: CancellationException) {
            mutex.withLock { pending.remove(key) }
            wait.complete(null)
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
        val image = fetched.getOrNull()
        mutex.withLock {
            pending.remove(key)
            if (fetched.isSuccess) {
                done[key] = image
                if (done.size > MAX) done.remove(done.keys.first())
            }
        }
        wait.complete(image)
        return image
    }
}

/**
 * Adresin avatar resmi varsa onu, yoksa [fallback]'i (baş harfli balon) gösterir.
 * Resim gelene kadar da [fallback] görünür; yerleşim hiç kaymaz.
 */
@Composable
internal fun AvatarPhoto(email: String, fallback: @Composable () -> Unit) {
    val loader = LocalAvatarLoader.current
    val key = email.trim().lowercase()
    var image by remember(key) { mutableStateOf(AvatarCache.peek(key)) }
    LaunchedEffect(key, loader) {
        if (image == null && loader != null && '@' in key) image = AvatarCache.load(key, loader)
    }
    val current = image
    if (current != null) {
        Image(current, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        fallback()
    }
}
