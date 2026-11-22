package com.gnterminal.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gnterminal.app.MainActivity
import com.gnterminal.app.R
import com.gnterminal.app.core.Fmt
import com.gnterminal.app.core.OptionsMath
import com.gnterminal.app.core.SnapshotBuilder
import com.gnterminal.app.core.WidgetSnapshot
import com.gnterminal.app.data.MarketRepository
import com.gnterminal.app.data.Prefs
import com.gnterminal.app.data.SnapshotStore
import com.gnterminal.app.data.Sources
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

class TerminalWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) render(context, appWidgetManager, id)
        RefreshWorker.enqueueNow(context)
    }

    override fun onEnabled(context: Context) {
        RefreshWorker.schedule(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) RefreshWorker.enqueueNow(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.gnterminal.app.action.REFRESH"

        private const val AMBER = 0xFFFFA028.toInt()
        private const val TEXT = 0xFFE6E6E6.toInt()
        private const val DIM = 0xFF8C8C8C.toInt()
        private const val UP = 0xFF1ED67F.toInt()
        private const val DOWN = 0xFFFF5252.toInt()

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TerminalWidgetProvider::class.java))
            for (id in ids) render(context, mgr, id)
        }

        private fun chgColor(v: Double?): Int {
            if (v == null || v.isNaN()) return DIM
            return if (v >= 0) UP else DOWN
        }

        private fun cell(label: String, value: String, chgPct: Double?, valueColor: Int = TEXT): CharSequence {
            val sb = SpannableStringBuilder()
            sb.append(label, ForegroundColorSpan(AMBER), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ")
            sb.append(value, ForegroundColorSpan(valueColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (chgPct != null && !chgPct.isNaN()) {
                sb.append(" ")
                sb.append(Fmt.pct(chgPct), ForegroundColorSpan(chgColor(chgPct)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return sb
        }

        fun render(context: Context, mgr: AppWidgetManager, id: Int) {
            val s: WidgetSnapshot = SnapshotStore.load(context)
            val rv = RemoteViews(context.packageName, R.layout.widget_terminal)

            rv.setTextViewText(R.id.w_time, if (s.updated > 0) Fmt.ist(s.updated, "HH:mm") + " IST" else "tap ⟳")
            rv.setTextViewText(R.id.w_nifty, cell("NIFTY", Fmt.num(s.nifty, 1), s.niftyChg))
            rv.setTextViewText(R.id.w_gold, cell("XAU$", Fmt.num(s.gold, 1), s.goldChg))
            rv.setTextViewText(R.id.w_vix, cell("VIX", Fmt.num(s.vix, 2), s.vixChg))
            rv.setTextViewText(R.id.w_mcx, cell(if (s.mcxLive) "MCX" else "LANDED", Fmt.inr(s.mcx), s.mcxChg))
            val pcr = s.pcr
            val pcrColor = if (pcr == null) DIM else if (pcr >= 1.0) UP else DOWN
            rv.setTextViewText(
                R.id.w_pcr,
                cell("PCR", Fmt.num(s.pcr, 2), null, pcrColor),
            )
            rv.setTextViewText(R.id.w_pain, cell("MAXPAIN", Fmt.num(s.maxPain, 0), null))
            rv.setTextViewText(R.id.w_inr, cell("USDINR", Fmt.num(s.usdinr, 2), s.usdinrChg))
            rv.setTextViewText(R.id.w_bias, cell("OPT", s.bias ?: Fmt.DASH, null, biasColor(s.bias)))

            val news = SpannableStringBuilder()
            if (s.headlineGold != 0) news.append(tagOf("AU", s.headlineGold), ForegroundColorSpan(if (s.headlineGold > 0) UP else DOWN), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.headlineNifty != 0) news.append(tagOf("NF", s.headlineNifty), ForegroundColorSpan(if (s.headlineNifty > 0) UP else DOWN), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            news.append(s.headline ?: "Headlines appear after the first refresh", ForegroundColorSpan(TEXT), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            rv.setTextViewText(R.id.w_news, news)

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val refresh = Intent(context, TerminalWidgetProvider::class.java).setAction(ACTION_REFRESH)
            rv.setOnClickPendingIntent(R.id.w_refresh, PendingIntent.getBroadcast(context, 0, refresh, flags))
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            rv.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 1, open, flags))

            mgr.updateAppWidget(id, rv)
        }

        private fun tagOf(label: String, v: Int): String = label + (if (v > 0) "▲ " else "▼ ")

        private fun biasColor(b: String?): Int {
            if (b == null) return DIM
            return if (b.startsWith("BULL")) UP else if (b.startsWith("BEAR")) DOWN else TEXT
        }
    }
}

/** Background refresh for the widget (WorkManager). */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            refreshSnapshot(applicationContext)
            TerminalWidgetProvider.updateAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            TerminalWidgetProvider.updateAll(applicationContext)
            Result.success()
        }
    }

    private suspend fun refreshSnapshot(ctx: Context) = coroutineScope {
        val repo = MarketRepository(ctx)
        val prefs = Prefs(ctx)
        val quotes = async { runCatching { repo.quotes(Sources.WIDGET) }.getOrDefault(emptyMap()) }
        val spot = async { runCatching { repo.goldSpot().first }.getOrNull() }
        val nse = async { runCatching { repo.nseChain(null, allowWebView = true) }.getOrNull() }
        val mcx = async { runCatching { repo.mcxGold(allowWebView = false) }.getOrNull() }
        val news = async { runCatching { repo.news(maxFeeds = 2) }.getOrDefault(emptyList()) }
        val chain = nse.await()?.chain
        val now = System.currentTimeMillis()
        val snap = SnapshotBuilder.build(
            quotes = quotes.await(),
            goldSpot = spot.await(),
            mcx = mcx.await(),
            opt = chain?.let { OptionsMath.stats(it, now) },
            expiry = chain?.expiry,
            news = news.await(),
            dutyPct = prefs.dutyPct,
            includeGst = prefs.includeGst,
            prev = SnapshotStore.load(ctx),
            now = now,
        )
        SnapshotStore.save(ctx, snap)
    }

    companion object {
        private const val PERIODIC = "gn_widget_periodic"
        private const val ONE_SHOT = "gn_widget_now"

        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun enqueueNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
