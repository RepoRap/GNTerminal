package com.gnterminal.app.data

import android.content.Context
import com.gnterminal.app.core.FlowParser
import com.gnterminal.app.core.GoldApiParser
import com.gnterminal.app.core.Json
import com.gnterminal.app.core.McxParser
import com.gnterminal.app.core.McxQuote
import com.gnterminal.app.core.NewsItem
import com.gnterminal.app.core.NewsTagger
import com.gnterminal.app.core.NseParser
import com.gnterminal.app.core.OptionChain
import com.gnterminal.app.core.FlowRow
import com.gnterminal.app.core.Quote
import com.gnterminal.app.core.RssParser
import com.gnterminal.app.core.Series
import com.gnterminal.app.core.YahooParser
import com.gnterminal.app.core.text
import com.gnterminal.app.net.Http
import com.gnterminal.app.net.HttpResult
import com.gnterminal.app.net.WebSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder

data class NseBundle(val chain: OptionChain?, val flows: List<FlowRow>, val via: String)

/** Remembers which transport works for each exchange across refreshes. */
private object ExchangeState {
    @Volatile var nseWarmAt = 0L
    @Volatile var nsePreferWeb = false
    @Volatile var mcxWarmAt = 0L
    @Volatile var mcxPreferWeb = false
}

class MarketRepository(private val context: Context) {

    private val yahooGate = Semaphore(4)

    private val htmlHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private val nseApiHeaders = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://www.nseindia.com/option-chain",
        "X-Requested-With" to "XMLHttpRequest",
    )

    private val mcxApiHeaders = mapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "https://www.mcxindia.com",
        "Referer" to "https://www.mcxindia.com/market-data/market-watch",
        "X-Requested-With" to "XMLHttpRequest",
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- Yahoo

    suspend fun yahoo(symbol: String, range: String, interval: String): YahooParser.Result =
        withContext(Dispatchers.IO) {
            yahooGate.withPermit {
                var last: Exception? = null
                var result: YahooParser.Result? = null
                for (host in listOf("query1", "query2")) {
                    try {
                        val r = Http.get(Sources.yahoo(host, symbol, range, interval), mapOf("Accept" to "application/json,text/plain,*/*"))
                        if (r.ok && r.looksJson) {
                            result = YahooParser.parse(symbol, r.body)
                            break
                        }
                        last = IOException("Yahoo HTTP ${r.code}")
                    } catch (e: Exception) {
                        last = e
                    }
                }
                result ?: throw (last ?: IOException("Yahoo failed"))
            }
        }

    /** Intraday quotes (5-minute bars) for the given symbols. Failed symbols are skipped. */
    suspend fun quotes(symbols: List<String> = Sources.WATCH.map { it.symbol }): Map<String, Quote> = coroutineScope {
        val results = symbols.map { s ->
            async { runCatching { yahoo(s, "1d", "5m").quote }.getOrNull() }
        }.awaitAll()
        val map = LinkedHashMap<String, Quote>()
        symbols.forEachIndexed { i, s -> results[i]?.let { map[s] = it } }
        if (map.isEmpty()) throw IOException("No quotes from Yahoo Finance")
        map
    }

    /** One year of daily bars, used for indicators and correlations. */
    suspend fun daily(symbols: List<String> = Sources.WATCH.map { it.symbol }): Map<String, Series> = coroutineScope {
        val results = symbols.map { s ->
            async { runCatching { yahoo(s, "1y", "1d").series }.getOrNull() }
        }.awaitAll()
        val map = LinkedHashMap<String, Series>()
        symbols.forEachIndexed { i, s -> results[i]?.takeIf { it.size > 0 }?.let { map[s] = it } }
        if (map.isEmpty()) throw IOException("No daily history from Yahoo Finance")
        map
    }

    // ---------------------------------------------------------------- Gold spot

    suspend fun goldSpot(): Pair<Double, String> = withContext(Dispatchers.IO) {
        val r = Http.get(Sources.GOLD_API, mapOf("Accept" to "application/json"))
        if (!r.ok) throw IOException("gold-api HTTP ${r.code}")
        GoldApiParser.parse(r.body) ?: throw IOException("gold-api: unexpected response")
    }

    // ---------------------------------------------------------------- NSE

    suspend fun nseChain(expiry: String?, allowWebView: Boolean = true): NseBundle = withContext(Dispatchers.IO) {
        var httpError: Exception? = null
        if (!ExchangeState.nsePreferWeb || !allowWebView) {
            try {
                val b = nseViaHttp(expiry)
                if (b.chain != null) return@withContext b
                httpError = IOException("NSE returned no option-chain data")
            } catch (e: Exception) {
                httpError = e
            }
            if (!allowWebView) throw httpError ?: IOException("NSE unavailable")
            ExchangeState.nsePreferWeb = true
        }
        try {
            val b = nseViaWebView(expiry)
            if (b.chain == null) {
                WebSessions.nse.invalidate()
                ExchangeState.nsePreferWeb = false
                throw IOException("NSE (browser mode) returned no data")
            }
            b
        } catch (e: Exception) {
            ExchangeState.nsePreferWeb = false
            throw IOException((e.message ?: "NSE failed") + (httpError?.let { " · http: " + it.message } ?: ""))
        }
    }

    private fun nseViaHttp(expiry: String?): NseBundle {
        val now = System.currentTimeMillis()
        if (now - ExchangeState.nseWarmAt > 4 * 60_000L) {
            Http.get(Sources.NSE + "/", htmlHeaders)
            Http.get(Sources.NSE + "/option-chain", htmlHeaders)
            ExchangeState.nseWarmAt = now
        }
        val ci = Http.get(Sources.NSE + "/api/option-chain-contract-info?symbol=NIFTY", nseApiHeaders)
        if (!ci.looksJson) {
            ExchangeState.nseWarmAt = 0L
            throw IOException("NSE blocked plain HTTP (${ci.code})")
        }
        val exps = NseParser.expiries(Json.parseOrNull(ci.body))
        val exp = expiry?.takeIf { it in exps } ?: exps.firstOrNull()
        var v3: HttpResult? = null
        if (exp != null) {
            v3 = Http.get(Sources.NSE + "/api/option-chain-v3?type=Indices&symbol=NIFTY&expiry=" + enc(exp), nseApiHeaders)
        }
        var chain = NseParser.parseChain("NIFTY", ci.body, v3?.body, null, exp)
        if (chain == null) {
            val legacy = Http.get(Sources.NSE + "/api/option-chain-indices?symbol=NIFTY", nseApiHeaders)
            chain = NseParser.parseChain("NIFTY", ci.body, null, legacy.body, exp)
        }
        if (chain == null) ExchangeState.nseWarmAt = 0L
        val flows = try {
            FlowParser.parse(Http.get(Sources.NSE + "/api/fiidiiTradeReact", nseApiHeaders).body)
        } catch (e: Exception) {
            emptyList()
        }
        return NseBundle(chain, flows, "http")
    }

    private suspend fun nseViaWebView(expiry: String?): NseBundle {
        val js = """
            var want = ${Json.quote(expiry)};
            var ci = await fetch('/api/option-chain-contract-info?symbol=NIFTY', {credentials: 'include'});
            var ciText = await ci.text();
            var exps = [];
            try { exps = JSON.parse(ciText).expiryDates || []; } catch (e) {}
            var exp = (want && exps.indexOf(want) >= 0) ? want : (exps[0] || '');
            var chainText = '';
            if (exp) {
              var r = await fetch('/api/option-chain-v3?type=Indices&symbol=NIFTY&expiry=' + encodeURIComponent(exp), {credentials: 'include'});
              chainText = await r.text();
            }
            var legacy = '';
            if (chainText.length < 200) {
              var r2 = await fetch('/api/option-chain-indices?symbol=NIFTY', {credentials: 'include'});
              legacy = await r2.text();
            }
            var fii = '';
            try { var r3 = await fetch('/api/fiidiiTradeReact', {credentials: 'include'}); fii = await r3.text(); } catch (e) {}
            return JSON.stringify({ci: ciText, chain: chainText, legacy: legacy, fii: fii, expiry: exp});
        """.trimIndent()
        val out = Json.parseOrNull(WebSessions.nse.run(context, js))
        val exp = out.text("expiry")?.takeIf { it.isNotBlank() }
        val chain = NseParser.parseChain("NIFTY", out.text("ci"), out.text("chain"), out.text("legacy"), exp)
        return NseBundle(chain, FlowParser.parse(out.text("fii")), "browser")
    }

    // ---------------------------------------------------------------- MCX

    suspend fun mcxGold(allowWebView: Boolean = true): McxQuote = withContext(Dispatchers.IO) {
        var httpError: Exception? = null
        if (!ExchangeState.mcxPreferWeb || !allowWebView) {
            try {
                val now = System.currentTimeMillis()
                if (now - ExchangeState.mcxWarmAt > 5 * 60_000L) {
                    Http.get(Sources.MCX + "/market-data/market-watch", htmlHeaders)
                    ExchangeState.mcxWarmAt = now
                }
                val r = Http.post(Sources.MCX + "/backpage.aspx/GetMarketWatch", "{}", "application/json; charset=utf-8", mcxApiHeaders)
                if (r.ok && r.looksJson) {
                    McxParser.parseGold(r.body)?.let { return@withContext it }
                    httpError = IOException("MCX: GOLD contract not found")
                } else {
                    ExchangeState.mcxWarmAt = 0L
                    httpError = IOException("MCX blocked plain HTTP (${r.code})")
                }
            } catch (e: Exception) {
                httpError = e
            }
            if (!allowWebView) throw httpError ?: IOException("MCX unavailable")
            ExchangeState.mcxPreferWeb = true
        }
        try {
            val js = """
                var r = await fetch('/backpage.aspx/GetMarketWatch', {method: 'POST', credentials: 'include',
                  headers: {'Content-Type': 'application/json; charset=utf-8', 'Accept': 'application/json, text/javascript, */*; q=0.01', 'X-Requested-With': 'XMLHttpRequest'},
                  body: '{}'});
                return await r.text();
            """.trimIndent()
            McxParser.parseGold(WebSessions.mcx.run(context, js)) ?: run {
                WebSessions.mcx.invalidate()
                throw IOException("MCX (browser mode): GOLD contract not found")
            }
        } catch (e: Exception) {
            ExchangeState.mcxPreferWeb = false
            throw IOException((e.message ?: "MCX failed") + (httpError?.let { " · http: " + it.message } ?: ""))
        }
    }

    // ---------------------------------------------------------------- News

    suspend fun news(maxFeeds: Int = Sources.NEWS.size): List<NewsItem> = coroutineScope {
        val feeds = Sources.NEWS.take(maxFeeds)
        val lists = feeds.map { f ->
            async(Dispatchers.IO) {
                runCatching {
                    val r = Http.get(f.url, mapOf("Accept" to "application/rss+xml, application/xml, text/xml, */*"))
                    if (r.ok) RssParser.parse(r.body, f.name) else emptyList()
                }.getOrDefault(emptyList())
            }
        }.awaitAll()
        val seen = HashSet<String>()
        val merged = ArrayList<NewsItem>()
        for (item in lists.flatten().sortedByDescending { it.publishedMs }) {
            val key = item.title.lowercase().filter { it.isLetterOrDigit() }.take(60)
            if (key.isEmpty() || !seen.add(key)) continue
            merged.add(NewsTagger.tag(item))
            if (merged.size >= 150) break
        }
        if (merged.isEmpty()) throw IOException("No news feeds reachable")
        merged
    }
}
