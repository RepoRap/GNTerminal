package com.gnterminal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gnterminal.app.core.Drivers
import com.gnterminal.app.core.Fmt
import com.gnterminal.app.core.GoldMath
import com.gnterminal.app.core.NewsItem
import com.gnterminal.app.core.NewsTagger
import com.gnterminal.app.core.OptionRow
import com.gnterminal.app.core.OptionsMath

@Composable
fun TerminalRoot(vm: TerminalViewModel) {
    val s by vm.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Term.Bg).systemBarsPadding()) {
        TopBar(s, onRefresh = { vm.refresh(force = true) })
        TabBar(tab) { tab = it }
        TickerStrip(s)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> MarketScreen(s)
                1 -> GoldScreen(s)
                2 -> OptionsScreen(s, onExpiry = { vm.selectExpiry(it) })
                3 -> DriversScreen(s)
                4 -> NewsScreen(s)
                else -> SettingsScreen(s, vm)
            }
        }
    }
}

// ------------------------------------------------------------------ F1 MKT

@Composable
fun MarketScreen(s: UiState) {
    val now = System.currentTimeMillis()
    val top = s.news.filter { it.gold != 0 || it.nifty != 0 }.take(6)
    LazyColumn(Modifier.fillMaxSize()) {
        item { WatchPanel(s) }
        item { GoldSummaryPanel(s) }
        item { OptionsSummaryPanel(s) }
        item { PressurePanel(s) }
        item {
            Panel("MARKET-MOVING HEADLINES", right = "${s.news.size} stories") {
                if (top.isEmpty()) MText("Loading news…", Term.Dim, 11)
                for (n in top) NewsRow(n, now)
            }
        }
        item { Disclaimer() }
    }
}

// ------------------------------------------------------------------ F2 GOLD

@Composable
fun GoldScreen(s: UiState) {
    val now = System.currentTimeMillis()
    val g = s.quotes[Drivers.GOLD]
    val goldNews = s.news.filter { it.aboutGold || it.gold != 0 }.take(30)
    LazyColumn(Modifier.fillMaxSize()) {
        item { GoldSummaryPanel(s) }
        item {
            Panel("XAU INTRADAY · COMEX 5-min", right = g?.let { Fmt.pct(it.changePct) }, rightColor = chgColor(g?.changePct)) {
                if (g != null && g.spark.size > 1) {
                    Sparkline(g.spark, Modifier.fillMaxWidth().height(120.dp), chgColor(g.change), g.prevClose)
                    MText(
                        "H " + Fmt.num(g.dayHigh, 1) + "   L " + Fmt.num(g.dayLow, 1) + "   prev " + Fmt.num(g.prevClose, 1),
                        Term.Dim, 10,
                    )
                } else {
                    MText("No intraday data yet.", Term.Dim, 11)
                }
            }
        }
        item { DailyChartPanel("XAU DAILY · 1 YEAR", s.daily[Drivers.GOLD]) }
        item { PriceBuildPanel(s) }
        item { TechPanel("GOLD TECHNICALS · daily", s.techGold) }
        item { DriverImpactPanel(s, gold = true) }
        item {
            Panel("GOLD NEWS", right = "${goldNews.size}") {
                if (goldNews.isEmpty()) MText("No gold stories yet.", Term.Dim, 11)
                for (n in goldNews) NewsRow(n, now)
            }
        }
        item { Disclaimer() }
    }
}

/** Step-by-step: international spot → India landed price → MCX premium. */
@Composable
fun PriceBuildPanel(s: UiState) {
    val g = s.quotes[Drivers.GOLD]
    val fx = s.quotes["INR=X"]?.price
    val spot = s.goldSpot ?: g?.price
    Panel("INDIA PRICE BUILD-UP", right = "₹ per 10 g") {
        if (spot == null || fx == null) {
            MText("Needs gold spot and USD/INR.", Term.Dim, 11)
        } else {
            val perOz = spot * fx
            val per10 = GoldMath.inrPer10g(spot, fx)
            val duty = per10 * s.dutyPct / 100.0
            val landed = per10 + duty
            val gst = landed * GoldMath.GST_PCT / 100.0
            KV("Spot $/oz", Fmt.num(spot, 2))
            KV("× USD/INR", Fmt.num(fx, 3))
            KV("= ₹ per troy oz", Fmt.inr(perOz))
            KV("÷ 31.1035 g × 10", Fmt.inr(per10))
            KV("+ import duty ${Fmt.num(s.dutyPct, 1)}%", Fmt.inrSigned(duty), Term.Down)
            KV("= LANDED (ex-GST)", Fmt.inr(landed), Term.Yellow)
            KV("+ GST 3%", Fmt.inrSigned(gst), Term.Down)
            KV("= RETAIL REFERENCE", Fmt.inr(landed + gst), Term.Yellow)
            val m = s.mcx
            if (m != null) {
                val prem = m.ltp - landed
                KV("MCX ${m.symbol} ${m.expiry}", Fmt.inr(m.ltp), chgColor(m.change))
                KV(
                    if (prem >= 0) "MCX PREMIUM to landed" else "MCX DISCOUNT to landed",
                    Fmt.inrSigned(prem), chgColor(prem), note = Fmt.pct(prem / landed * 100),
                )
            }
            MText(
                "Duty is levied on the CBIC tariff value, and MCX futures add cost of carry, so live prices differ a little from this estimate. " +
                    "Default duty is 15% (10% BCD + 5% AIDC since 13 May 2026); change it in F6 SET.",
                Term.Dim, 9,
            )
        }
    }
}

// ------------------------------------------------------------------ F3 OPTIONS

@Composable
fun OptionsScreen(s: UiState, onExpiry: (String) -> Unit) {
    val chain = s.chain
    val st = s.optStats
    val window = if (chain != null) OptionsMath.window(chain, st?.atm, 12) else emptyList()
    val maxOi = (window.maxOfOrNull { maxOf(it.ce?.oi ?: 0.0, it.pe?.oi ?: 0.0) } ?: 0.0).takeIf { it > 0 } ?: 1.0
    LazyColumn(Modifier.fillMaxSize()) {
        item { OptionsSummaryPanel(s) }
        if (chain != null) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp)) {
                    for (e in chain.expiries.take(10)) Chip(e, e == chain.expiry) { onExpiry(e) }
                }
            }
            item { ChainHeader() }
            items(window) { r -> ChainRow(r, st?.atm, chain.spot, maxOi) }
            item {
                MText(
                    "OI bars are scaled to the largest strike shown. Shaded = in-the-money. Highlighted = ATM.",
                    Term.Dim, 9, modifier = Modifier.padding(8.dp),
                )
            }
        }
        item { DailyChartPanel("NIFTY 50 DAILY · 1 YEAR", s.daily[Drivers.NIFTY]) }
        item { TechPanel("NIFTY TECHNICALS · daily", s.techNifty) }
        item { FlowsPanel(s.flows) }
        item { DriverImpactPanel(s, gold = false) }
        item { Disclaimer() }
    }
}

@Composable
fun ChainHeader() {
    Row(Modifier.fillMaxWidth().background(Term.Header).padding(horizontal = 4.dp, vertical = 3.dp)) {
        Cell("CE OI", 1.2f, Term.Down, bold = true)
        Cell("ΔOI", 1f, Term.Dim)
        Cell("IV", 0.75f, Term.Dim)
        Cell("LTP", 1f, Term.Dim)
        Cell("STRIKE", 1.15f, Term.Amber, bold = true)
        Cell("LTP", 1f, Term.Dim)
        Cell("IV", 0.75f, Term.Dim)
        Cell("ΔOI", 1f, Term.Dim)
        Cell("PE OI", 1.2f, Term.Up, bold = true)
    }
}

@Composable
fun ChainRow(r: OptionRow, atm: Double?, spot: Double, maxOi: Double) {
    val isAtm = atm != null && r.strike == atm
    val ceItm = spot > 0 && r.strike < spot
    val peItm = spot > 0 && r.strike > spot
    val ce = r.ce
    val pe = r.pe
    val ceBg = if (ceItm) Term.Itm else Color.Transparent
    val peBg = if (peItm) Term.Itm else Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(if (isAtm) Term.AtmBg else Term.Bg).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell(Fmt.compact(ce?.oi), 1.2f, bar = ((ce?.oi ?: 0.0) / maxOi).toFloat(), barColor = Term.CeBar, barEnd = true, bg = ceBg)
        Cell(Fmt.compactSigned(ce?.chgOi), 1f, chgColor(ce?.chgOi), bg = ceBg)
        Cell(Fmt.num(ce?.iv, 1), 0.75f, Term.Dim, bg = ceBg)
        Cell(Fmt.num(ce?.ltp, 1), 1f, Term.Text, bg = ceBg)
        Cell(
            Fmt.num(r.strike, 0), 1.15f,
            if (isAtm) Term.Bg else Term.Amber,
            bold = true,
            bg = if (isAtm) Term.Amber else Term.Header,
        )
        Cell(Fmt.num(pe?.ltp, 1), 1f, Term.Text, bg = peBg)
        Cell(Fmt.num(pe?.iv, 1), 0.75f, Term.Dim, bg = peBg)
        Cell(Fmt.compactSigned(pe?.chgOi), 1f, chgColor(pe?.chgOi), bg = peBg)
        Cell(Fmt.compact(pe?.oi), 1.2f, bar = ((pe?.oi ?: 0.0) / maxOi).toFloat(), barColor = Term.PeBar, barEnd = false, bg = peBg)
    }
}

// ------------------------------------------------------------------ F4 DRIVERS

@Composable
fun DriversScreen(s: UiState) {
    val b = s.board
    LazyColumn(Modifier.fillMaxSize()) {
        item { PressurePanel(s) }
        item {
            Panel("CORRELATION MAP", right = "60 sessions · daily returns") {
                if (b == null) {
                    MText("Waiting for history…", Term.Dim, 11)
                } else {
                    HeaderRow(listOf("DRIVER" to 1.8f, "LAST" to 1.2f, "%CHG" to 0.9f, "ρ GOLD" to 0.9f, "ρ NIFTY" to 0.9f))
                    for (r in b.readings) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            MText(r.def.label, Term.Amber, 10, modifier = Modifier.weight(1.8f), maxLines = 1)
                            MText(Fmt.num(r.price, 2), Term.Text, 10, modifier = Modifier.weight(1.2f), align = TextAlign.End, maxLines = 1)
                            MText(Fmt.pct(r.changePct), chgColor(r.changePct), 10, modifier = Modifier.weight(0.9f), align = TextAlign.End, maxLines = 1)
                            MText(Fmt.num(r.corrGold, 2), corrColor(r.corrGold), 10, bold = true, modifier = Modifier.weight(0.9f), align = TextAlign.End, maxLines = 1)
                            MText(Fmt.num(r.corrNifty, 2), corrColor(r.corrNifty), 10, bold = true, modifier = Modifier.weight(0.9f), align = TextAlign.End, maxLines = 1)
                        }
                    }
                    Divider()
                    KV("GOLD ↔ NIFTY ρ", Fmt.num(b.goldNiftyCorr, 2), corrColor(b.goldNiftyCorr))
                    MText(
                        "ρ above +0.4 (green) = tend to move together; below −0.4 (red) = tend to move opposite. Correlations drift — check them, don't assume them.",
                        Term.Dim, 9,
                    )
                }
            }
        }
        item { DriverImpactPanel(s, gold = true) }
        item { DriverImpactPanel(s, gold = false) }
        item {
            Panel("PLAYBOOK · textbook links") {
                for (d in Drivers.LIST) {
                    MText(d.label, Term.Amber, 10, bold = true, modifier = Modifier.padding(top = 4.dp))
                    if (d.goldWhy.isNotEmpty()) MText("AU " + arrow(d.goldTheory) + "  " + d.goldWhy, Term.Text, 10)
                    if (d.niftyWhy.isNotEmpty()) MText("NF " + arrow(d.niftyTheory) + "  " + d.niftyWhy, Term.Text, 10)
                }
                MText(
                    "Also watch: Fed and RBI decisions, US CPI and payrolls, India CPI, FII flows, India import-duty changes, central-bank gold buying.",
                    Term.Dim, 9, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item { Disclaimer() }
    }
}

private fun arrow(theory: Int): String = when {
    theory > 0 -> "moves WITH  ▲▲"
    theory < 0 -> "moves AGAINST ▲▼"
    else -> "mixed"
}

// ------------------------------------------------------------------ F5 NEWS

@Composable
fun NewsScreen(s: UiState) {
    var filter by remember { mutableStateOf("ALL") }
    val now = System.currentTimeMillis()
    val list: List<NewsItem> = when (filter) {
        "GOLD" -> s.news.filter { it.aboutGold || it.gold != 0 }
        "NIFTY" -> s.news.filter { it.aboutNifty || it.nifty != 0 }
        "DRIVERS" -> s.news.filter { it.tags.isNotEmpty() }
        else -> s.news
    }
    val since = now - 24 * 3_600_000L
    val gt = NewsTagger.tilt(s.news, true, since)
    val nt = NewsTagger.tilt(s.news, false, since)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp)) {
                for (f in listOf("ALL", "GOLD", "NIFTY", "DRIVERS")) Chip(f, f == filter) { filter = f }
            }
        }
        item {
            Panel("NEWS TILT · last 24h", right = "keyword heuristic") {
                KV("GOLD", "▲${gt.up}  ▼${gt.down}", chgColor(gt.score.toDouble()), note = "net " + Fmt.signed(gt.score.toDouble(), 0))
                KV("NIFTY", "▲${nt.up}  ▼${nt.down}", chgColor(nt.score.toDouble()), note = "net " + Fmt.signed(nt.score.toDouble(), 0))
                MText("Tap a headline to open the story. AU = gold, NF = Nifty; arrows show the likely push.", Term.Dim, 9)
            }
        }
        if (list.isEmpty()) {
            item { MText(s.status["News feeds"]?.message ?: "Loading news…", Term.Dim, 11, modifier = Modifier.padding(10.dp)) }
        }
        items(list) { n ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                NewsRow(n, now)
                Divider()
            }
        }
        item { Disclaimer() }
    }
}

// ------------------------------------------------------------------ F6 SET

@Composable
fun SettingsScreen(s: UiState, vm: TerminalViewModel) {
    val now = System.currentTimeMillis()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Panel("INDIA GOLD IMPORT DUTY", right = Fmt.num(s.dutyPct, 1) + "%", rightColor = Term.Yellow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip("− 0.5", false) { vm.setDuty(s.dutyPct - 0.5) }
                    Chip("+ 0.5", false) { vm.setDuty(s.dutyPct + 0.5) }
                    Chip("RESET 15%", false) { vm.setDuty(GoldMath.DEFAULT_DUTY_PCT) }
                }
                MText(
                    "Since 13 May 2026: 10% basic customs duty + 5% AIDC = 15%. Update this if the government revises it.",
                    Term.Dim, 10,
                )
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MText("Add 3% GST to landed price", Term.Text, 11, modifier = Modifier.weight(1f))
                    Chip("OFF", !s.includeGst) { vm.setGst(false) }
                    Chip("ON", s.includeGst) { vm.setGst(true) }
                }
            }
        }
        item {
            Panel("AUTO-REFRESH (app open)", right = "${s.refreshSec}s") {
                Row {
                    for (sec in listOf(30, 60, 120, 300)) Chip("${sec}s", s.refreshSec == sec) { vm.setRefresh(sec) }
                }
                MText(
                    "The home-screen widget refreshes about every 15 minutes (Android's minimum) — tap ⟳ on the widget to refresh now. " +
                        "Please don't hammer NSE: 60s is plenty.",
                    Term.Dim, 10,
                )
            }
        }
        item {
            Panel("DATA SOURCES") {
                if (s.status.isEmpty()) MText("No refresh yet.", Term.Dim, 11)
                for (name in s.status.keys.sorted()) {
                    val st = s.status.getValue(name)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        MText(if (st.ok) "●" else "●", if (st.ok) Term.Up else Term.Down, 11)
                        MText(" $name", Term.Text, 11, modifier = Modifier.weight(1f), maxLines = 1)
                        MText(Fmt.timeAgo(st.at, now), Term.Dim, 10)
                    }
                    if (!st.ok) MText(st.message, Term.Down, 9, maxLines = 3)
                }
                MText(
                    "NSE and MCX block some non-browser traffic. If plain requests fail, the app retries inside a hidden browser view, which is slower on the first load.",
                    Term.Dim, 9, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            Panel("ABOUT") {
                MText("GN Terminal 1.0 — gold & Nifty options monitor with drivers and news.", Term.Text, 11)
                MText(
                    "Prices: Yahoo Finance (indices, COMEX gold, FX, yields, crude), gold-api.com (spot), NSE (option chain, FII/DII), MCX (gold futures). " +
                        "News: Google News, Economic Times, Mint, Moneycontrol RSS.",
                    Term.Dim, 10,
                )
                MText(
                    "Unofficial free sources: data can be delayed, incomplete or change format. Nothing here is investment advice.",
                    Term.Dim, 10, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
