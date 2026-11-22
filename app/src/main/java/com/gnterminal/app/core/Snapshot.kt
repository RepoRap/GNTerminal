package com.gnterminal.app.core

/** Compact state shown on the home-screen widget. Missing fields keep their previous values. */
data class WidgetSnapshot(
    val nifty: Double? = null,
    val niftyChg: Double? = null,
    val vix: Double? = null,
    val vixChg: Double? = null,
    val gold: Double? = null,
    val goldChg: Double? = null,
    val mcx: Double? = null,
    val mcxChg: Double? = null,
    val mcxLive: Boolean = false,
    val usdinr: Double? = null,
    val usdinrChg: Double? = null,
    val pcr: Double? = null,
    val maxPain: Double? = null,
    val expiry: String? = null,
    val bias: String? = null,
    val headline: String? = null,
    val headlineGold: Int = 0,
    val headlineNifty: Int = 0,
    val updated: Long = 0L,
)

object SnapshotBuilder {
    private fun Double?.ok(): Double? = this?.takeIf { !it.isNaN() && !it.isInfinite() }

    fun build(
        quotes: Map<String, Quote>,
        goldSpot: Double?,
        mcx: McxQuote?,
        opt: OptionStats?,
        expiry: String?,
        news: List<NewsItem>,
        dutyPct: Double,
        includeGst: Boolean,
        prev: WidgetSnapshot,
        now: Long,
    ): WidgetSnapshot {
        val n = quotes[Drivers.NIFTY]
        val v = quotes["^INDIAVIX"]
        val g = quotes[Drivers.GOLD]
        val fx = quotes["INR=X"]
        val goldPx = goldSpot.ok() ?: g?.price.ok()
        val goldChg = g?.changePct.ok()
        val usdinr = fx?.price.ok()

        var mcxVal = prev.mcx
        var mcxChg = prev.mcxChg
        var live = prev.mcxLive
        if (mcx != null) {
            mcxVal = mcx.ltp
            mcxChg = mcx.changePct.ok()
            live = true
        } else if (goldPx != null && usdinr != null) {
            var x = GoldMath.landed(goldPx, usdinr, dutyPct)
            if (includeGst) x = GoldMath.withGst(x)
            mcxVal = x
            val fxChg = fx?.changePct.ok()
            mcxChg = if (goldChg != null && fxChg != null) ((1 + goldChg / 100) * (1 + fxChg / 100) - 1) * 100 else goldChg
            live = false
        }

        val top = news.firstOrNull { it.gold != 0 || it.nifty != 0 } ?: news.firstOrNull()
        return WidgetSnapshot(
            nifty = n?.price.ok() ?: prev.nifty,
            niftyChg = n?.changePct.ok() ?: prev.niftyChg,
            vix = v?.price.ok() ?: prev.vix,
            vixChg = v?.changePct.ok() ?: prev.vixChg,
            gold = goldPx ?: prev.gold,
            goldChg = goldChg ?: prev.goldChg,
            mcx = mcxVal,
            mcxChg = mcxChg,
            mcxLive = live,
            usdinr = usdinr ?: prev.usdinr,
            usdinrChg = fx?.changePct.ok() ?: prev.usdinrChg,
            pcr = opt?.pcrOi.ok() ?: prev.pcr,
            maxPain = opt?.maxPain.ok() ?: prev.maxPain,
            expiry = expiry ?: prev.expiry,
            bias = opt?.bias ?: prev.bias,
            headline = top?.title ?: prev.headline,
            headlineGold = top?.gold ?: prev.headlineGold,
            headlineNifty = top?.nifty ?: prev.headlineNifty,
            updated = now,
        )
    }
}
