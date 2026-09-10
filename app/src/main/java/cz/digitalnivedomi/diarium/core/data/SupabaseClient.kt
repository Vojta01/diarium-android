package cz.digitalnivedomi.diarium.core.data

import cz.digitalnivedomi.diarium.BuildConfig
import cz.digitalnivedomi.diarium.auth.SessionStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Minimal Supabase REST (PostgREST) client for the native app.
 *
 * The app talks to Supabase directly with the signed-in user's JWT — there is no
 * server of ours in between. Two rules make that safe and correct:
 *
 *  1. `apikey` is always the **anon** key from [BuildConfig] (public by design).
 *     A `service_role` key must never ship inside the APK.
 *  2. The `Authorization` header is built from [SessionStore.validAccessToken],
 *     which refreshes an expired token via `/auth/v1/token` before returning —
 *     background jobs and UI reads therefore never send a dead token.
 *
 * The HTTP exchange is behind [HttpTransport] so the URL/header/body building is
 * plain JVM logic and can be unit tested without OkHttp or a network.
 */
class SupabaseClient(
    private val sessionStore: SessionStore? = null,
    private val transport: HttpTransport = OkHttpTransport(),
    baseUrl: String = "${BuildConfig.SUPABASE_URL}/rest/v1",
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY,
) {

    private val baseUrl: String = baseUrl.trimEnd('/')

    /** PostgREST SELECT, e.g. `get("entries", mapOf("user_id" to "eq.$id", "limit" to "50"))`. */
    fun get(path: String, query: Map<String, String> = emptyMap()): HttpResponse =
        execute("GET", path, query, body = null)

    /** PostgREST INSERT/UPSERT (add `Prefer: resolution=merge-duplicates` via [extraHeaders]). */
    fun post(
        path: String,
        body: JSONObject,
        query: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse = execute("POST", path, query, body.toString(), extraHeaders)

    /** PostgREST PATCH (partial update). */
    fun patch(
        path: String,
        body: JSONObject,
        query: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse = execute("PATCH", path, query, body.toString(), extraHeaders)

    /** PostgREST DELETE. */
    fun delete(path: String, query: Map<String, String> = emptyMap()): HttpResponse =
        execute("DELETE", path, query, body = null)

    private fun execute(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse {
        // Refreshes an expired token before the request goes out.
        val token = sessionStore?.validAccessToken()
        val headers = LinkedHashMap<String, String>()
        headers["apikey"] = anonKey
        headers["Accept"] = "application/json"
        if (token != null) headers["Authorization"] = "Bearer $token"
        if (body != null) headers["Content-Type"] = "application/json"
        headers.putAll(extraHeaders)
        return transport.execute(HttpRequest(method, buildUrl(baseUrl, path, query), headers, body))
    }

    /**
     * Uploads bytes to Supabase Storage. Storage lives at `/storage/v1`, not
     * `/rest/v1`, so this bypasses [buildUrl] and talks to the project root.
     * `x-upsert: true` makes a re-save of the same date overwrite the previous
     * photo instead of failing on conflict.
     */
    fun uploadObject(
        path: String,
        bytes: ByteArray,
        contentType: String = "image/jpeg",
        upsert: Boolean = true,
    ): HttpResponse {
        val token = sessionStore?.validAccessToken()
        val headers = LinkedHashMap<String, String>()
        headers["apikey"] = anonKey
        headers["Accept"] = "application/json"
        headers["Content-Type"] = contentType
        if (token != null) headers["Authorization"] = "Bearer $token"
        if (upsert) headers["x-upsert"] = "true"
        val url = "${projectUrl()}/storage/v1/object/${path.trimStart('/')}"
        return transport.execute(HttpRequest("POST", url, headers, body = null, binaryBody = bytes))
    }

    /** Public URL for an object in the public `diary-photos` bucket. */
    fun publicStorageUrl(path: String): String =
        "${projectUrl()}/storage/v1/object/public/${path.trimStart('/')}"

    /** Project root (`https://<ref>.supabase.co`), derived from the REST base URL. */
    fun projectUrl(): String = baseUrl.removeSuffix("/rest/v1")

    companion object {
        /** Joins the REST base URL with a table path and an encoded query string. */
        fun buildUrl(baseUrl: String, path: String, query: Map<String, String> = emptyMap()): String {
            val url = StringBuilder(baseUrl.trimEnd('/')).append('/').append(path.trimStart('/'))
            if (query.isEmpty()) return url.toString()
            return query.entries
                .joinToString(separator = "&", prefix = "?") { (key, value) ->
                    "${urlEncode(key)}=${urlEncode(value)}"
                }
                .let { url.append(it).toString() }
        }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}

/** One HTTP exchange, decoupled from OkHttp so the client is testable on the JVM. */
fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
    /** Raw body for binary uploads (Storage); takes precedence over [body]. */
    val binaryBody: ByteArray? = null,
)

data class HttpResponse(val code: Int, val body: String) {

    val isSuccessful: Boolean get() = code in 200..299

    /** Parsed body, or null when the response is empty/not a JSON array. */
    fun asJsonArray(): JSONArray? = try {
        JSONArray(body)
    } catch (_: Exception) {
        null
    }

    /** Parsed body, or null when the response is empty/not a JSON object. */
    fun asJsonObject(): JSONObject? = try {
        JSONObject(body)
    } catch (_: Exception) {
        null
    }

    /** Supabase error text (`{"message": "..."}`), for logs and 401 handling. */
    val errorMessage: String?
        get() = if (isSuccessful) null else asJsonObject()?.optString("message")?.takeIf { it.isNotBlank() }
}

/** OkHttp-backed transport (the only Android/network-coupled part of the client). */
class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) : HttpTransport {

    override fun execute(request: HttpRequest): HttpResponse {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val contentType = request.headers["Content-Type"]?.toMediaType() ?: JSON
        val body = request.binaryBody?.toRequestBody(contentType)
            ?: request.body?.toRequestBody(JSON)
        when (request.method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post(body ?: EMPTY.toRequestBody(JSON))
            "PATCH" -> builder.patch(body ?: EMPTY.toRequestBody(JSON))
            "DELETE" -> builder.delete()
            else -> throw IllegalArgumentException("Unsupported HTTP method: ${request.method}")
        }
        client.newCall(builder.build()).execute().use { response ->
            return HttpResponse(response.code, response.body?.string().orEmpty())
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val EMPTY = ""
    }
}
