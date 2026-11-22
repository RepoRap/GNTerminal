package com.gnterminal.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gnterminal.app.core.Fmt
import com.gnterminal.app.core.NewsItem
import kotlin.math.abs

/** Terminal palette: amber on black. */
object Term {
    val Bg = Color(0xFF000000)
    val Panel = Color(0xFF080808)
    val Header = Color(0xFF1A1A1A)
    val Border = Color(0xFF2C2C2C)
    val Grid = Color(0xFF1E1E1E)
    val Amber = Color(0xFFFFA028)
    val AmberDim = Color(0xFFB07020)
    val Text = Color(0xFFE6E6E6)
    val Dim = Color(0xFF8C8C8C)
    val Up = Color(0xFF1ED67F)
    val Down = Color(0xFFFF5252)
    val Cyan = Color(0xFF4FC3F7)
    val Yellow = Color(0xFFFFE066)
    val AtmBg = Color(0xFF2A1F05)
    val Itm = Color(0xFF101722)
    val CeBar = Color(0x55FF5252)
    val PeBar = Color(0x551ED67F)
}

fun chgColor(v: Double?): Color {
    if (v == null || v.isNaN()) return Term.Text
    return when {
        v > 0 -> Term.Up
        v < 0 -> Term.Down
        else -> Term.Text
    }
}

fun voteColor(v: Int): Color = when {
    v > 0 -> Term.Up
    v < 0 -> Term.Down
    else -> Term.Dim
}

@Composable
fun MText(
    text: String,
    color: Color = Term.Text,
    size: Int = 12,
    bold: Boolean = false,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = align,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Panel(
    title: String,
    right: String? = null,
    rightColor: Color = Term.Dim,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .border(1.dp, Term.Border)
            .background(Term.Panel)
    ) {
        Row(
            Modifier.fillMaxWidth().background(Term.Header).padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MText(title, Term.Amber, 11, bold = true, modifier = Modifier.weight(1f), maxLines = 1)
            if (right != null) MText(right, rightColor, 10, bold = rightColor != Term.Dim, maxLines = 1)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), content = content)
    }
}

@Composable
fun KV(k: String, v: String, color: Color = Term.Text, note: String? = null, noteColor: Color = Term.Dim) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        MText(k, Term.Dim, 11, modifier = Modifier.weight(1f), maxLines = 1)
        MText(v, color, 12, bold = true, align = TextAlign.End, maxLines = 1)
        if (note != null) MText(" $note", noteColor, 10, maxLines = 1)
    }
}

@Composable
fun HeaderRow(cols: List<Pair<String, Float>>) {
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        cols.forEachIndexed { i, (label, w) ->
            MText(
                label, Term.Dim, 9, modifier = Modifier.weight(w),
                align = if (i == 0) TextAlign.Start else TextAlign.End, maxLines = 1,
            )
        }
    }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Term.Border))
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    MText(
        label,
        if (selected) Term.Bg else Term.Amber,
        11,
        bold = true,
        maxLines = 1,
        modifier = Modifier
            .padding(end = 6.dp, bottom = 4.dp)
            .border(1.dp, Term.Amber)
            .background(if (selected) Term.Amber else Term.Bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun Sparkline(values: List<Double>, modifier: Modifier, color: Color, baseline: Double? = null) {
    Canvas(modifier) {
        val v = values.filter { !it.isNaN() }
        if (v.size < 2) return@Canvas
        var min = v.min()
        var max = v.max()
        if (baseline != null && !baseline.isNaN()) {
            min = minOf(min, baseline)
            max = maxOf(max, baseline)
        }
        val range = if (max - min > 0) max - min else 1.0
        val stepX = size.width / (v.size - 1)
        if (baseline != null && !baseline.isNaN()) {
            val by = (size.height - (baseline - min) / range * size.height).toFloat()
            drawLine(
                color = Term.Dim,
                start = Offset(0f, by),
                end = Offset(size.width, by),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }
        val path = Path()
        v.forEachIndexed { i, y ->
            val px = i * stepX
            val py = (size.height - (y - min) / range * size.height).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(width = 2f))
    }
}

@Composable
fun LineChart(lines: List<Pair<DoubleArray, Color>>, modifier: Modifier) {
    Canvas(modifier) {
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        var n = 0
        for ((arr, _) in lines) {
            n = maxOf(n, arr.size)
            for (x in arr) {
                if (x.isNaN()) continue
                if (x < min) min = x
                if (x > max) max = x
            }
        }
        if (n < 2 || min > max) return@Canvas
        val range = if (max - min > 0) max - min else 1.0
        for (g in 1..3) {
            val y = size.height * g / 4f
            drawLine(color = Term.Grid, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f)
        }
        val step = size.width / (n - 1)
        for ((arr, color) in lines) {
            val path = Path()
            var started = false
            val offset = n - arr.size
            for (i in arr.indices) {
                val x = arr[i]
                if (x.isNaN()) continue
                val px = (i + offset) * step
                val py = (size.height - (x - min) / range * size.height).toFloat()
                if (!started) {
                    path.moveTo(px, py)
                    started = true
                } else {
                    path.lineTo(px, py)
                }
            }
            drawPath(path, color, style = Stroke(width = 2f))
        }
    }
}

/** Horizontal gauge from -3σ to +3σ. */
@Composable
fun Gauge(label: String, value: Double?, caption: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MText(label, Term.Amber, 11, bold = true, modifier = Modifier.weight(1f))
            MText(caption, chgColor(value), 11, bold = true)
            MText("  " + (value?.let { Fmt.signed(it, 2) + "σ" } ?: Fmt.DASH), chgColor(value), 11)
        }
        Canvas(Modifier.fillMaxWidth().height(10.dp).padding(top = 2.dp)) {
            drawRect(color = Term.Header)
            val mid = size.width / 2f
            drawLine(color = Term.Dim, start = Offset(mid, 0f), end = Offset(mid, size.height), strokeWidth = 1f)
            if (value != null && !value.isNaN()) {
                val f = (value / 3.0).coerceIn(-1.0, 1.0).toFloat()
                val x = mid + f * mid
                drawRect(
                    color = if (f >= 0f) Term.Up else Term.Down,
                    topLeft = Offset(minOf(mid, x), 1f),
                    size = Size(abs(x - mid), size.height - 2f),
                )
            }
        }
    }
}

/** One cell of the option-chain table with an optional OI bar behind the number. */
@Composable
fun RowScope.Cell(
    text: String,
    w: Float,
    color: Color = Term.Text,
    bar: Float = 0f,
    barColor: Color = Color.Transparent,
    barEnd: Boolean = false,
    bold: Boolean = false,
    bg: Color = Color.Transparent,
) {
    Box(Modifier.weight(w).height(19.dp).background(bg)) {
        if (bar > 0f) {
            Box(
                Modifier
                    .align(if (barEnd) Alignment.CenterEnd else Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(bar.coerceIn(0f, 1f))
                    .background(barColor)
            )
        }
        MText(
            text, color, 10, bold = bold, align = TextAlign.End, maxLines = 1,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth().padding(horizontal = 2.dp),
        )
    }
}

@Composable
fun ImpactChip(label: String, v: Int) {
    val arrow = if (v > 0) "▲" else "▼"
    MText(
        "$label$arrow${abs(v)}", Term.Bg, 9, bold = true,
        modifier = Modifier
            .padding(start = 4.dp)
            .background(if (v > 0) Term.Up else Term.Down)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
fun NewsRow(n: NewsItem, now: Long) {
    val uri = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { runCatching { uri.openUri(n.link) } }
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MText(Fmt.timeAgo(n.publishedMs, now), Term.Cyan, 10)
            MText("  " + n.source.uppercase(), Term.Dim, 10, modifier = Modifier.weight(1f), maxLines = 1)
            if (n.gold != 0) ImpactChip("AU", n.gold)
            if (n.nifty != 0) ImpactChip("NF", n.nifty)
        }
        MText(n.title, Term.Text, 12, maxLines = 3)
        if (n.tags.isNotEmpty()) MText(n.tags.joinToString(" · "), Term.AmberDim, 9, maxLines = 1)
    }
}
