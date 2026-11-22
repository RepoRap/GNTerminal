package com.gnterminal.app.data

import android.content.Context
import android.content.SharedPreferences
import com.gnterminal.app.core.GoldMath
import com.gnterminal.app.core.WidgetSnapshot

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("gn_prefs", Context.MODE_PRIVATE)

    var dutyPct: Double
        get() = sp.getFloat("duty", GoldMath.DEFAULT_DUTY_PCT.toFloat()).toDouble()
        set(v) {
            sp.edit().putFloat("duty", v.toFloat()).apply()
        }

    var includeGst: Boolean
        get() = sp.getBoolean("gst", false)
        set(v) {
            sp.edit().putBoolean("gst", v).apply()
        }

    var refreshSec: Int
        get() = sp.getInt("refresh", 60)
        set(v) {
            sp.edit().putInt("refresh", v).apply()
        }
}

/** Persists the widget snapshot so the widget can render without network. */
object SnapshotStore {
    private const val NAME = "gn_snapshot"

    private fun SharedPreferences.Editor.putD(k: String, v: Double?): SharedPreferences.Editor =
        if (v == null || v.isNaN() || v.isInfinite()) remove(k) else putString(k, v.toString())

    private fun SharedPreferences.getD(k: String): Double? = getString(k, null)?.toDoubleOrNull()

    fun save(context: Context, s: WidgetSnapshot) {
        val e = context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
        e.putD("nifty", s.nifty).putD("niftyChg", s.niftyChg)
            .putD("vix", s.vix).putD("vixChg", s.vixChg)
            .putD("gold", s.gold).putD("goldChg", s.goldChg)
            .putD("mcx", s.mcx).putD("mcxChg", s.mcxChg)
            .putD("usdinr", s.usdinr).putD("usdinrChg", s.usdinrChg)
            .putD("pcr", s.pcr).putD("maxPain", s.maxPain)
        e.putBoolean("mcxLive", s.mcxLive)
        e.putString("expiry", s.expiry)
        e.putString("bias", s.bias)
        e.putString("headline", s.headline)
        e.putInt("hGold", s.headlineGold)
        e.putInt("hNifty", s.headlineNifty)
        e.putLong("updated", s.updated)
        e.apply()
    }

    fun load(context: Context): WidgetSnapshot {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return WidgetSnapshot(
            nifty = p.getD("nifty"), niftyChg = p.getD("niftyChg"),
            vix = p.getD("vix"), vixChg = p.getD("vixChg"),
            gold = p.getD("gold"), goldChg = p.getD("goldChg"),
            mcx = p.getD("mcx"), mcxChg = p.getD("mcxChg"), mcxLive = p.getBoolean("mcxLive", false),
            usdinr = p.getD("usdinr"), usdinrChg = p.getD("usdinrChg"),
            pcr = p.getD("pcr"), maxPain = p.getD("maxPain"),
            expiry = p.getString("expiry", null),
            bias = p.getString("bias", null),
            headline = p.getString("headline", null),
            headlineGold = p.getInt("hGold", 0),
            headlineNifty = p.getInt("hNifty", 0),
            updated = p.getLong("updated", 0L),
        )
    }
}
