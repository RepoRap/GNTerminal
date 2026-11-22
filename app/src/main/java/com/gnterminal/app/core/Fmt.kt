package com.gnterminal.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

object Fmt {
    const val DASH = "—"

    private fun bad(v: Double?): Boolean = v == null || v.isNaN() || v.isInfinite()

    fun num(v: Double?, dec: Int = 2): String =
        if (bad(v)) DASH else String.format(Locale.US, "%,.${dec}f", v)

    fun signed(v: Double?, dec: Int = 2): String {
        if (v == null || bad(v)) return DASH
        return (if (v > 0) "+" else "") + String.format(Locale.US, "%,.${dec}f", v)
    }

    fun pct(v: Double?, dec: Int = 2): String = if (bad(v)) DASH else signed(v, dec) + "%"

    /** Indian digit grouping: 1,45,320.50 */
    fun inr(v: Double?, dec: Int = 0): String {
        if (v == null || bad(v)) return DASH
        val neg = v < 0
        val s = String.format(Locale.US, "%.${dec}f", abs(v))
        val intPart = s.substringBefore('.')
        val frac = if (dec > 0) "." + s.substringAfter('.') else ""
        val grouped = if (intPart.length <= 3) {
            intPart
        } else {
            val last3 = intPart.takeLast(3)
            var rest = intPart.dropLast(3)
            val parts = ArrayList<String>()
            while (rest.length > 2) {
                parts.add(0, rest.takeLast(2))
                rest = rest.dropLast(2)
            }
            if (rest.isNotEmpty()) parts.add(0, rest)
            parts.joinToString(",") + "," + last3
        }
        return (if (neg) "-" else "") + "₹" + grouped + frac
    }

    fun inrSigned(v: Double?, dec: Int = 0): String {
        if (v == null || bad(v)) return DASH
        return if (v > 0) "+" + inr(v, dec) else inr(v, dec)
    }

    /** Compact Indian units: 12.3K, 4.5L, 1.20Cr */
    fun compact(v: Double?): String {
        if (v == null || bad(v)) return DASH
        val a = abs(v)
        val sign = if (v < 0) "-" else ""
        val body = when {
            a >= 1e7 -> String.format(Locale.US, "%.2fCr", a / 1e7)
            a >= 1e5 -> String.format(Locale.US, "%.1fL", a / 1e5)
            a >= 1e3 -> String.format(Locale.US, "%.1fK", a / 1e3)
            else -> String.format(Locale.US, "%.0f", a)
        }
        return sign + body
    }

    fun compactSigned(v: Double?): String {
        if (v == null || bad(v)) return DASH
        return if (v > 0) "+" + compact(v) else compact(v)
    }

    fun timeAgo(ms: Long, now: Long): String {
        if (ms <= 0) return ""
        val m = (now - ms) / 60_000
        return when {
            m < 1 -> "now"
            m < 60 -> "${m}m"
            m < 1440 -> "${m / 60}h"
            else -> "${m / 1440}d"
        }
    }

    fun ist(ms: Long, pattern: String = "HH:mm:ss"): String {
        val f = SimpleDateFormat(pattern, Locale.US)
        f.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return f.format(Date(ms))
    }
}
