package com.gnterminal.app.core


/**
 * Cross-asset drivers of gold and Nifty. "Theory" is the textbook direction;
 * the board also measures the actual 60-day correlation and uses it to turn
 * today's move in each driver into an estimated push on gold / Nifty.
 */
data class DriverDef(
    val symbol: String,
    val label: String,
    val goldTheory: Int,
    val niftyTheory: Int,
    val goldWhy: String,
    val niftyWhy: String,
    /** Contemporaneous mirrors (silver, VIX, Bank Nifty) are shown but excluded from pressure. */
    val inPressure: Boolean = true,
)

data class DriverReading(
    val def: DriverDef,
    val price: Double?,
    val changePct: Double?,
    val z: Double?,
    val corrGold: Double?,
    val corrNifty: Double?,
    val goldImpact: Double?,
    val niftyImpact: Double?,
)

data class DriverBoard(
    val readings: List<DriverReading>,
    val goldPressure: Double?,
    val niftyPressure: Double?,
    val goldNiftyCorr: Double?,
)

object Drivers {
    const val GOLD = "GC=F"
    const val NIFTY = "^NSEI"

    val LIST = listOf(
        DriverDef(
            "DX-Y.NYB", "US DOLLAR (DXY)", -1, -1,
            "Gold is priced in dollars; a stronger dollar makes it dearer for everyone else",
            "A strong dollar tends to pull foreign portfolio money out of India",
        ),
        DriverDef(
            "^TNX", "US 10Y YIELD", -1, -1,
            "Higher yields raise the cost of holding gold, which pays no interest",
            "Higher US yields draw foreign money away from emerging markets",
        ),
        DriverDef(
            "INR=X", "USD/INR", 1, -1,
            "A weaker rupee lifts the rupee price of gold (MCX) even if global gold is flat",
            "A weak rupee signals outflows and raises India's import bill",
        ),
        DriverDef(
            "BZ=F", "BRENT CRUDE", 1, -1,
            "Oil spikes feed inflation fears and hedging demand",
            "India imports most of its crude; costlier oil hurts margins and the deficit",
        ),
        DriverDef(
            "^GSPC", "S&P 500", -1, 1,
            "Strong risk appetite can pull money away from safe havens",
            "Global risk sentiment spills over into Indian equities",
        ),
        DriverDef(
            "^INDIAVIX", "INDIA VIX", 0, -1,
            "",
            "Rising expected volatility usually comes with a falling Nifty",
            inPressure = false,
        ),
        DriverDef(
            "SI=F", "SILVER", 1, 0,
            "Silver and gold usually move together as precious metals",
            "",
            inPressure = false,
        ),
        DriverDef(
            "^NSEBANK", "BANK NIFTY", 0, 1,
            "",
            "Banks are roughly a third of the Nifty's weight",
            inPressure = false,
        ),
    )

    /** Standard deviation of daily % moves over the last [n] sessions. */
    fun dailyVolPct(s: Series, n: Int = 60): Double {
        val r = Ta.logReturns(s.close)
        if (r.size < 10) return Double.NaN
        val w = r.copyOfRange(maxOf(0, r.size - n), r.size)
        return Ta.stdev(w) * 100.0
    }

    fun board(quotes: Map<String, Quote>, daily: Map<String, Series>): DriverBoard {
        val gold = daily[GOLD]
        val nifty = daily[NIFTY]
        val readings = LIST.map { d ->
            val q = quotes[d.symbol]
            val s = daily[d.symbol]
            val chg = q?.changePct?.takeIf { !it.isNaN() }
            val vol = s?.let { dailyVolPct(it) }?.takeIf { !it.isNaN() && it > 0 }
            val z = if (chg != null && vol != null) chg / vol else null
            val cg = Ta.correlation(s, gold).takeIf { !it.isNaN() }
            val cn = Ta.correlation(s, nifty).takeIf { !it.isNaN() }
            val gi = z?.let { it * (cg ?: (d.goldTheory * 0.3)) }
            val ni = z?.let { it * (cn ?: (d.niftyTheory * 0.3)) }
            DriverReading(d, q?.price, chg, z, cg, cn, gi, ni)
        }
        val gImpacts = readings.filter { it.def.inPressure }.mapNotNull { it.goldImpact }
        val nImpacts = readings.filter { it.def.inPressure }.mapNotNull { it.niftyImpact }
        return DriverBoard(
            readings = readings,
            goldPressure = if (gImpacts.isEmpty()) null else gImpacts.sum(),
            niftyPressure = if (nImpacts.isEmpty()) null else nImpacts.sum(),
            goldNiftyCorr = Ta.correlation(gold, nifty).takeIf { !it.isNaN() },
        )
    }

    fun label(p: Double?): String {
        if (p == null || p.isNaN()) return "—"
        return when {
            p >= 0.5 -> "TAILWIND"
            p <= -0.5 -> "HEADWIND"
            else -> "NEUTRAL"
        }
    }
}

/** India landed-cost maths for gold. */
object GoldMath {
    const val GRAMS_PER_OZ = 31.1034768
    const val DEFAULT_DUTY_PCT = 15.0 // 10% BCD + 5% AIDC, effective 13 May 2026
    const val GST_PCT = 3.0

    fun inrPer10g(usdPerOz: Double, usdInr: Double): Double = usdPerOz * usdInr / GRAMS_PER_OZ * 10.0

    fun landed(usdPerOz: Double, usdInr: Double, dutyPct: Double): Double =
        inrPer10g(usdPerOz, usdInr) * (1.0 + dutyPct / 100.0)

    fun withGst(v: Double): Double = v * (1.0 + GST_PCT / 100.0)
}
