package com.gnterminal.app.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class HttpResult(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
    val looksJson: Boolean
        get() {
            val t = body.trimStart()
            return t.startsWith("{") || t.startsWith("[")
        }
}

/** In-memory cookie store (NSE / MCX need the cookies set by their HTML pages). */
class MemoryCookieJar : CookieJar {
    private val cookies = ArrayList<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            this.cookies.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt < now }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun clearHost(hostSuffix: String) {
        cookies.removeAll { it.domain.endsWith(hostSuffix) }
    }
}

object Http {
    const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val cookieJar = MemoryCookieJar()

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun build(url: String, headers: Map<String, String>): Request.Builder {
        val b = Request.Builder().url(url)
        if (!headers.containsKey("User-Agent")) b.header("User-Agent", UA_DESKTOP)
        for ((k, v) in headers) b.header(k, v)
        return b
    }

    /** Blocking GET — call from Dispatchers.IO. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult {
        val req = build(url, headers).get().build()
        client.newCall(req).execute().use { resp ->
            return HttpResult(resp.code, resp.body?.string() ?: "")
        }
    }

    /** Blocking POST — call from Dispatchers.IO. */
    fun post(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap()): HttpResult {
        val req = build(url, headers).post(body.toRequestBody(contentType.toMediaType())).build()
        client.newCall(req).execute().use { resp ->
            return HttpResult(resp.code, resp.body?.string() ?: "")
        }
    }
}
