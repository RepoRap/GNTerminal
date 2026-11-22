package com.gnterminal.app.core

/**
 * Keyword heuristics that tag a headline with the market driver it talks about
 * and an expected push on gold (AU) and Nifty (NF): +1 supportive, -1 adverse.
 * This is a quick triage aid, not sentiment analysis — always read the story.
 */
object NewsTagger {

    private val UP_STEMS = listOf(
        "rise", "rising", "rose", "risen", "gain", "jump", "surg", "rall", "climb", "firm", "strong",
        "strength", "soar", "spik", "high", "peak", "advanc", "grow", "grew", "rebound", "recover",
        "accelerat", "boost", "spurt", "rocket", "hot", "up",
    )
    private val DOWN_STEMS = listOf(
        "fall", "fell", "drop", "slip", "slid", "slump", "declin", "weak", "tumbl", "plung", "sink",
        "sank", "low", "lose", "losing", "loss", "crash", "retreat", "dip", "ease", "eased", "eases",
        "easing", "cool", "soft", "shed", "skid", "tank", "plummet", "dive", "sag", "erode", "down", "slow",
    )
    private val SHORT_EXACT = setOf("up", "down", "hot")
    private val WORD = Regex("[A-Za-z&-]+")

    private fun stemHit(word: String, stems: List<String>): Boolean =
        stems.any { st -> if (st in SHORT_EXACT) word == st else word.startsWith(st) }

    /** Subject + direction rule, e.g. "rupee ... falls". */
    private class Dir(
        val tagUp: String,
        val tagDown: String,
        subject: String,
        val goldUp: Int,
        val niftyUp: Int,
        val goldDown: Int,
        val niftyDown: Int,
        val up: List<String> = NewsTagger.UP_STEMS,
        val down: List<String> = NewsTagger.DOWN_STEMS,
        val maxWords: Int = 4,
    ) {
        val re = Regex(subject, RegexOption.IGNORE_CASE)
    }

    private class Kw(val tag: String, pattern: String, val gold: Int, val nifty: Int, val againstMentioned: Boolean = false) {
        val re = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    private val DIRS = listOf(
        Dir("GOLD ▲", "GOLD ▼", "\\b(gold|bullion|yellow metal|xau)\\b", 1, 0, -1, 0),
        Dir("NIFTY ▲", "NIFTY ▼", "\\b(nifty|sensex|dalal street|benchmark indices|indian (stocks|shares|equities|markets?))\\b", 0, 1, 0, -1),
        Dir("USD STRONG", "USD WEAK", "\\b(dollar|dxy|greenback)\\b", -1, -1, 1, 1),
        Dir("INR STRONG", "INR WEAK", "\\b(rupee|inr)\\b", -1, 1, 1, -1),
        Dir("CRUDE UP", "CRUDE DOWN", "\\b(crude|oil prices?|brent|wti)\\b", 0, -1, 0, 1),
        Dir("YIELDS UP", "YIELDS DOWN", "\\b(yields?|treasur(y|ies)|bond yields?)\\b", -1, -1, 1, 1),
        Dir(
            "FII BUYING", "FII SELLING", "\\b(fiis?|fpis?|foreign (portfolio )?investors|foreign funds)\\b", 0, 1, 0, -1,
            up = listOf("buy", "bought", "inflow", "pour", "invest", "purchas", "return", "add"),
            down = listOf("sell", "sold", "outflow", "pull", "dump", "offload", "exit", "withdr", "flee"),
            maxWords = 5,
        ),
        Dir("VIX UP", "VIX DOWN", "\\b(india vix|vix)\\b", 0, -1, 0, 1),
        Dir("INFLATION HOT", "INFLATION COOL", "\\b(inflation|cpi|wpi|retail prices)\\b", 0, -1, 0, 1),
        Dir("GDP STRONG", "GDP WEAK", "\\bgdp\\b", 0, 1, 0, -1),
    )

    private const val FED = "(fed|fomc|powell|federal reserve|us central bank)"
    private const val RBI = "(rbi|mpc|reserve bank)"

    private val KWS = listOf(
        Kw("FED CUT", "\\b$FED\\b[^.]{0,60}\\b(rate cuts?|cuts? (interest )?rates?|dovish|easing)\\b|\\brate cuts?\\b[^.]{0,40}\\b$FED\\b", 1, 1),
        Kw("FED HIKE", "\\b$FED\\b[^.]{0,60}\\b(rate hikes?|hikes? (interest )?rates?|hawkish|tighten\\w*|higher for longer)\\b", -1, -1),
        Kw("RBI CUT", "\\b$RBI\\b[^.]{0,60}\\b(rate cuts?|cuts? (the )?(repo )?rates?|dovish|easing)\\b", 0, 1),
        Kw("RBI HIKE", "\\b$RBI\\b[^.]{0,60}\\b(rate hikes?|hikes? (the )?(repo )?rates?|hawkish|tighten\\w*)\\b", 0, -1),
        Kw("RBI", "\\b(rbi|reserve bank of india|monetary policy committee)\\b", 0, 0),
        Kw("GEOPOLITICS", "\\b(wars?|missiles?|airstrikes?|military strikes?|attacks?|conflict|escalat\\w*|tensions?|sanctions?|geopolitic\\w*|invasion|hostilities)\\b", 1, -1),
        Kw("DE-ESCALATION", "\\b(ceasefire|truce|peace (deal|talks|agreement|accord)|de-?escalat\\w*)\\b", -1, 1),
        Kw("TARIFFS", "\\b(tariffs?|trade war|import curbs?)\\b", 1, -1),
        Kw("TRADE DEAL", "\\btrade (deal|pact|agreement|truce)\\b", 0, 1),
        Kw("SAFE HAVEN", "\\bsafe[- ]havens?\\b", 1, 0),
        Kw("CB GOLD BUYING", "\\bcentral banks?\\b[^.]{0,50}\\b(buy\\w*|purchas\\w*|accumulat\\w*|add\\w*)\\b|\\bgold (reserves|purchases|buying) by central banks?\\b", 1, 0),
        Kw("ETF INFLOWS", "\\betfs?\\b[^.]{0,40}\\binflows?\\b|\\binflows?\\b[^.]{0,30}\\betfs?\\b", 1, 0),
        Kw("ETF OUTFLOWS", "\\betfs?\\b[^.]{0,40}\\boutflows?\\b|\\boutflows?\\b[^.]{0,30}\\betfs?\\b", -1, 0),
        Kw("DUTY HIKE", "\\b(import|customs) duty\\b[^.]{0,40}\\b(hike\\w*|rais\\w*|increas\\w*)\\b|\\b(hike\\w*|rais\\w*|increas\\w*)\\b[^.]{0,25}\\b(import|customs) duty\\b", 1, 0),
        Kw("DUTY CUT", "\\b(import|customs) duty\\b[^.]{0,40}\\b(cut\\w*|reduc\\w*|lower\\w*)\\b|\\b(cut\\w*|reduc\\w*|lower\\w*)\\b[^.]{0,25}\\b(import|customs) duty\\b", -1, 0),
        Kw("RECESSION", "\\brecession\\b", 1, -1),
        Kw("PROFIT BOOKING", "\\bprofit[- ](booking|taking)\\b", 0, 0, againstMentioned = true),
        Kw("US JOBS", "\\b(payrolls?|nonfarm|non-farm|jobs report|jobless claims)\\b", 0, 0),
        Kw("EARNINGS", "\\b(q[1-4] (results|earnings|profit)|quarterly (results|earnings)|earnings)\\b", 0, 0),
        Kw("SEBI F&O", "\\bsebi\\b[^.]{0,60}\\b(f&o|derivatives?|options|expiry|lot size)\\b", 0, 0),
        Kw("OPTIONS DATA", "\\b(open interest|put[- ]call ratio|pcr|max pain|option chain|weekly expiry)\\b", 0, 0),
        Kw("BUDGET/POLICY", "\\b(union budget|budget 20\\d\\d|gst council|fiscal deficit)\\b", 0, 0),
        Kw("CHINA", "\\b(china|chinese|pboc|beijing)\\b", 0, 0),
    )

    private val ABOUT_GOLD = Regex("\\b(gold|bullion|yellow metal|precious metals?|silver|mcx|xau|sovereign gold)\\b", RegexOption.IGNORE_CASE)
    private val ABOUT_NIFTY = Regex("\\b(nifty|sensex|stocks?|shares|equit\\w*|dalal|fiis?|fpis?|banknifty|ipos?|midcaps?|smallcaps?|stock market)\\b", RegexOption.IGNORE_CASE)

    private fun direction(d: Dir, text: String): Int {
        val m = d.re.find(text) ?: return 0
        // A qualifier right before the subject ("weak rupee", "rising dollar").
        val before = WORD.findAll(text.substring(0, m.range.first)).map { it.value.lowercase() }.toList()
        val prev = before.lastOrNull()
        if (prev != null) {
            if (stemHit(prev, d.up)) return 1
            if (stemHit(prev, d.down)) return -1
        }
        val after = WORD.findAll(text.substring(m.range.last + 1)).map { it.value.lowercase() }.take(d.maxWords + 1).toList()
        for (w in after) {
            if (stemHit(w, d.up)) return 1
            if (stemHit(w, d.down)) return -1
        }
        return 0
    }

    fun tag(item: NewsItem): NewsItem {
        val t = item.title
        val tags = LinkedHashSet<String>()
        var g = 0
        var n = 0
        val aboutGold = ABOUT_GOLD.containsMatchIn(t)
        val aboutNifty = ABOUT_NIFTY.containsMatchIn(t)
        for (d in DIRS) {
            when (direction(d, t)) {
                1 -> {
                    tags.add(d.tagUp)
                    g += d.goldUp
                    n += d.niftyUp
                }
                -1 -> {
                    tags.add(d.tagDown)
                    g += d.goldDown
                    n += d.niftyDown
                }
            }
        }
        for (k in KWS) {
            if (!k.re.containsMatchIn(t)) continue
            tags.add(k.tag)
            g += k.gold
            n += k.nifty
            if (k.againstMentioned) {
                if (aboutGold) g -= 1
                if (aboutNifty) n -= 1
            }
        }
        // The RBI tag is redundant once a directional RBI tag fired.
        if (tags.contains("RBI CUT") || tags.contains("RBI HIKE")) tags.remove("RBI")
        return item.copy(
            tags = tags.toList(),
            gold = g.coerceIn(-3, 3),
            nifty = n.coerceIn(-3, 3),
            aboutGold = aboutGold,
            aboutNifty = aboutNifty,
        )
    }

    data class Tilt(val score: Int, val up: Int, val down: Int, val count: Int)

    fun tilt(items: List<NewsItem>, gold: Boolean, sinceMs: Long): Tilt {
        var s = 0
        var up = 0
        var dn = 0
        var c = 0
        for (x in items) {
            if (x.publishedMs in 1 until sinceMs) continue
            val v = if (gold) x.gold else x.nifty
            if (v == 0) continue
            c++
            s += v
            if (v > 0) up++ else dn++
        }
        return Tilt(s, up, dn, c)
    }
}
