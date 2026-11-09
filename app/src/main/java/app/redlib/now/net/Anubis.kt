package app.redlib.now.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Anubis bot-check handling for public Redlib instances.
 *
 * Anubis ≥ v1.28 ships WASM / argon2id (and related) proof-of-work that cannot
 * be solved by pure Kotlin SHA-256 grinding.  The only reliable path is to let
 * a real browser engine execute the challenge page.
 *
 * Strategy:
 *  - Detect challenge HTML with the existing heuristics.
 *  - On every solve request create a **fresh, completely hidden WebView**,
 *    load the origin, wait for the clearance cookies that Anubis sets
 *    (`techaro.lol-anubis*` family), inject those cookies into OkHttp’s
 *    [Http.cookieJar], then destroy the WebView.
 *  - Up to 3 attempts with a per-attempt timeout.  After that the caller
 *    treats the instance as dead.
 *
 * No pure-PoW path remains; the old SHA-256 / preact solvers were removed
 * because they are ineffective against current Anubis deployments.
 */
object Anubis {

    private const val MAX_ATTEMPTS = 3
    private const val ATTEMPT_TIMEOUT_MS = 15_000L
    private const val POLL_INTERVAL_MS = 400L

    /** Cookie name fragment that indicates a successful Anubis clearance. */
    private const val AUTH_COOKIE_MARKER = "techaro.lol-anubis"

    @Volatile
    private var appContext: Context? = null

    /** Must be called once from Application / Repo.init with the application context. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ------------------------------------------------------------------
    // Detection helpers (kept from previous implementation)
    // ------------------------------------------------------------------

    fun isChallenge(html: String): Boolean =
        html.contains("anubis_challenge") ||
            html.contains("Making sure you&#39;re not a bot") ||
            html.contains("Verifying your browser") ||
            html.contains("anubis_version")

    /** True if body looks like bot-wall / challenge HTML rather than media bytes. */
    fun looksLikeBotWall(bodySample: String): Boolean {
        val s = bodySample.lowercase()
        return isChallenge(bodySample) ||
            s.contains("access denied") ||
            (s.contains("<html") && (s.contains("anubis") || s.contains("bot")))
    }

    // ------------------------------------------------------------------
    // Public solve API
    // ------------------------------------------------------------------

    /**
     * Solve an Anubis challenge for [origin] by loading it in a hidden WebView
     * and waiting for the clearance cookies.  Cookies are written into
     * [Http.cookieJar] so subsequent OkHttp / Coil requests succeed.
     *
     * @return true if a usable clearance cookie was obtained.
     */
    suspend fun solveWithWebView(origin: String): Boolean {
        val ctx = appContext
        if (ctx == null) {
            Logd.e("Anubis.solveWithWebView: not initialised (call Anubis.init first)")
            return false
        }
        val clean = origin.trim().removeSuffix("/")
        if (clean.isEmpty() || !clean.startsWith("http")) {
            Logd.w("Anubis.solveWithWebView: bad origin '$origin'")
            return false
        }

        repeat(MAX_ATTEMPTS) { attempt ->
            Logd.i("Anubis WebView solve attempt ${attempt + 1}/$MAX_ATTEMPTS for $clean")
            val ok = try {
                runOneWebViewSolve(ctx, clean)
            } catch (t: Throwable) {
                Logd.w("Anubis WebView attempt ${attempt + 1} failed: ${t.message}")
                false
            }
            if (ok) {
                Logd.i("Anubis WebView solve succeeded on attempt ${attempt + 1}")
                return true
            }
            // brief pause before retry so the previous WebView can finish teardown
            kotlinx.coroutines.delay(350)
        }
        Logd.w("Anubis WebView solve failed after $MAX_ATTEMPTS attempts for $clean")
        return false
    }

    // ------------------------------------------------------------------
    // Single-attempt implementation
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runOneWebViewSolve(ctx: Context, origin: String): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val finished = AtomicBoolean(false)
                val handler = Handler(Looper.getMainLooper())
                var webView: WebView? = null
                var pollRunnable: Runnable? = null

                fun cleanup() {
                    pollRunnable?.let { handler.removeCallbacks(it) }
                    pollRunnable = null
                    try {
                        webView?.stopLoading()
                        webView?.loadUrl("about:blank")
                        webView?.destroy()
                    } catch (_: Throwable) { /* ignore */ }
                    webView = null
                }

                fun succeed(cookieHeader: String) {
                    if (!finished.compareAndSet(false, true)) return
                    cleanup()
                    val injected = injectCookiesIntoJar(origin, cookieHeader)
                    cont.resume(injected)
                }

                fun fail(reason: String) {
                    if (!finished.compareAndSet(false, true)) return
                    Logd.w("Anubis WebView fail: $reason")
                    cleanup()
                    cont.resume(false)
                }

                cont.invokeOnCancellation {
                    if (finished.compareAndSet(false, true)) {
                        cleanup()
                    }
                }

                fun startPolling() {
                    if (finished.get()) return
                    pollRunnable = object : Runnable {
                        override fun run() {
                            if (finished.get()) return
                            val cookies = CookieManager.getInstance().getCookie(origin)
                            if (cookies != null && cookies.contains(AUTH_COOKIE_MARKER)) {
                                Logd.i("Anubis WebView cookies observed: ${cookies.take(180)}…")
                                succeed(cookies)
                                return
                            }
                            handler.postDelayed(this, POLL_INTERVAL_MS)
                        }
                    }
                    handler.post(pollRunnable!!)
                }

                try {
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)

                    webView = WebView(ctx).apply {
                        // Completely invisible & non-interactive.
                        setWillNotDraw(true)
                        visibility = android.view.View.GONE
                        layout(0, 0, 1, 1)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = false
                            cacheMode = WebSettings.LOAD_DEFAULT
                            userAgentString = Http.USER_AGENT
                            setSupportZoom(false)
                            builtInZoomControls = false
                            displayZoomControls = false
                            mediaPlaybackRequiresUserGesture = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }

                        // Third-party cookies required by some Anubis deployments.
                        cm.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                startPolling()
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onReceivedError(
                                view: WebView?,
                                errorCode: Int,
                                description: String?,
                                failingUrl: String?,
                            ) {
                                Logd.w("Anubis WebView onReceivedError $errorCode $description")
                            }
                        }
                    }

                    // Timeout for the whole attempt.
                    handler.postDelayed({
                        fail("timeout after ${ATTEMPT_TIMEOUT_MS}ms")
                    }, ATTEMPT_TIMEOUT_MS)

                    webView!!.loadUrl(origin)
                } catch (t: Throwable) {
                    fail("construction/load exception: ${t.message}")
                }
            }
        }

    // ------------------------------------------------------------------
    // Cookie transfer into OkHttp jar
    // ------------------------------------------------------------------

    /**
     * Parse a CookieManager header ("name=value; name2=value2") and store the
     * Anubis-related cookies into [Http.cookieJar] so OkHttp requests carry them.
     */
    private fun injectCookiesIntoJar(origin: String, cookieHeader: String): Boolean {
        val url = origin.toHttpUrlOrNull() ?: run {
            Logd.w("Anubis: cannot parse origin as HttpUrl: $origin")
            return false
        }
        val host = url.host
        val now = System.currentTimeMillis()
        // Default expiry: 7 days (Anubis default COOKIE_EXPIRATION_TIME).
        val expiresAt = now + 7L * 24 * 60 * 60 * 1000

        val parsed = mutableListOf<Cookie>()
        for (part in cookieHeader.split(';')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (!name.contains(AUTH_COOKIE_MARKER, ignoreCase = true)) continue
            try {
                val cookie = Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(host)
                    .path("/")
                    .expiresAt(expiresAt)
                    .secure()
                    .build()
                parsed += cookie
            } catch (t: Throwable) {
                Logd.w("Anubis: skip malformed cookie $name: ${t.message}")
            }
        }

        if (parsed.isEmpty()) {
            Logd.w("Anubis: no usable techaro.lol-anubis cookies found in header")
            return false
        }

        Http.cookieJar.saveFromResponse(url, parsed)
        Logd.i(
            "Anubis: injected ${parsed.size} cookie(s) for $host → " +
                parsed.joinToString { it.name },
        )
        return true
    }
}
