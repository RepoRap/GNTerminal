package com.gnterminal.app.core

/** Daily or intraday OHLC series (NaN-free closes). Timestamps are epoch seconds. */
class Series(
    val symbol: String,
    val ts: LongArray,
    val open: DoubleArray,
    val high: DoubleArray,
    val low: DoubleArray,
    val close: DoubleArray,
    val gmtOffsetSec: Long,
) {
    val size: Int get() = close.size

    /** Local calendar day number for each bar, used to align different markets. */
    fun dayKeys(): LongArray = LongArray(size) { (ts[it] + gmtOffsetSec).floorDiv(86_400L) }
}

data class Quote(
    val symbol: String,
    val price: Double,
    val prevClose: Double,
    val dayHigh: Double,
    val dayLow: Double,
    val currency: String,
    val timeSec: Long,
    val spark: List<Double>,
) {
    val change: Double
        get() = if (prevClose.isNaN()) Double.NaN else price - prevClose

    val changePct: Double
        get() = if (prevClose.isNaN() || prevClose == 0.0) Double.NaN else (price - prevClose) / prevClose * 100.0
}

data class OptLeg(
    val oi: Double,
    val chgOi: Double,
    val volume: Double,
    val iv: Double,
    val ltp: Double,
    val change: Double,
)

data class OptionRow(val strike: Double, val ce: OptLeg?, val pe: OptLeg?)

data class OptionChain(
    val symbol: String,
    val expiry: String,
    val expiries: List<String>,
    val spot: Double,
    val timestamp: String,
    val rows: List<OptionRow>,
)

data class McxQuote(
    val symbol: String,
    val expiry: String,
    val ltp: Double,
    val prevClose: Double,
    val oi: Double?,
    val volume: Double?,
) {
    val change: Double
        get() = if (prevClose > 0) ltp - prevClose else Double.NaN

    val changePct: Double
        get() = if (prevClose > 0) (ltp - prevClose) / prevClose * 100.0 else Double.NaN
}

/** FII / DII cash-market activity (NSE, ₹ crore). */
data class FlowRow(
    val category: String,
    val date: String,
    val buy: Double,
    val sell: Double,
    val net: Double,
)

data class NewsItem(
    val title: String,
    val link: String,
    val source: String,
    val publishedMs: Long,
    val tags: List<String> = emptyList(),
    val gold: Int = 0,
    val nifty: Int = 0,
    val aboutGold: Boolean = false,
    val aboutNifty: Boolean = false,
)
