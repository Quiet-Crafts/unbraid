package app.unbraid.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// Small Canvas charts for Stats. Route colours encode exits and nothing else;
// every chart carries a spoken summary, and draws in once with a spatial spring (snapped when animations are off).

/** 0 → 1 once, on first composition: the draw-in for a chart. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun drawIn(): Float {
    val a = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) { a.animateTo(1f, spec) }
    return a.value.coerceIn(0f, 1.05f)
}

/** Share by exit as a segmented ring with round ends and gaps. */
@Composable
fun Ring(parts: List<Pair<Float, Color>>, summary: String, size: Dp = 120.dp, stroke: Dp = 14.dp) {
    val p = drawIn()
    Canvas(Modifier.size(size).semantics { contentDescription = summary }) {
        val total = parts.sumOf { it.first.toDouble() }.toFloat().takeIf { it > 0 } ?: return@Canvas
        val w = stroke.toPx()
        val gap = if (parts.count { it.first > 0 } > 1) 14f else 0f
        var start = -90f
        parts.filter { it.first > 0 }.forEach { (v, c) ->
            val sweep = 360f * v / total
            drawArc(c, start + gap / 2, ((sweep - gap) * p).coerceAtLeast(0.1f), false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
            start += sweep
        }
    }
}

/** A thin horizontal bar: [frac] of the track filled in [color]. */
@Composable
fun Meter(frac: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val p = drawIn()
    Canvas(modifier.height(height)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Ink.surfaceTop, cornerRadius = r)
        if (frac > 0) drawRoundRect(color, size = Size((size.width * frac.coerceIn(0f, 1f) * p).coerceAtLeast(size.height), size.height), cornerRadius = r)
    }
}

/** Columns of stacked parts (bottom first) with a 0…[max] scale in a 48 dp gutter; [selected] is drawn at full strength. */
@Composable
fun StackedBars(columns: List<List<Pair<Float, Color>>>, labels: List<String>, max: Float, scale: String, summary: String, selected: Int = -1, height: Dp = 120.dp) {
    val p = drawIn()
    val tm = rememberTextMeasurer()
    Column(Modifier.semantics { contentDescription = summary }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val gutter = 48.dp.toPx()
            val plotW = size.width - gutter
            val slot = plotW / columns.size
            val bw = minOf(24.dp.toPx(), slot * 0.6f)
            drawLine(Ink.line, Offset(0f, size.height), Offset(plotW, size.height), 1.dp.toPx())
            columns.forEachIndexed { i, parts ->
                var y = size.height
                val x = slot * i + (slot - bw) / 2
                val alpha = if (selected < 0 || i == selected) 1f else 0.55f
                val drawn = parts.filter { it.first > 0 }
                drawn.forEachIndexed { j, (v, c) ->
                    val h = size.height * v / max * p
                    val top = j == drawn.lastIndex
                    val path = Path().apply {
                        val r = 6.dp.toPx().coerceAtMost(h / 2)
                        val t = CornerRadius(if (top) r else 0f); val b = CornerRadius(if (j == 0) r else 0f)
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(x, y - h, x + bw, y, topLeftCornerRadius = t, topRightCornerRadius = t, bottomRightCornerRadius = b, bottomLeftCornerRadius = b))
                    }
                    drawPath(path, c.copy(alpha = alpha))
                    y -= h
                }
            }
            label(tm, scale, Offset(size.width, 0f), end = true)
            label(tm, "0", Offset(size.width, size.height - 16.dp.toPx()), end = true)
        }
        Row(Modifier.fillMaxWidth().padding(end = 48.dp, top = 6.dp)) {
            labels.forEachIndexed { i, l ->
                Text(l, style = Type.code.copy(fontSize = 12.sp), color = if (i == selected) Ink.text else Ink.dim, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

private fun DrawScope.label(tm: TextMeasurer, text: String, at: Offset, end: Boolean = false, color: Color = Ink.dim, size: Float = 12f) {
    val l = tm.measure(text, TextStyle(fontFamily = Mono, fontSize = size.sp, color = color))
    drawText(l, topLeft = Offset(if (end) at.x - l.size.width else at.x - l.size.width / 2f, at.y))
}

/** Stacked areas of the last samples per exit (oldest left, now right) with an outline of the total and a 0…[cap] scale. */
@Composable
fun LiveArea(series: List<Pair<FloatArray, Color>>, cap: Float, unit: String, summary: String, height: Dp = 120.dp) {
    val tm = rememberTextMeasurer()
    val reveal = drawIn() // sweeps in from the left once, like every chart; new samples then update in place
    Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = summary }.drawWithContent { if (reveal >= 1f) drawContent() else clipRect(right = size.width * reveal) { this@drawWithContent.drawContent() } }) {
        val gutter = 48.dp.toPx()
        val w = size.width - gutter
        val n = series.firstOrNull()?.first?.size ?: return@Canvas
        val acc = FloatArray(n)
        val x = { i: Int -> w * i / (n - 1) }
        val y = { v: Float -> size.height - size.height * (v / cap).coerceIn(0f, 1f) }
        series.forEach { (s, c) ->
            val path = Path().apply {
                moveTo(x(0), y(acc[0] + s[0]))
                for (i in 1 until n) lineTo(x(i), y(acc[i] + s[i]))
                for (i in n - 1 downTo 0) lineTo(x(i), y(acc[i]))
                close()
            }
            drawPath(path, c.copy(alpha = 0.85f))
            for (i in 0 until n) acc[i] += s[i]
        }
        val outline = Path().apply { moveTo(x(0), y(acc[0])); for (i in 1 until n) lineTo(x(i), y(acc[i])) }
        drawPath(outline, Ink.text, style = Stroke(1.5.dp.toPx()))
        drawCircle(Ink.text, 4.dp.toPx(), Offset(w, y(acc[n - 1])))
        drawLine(Ink.line, Offset(0f, size.height), Offset(w, size.height), 1.dp.toPx())
        // Unit on the top label, as on the bar charts; a bare 0 at the bottom keeps clear of the now-dot.
        label(tm, "${scaleText(cap)} $unit", Offset(size.width, 0f), end = true)
        label(tm, "0", Offset(size.width, size.height - 16.dp.toPx()), end = true)
    }
}

/** A scale value as a label: whole numbers plain, others with one decimal (2.5, 0.1). */
fun scaleText(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)

/** A small line of recent values ending in a dot at "now". */
@Composable
fun Spark(values: FloatArray, color: Color, modifier: Modifier = Modifier) {
    val reveal = drawIn()
    Canvas(modifier.height(36.dp).drawWithContent { if (reveal >= 1f) drawContent() else clipRect(right = size.width * reveal) { this@drawWithContent.drawContent() } }) {
        if (values.isEmpty()) return@Canvas
        val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f) * 1.15f
        val x = { i: Int -> size.width * i / (values.size - 1).coerceAtLeast(1) }
        val y = { v: Float -> size.height - size.height * v / max }
        val line = Path().apply { moveTo(x(0), y(values[0])); for (i in 1 until values.size) lineTo(x(i), y(values[i])) }
        val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(area, color.copy(alpha = 0.10f))
        drawPath(line, color, style = Stroke(2.dp.toPx()))
        drawCircle(color, 3.5.dp.toPx(), Offset(size.width, y(values.last())))
    }
}

/**
 * One cell per day, oldest first, weeks as columns (Monday on top); negative = not yet (not drawn). [selected] is
 * outlined. Weekday letters on the left, month names above the week they start in.
 */
@Composable
fun Heatmap(hours: List<Float>, selected: Int, weekdays: List<String>, months: Map<Int, String>, summary: String, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(176.dp).semantics { contentDescription = summary }) {
        val weeks = (hours.size + 6) / 7
        val gap = 4.dp.toPx(); val left = 20.dp.toPx(); val head = 18.dp.toPx()
        val cell = minOf((size.width - left - gap * (weeks - 1)) / weeks, (size.height - head - gap * 6) / 7)
        listOf(0, 2, 4, 6).forEach { r -> label(tm, weekdays[r], Offset(6.dp.toPx(), head + r * (cell + gap) + (cell - 16.dp.toPx()) / 2)) }
        months.forEach { (w, m) -> label(tm, m, Offset(left + w * (cell + gap) + tm.measure(m, TextStyle(fontFamily = Mono, fontSize = 12.sp)).size.width / 2f, 0f)) }
        hours.forEachIndexed { i, h ->
            if (h < 0) return@forEachIndexed
            val x = left + (i / 7) * (cell + gap); val y = head + (i % 7) * (cell + gap)
            val c = if (h < 0.05f) Ink.line else Ink.good.copy(alpha = 0.22f + 0.78f * (h / 12f).coerceIn(0f, 1f))
            drawRoundRect(c, Offset(x, y), Size(cell, cell), CornerRadius(cell * 0.28f))
            if (i == selected) drawRoundRect(Ink.text, Offset(x, y), Size(cell, cell), CornerRadius(cell * 0.28f), style = Stroke(2.dp.toPx()))
        }
    }
}

/** A day as a 24 h track: protected in green, paused in the accent; fractions of the day. */
@Composable
fun Timeline(spans: List<Triple<Float, Float, Boolean>>, summary: String) {
    val p = drawIn()
    Canvas(Modifier.fillMaxWidth().height(20.dp).semantics { contentDescription = summary }) {
        val r = CornerRadius(6.dp.toPx())
        drawRoundRect(Ink.surfaceTop, cornerRadius = r)
        spans.forEach { (a, b, paused) ->
            drawRoundRect(if (paused) Ink.accent else Ink.good, Offset(size.width * a, 0f), Size((size.width * (b - a) * p).coerceAtLeast(2f), size.height), r)
        }
    }
}

private val TICKS = floatArrayOf(0f, 5f, 10f, 50f, 100f, 250f, 500f, 750f, 1000f)

/** Ookla-style non-linear position (0…1) of [mbps]: ticks evenly spaced, values interpolated between them. */
fun dialFraction(mbps: Float): Float {
    for (i in 0 until TICKS.size - 1) if (mbps <= TICKS[i + 1]) return (i + (mbps - TICKS[i]) / (TICKS[i + 1] - TICKS[i])) / (TICKS.size - 1)
    return 1f
}

/**
 * The speed-test dial: a 270° arc filled in the exit's colour up to the reading, ticks and a needle. [mbps] is read
 * only while drawing, so a value animating every frame redraws the dial without recomposing anything.
 */
@Composable
fun Dial(mbps: () -> Float, color: Color, label: String, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val labels = remember(tm) { TICKS.map { tm.measure(if (it >= 1000f) "1k" else it.toInt().toString(), TextStyle(fontFamily = Mono, fontSize = 13.sp)) } }
    val reveal = drawIn() // the track sweeps in once, like every chart
    Canvas(modifier.size(300.dp).semantics { contentDescription = label }) {
        val m = mbps(); val f = dialFraction(m) * reveal.coerceAtMost(1f)
        val sw = 22.dp.toPx()
        val r = size.minDimension / 2 - sw / 2
        val c = center
        val box = Size(r * 2, r * 2); val tl = Offset(c.x - r, c.y - r)
        drawArc(Ink.surfaceTop, 135f, 270f * reveal.coerceAtMost(1f), false, tl, box, style = Stroke(sw, cap = StrokeCap.Round))
        if (f > 0.002f) drawArc(color, 135f, 270f * f, false, tl, box, style = Stroke(sw, cap = StrokeCap.Round))
        val angle = { frac: Float -> Math.toRadians((135.0 + 270.0 * frac)) }
        TICKS.forEachIndexed { i, t ->
            val a = angle(dialFraction(t)); val rr = r - 40.dp.toPx(); val l = labels[i]
            drawText(l, if (t <= m) Ink.text else Ink.dim, alpha = reveal.coerceIn(0f, 1f), topLeft = Offset(c.x + rr * cos(a).toFloat() - l.size.width / 2f, c.y + rr * sin(a).toFloat() - l.size.height / 2f))
        }
        val a = angle(f.coerceIn(0f, 1f))
        val from = 18.dp.toPx(); val to = r - 62.dp.toPx()
        drawLine(Ink.text, Offset(c.x + from * cos(a).toFloat(), c.y + from * sin(a).toFloat()), Offset(c.x + to * cos(a).toFloat(), c.y + to * sin(a).toFloat()), 8.dp.toPx(), StrokeCap.Round)
        drawCircle(Ink.text, 9.dp.toPx(), c)
    }
}

/** Seven small columns of hours protected (0–12 h), today strongest; for the hub's Journal tile. */
@Composable
fun WeekMarks(hours: List<Float>, labels: List<String>, today: Int) {
    val reveal = drawIn()
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        hours.forEachIndexed { i, h ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.width(12.dp).height(36.dp)) {
                    val r = CornerRadius(size.width / 2)
                    drawRoundRect(Ink.surfaceTop, cornerRadius = r)
                    val v = size.height * (h / 12f).coerceIn(0f, 1f) * reveal.coerceAtMost(1f)
                    if (v > 0) drawRoundRect(if (i == today) Ink.good else Ink.text.copy(alpha = 0.7f), Offset(0f, size.height - v), Size(size.width, v), r)
                }
                Text(labels[i], style = Type.code.copy(fontSize = 12.sp), color = if (i == today) Ink.text else Ink.dim)
            }
        }
    }
}

