package com.gnterminal.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gnterminal.app.core.DriverBoard
import com.gnterminal.app.core.Drivers
import com.gnterminal.app.core.FlowRow
import com.gnterminal.app.core.McxQuote
import com.gnterminal.app.core.NewsItem
import com.gnterminal.app.core.OptionChain
import com.gnterminal.app.core.OptionStats
import com.gnterminal.app.core.OptionsMath
import com.gnterminal.app.core.Quote
import com.gnterminal.app.core.Series
import com.gnterminal.app.core.SnapshotBuilder
import com.gnterminal.app.core.Tech
import com.gnterminal.app.core.TechSummary
import com.gnterminal.app.data.MarketRepository
import com.gnterminal.app.data.Prefs
import com.gnterminal.app.data.SnapshotStore
import com.gnterminal.app.widget.TerminalWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SourceStatus(val ok: Boolean, val message: String, val at: Long)

data class UiState(
    val loading: Boolean = false,
    val quotes: Map<String, Quote> = emptyMap(),
    val daily: Map<String, Series> = emptyMap(),
    val goldSpot: Double? = null,
    val goldSpotAt: String = "",
    val mcx: McxQuote? = null,
    val chain: OptionChain? = null,
    val chainVia: String = "",
    val optStats: OptionStats? = null,
    val flows: List<FlowRow> = emptyList(),
    val news: List<NewsItem> = emptyList(),
    val techGold: TechSummary? = null,
    val techNifty: TechSummary? = null,
    val board: DriverBoard? = null,
    val status: Map<String, SourceStatus> = emptyMap(),
    val lastUpdate: Long = 0L,
    val selectedExpiry: String? = null,
    val dutyPct: Double = 15.0,
    val includeGst: Boolean = false,
    val refreshSec: Int = 60,
)

class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MarketRepository(app)
    private val prefs = Prefs(app)

    private val _state = MutableStateFlow(
        UiState(dutyPct = prefs.dutyPct, includeGst = prefs.includeGst, refreshSec = prefs.refreshSec)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var running: Job? = null
    private var lastNews = 0L
    private var lastDaily = 0L

    fun refresh(force: Boolean = false) {
        if (running?.isActive == true) return
        running = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val now = System.currentTimeMillis()
            val jobs = ArrayList<Job>()

            jobs += launch {
                load("Yahoo quotes") { repo.quotes() }?.let { q -> _state.update { it.copy(quotes = it.quotes + q) } }
            }
            jobs += launch {
                load("Gold spot (gold-api)") { repo.goldSpot() }?.let { (p, at) -> _state.update { it.copy(goldSpot = p, goldSpotAt = at) } }
            }
            jobs += launch {
                val exp = _state.value.selectedExpiry
                load("NSE option chain") { repo.nseChain(exp) }?.let { b ->
                    _state.update { s ->
                        s.copy(chain = b.chain, chainVia = b.via, flows = if (b.flows.isNotEmpty()) b.flows else s.flows)
                    }
                }
            }
            jobs += launch {
                load("MCX gold") { repo.mcxGold() }?.let { m -> _state.update { it.copy(mcx = m) } }
            }
            if (force || now - lastNews > 5 * 60_000L || _state.value.news.isEmpty()) {
                jobs += launch {
                    load("News feeds") { repo.news() }?.let { n ->
                        lastNews = System.currentTimeMillis()
                        _state.update { it.copy(news = n) }
                    }
                }
            }
            if (force || now - lastDaily > 30 * 60_000L || _state.value.daily.isEmpty()) {
                jobs += launch {
                    load("Yahoo daily history") { repo.daily() }?.let { d ->
                        lastDaily = System.currentTimeMillis()
                        _state.update { it.copy(daily = d) }
                    }
                }
            }
            jobs.joinAll()
            recompute()
            _state.update { it.copy(loading = false, lastUpdate = System.currentTimeMillis()) }
            pushWidget()
        }
    }

    fun selectExpiry(expiry: String) {
        _state.update { it.copy(selectedExpiry = expiry) }
        viewModelScope.launch {
            load("NSE option chain") { repo.nseChain(expiry) }?.let { b ->
                _state.update { it.copy(chain = b.chain, chainVia = b.via) }
                recompute()
            }
        }
    }

    fun setDuty(v: Double) {
        val x = Math.round(v.coerceIn(0.0, 40.0) * 10.0) / 10.0
        prefs.dutyPct = x
        _state.update { it.copy(dutyPct = x) }
        pushWidget()
    }

    fun setGst(on: Boolean) {
        prefs.includeGst = on
        _state.update { it.copy(includeGst = on) }
        pushWidget()
    }

    fun setRefresh(sec: Int) {
        prefs.refreshSec = sec
        _state.update { it.copy(refreshSec = sec) }
    }

    private suspend fun <R> load(name: String, block: suspend () -> R): R? {
        return try {
            val v = block()
            setStatus(name, true, "OK")
            v
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus(name, false, e.message ?: e.javaClass.simpleName)
            null
        }
    }

    private fun setStatus(name: String, ok: Boolean, msg: String) {
        _state.update { it.copy(status = it.status + (name to SourceStatus(ok, msg, System.currentTimeMillis()))) }
    }

    private suspend fun recompute() {
        val s = _state.value
        val result = withContext(Dispatchers.Default) {
            val tg = s.daily[Drivers.GOLD]?.let { Tech.summarize(it) }
            val tn = s.daily[Drivers.NIFTY]?.let { Tech.summarize(it) }
            val board = if (s.quotes.isNotEmpty()) Drivers.board(s.quotes, s.daily) else null
            val opt = s.chain?.let { OptionsMath.stats(it, System.currentTimeMillis()) }
            Recomputed(tg, tn, board, opt)
        }
        _state.update {
            it.copy(techGold = result.tg, techNifty = result.tn, board = result.board, optStats = result.opt)
        }
    }

    private class Recomputed(val tg: TechSummary?, val tn: TechSummary?, val board: DriverBoard?, val opt: OptionStats?)

    private fun pushWidget() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = _state.value
                val snap = SnapshotBuilder.build(
                    quotes = s.quotes,
                    goldSpot = s.goldSpot,
                    mcx = s.mcx,
                    opt = s.optStats,
                    expiry = s.chain?.expiry,
                    news = s.news,
                    dutyPct = s.dutyPct,
                    includeGst = s.includeGst,
                    prev = SnapshotStore.load(app),
                    now = System.currentTimeMillis(),
                )
                SnapshotStore.save(app, snap)
                TerminalWidgetProvider.updateAll(app)
            } catch (e: Exception) {
                // widget refresh is best-effort
            }
        }
    }
}
