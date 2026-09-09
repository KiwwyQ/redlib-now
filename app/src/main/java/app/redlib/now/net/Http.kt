package app.redlib.now.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * OkHttp client with a persistent-per-process cookie jar. Anubis clearance
 * cookies issued by [RedlibClient] live here, so challenges are solved once
 * per instance until they expire server-side.
 *
 * [rawClient] has no Anubis-retry interceptor — used by challenge solve itself
 * to avoid recursion. [client] is what the rest of the app (and Coil) use.
 */
class MemoryCookieJar : CookieJar {
    private val store = LinkedHashMap<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (c in cookies) {
            val key = "${c.name}|${c.domain}|${c.path}"
            if (c.expiresAt in 1..now) {
                store.remove(key)
            } else {
                store[key] = c
            }
        }
        store.entries.removeAll { it.value.expiresAt in 1..now }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.values.filter { it.expiresAt > now && it.matches(url) }
    }

    fun clear() = store.clear()
}

object Http {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    val cookieJar = MemoryCookieJar()

    /** Debounce ensureAuth from parallel Coil/media calls. */
    val lastAuthMs = AtomicLong(0L)

    private fun uaInterceptor() = Interceptor { chain ->
        val resp = chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
        )
        Logd.d("HTTP ${resp.code} <- ${chain.request().url}")
        resp
    }

    private fun baseBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(uaInterceptor())

    /** No Anubis auto-retry — for pass-challenge and ensureAuth only. */
    val rawClient: OkHttpClient = baseBuilder().build()

    /**
     * App + Coil client. On challenge HTML / 401-429 from an instance host,
     * debounced ensureAuth + single retry.
     */
    val client: OkHttpClient = baseBuilder()
        .addInterceptor(AnubisRetryInterceptor)
        .build()
}

/**
 * If a response is an Anubis wall (or 401/403/429) for a normal page/media
 * request, refresh clearance once and retry the original request.
 */
private object AnubisRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val path = url.encodedPath
        if (path.contains("pass-challenge") ||
            path.contains("/.within.website/") ||
            request.header("X-Anubis-Retry") != null
        ) {
            return chain.proceed(request)
        }

        val response = chain.proceed(request)
        val code = response.code
        if (code !in listOf(200, 401, 403, 429)) return response

        val peek = try {
            response.peekBody(512).string()
        } catch (_: Throwable) {
            return response
        }
        val wall = code in listOf(401, 403, 429) || Anubis.looksLikeBotWall(peek)
        if (!wall) return response

        val origin = "${url.scheme}://${url.host}"
        val now = System.currentTimeMillis()
        val last = Http.lastAuthMs.get()
        if (now - last < 2_000L) {
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
        } else {
            Http.lastAuthMs.set(now)
            Logd.i("AnubisRetry: wall on $url — ensureAuth($origin)")
            val ok = try {
                app.redlib.now.data.Repo.client.ensureAuth(origin)
            } catch (t: Throwable) {
                Logd.w("AnubisRetry: ensureAuth error ${t.message}")
                false
            }
            if (!ok) return response
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
        }

        response.close()
        return chain.proceed(
            request.newBuilder().header("X-Anubis-Retry", "1").build()
        )
    }
}
