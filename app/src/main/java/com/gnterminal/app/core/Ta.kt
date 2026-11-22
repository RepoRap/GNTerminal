package com.gnterminal.app.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Technical-analysis primitives. Arrays are aligned with the input; warm-up values are NaN. */
object Ta {

    fun sma(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        if (n <= 0 || x.size < n) return out
        var sum = 0.0
        for (i in x.indices) {
            sum += x[i]
            if (i >= n) sum -= x[i - n]
            if (i >= n - 1) out[i] = sum / n
        }
        return out
    }

    /** EMA seeded with the SMA of the first n valid values; tolerates leading NaNs. */
    fun ema(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        val first = x.indexOfFirst { !it.isNaN() }
        if (n <= 0 || first < 0 || x.size - first < n) return out
        var prev = (first until first + n).sumOf { x[it] } / n
        out[first + n - 1] = prev
        val k = 2.0 / (n + 1)
        for (i in first + n until x.size) {
            prev = x[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** Wilder RSI. */
    fun rsi(x: DoubleArray, n: Int = 14): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        if (x.size <= n) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..n) {
            val d = x[i] - x[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        gain /= n
        loss /= n
        out[n] = rsiOf(gain, loss)
        for (i in n + 1 until x.size) {
            val d = x[i] - x[i - 1]
            gain = (gain * (n - 1) + maxOf(d, 0.0)) / n
            loss = (loss * (n - 1) + maxOf(-d, 0.0)) / n
            out[i] = rsiOf(gain, loss)
        }
        return out
    }

    private fun rsiOf(g: Double, l: Double): Double = if (l == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + g / l)

    class Macd(val line: DoubleArray, val signal: DoubleArray, val hist: DoubleArray)

    fun macd(x: DoubleArray, fast: Int = 12, slow: Int = 26, sig: Int = 9): Macd {
        val f = ema(x, fast)
        val s = ema(x, slow)
        val line = DoubleArray(x.size) { if (f[it].isNaN() || s[it].isNaN()) Double.NaN else f[it] - s[it] }
        val signal = ema(line, sig)
        val hist = DoubleArray(x.size) { if (line[it].isNaN() || signal[it].isNaN()) Double.NaN else line[it] - signal[it] }
        return Macd(line, signal, hist)
    }

    class Bands(val mid: DoubleArray, val upper: DoubleArray, val lower: DoubleArray)

    fun bollinger(x: DoubleArray, n: Int = 20, k: Double = 2.0): Bands {
        val mid = sma(x, n)
        val up = DoubleArray(x.size) { Double.NaN }
        val lo = DoubleArray(x.size) { Double.NaN }
        for (i in n - 1 until x.size) {
            var ss = 0.0
            for (j in i - n + 1..i) {
                val d = x[j] - mid[i]
                ss += d * d
            }
            val sd = sqrt(ss / n)
            up[i] = mid[i] + k * sd
            lo[i] = mid[i] - k * sd
        }
        return Bands(mid, up, lo)
    }

    /** Wilder ATR. */
    fun atr(h: DoubleArray, l: DoubleArray, c: DoubleArray, n: Int = 14): DoubleArray {
        val size = c.size
        val out = DoubleArray(size) { Double.NaN }
        if (size <= n) return out
        val tr = DoubleArray(size) { i ->
            if (i == 0) h[0] - l[0] else maxOf(h[i] - l[i], abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1]))
        }
        var a = (1..n).sumOf { tr[it] } / n
        out[n] = a
        for (i in n + 1 until size) {
            a = (a * (n - 1) + tr[i]) / n
            out[i] = a
        }
        return out
    }

    fun logReturns(x: DoubleArray): DoubleArray =
        DoubleArray(maxOf(0, x.size - 1)) { if (x[it] > 0 && x[it + 1] > 0) ln(x[it + 1] / x[it]) else 0.0 }

    fun stdev(x: DoubleArray): Double {
        if (x.size < 2) return Double.NaN
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }

    /** Annualised realised volatility (%) over the last n daily returns. */
    fun realizedVolPct(close: DoubleArray, n: Int = 20): Double {
        val r = logReturns(close)
        if (r.size < n) return Double.NaN
        return stdev(r.copyOfRange(r.size - n, r.size)) * sqrt(252.0) * 100.0
    }

    fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val n = minOf(a.size, b.size)
        if (n < 10) return Double.NaN
        val x = a.copyOfRange(a.size - n, a.size)
        val y = b.copyOfRange(b.size - n, b.size)
        val mx = x.average()
        val my = y.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = x[i] - mx
            val dy = y[i] - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx == 0.0 || syy == 0.0) return Double.NaN
        return sxy / sqrt(sxx * syy)
    }

    private fun dayMap(s: Series): HashMap<Long, Double> {
        val keys = s.dayKeys()
        val m = HashMap<Long, Double>()
        for (i in 0 until s.size) m[keys[i]] = s.close[i]
        return m
    }

    /** Daily log returns of two markets on their common trading days, last [lookback] values. */
    fun pairReturns(a: Series, b: Series, lookback: Int = 60): Pair<DoubleArray, DoubleArray> {
        val ma = dayMap(a)
        val mb = dayMap(b)
        val days = ma.keys.intersect(mb.keys).sorted()
        if (days.size < 3) return DoubleArray(0) to DoubleArray(0)
        val ca = DoubleArray(days.size) { ma.getValue(days[it]) }
        val cb = DoubleArray(days.size) { mb.getValue(days[it]) }
        val ra = logReturns(ca)
        val rb = logReturns(cb)
        val k = minOf(lookback, ra.size)
        return ra.copyOfRange(ra.size - k, ra.size) to rb.copyOfRange(rb.size - k, rb.size)
    }

    fun correlation(a: Series?, b: Series?, lookback: Int = 60): Double {
        if (a == null || b == null) return Double.NaN
        val (x, y) = pairReturns(a, b, lookback)
        return pearson(x, y)
    }
}

data class Signal(val name: String, val value: String, val vote: Int)

data class TechSummary(
    val last: Double,
    val sma20: Double,
    val sma50: Double,
    val sma200: Double,
    val rsi: Double,
    val macd: Double,
    val macdSignal: Double,
    val macdHist: Double,
    val bbUpper: Double,
    val bbLower: Double,
    val pctB: Double,
    val atr: Double,
    val atrPct: Double,
    val rv20: Double,
    val high52: Double,
    val low52: Double,
    val signals: List<Signal>,
    val score: Int,
    val tilt: String,
)

object Tech {
    fun summarize(s: Series): TechSummary? {
        val c = s.close
        if (c.size < 35) return null
        val n = c.size - 1
        val last = c[n]
        val sma20 = Ta.sma(c, 20)[n]
        val sma50 = Ta.sma(c, 50)[n]
        val sma200 = Ta.sma(c, 200)[n]
        val rsi = Ta.rsi(c)[n]
        val m = Ta.macd(c)
        val bb = Ta.bollinger(c)
        val atr = Ta.atr(s.high, s.low, c)[n]
        val rv = Ta.realizedVolPct(c)
        val pctB = if (!bb.upper[n].isNaN() && bb.upper[n] != bb.lower[n]) (last - bb.lower[n]) / (bb.upper[n] - bb.lower[n]) else Double.NaN
        val from = maxOf(0, c.size - 252)
        var hi = -Double.MAX_VALUE
        var lo = Double.MAX_VALUE
        for (i in from..n) {
            if (s.high[i] > hi) hi = s.high[i]
            if (s.low[i] < lo) lo = s.low[i]
        }

        val sig = ArrayList<Signal>()
        if (!sma50.isNaN()) {
            sig.add(Signal("Price vs SMA50", if (last > sma50) "above" else "below", if (last > sma50) 1 else -1))
        }
        if (!sma50.isNaN() && !sma200.isNaN()) {
            val golden = sma50 > sma200
            sig.add(Signal("SMA50 vs SMA200", if (golden) "golden (up-trend)" else "death (down-trend)", if (golden) 1 else -1))
        }
        if (!rsi.isNaN()) {
            val (txt, vote) = when {
                rsi >= 70 -> "overbought" to 0
                rsi >= 55 -> "bullish momentum" to 1
                rsi > 45 -> "neutral" to 0
                rsi > 30 -> "bearish momentum" to -1
                else -> "oversold" to 0
            }
            sig.add(Signal("RSI(14) ${Fmt.num(rsi, 1)}", txt, vote))
        }
        if (!m.hist[n].isNaN()) {
            val crossedUp = n >= 3 && (n - 2..n).any { it > 0 && m.hist[it - 1] <= 0 && m.hist[it] > 0 }
            val crossedDn = n >= 3 && (n - 2..n).any { it > 0 && m.hist[it - 1] >= 0 && m.hist[it] < 0 }
            val txt = when {
                crossedUp -> "fresh bullish cross"
                crossedDn -> "fresh bearish cross"
                m.hist[n] > 0 -> "above signal"
                else -> "below signal"
            }
            sig.add(Signal("MACD(12,26,9)", txt, if (m.hist[n] > 0) 1 else -1))
        }
        if (!pctB.isNaN()) {
            val txt = when {
                pctB > 1 -> "above upper band (stretched)"
                pctB < 0 -> "below lower band (stretched)"
                pctB > 0.8 -> "near upper band"
                pctB < 0.2 -> "near lower band"
                else -> "mid-band"
            }
            sig.add(Signal("Bollinger %B ${Fmt.num(pctB, 2)}", txt, 0))
        }
        if (c.size > 21) {
            val r20 = (last / c[n - 20] - 1) * 100
            sig.add(Signal("20-day return", Fmt.pct(r20), if (r20 > 0) 1 else -1))
        }
        val score = sig.sumOf { it.vote }
        val tilt = when {
            score >= 2 -> "BULLISH"
            score <= -2 -> "BEARISH"
            else -> "NEUTRAL"
        }
        return TechSummary(
            last = last, sma20 = sma20, sma50 = sma50, sma200 = sma200, rsi = rsi,
            macd = m.line[n], macdSignal = m.signal[n], macdHist = m.hist[n],
            bbUpper = bb.upper[n], bbLower = bb.lower[n], pctB = pctB,
            atr = atr, atrPct = if (atr.isNaN()) Double.NaN else atr / last * 100, rv20 = rv,
            high52 = hi, low52 = lo, signals = sig, score = score, tilt = tilt,
        )
    }
}
