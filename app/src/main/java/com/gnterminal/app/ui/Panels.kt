package com.gnterminal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gnterminal.app.core.Drivers
import com.gnterminal.app.core.FlowRow
import com.gnterminal.app.core.Fmt
import com.gnterminal.app.core.GoldMath
import com.gnterminal.app.core.NewsTagger
import com.gnterminal.app.core.Series
import com.gnterminal.app.core.Ta
import com.gnterminal.app.core.TechSummary
import com.gnterminal.app.data.Sources
import kotlin.math.abs

val TABS = listOf("F1 MKT", "F2 GOLD", "F3 OPTIONS", "F4 DRIVERS", "F5 NEWS", "F6 SET")

@Composable
fun TopBar(s: UiState, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Term.Amber).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MText("GN TERMINAL", Term.Bg, 13, bold = true)
        MText("  GOLD · NIFTY OPT", Term.Bg, 10, modifier = Modifier.weight(1f), maxLines = 1)
        MText(if (s.lastUpdate > 0) Fmt.ist(s.lastUpdate) + " IST" else "--:--:--", Term.Bg, 10)
        MText(
            if (s.loading) "  …" else "  ⟳",
            Term.Bg, 16, bold = true,
            modifier = Modifier.clickable { onRefresh() }.padding(start = 6.dp),
        )
    }
}

@Composable
fun TabBar(sel: Int, onSel: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Term.Header).horizontalScroll(rememberScrollState())) {
        TABS.forEachIndexed { i, t ->
            MText(
                t, if (i == sel) Term.Bg else Term.Amber, 11, bold = true, maxLines = 1,
                modifier = Modifier
                    .background(if (i == sel) Term.Amber else Term.Header)
                    .clickable { onSel(i) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** Scrolling strip of the key prices under the tabs. */
@Composable
fun TickerStrip(s: UiState) {
    Row(
        Modifier.fillMaxWidth().background(Term.Bg).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (sym in Sources.WATCH) {
            val q = s.quotes[sym.symbol]
            if (q != null) {
                MText(sym.label + " ", Term.Amber, 10, maxLines = 1)
                MText(Fmt.num(q.price, sym.decimals) + " ", Term.Text, 10, maxLines = 1)
                MText(Fmt.pct(q.changePct), chgColor(q.changePct), 10, maxLines = 1)
                Spacer(Modifier.width(14.dp))
            }
        }
    }
}

@Composable
fun WatchPanel(s: UiState) {
    Panel("MONITOR", right = "Yahoo Finance · may be delayed") {
        HeaderRow(listOf("SECURITY" to 1.5f, "LAST" to 1.3f, "CHG" to 1.0f, "%CHG" to 0.9f, "TODAY" to 1.0f))
        for (sym in Sources.WATCH) {
            val q = s.quotes[sym.symbol]
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                MText(sym.label, Term.Amber, 11, modifier = Modifier.weight(1.5f), maxLines = 1)
                if (q == null) {
                    MText(Fmt.DASH, Term.Dim, 11, modifier = Modifier.weight(4.2f), align = TextAlign.End)
                } else {
                    MText(Fmt.num(q.price, sym.decimals), Term.Text, 11, modifier = Modifier.weight(1.3f), align = TextAlign.End, maxLines = 1)
                    MText(Fmt.signed(q.change, sym.decimals), chgColor(q.change), 11, modifier = Modifier.weight(1.0f), align = TextAlign.End, maxLines = 1)
                    MText(Fmt.pct(q.changePct), chgColor(q.change), 11, modifier = Modifier.weight(0.9f), align = TextAlign.End, maxLines = 1)
                    Sparkline(
                        q.spark,
                        Modifier.weight(1.0f).height(18.dp).padding(start = 6.dp),
                        chgColor(q.change),
                        q.prevClose,
                    )
                }
            }
        }
    }
}

@Composable
fun GoldSummaryPanel(s: UiState) {
    val g = s.quotes[Drivers.GOLD]
    val fx = s.quotes["INR=X"]
    val spot = s.goldSpot ?: g?.price
    val landed = if (spot != null && fx != null) GoldMath.landed(spot, fx.price, s.dutyPct) else null
    val m = s.mcx
    Panel("GOLD", right = if (s.goldSpot != null) "spot: gold-api.com" else "spot: COMEX proxy") {
        KV("XAU SPOT  $/oz", Fmt.num(spot, 2), chgColor(g?.changePct), note = Fmt.pct(g?.changePct), noteColor = chgColor(g?.changePct))
        KV("COMEX FUT $/oz", Fmt.num(g?.price, 1), chgColor(g?.change))
        KV("USD/INR", Fmt.num(fx?.price, 3), chgColor(fx?.change), note = Fmt.pct(fx?.changePct), noteColor = chgColor(fx?.changePct))
        KV("LANDED ₹/10g · duty ${Fmt.num(s.dutyPct, 1)}%", Fmt.inr(landed), Term.Yellow)
        if (s.includeGst && landed != null) KV("  incl. 3% GST", Fmt.inr(GoldMath.withGst(landed)), Term.Yellow)
        if (m != null) {
            KV("MCX GOLD ${m.expiry}", Fmt.inr(m.ltp), chgColor(m.change), note = Fmt.pct(m.changePct), noteColor = chgColor(m.changePct))
            if (landed != null) {
                val prem = m.ltp - landed
                KV("MCX vs landed", Fmt.inrSigned(prem), chgColor(prem), note = Fmt.pct(prem / landed * 100))
            }
        } else {
            KV("MCX GOLD", "not reachable · using landed", Term.Dim)
        }
        TiltLine("TECH", s.techGold?.tilt)
        TiltLine("MACRO", Drivers.label(s.board?.goldPressure))
        val nt = NewsTagger.tilt(s.news, true, System.currentTimeMillis() - 24 * 3_600_000L)
        TiltLine("NEWS 24h", if (nt.count == 0) null else "▲${nt.up}  ▼${nt.down}  net ${Fmt.signed(nt.score.toDouble(), 0)}")
    }
}

@Composable
fun TiltLine(label: String, value: String?) {
    val c = if (value == null) {
        Term.Dim
    } else if (value.contains("BULL") || value.contains("TAIL")) {
        Term.Up
    } else if (value.contains("BEAR") || value.contains("HEAD")) {
        Term.Down
    } else {
        Term.Text
    }
    KV(label, value ?: Fmt.DASH, c)
}

@Composable
fun OptionsSummaryPanel(s: UiState) {
    val st = s.optStats
    val c = s.chain
    val status = s.status["NSE option chain"]
    Panel(
        "NIFTY OPTIONS" + (c?.let { " · " + it.expiry } ?: ""),
        right = st?.bias ?: (if (status?.ok == false) "UNAVAILABLE" else "loading…"),
        rightColor = if (st == null) Term.Dim else if (st.biasScore >= 2) Term.Up else if (st.biasScore <= -2) Term.Down else Term.Yellow,
    ) {
        if (st == null || c == null) {
            MText(status?.message?.let { "NSE: $it" } ?: "Fetching option chain from NSE…", Term.Dim, 11)
        } else {
            KV("SPOT", Fmt.num(st.spot, 2), note = "ATM ${Fmt.num(st.atm, 0)}")
            KV("DAYS TO EXPIRY", Fmt.num(st.dte, 1))
            KV("PCR (OI / VOL)", Fmt.num(st.pcrOi) + " / " + Fmt.num(st.pcrVol), if (st.pcrOi >= 1) Term.Up else Term.Down)
            KV("MAX PAIN", Fmt.num(st.maxPain, 0), note = Fmt.pct((st.spot - st.maxPain) / st.spot * 100) + " vs spot")
            KV("RESISTANCE (CE OI)", st.resistance.joinToString(" · ") { Fmt.num(it, 0) }, Term.Down)
            KV("SUPPORT (PE OI)", st.support.joinToString(" · ") { Fmt.num(it, 0) }, Term.Up)
            KV("ATM IV", Fmt.num(st.atmIv, 1) + "%", note = "skew " + Fmt.num(st.skew, 1))
            KV("ATM STRADDLE", Fmt.num(st.straddle, 1), note = "±" + Fmt.num(st.straddleMovePct, 2) + "%")
            KV("1σ MOVE TO EXPIRY", "±" + Fmt.num(st.ivMove, 0), note = Fmt.num(st.spot - st.ivMove, 0) + "–" + Fmt.num(st.spot + st.ivMove, 0))
            KV("Δ OI  CE / PE", Fmt.compactSigned(st.ceChgOi) + " / " + Fmt.compactSigned(st.peChgOi))
            for (n in st.notes) MText("• $n", Term.Dim, 10)
            MText("Source: NSE (${s.chainVia}) · ${c.timestamp}", Term.Dim, 9)
        }
    }
}

@Composable
fun PressurePanel(s: UiState) {
    val b = s.board
    Panel("CROSS-ASSET PRESSURE", right = "today's driver moves × 60d correlation") {
        Gauge("GOLD", b?.goldPressure, Drivers.label(b?.goldPressure))
        Gauge("NIFTY", b?.niftyPressure, Drivers.label(b?.niftyPressure))
        MText(
            "Sums each macro driver's move today (in standard deviations) weighted by its 60-day correlation with the asset. " +
                "Descriptive, not a forecast.",
            Term.Dim, 9,
        )
    }
}

@Composable
fun DailyChartPanel(title: String, series: Series?) {
    Panel(title) {
        if (series == null || series.size < 30) {
            MText("loading history…", Term.Dim, 11)
        } else {
            val lines = remember(series) {
                listOf(
                    series.close to Term.Text,
                    Ta.sma(series.close, 50) to Term.Amber,
                    Ta.sma(series.close, 200) to Term.Cyan,
                )
            }
            LineChart(lines, Modifier.fillMaxWidth().height(150.dp))
            Row(Modifier.padding(top = 3.dp)) {
                MText("— close  ", Term.Text, 9)
                MText("— SMA50  ", Term.Amber, 9)
                MText("— SMA200", Term.Cyan, 9)
            }
        }
    }
}

@Composable
fun TechPanel(title: String, t: TechSummary?) {
    val tiltColor = when (t?.tilt) {
        "BULLISH" -> Term.Up
        "BEARISH" -> Term.Down
        null -> Term.Dim
        else -> Term.Yellow
    }
    Panel(title, right = t?.tilt ?: "loading…", rightColor = tiltColor) {
        if (t == null) {
            MText("Needs daily history.", Term.Dim, 11)
        } else {
            KV("LAST", Fmt.num(t.last, 2), note = "52w " + Fmt.num(t.low52, 0) + "–" + Fmt.num(t.high52, 0))
            KV("SMA 20 / 50 / 200", Fmt.num(t.sma20, 0) + " / " + Fmt.num(t.sma50, 0) + " / " + Fmt.num(t.sma200, 0))
            KV("MACD / SIGNAL", Fmt.num(t.macd, 2) + " / " + Fmt.num(t.macdSignal, 2), chgColor(t.macdHist))
            KV("BOLLINGER 20,2", Fmt.num(t.bbLower, 0) + " – " + Fmt.num(t.bbUpper, 0))
            KV("ATR(14)", Fmt.num(t.atr, 1), note = Fmt.num(t.atrPct, 2) + "% of price")
            KV("REALISED VOL 20d", Fmt.num(t.rv20, 1) + "%")
            Spacer(Modifier.height(4.dp))
            for (sg in t.signals) {
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    MText(sg.name, Term.Text, 10, modifier = Modifier.weight(1.2f), maxLines = 1)
                    MText(sg.value, Term.Dim, 10, modifier = Modifier.weight(1.2f), align = TextAlign.End, maxLines = 1)
                    MText(
                        when {
                            sg.vote > 0 -> "  ▲"
                            sg.vote < 0 -> "  ▼"
                            else -> "  •"
                        },
                        voteColor(sg.vote), 10, bold = true,
                    )
                }
            }
            KV("SCORE", "${t.score} → ${t.tilt}", tiltColor)
        }
    }
}

@Composable
fun DriverImpactPanel(s: UiState, gold: Boolean) {
    val b = s.board
    Panel(if (gold) "WHAT'S MOVING GOLD" else "WHAT'S MOVING NIFTY", right = "ρ = 60-day correlation") {
        if (b == null) {
            MText("Waiting for quotes and history…", Term.Dim, 11)
        } else {
            HeaderRow(listOf("DRIVER" to 1.7f, "%CHG" to 0.9f, "Z" to 0.7f, "ρ" to 0.7f, "PUSH" to 0.8f))
            val rows = b.readings
                .filter { (if (gold) it.def.goldWhy else it.def.niftyWhy).isNotEmpty() }
                .sortedByDescending { abs((if (gold) it.goldImpact else it.niftyImpact) ?: 0.0) }
            for (r in rows) {
                val corr = if (gold) r.corrGold else r.corrNifty
                val push = if (gold) r.goldImpact else r.niftyImpact
                Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    MText(r.def.label, Term.Amber, 10, modifier = Modifier.weight(1.7f), maxLines = 1)
                    MText(Fmt.pct(r.changePct), chgColor(r.changePct), 10, modifier = Modifier.weight(0.9f), align = TextAlign.End, maxLines = 1)
                    MText(Fmt.num(r.z, 1), Term.Text, 10, modifier = Modifier.weight(0.7f), align = TextAlign.End, maxLines = 1)
                    MText(Fmt.num(corr, 2), chgColor(corr), 10, modifier = Modifier.weight(0.7f), align = TextAlign.End, maxLines = 1)
                    MText(Fmt.signed(push, 2), chgColor(push), 10, bold = true, modifier = Modifier.weight(0.8f), align = TextAlign.End, maxLines = 1)
                }
                MText(if (gold) r.def.goldWhy else r.def.niftyWhy, Term.Dim, 9)
            }
            Spacer(Modifier.height(4.dp))
            if (gold) {
                Gauge("NET MACRO PUSH ON GOLD", b.goldPressure, Drivers.label(b.goldPressure))
            } else {
                Gauge("NET MACRO PUSH ON NIFTY", b.niftyPressure, Drivers.label(b.niftyPressure))
            }
        }
    }
}

@Composable
fun FlowsPanel(flows: List<FlowRow>) {
    Panel("FII / DII CASH FLOWS", right = "NSE · ₹ crore") {
        if (flows.isEmpty()) {
            MText("Not available yet (NSE publishes after market close).", Term.Dim, 11)
        } else {
            HeaderRow(listOf("CATEGORY" to 1.4f, "DATE" to 1.2f, "BUY" to 1f, "SELL" to 1f, "NET" to 1f))
            for (f in flows) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    MText(f.category, Term.Amber, 10, modifier = Modifier.weight(1.4f), maxLines = 1)
                    MText(f.date, Term.Dim, 10, modifier = Modifier.weight(1.2f), align = TextAlign.End, maxLines = 1)
                    MText(Fmt.num(f.buy, 0), Term.Text, 10, modifier = Modifier.weight(1f), align = TextAlign.End, maxLines = 1)
                    MText(Fmt.num(f.sell, 0), Term.Text, 10, modifier = Modifier.weight(1f), align = TextAlign.End, maxLines = 1)
                    MText(Fmt.signed(f.net, 0), chgColor(f.net), 10, bold = true, modifier = Modifier.weight(1f), align = TextAlign.End, maxLines = 1)
                }
            }
            MText("Sustained FII selling is one of the strongest headwinds for the Nifty; DII buying often cushions it.", Term.Dim, 9)
        }
    }
}

@Composable
fun Disclaimer() {
    MText(
        "Free public data (Yahoo Finance, NSE, MCX, gold-api.com, news RSS) — can be delayed or briefly unavailable. " +
            "Signals are rule-based heuristics, not investment advice.",
        Term.Dim, 9,
        modifier = Modifier.padding(10.dp),
    )
}

fun corrColor(v: Double?): Color {
    if (v == null || v.isNaN()) return Term.Dim
    return when {
        v >= 0.4 -> Term.Up
        v <= -0.4 -> Term.Down
        abs(v) >= 0.2 -> Term.Yellow
        else -> Term.Text
    }
}
