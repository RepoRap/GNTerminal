package com.gnterminal.app.data

import java.net.URLEncoder

object Sources {
    data class Sym(val symbol: String, val label: String, val decimals: Int)
    data class Feed(val name: String, val url: String)

    /** Main monitor. Yahoo Finance symbols. */
    val WATCH = listOf(
        Sym("^NSEI", "NIFTY 50", 2),
        Sym("^NSEBANK", "BANKNIFTY", 2),
        Sym("^INDIAVIX", "INDIA VIX", 2),
        Sym("GC=F", "GOLD $/oz", 1),
        Sym("SI=F", "SILVER $/oz", 3),
        Sym("INR=X", "USD/INR", 3),
        Sym("DX-Y.NYB", "DXY", 2),
        Sym("^TNX", "US 10Y %", 3),
        Sym("BZ=F", "BRENT $", 2),
        Sym("^GSPC", "S&P 500", 2),
    )

    /** Small set refreshed by the home-screen widget. */
    val WIDGET = listOf("^NSEI", "^INDIAVIX", "GC=F", "INR=X")

    fun decimals(symbol: String): Int = WATCH.firstOrNull { it.symbol == symbol }?.decimals ?: 2
    fun label(symbol: String): String = WATCH.firstOrNull { it.symbol == symbol }?.label ?: symbol

    private fun gnews(q: String): String =
        "https://news.google.com/rss/search?q=" + URLEncoder.encode(q, "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en"

    val NEWS = listOf(
        Feed("Google News", gnews("gold price OR bullion OR \"MCX gold\" when:1d")),
        Feed("Google News", gnews("Nifty OR Sensex OR \"Indian stock market\" when:1d")),
        Feed("Google News", gnews("\"Federal Reserve\" OR \"US dollar\" OR \"Treasury yields\" OR RBI OR rupee when:1d")),
        Feed("Google News", gnews("\"crude oil\" OR tariffs OR geopolitical markets when:1d")),
        Feed("ET Markets", "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms"),
        Feed("ET Commodities", "https://economictimes.indiatimes.com/markets/commodities/rssfeeds/1808152121.cms"),
        Feed("Mint", "https://www.livemint.com/rss/markets"),
        Feed("Moneycontrol", "https://www.moneycontrol.com/rss/marketreports.xml"),
    )

    const val GOLD_API = "https://api.gold-api.com/price/XAU"
    const val NSE = "https://www.nseindia.com"
    const val MCX = "https://www.mcxindia.com"

    fun yahoo(host: String, symbol: String, range: String, interval: String): String =
        "https://$host.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(symbol, "UTF-8") +
            "?range=$range&interval=$interval&includePrePost=false"
}
