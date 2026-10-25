package io.maildots.app.web

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Bir HTTP yanıtı. [bytes] ham gövde; [json] gövde JSON nesnesiyse ayrıştırılmış hâli (değilse boş). */
class WebReply(val status: Int, val bytes: ByteArray, val json: JsonObject, val cookies: Map<String, String>, val contentType: String)

/** İstek gövdesi: JSON, düz metin ya da ham bayt. */
sealed interface WebBody {
    class Json(val value: JsonObject) : WebBody
    class Text(val value: String, val contentType: String) : WebBody
    class Bytes(val value: ByteArray, val contentType: String) : WebBody
}

/**
 * Hesap servisinin (webmail) ortak HTTP katmanı: Android'de OkHttp, iOS'ta NSURLSession
 * (Ktor motoru bağımlılıktan seçilir; kodda platform ayrımı yoktur).
 *
 * - Yalnızca HTTPS (test dışında düz bağlantı reddedilir); sistem sertifika deposu.
 * - Yönlendirmeler izlenmez: oturum çerezi başka bir adrese taşınamaz.
 * - Yanıt boyutu sınırlıdır; oturum değeri hiçbir hata iletisine ya da günlüğe girmez.
 */
class WebClient(
    baseUrl: String = AccountService.BASE_URL,
    allowInsecureForTests: Boolean = false,
    /** İstemci adı (ör. "MailDots-Android", "MailDots-iOS"). */
    private val userAgent: String = "MailDots-App",
    /** Teşhis günlüğü: yalnızca yöntem, yol ve sonuç yazılır (gövde/çerez ASLA). */
    private val log: (String) -> Unit = {},
    /** Çevrimdışı çalışma: true iken istek gönderilmez (ağ yok gibi davranılır). */
    private val offline: () -> Boolean = { false },
) {
    val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    init {
        require(base.startsWith("https://") || allowInsecureForTests) { "Hesap servisi yalnızca HTTPS ile kullanılır" }
    }

    private val http = HttpClient {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 120_000
        }
    }

    private fun note(method: String, path: String, outcome: String) {
        runCatching { log(method + " " + path.substringBefore('?') + " -> " + outcome) }
    }

    /**
     * İstek gönderir. Ağ/sertifika sorunları [WebError.Network] olur; durum kodu
     * yorumlanmaz (çağıran [WebReply.status]'a bakar). [maxBytes] aşılırsa [WebError.Server].
     */
    suspend fun call(
        method: String,
        path: String,
        cookie: Pair<String, String>? = null,
        body: WebBody? = null,
        accept: String = "application/json",
        maxBytes: Int = MAX_JSON_BYTES,
    ): WebReply {
        if (offline()) throw WebError.Network(null)
        val response = try {
            http.request(base + path) {
                this.method = HttpMethod.parse(method)
                header(HttpHeaders.Accept, accept)
                header(HttpHeaders.UserAgent, userAgent)
                cookie?.let { header(HttpHeaders.Cookie, "${it.first}=${it.second}") }
                when (body) {
                    is WebBody.Json -> setBody(TextContent(body.value.toString(), ContentType.Application.Json.withCharset(Charsets.UTF_8)))
                    is WebBody.Text -> setBody(TextContent(body.value, ContentType.parse(body.contentType)))
                    is WebBody.Bytes -> setBody(ByteArrayContent(body.value, ContentType.parse(body.contentType)))
                    null -> {}
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note(method, path, e::class.simpleName ?: "error")
            throw WebError.Network(e)
        }
        val status = response.status.value
        if (status !in 200..299) note(method, path, status.toString())
        val bytes = try {
            response.bodyAsChannel().readRemaining(maxBytes.toLong() + 1).readByteArray()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note(method, path, e::class.simpleName ?: "error")
            throw WebError.Network(e)
        }
        if (bytes.size > maxBytes) throw WebError.Server(status)
        val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
        val parsed = if (contentType.contains("json", ignoreCase = true) || bytes.firstOrNull() == '{'.code.toByte()) {
            runCatching { json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull()
        } else null
        val cookies = HashMap<String, String>()
        response.headers.getAll(HttpHeaders.SetCookie)?.forEach { header ->
            val pair = header.substringBefore(';')
            val key = pair.substringBefore('=').trim()
            if (key.isNotEmpty()) cookies[key] = pair.substringAfter('=', "").trim()
        }
        return WebReply(status, bytes, parsed ?: JsonObject(emptyMap()), cookies, contentType)
    }

    companion object {
        const val MAX_JSON_BYTES = 6 * 1024 * 1024
    }
}
