package com.gnterminal.app.core

import kotlin.math.abs
import kotlin.math.sqrt

data class OptionStats(
    val spot: Double,
    val atm: Double,
    val dte: Double,
    val totalCeOi: Double,
    val totalPeOi: Double,
    val totalCeVol: Double,
    val totalPeVol: Double,
    val ceChgOi: Double,
    val peChgOi: Double,
    val pcrOi: Double,
    val pcrVol: Double,
    val maxPain: Double,
    val resistance: List<Double>,
    val support: List<Double>,
    val atmIv: Double,
    val straddle: Double,
    val straddleMovePct: Double,
    val ivMove: Double,
    val skew: Double,
    val bias: String,
    val biasScore: Int,
    val notes: List<String>,
)

object OptionsMath {

    /** Strike at which option writers' total payout to buyers is smallest. */
    fun maxPain(rows: List<OptionRow>): Double {
        var best = Double.NaN
        var bestPain = Double.MAX_VALUE
        for (k in rows) {
            var pain = 0.0
            for (r in rows) {
                if (k.strike > r.strike) pain += (r.ce?.oi ?: 0.0) * (k.strike - r.strike)
                if (k.strike < r.strike) pain += (r.pe?.oi ?: 0.0) * (r.strike - k.strike)
            }
            if (pain < bestPain) {
                bestPain = pain
                best = k.strike
            }
        }
        return best
    }

    /** Rows within [width] strikes either side of the ATM strike. */
    fun window(chain: OptionChain, atm: Double?, width: Int = 12): List<OptionRow> {
        val rows = chain.rows
        if (rows.isEmpty()) return rows
        val center = atm ?: chain.spot
        var idx = 0
        var bestD = Double.MAX_VALUE
        rows.forEachIndexed { i, r ->
            val d = abs(r.strike - center)
            if (d < bestD) {
                bestD = d
                idx = i
            }
        }
        return rows.subList(maxOf(0, idx - width), minOf(rows.size, idx + width + 1))
    }

    private fun nearest(rows: List<OptionRow>, target: Double): OptionRow? = rows.minByOrNull { abs(it.strike - target) }

    fun stats(chain: OptionChain, nowMs: Long): OptionStats? {
        val rows = chain.rows.filter { it.ce != null || it.pe != null }
        if (rows.isEmpty()) return null
        val spot = if (chain.spot > 0) chain.spot else rows[rows.size / 2].strike
        val atmRow = nearest(rows, spot) ?: return null
        val atm = atmRow.strike

        var ceOi = 0.0
        var peOi = 0.0
        var ceVol = 0.0
        var peVol = 0.0
        var ceChg = 0.0
        var peChg = 0.0
        for (r in rows) {
            r.ce?.let {
                ceOi += it.oi
                ceVol += it.volume
                ceChg += it.chgOi
            }
            r.pe?.let {
                peOi += it.oi
                peVol += it.volume
                peChg += it.chgOi
            }
        }
        val pcrOi = if (ceOi > 0) peOi / ceOi else Double.NaN
        val pcrVol = if (ceVol > 0) peVol / ceVol else Double.NaN
        val mp = maxPain(rows)

        val resistance = rows.filter { it.ce != null && it.strike >= atm }
            .sortedByDescending { it.ce?.oi ?: 0.0 }.take(3).map { it.strike }
        val support = rows.filter { it.pe != null && it.strike <= atm }
            .sortedByDescending { it.pe?.oi ?: 0.0 }.take(3).map { it.strike }

        val ivs = listOfNotNull(atmRow.ce?.iv?.takeIf { it > 0 }, atmRow.pe?.iv?.takeIf { it > 0 })
        val atmIv = if (ivs.isEmpty()) Double.NaN else ivs.average()
        val ceL = atmRow.ce?.ltp ?: 0.0
        val peL = atmRow.pe?.ltp ?: 0.0
        val straddle = if (ceL > 0 && peL > 0) ceL + peL else Double.NaN
        val dte = NseParser.daysToExpiry(chain.expiry, nowMs)
        val ivMove = if (!atmIv.isNaN() && !dte.isNaN()) spot * atmIv / 100.0 * sqrt(dte / 365.0) else Double.NaN

        val putIv = nearest(rows, spot * 0.97)?.pe?.iv ?: 0.0
        val callIv = nearest(rows, spot * 1.03)?.ce?.iv ?: 0.0
        val skew = if (putIv > 0 && callIv > 0) putIv - callIv else Double.NaN

        // ---- Heuristic positioning read ----
        var score = 0
        val notes = ArrayList<String>()
        if (!pcrOi.isNaN()) {
            when {
                pcrOi >= 1.3 -> {
                    score += 1
                    notes.add("PCR ${Fmt.num(pcrOi)}: heavy put writing → firm support (very high PCR can also mean an overstretched rally)")
                }
                pcrOi >= 1.0 -> {
                    score += 1
                    notes.add("PCR ${Fmt.num(pcrOi)}: put OI > call OI → mildly bullish positioning")
                }
                pcrOi >= 0.8 -> notes.add("PCR ${Fmt.num(pcrOi)}: balanced positioning")
                else -> {
                    score -= 1
                    notes.add("PCR ${Fmt.num(pcrOi)}: call writers dominant → overhead supply")
                }
            }
        }
        val netChg = peChg - ceChg
        if (abs(netChg) > 0) {
            if (netChg > 0) {
                score += 1
                notes.add("Fresh put OI exceeds call OI by ${Fmt.compact(netChg)} → support being built")
            } else {
                score -= 1
                notes.add("Fresh call OI exceeds put OI by ${Fmt.compact(-netChg)} → ceiling being built")
            }
        }
        if (!mp.isNaN() && spot > 0) {
            val gap = (spot - mp) / spot * 100
            val near = !dte.isNaN() && dte <= 3
            when {
                gap > 0.75 -> {
                    notes.add("Spot ${Fmt.pct(gap)} above max pain ${Fmt.num(mp, 0)}" + (if (near) " → expiry pull lower" else ""))
                    if (near) score -= 1
                }
                gap < -0.75 -> {
                    notes.add("Spot ${Fmt.pct(gap)} below max pain ${Fmt.num(mp, 0)}" + (if (near) " → expiry pull higher" else ""))
                    if (near) score += 1
                }
                else -> notes.add("Spot pinned near max pain ${Fmt.num(mp, 0)}")
            }
        }
        if (resistance.isNotEmpty() && support.isNotEmpty()) {
            notes.add("OI range ${Fmt.num(support.first(), 0)} – ${Fmt.num(resistance.first(), 0)} (largest put / call walls)")
        }
        if (!skew.isNaN()) {
            notes.add(if (skew > 2) "Put skew ${Fmt.num(skew, 1)} vol pts → demand for downside protection" else "Skew ${Fmt.num(skew, 1)} vol pts → calm hedging demand")
        }
        val bias = when {
            score >= 2 -> "BULLISH"
            score <= -2 -> "BEARISH"
            else -> "RANGE / NEUTRAL"
        }

        return OptionStats(
            spot = spot, atm = atm, dte = dte,
            totalCeOi = ceOi, totalPeOi = peOi, totalCeVol = ceVol, totalPeVol = peVol,
            ceChgOi = ceChg, peChgOi = peChg, pcrOi = pcrOi, pcrVol = pcrVol, maxPain = mp,
            resistance = resistance, support = support, atmIv = atmIv, straddle = straddle,
            straddleMovePct = if (straddle.isNaN()) Double.NaN else straddle / spot * 100,
            ivMove = ivMove, skew = skew, bias = bias, biasScore = score, notes = notes,
        )
    }
}
