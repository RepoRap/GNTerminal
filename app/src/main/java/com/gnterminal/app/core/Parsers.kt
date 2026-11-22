package com.gnterminal.app.core

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Yahoo Finance v8 chart endpoint. */
object YahooParser {
    class Result(val quote: Quote?, val series: Series?)

    fun parse(symbol: String, text: String): Result {
        val root = Json.parse(text)
        val res = root.obj("chart").list("result")?.firstOrNull() ?: return Result(null, null)
        val meta = res.obj("meta")
        val ts = res.list("timestamp") ?: emptyList()
        val q = res.obj("indicators").list("quote")?.firstOrNull()
        val c = q.list("close")
        val o = q.list("open")
        val h = q.list("high")
        val l = q.list("low")

        val idx = ts.indices.filter { i -> toNum(c?.getOrNull(i)) != null }
        val close = DoubleArray(idx.size) { toNum(c?.getOrNull(idx[it])) ?: Double.NaN }
        val open = DoubleArray(idx.size) { toNum(o?.getOrNull(idx[it])) ?: close[it] }
        val high = DoubleArray(idx.size) { toNum(h?.getOrNull(idx[it])) ?: close[it] }
        val low = DoubleArray(idx.size) { toNum(l?.getOrNull(idx[it])) ?: close[it] }
        val times = LongArray(idx.size) { (toNum(ts[idx[it]]) ?: 0.0).toLong() }
        val gmt = meta.num("gmtoffset")?.toLong() ?: 0L
        val series = Series(symbol, times, open, high, low, close, gmt)

        val price = meta.num("regularMarketPrice") ?: close.lastOrNull() ?: return Result(null, series)
        val prev = meta.num("previousClose") ?: meta.num("chartPreviousClose") ?: Double.NaN
        val quote = Quote(
            symbol = symbol,
            price = price,
            prevClose = prev,
            dayHigh = meta.num("regularMarketDayHigh") ?: high.maxOrNull() ?: Double.NaN,
            dayLow = meta.num("regularMarketDayLow") ?: low.minOrNull() ?: Double.NaN,
            currency = meta.text("currency") ?: "",
            timeSec = meta.num("regularMarketTime")?.toLong() ?: times.lastOrNull() ?: 0L,
            spark = close.toList(),
        )
        return Result(quote, series)
    }
}

/** gold-api.com real-time price: {"price": 4165.2, "updatedAt": "..."} */
object GoldApiParser {
    fun parse(text: String): Pair<Double, String>? {
        val root = Json.parseOrNull(text) ?: return null
        val p = root.num("price") ?: return null
        return p to (root.text("updatedAt") ?: "")
    }
}

/** NSE option chain (v3 endpoint, with legacy fallback). */
object NseParser {
    private val EXP_FMT = "dd-MMM-yyyy"

    fun expiries(contractInfo: Any?): List<String> =
        contractInfo.list("expiryDates")?.mapNotNull { toText(it) } ?: emptyList()

    private fun rowExpiry(r: Any?): String? =
        r.text("expiryDates") ?: r.text("expiryDate") ?: r.obj("CE").text("expiryDate") ?: r.obj("PE").text("expiryDate")

    private fun leg(m: Any?): OptLeg? {
        if (m.asMap() == null) return null
        return OptLeg(
            oi = m.num("openInterest") ?: 0.0,
            chgOi = m.num("changeinOpenInterest") ?: 0.0,
            volume = m.num("totalTradedVolume") ?: 0.0,
            iv = m.num("impliedVolatility") ?: 0.0,
            ltp = m.num("lastPrice") ?: 0.0,
            change = m.num("change") ?: 0.0,
        )
    }

    fun parseChain(
        symbol: String,
        contractInfoText: String?,
        chainText: String?,
        legacyText: String?,
        requested: String?,
    ): OptionChain? {
        val ci = Json.parseOrNull(contractInfoText)
        val v3 = Json.parseOrNull(chainText)
        val legacy = Json.parseOrNull(legacyText)

        var expiries = expiries(ci)
        if (expiries.isEmpty()) expiries = legacy.obj("records").list("expiryDates")?.mapNotNull { toText(it) } ?: emptyList()
        if (expiries.isEmpty()) expiries = v3.obj("records").list("expiryDates")?.mapNotNull { toText(it) } ?: emptyList()

        val v3Rows = v3.obj("records").list("data")?.takeIf { it.isNotEmpty() }
            ?: v3.list("data")?.takeIf { it.isNotEmpty() }
            ?: v3.obj("filtered").list("data")?.takeIf { it.isNotEmpty() }

        val records: Any?
        var raw: List<Any?>
        if (v3Rows != null) {
            raw = v3Rows
            records = v3.obj("records") ?: v3
        } else {
            raw = legacy.obj("records").list("data") ?: return null
            records = legacy.obj("records")
        }

        val expiry = requested?.takeIf { it.isNotBlank() }
            ?: expiries.firstOrNull()
            ?: raw.firstNotNullOfOrNull { rowExpiry(it) }
            ?: ""

        // Keep only the chosen expiry when the payload mixes several.
        val distinct = raw.mapNotNull { rowExpiry(it) }.toSet()
        if (distinct.size > 1 && expiry.isNotEmpty()) {
            raw = raw.filter { rowExpiry(it).equals(expiry, ignoreCase = true) }
        }
        if (raw.isEmpty()) return null

        val byStrike = LinkedHashMap<Double, OptionRow>()
        for (r in raw) {
            val k = r.num("strikePrice") ?: r.obj("CE").num("strikePrice") ?: r.obj("PE").num("strikePrice") ?: continue
            val ce = leg(r.field("CE"))
            val pe = leg(r.field("PE"))
            val prev = byStrike[k]
            byStrike[k] = OptionRow(k, ce ?: prev?.ce, pe ?: prev?.pe)
        }
        if (byStrike.isEmpty()) return null

        val spot = records.num("underlyingValue")
            ?: raw.firstNotNullOfOrNull { it.obj("CE").num("underlyingValue") ?: it.obj("PE").num("underlyingValue") }
            ?: 0.0

        val allExp = if (expiries.isEmpty() && expiry.isNotEmpty()) listOf(expiry) else expiries
        return OptionChain(
            symbol = symbol,
            expiry = expiry,
            expiries = allExp,
            spot = spot,
            timestamp = records.text("timestamp") ?: "",
            rows = byStrike.values.sortedBy { it.strike },
        )
    }

    /** Days to expiry, using 15:30 IST on expiry day. */
    fun daysToExpiry(expiry: String, nowMs: Long): Double {
        return try {
            val f = SimpleDateFormat(EXP_FMT, Locale.ENGLISH)
            f.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            val d = f.parse(expiry) ?: return Double.NaN
            val close = d.time + (15 * 60 + 30) * 60_000L
            ((close - nowMs) / 86_400_000.0).coerceAtLeast(0.02)
        } catch (e: Exception) {
            Double.NaN
        }
    }
}

/** NSE FII/DII cash market activity (fiidiiTradeReact). */
object FlowParser {
    fun parse(text: String?): List<FlowRow> {
        val root = Json.parseOrNull(text).asList() ?: return emptyList()
        return root.mapNotNull { r ->
            val cat = r.text("category") ?: return@mapNotNull null
            FlowRow(
                category = cat.replace("*", "").trim(),
                date = r.text("date") ?: "",
                buy = r.num("buyValue") ?: Double.NaN,
                sell = r.num("sellValue") ?: Double.NaN,
                net = r.num("netValue") ?: Double.NaN,
            )
        }
    }
}

/** MCX market watch (GetMarketWatch). The schema has shifted over time, so parsing is defensive. */
object McxParser {
    fun rows(root: Any?): List<Any?> {
        var d: Any? = root.fieldCI("d") ?: root
        if (d is String) d = Json.parseOrNull(d)
        val data = d.fieldCI("Data")
        return when {
            data is List<*> -> data.map { it }
            data is Map<*, *> -> (data as Any?).fieldCI("Data").asList() ?: emptyList()
            d is List<*> -> d.map { it }
            else -> emptyList()
        }
    }

    private val NET_DATE = Regex("/Date\\((-?\\d+)")

    fun expiryMillis(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        NET_DATE.find(s)?.let { return it.groupValues[1].toLongOrNull() }
        val cleaned = s.trim().uppercase(Locale.ENGLISH)
        for (p in listOf("ddMMMyyyy", "dd MMM yyyy", "dd-MMM-yyyy", "yyyy-MM-dd")) {
            try {
                val f = SimpleDateFormat(p, Locale.ENGLISH)
                f.isLenient = false
                val d = f.parse(cleaned)
                if (d != null) return d.time
            } catch (e: Exception) {
                // try next
            }
        }
        return null
    }

    fun parseGold(text: String, symbol: String = "GOLD", nowMs: Long = System.currentTimeMillis()): McxQuote? {
        val root = Json.parseOrNull(text) ?: return null
        val candidates = rows(root).filter { r ->
            val sym = r.textCI("Symbol")?.trim()?.uppercase(Locale.ENGLISH)
            val inst = r.textCI("InstrumentName")?.uppercase(Locale.ENGLISH)
            sym == symbol && (inst == null || inst.contains("FUT"))
        }
        if (candidates.isEmpty()) return null
        val yesterday = nowMs - 86_400_000L
        val best = candidates
            .map { it to (expiryMillis(it.textCI("ExpiryDate")) ?: Long.MAX_VALUE) }
            .filter { it.second >= yesterday }
            .minByOrNull { it.second }?.first ?: candidates.first()
        val ltp = best.numCI("LTP") ?: best.numCI("LastTradedPrice") ?: return null
        if (ltp <= 0) return null
        val prev = best.numCI("PreviousClose") ?: best.numCI("PrevClose")
            ?: best.numCI("AbsoluteChange")?.let { ltp - it } ?: Double.NaN
        return McxQuote(
            symbol = symbol,
            expiry = best.textCI("ExpiryDate")?.trim() ?: "",
            ltp = ltp,
            prevClose = prev,
            oi = best.numCI("OpenInterest"),
            volume = best.numCI("Volume"),
        )
    }
}

/** Regex-based RSS 2.0 reader (Google News, ET, Mint, Moneycontrol). */
object RssParser {
    private val OPTS = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    private val ITEM = Regex("<item\\b[^>]*>(.*?)</item>", OPTS)
    private val TAGS = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")
    private val NUM_ENTITY = Regex("&#(\\d+);")
    private val HEX_ENTITY = Regex("&#[xX]([0-9a-fA-F]+);")
    private val tagCache = HashMap<String, Regex>()

    private fun tagRegex(name: String): Regex = synchronized(tagCache) {
        tagCache.getOrPut(name) { Regex("<$name\\b[^>]*>(.*?)</$name>", OPTS) }
    }

    private val DATE_FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
    )

    fun decodeEntities(s: String): String {
        var r = NUM_ENTITY.replace(s) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        r = HEX_ENTITY.replace(r) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        return r.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
    }

    fun clean(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("<![CDATA[")) s = s.removePrefix("<![CDATA[").removeSuffix("]]>")
        s = TAGS.replace(s, " ")
        s = decodeEntities(s)
        return SPACES.replace(s, " ").trim()
    }

    private fun tag(block: String, name: String): String? {
        val m = tagRegex(name).find(block) ?: return null
        return clean(m.groupValues[1])
    }

    fun parseDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        for (p in DATE_FORMATS) {
            try {
                val d = SimpleDateFormat(p, Locale.US).parse(s.trim())
                if (d != null) return d.time
            } catch (e: Exception) {
                // try next pattern
            }
        }
        return 0L
    }

    fun parse(xml: String, defaultSource: String): List<NewsItem> {
        val out = ArrayList<NewsItem>()
        for (m in ITEM.findAll(xml)) {
            val block = m.groupValues[1]
            var title = tag(block, "title") ?: continue
            if (title.isBlank()) continue
            val link = tag(block, "link") ?: ""
            var source = tag(block, "source")?.takeIf { it.isNotBlank() } ?: defaultSource
            val dash = title.lastIndexOf(" - ")
            if (dash > 20) {
                val tail = title.substring(dash + 3).trim()
                if (tail.equals(source, ignoreCase = true) || (source == defaultSource && tail.length <= 40)) {
                    if (source == defaultSource) source = tail
                    title = title.substring(0, dash).trim()
                }
            }
            out.add(NewsItem(title = title, link = link, source = source, publishedMs = parseDate(tag(block, "pubDate"))))
        }
        return out
    }
}
