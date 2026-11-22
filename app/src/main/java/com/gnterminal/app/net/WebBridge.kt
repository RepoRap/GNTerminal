package com.gnterminal.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Receives results from page JavaScript. Must be a public top-level class for the WebView bridge. */
class JsBridge(
    private val onOk: (String, String) -> Unit,
    private val onFail: (String, String) -> Unit,
) {
    @JavascriptInterface
    fun onResult(id: String, text: String) = onOk(id, text)

    @JavascriptInterface
    fun onError(id: String, message: String) = onFail(id, message)
}

/**
 * A hidden WebView that loads an exchange page once (so the site's bot checks
 * see a real Chrome engine and set their cookies), then runs same-origin
 * fetch() calls inside it. Used only when plain HTTP is blocked.
 */
class WebSession(private val pageUrl: String, private val settleMs: Long = 2500L) {
    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<String, CancellableContinuation<String>>()
    private var webView: WebView? = null
    private var ready = false

    suspend fun run(context: Context, jsBody: String, timeoutMs: Long = 45_000L): String = mutex.withLock {
        try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.Main) {
                    if (webView == null || !ready) load(context)
                    execute(jsBody)
                }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.Main) { reset() }
            throw e
        }
    }

    /** Force a fresh page load next time (e.g. after the site returned a block page). */
    suspend fun invalidate() {
        withContext(NonCancellable + Dispatchers.Main) { reset() }
    }

    private fun onJsResult(id: String, text: String) {
        pending.remove(id)?.resume(text)
    }

    private fun onJsError(id: String, message: String) {
        pending.remove(id)?.resumeWithException(IllegalStateException(message))
    }

    private fun reset() {
        ready = false
        val wv = webView
        webView = null
        if (wv != null) {
            try {
                wv.stopLoading()
                wv.removeJavascriptInterface(BRIDGE)
                wv.destroy()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(context: Context) {
        reset()
        suspendCancellableCoroutine<Unit> { cont ->
            val wv = WebView(context.applicationContext)
            webView = wv
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.loadsImagesAutomatically = false
            wv.settings.blockNetworkImage = true
            CookieManager.getInstance().setAcceptCookie(true)
            wv.addJavascriptInterface(JsBridge(this@WebSession::onJsResult, this@WebSession::onJsError), BRIDGE)
            var resumed = false
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (resumed) return
                    resumed = true
                    main.postDelayed({
                        ready = true
                        if (cont.isActive) cont.resume(Unit)
                    }, settleMs)
                }
            }
            wv.loadUrl(pageUrl)
        }
    }

    private suspend fun execute(jsBody: String): String {
        val wv = webView ?: throw IllegalStateException("WebView not ready")
        return suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            pending[id] = cont
            cont.invokeOnCancellation { pending.remove(id) }
            val script = "(async function(){try{var __r=await (async function(){\n" + jsBody +
                "\n})();" + BRIDGE + ".onResult('" + id + "', typeof __r==='string'?__r:JSON.stringify(__r));}" +
                "catch(e){" + BRIDGE + ".onError('" + id + "', String((e&&e.message)||e));}})();"
            wv.evaluateJavascript(script, null)
        }
    }

    companion object {
        private const val BRIDGE = "GNBridge"
    }
}

object WebSessions {
    val nse = WebSession("https://www.nseindia.com/option-chain")
    val mcx = WebSession("https://www.mcxindia.com/market-data/market-watch")
}
