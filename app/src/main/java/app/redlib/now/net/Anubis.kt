package app.redlib.now.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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
 *    load the origin, wait until either:
 *      a) techaro.lol-anubis* clearance cookies appear, or
 *      b) the page navigates away from the challenge interstitial
 *    then inject cookies into OkHttp’s [Http.cookieJar] and destroy the WebView.
 *  - Up to 3 attempts with a generous per-attempt timeout (WASM is slow on
 *    some phones).  After that the caller treats the instance as dead.
 */
object Anubis {

    private const val MAX_ATTEMPTS = 3
    /** WASM argon2id on mid-range phones can take 10–25 s at higher difficulties. */
    private const val ATTEMPT_TIMEOUT_MS = 28_000L
    private const val POLL_INTERVAL_MS = 350L

    /** Cookie name fragment that indicates a successful Anubis clearance. */
    private const val AUTH_COOKIE_MARKER = "techaro.lol-anubis"

    @Volatile
    private var appContext: Context? = null

    /** Must be called once from Application / Repo.init with the application context. */
    fun init(context: Context) {
        appContext = context.applicationContext
        // Ensure CookieManager is created on a known thread early.
        try {
            CookieManager.getInstance().setAcceptCookie(true)
        } catch (_: Throwable) { /* ignore */ }
    }

    // ------------------------------------------------------------------
    // Detection helpers
    // ------------------------------------------------------------------

    fun isChallenge(html: String): Boolean =
        html.contains("anubis_challenge") ||
            html.contains("Making sure you&#39;re not a bot") ||
            html.contains("Making sure you're not a bot") ||
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
     * and waiting for the clearance cookies / navigation past the interstitial.
     * Cookies are written into [Http.cookieJar] so subsequent OkHttp / Coil
     * requests succeed.
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
            delay(400)
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
                var lastUrl: String = origin

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

                fun succeed(cookieHeader: String?) {
                    if (!finished.compareAndSet(false, true)) return
                    // Force CookieManager to disk so getCookie is complete.
                    try {
                        CookieManager.getInstance().flush()
                    } catch (_: Throwable) { /* ignore */ }
                    val header = cookieHeader
                        ?: CookieManager.getInstance().getCookie(origin)
                        ?: CookieManager.getInstance().getCookie(lastUrl)
                    cleanup()
                    val injected = if (header.isNullOrBlank()) {
                        Logd.w("Anubis: success signal but no cookie header readable")
                        false
                    } else {
                        injectCookiesIntoJar(origin, header)
                    }
                    cont.resume(injected)
                }

                fun fail(reason: String) {
                    if (!finished.compareAndSet(false, true)) return
                    Logd.w("Anubis WebView fail: $reason")
                    cleanup()
                    cont.resume(false)
                }

                cont.invokeOnCancellation {
                    if (finished.compareAndSet(false, true)) cleanup()
                }

                fun cookiesLookGood(header: String?): Boolean {
                    if (header.isNullOrBlank()) return false
                    // Accept any techaro.lol-anubis* cookie. Prefer ones that are
                    // not purely the verification/test cookie.
                    if (!header.contains(AUTH_COOKIE_MARKER, ignoreCase = true)) return false
                    val parts = header.split(';').map { it.trim() }
                    val hasAuth = parts.any { p ->
                        val name = p.substringBefore('=').lowercase()
                        name.contains("anubis") &&
                            !name.contains("verification") &&
                            !name.contains("cookie-test")
                    }
                    // verification-only is still a progress signal; auth is the real one.
                    return hasAuth || header.contains(AUTH_COOKIE_MARKER, ignoreCase = true)
                }

                fun startPolling() {
                    if (finished.get()) return
                    pollRunnable = object : Runnable {
                        override fun run() {
                            if (finished.get()) return
                            try {
                                CookieManager.getInstance().flush()
                            } catch (_: Throwable) { /* ignore */ }
                            val c1 = CookieManager.getInstance().getCookie(origin)
                            val c2 = if (lastUrl != origin) {
                                CookieManager.getInstance().getCookie(lastUrl)
                            } else null
                            when {
                                cookiesLookGood(c1) -> {
                                    Logd.i("Anubis WebView cookies on origin: ${c1!!.take(200)}…")
                                    succeed(c1)
                                    return
                                }
                                cookiesLookGood(c2) -> {
                                    Logd.i("Anubis WebView cookies on lastUrl: ${c2!!.take(200)}…")
                                    succeed(c2)
                                    return
                                }
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
                        setWillNotDraw(true)
                        visibility = android.view.View.GONE
                        // Tiny but non-zero size; some engines skip work at 0×0.
                        layout(0, 0, 4, 4)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = false
                            cacheMode = WebSettings.LOAD_NO_CACHE
                            userAgentString = Http.USER_AGENT
                            // Match a normal browser Accept-Language so challenge
                            // metadata that fingerprints language still passes.
                            // (WebView does not expose a direct setter; we rely on
                            // the system locale + the UA we already set.)
                            setSupportZoom(false)
                            builtInZoomControls = false
                            displayZoomControls = false
                            mediaPlaybackRequiresUserGesture = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            // Helps some WASM / canvas paths.
                            loadsImagesAutomatically = true
                            blockNetworkImage = false
                            javaScriptCanOpenWindowsAutomatically = false
                            // Avoid text autosizing side-effects.
                            textZoom = 100
                        }

                        cm.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val u = request?.url?.toString()
                                if (u != null) lastUrl = u
                                return false // let WebView navigate
                            }

                            @Deprecated("Deprecated in Java")
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                if (url != null) lastUrl = url
                                return false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (url != null) lastUrl = url
                                Logd.d("Anubis WebView onPageFinished: $url")
                                // After a successful solve Anubis usually redirects
                                // away from the challenge page.  If we land on a
                                // non-challenge URL under the same host, treat that
                                // as success even before cookies are visible.
                                if (url != null &&
                                    url.startsWith(origin) &&
                                    !url.contains("anubis") &&
                                    !url.contains("pass-challenge") &&
                                    !url.contains("within.website")
                                ) {
                                    // Give CookieManager a moment, then succeed.
                                    handler.postDelayed({
                                        if (!finished.get()) {
                                            val h = CookieManager.getInstance().getCookie(origin)
                                                ?: CookieManager.getInstance().getCookie(url)
                                            if (cookiesLookGood(h) || h != null) {
                                                succeed(h)
                                            }
                                        }
                                    }, 400)
                                }
                                startPolling()
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onReceivedError(
                                view: WebView?,
                                errorCode: Int,
                                description: String?,
                                failingUrl: String?,
                            ) {
                                Logd.w("Anubis WebView onReceivedError $errorCode $description url=$failingUrl")
                            }
                        }
                    }

                    handler.postDelayed({
                        fail("timeout after ${ATTEMPT_TIMEOUT_MS}ms (lastUrl=$lastUrl)")
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

    private fun injectCookiesIntoJar(origin: String, cookieHeader: String): Boolean {
        val url = origin.toHttpUrlOrNull() ?: run {
            Logd.w("Anubis: cannot parse origin as HttpUrl: $origin")
            return false
        }
        val host = url.host
        val now = System.currentTimeMillis()
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
            // Skip empty / cleared cookies.
            if (value.isEmpty()) continue
            try {
                // Host-only cookie (no leading dot) matches how most Anubis
                // deployments set the clearance cookie.
                val cookie = Cookie.Builder()
                    .name(name)
                    .value(value)
                    .hostOnlyDomain(host)
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
