package app.unbraid.ui

import app.unbraid.APP

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/**
 * First start: the idea, the four places, then the terms. [fresh] false = someone updating
 * from before the terms: only the terms page, as "One thing first". Close skips to the terms, which can't be skipped.
 */
@Composable
fun Welcome(fresh: Boolean, accept: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(if (fresh) 0 else 2) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    BackHandler(fresh && page > 0) { page-- }
    val motion = MaterialTheme.motionScheme
    Column(Modifier.fillMaxSize().background(Ink.ground).windowInsetsPadding(WindowInsets.systemBars)) {
        Box(Modifier.height(56.dp).padding(start = 4.dp, top = 4.dp), Alignment.CenterStart) {
            if (fresh && page < 2) IconTap(Icons.close, "Skip the introduction", { page = 2 })
        }
        AnimatedContent(page, Modifier.weight(1f), transitionSpec = { fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) }, label = "welcome") { p ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Text(listOf("Welcome", "Four places", if (fresh) "Terms" else "Updated")[p].uppercase(), style = Type.overline, color = Ink.dim)
                Gap(6.dp)
                Text(
                    buildAnnotatedString {
                        val (t, end) = listOf("Every app, its own exit" to ".", "Find your way" to ".", (if (fresh) "Before you start" else "One thing first") to ".")[p]
                        append(t.uppercase()); withStyle(SpanStyle(color = Ink.accent)) { append(end) }
                    },
                    style = Type.headline.copy(fontSize = 52.sp, lineHeight = 52.sp), color = Ink.text,
                )
                Gap(20.dp)
                when (p) {
                    0 -> {
                        Lead("Your bank direct, streaming through another country, everything else private. All at once, from one switch.")
                        Gap(40.dp)
                        Braid()
                    }
                    1 -> {
                        Lead("Everything $APP does lives in one of four places.")
                        Gap(32.dp)
                        Places()
                    }
                    else -> Terms(fresh, agreed) { agreed = !agreed }
                }
                Gap(24.dp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fresh) Progress(3, page, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            if (fresh && page > 0) IconTap(Icons.back, "Back", { page-- }, background = Ink.surfaceHigh)
            if (page < 2) NextPill("Next", { page++ }) else NextPill("Accept and continue", accept, enabled = agreed)
        }
    }
}

@Composable
private fun Lead(text: String) = Text(text, style = Type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, lineHeight = 24.sp), color = Ink.dim)

/** Three strands braided together leave the phone, then part for their exits: the name, drawn. Draws in once. */
@Composable
private fun Braid() {
    val reveal = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { reveal.animateTo(1f, spec) }
    val strands = remember { braid() }
    val colors = listOf(Ink.accent, Ink.route(1), Ink.direct)
    BoxWithConstraints(Modifier.fillMaxWidth().height(230.dp).semantics {
        contentDescription = "Three braided strands leave the phone and part into three exits: the Netherlands for everything else, the United States for streaming, Direct for the bank."
    }) {
        val s = maxWidth.value / 342f
        Canvas(Modifier.fillMaxSize()) {
            clipRect(right = size.width * reveal.value.coerceIn(0f, 1f)) {
                scale(size.width / 342f, size.width / 342f, pivot = Offset.Zero) {
                    strands.forEach { (path, i, front) ->
                        drawPath(path, Ink.ground, style = Stroke(13f, cap = if (front) StrokeCap.Butt else StrokeCap.Round))
                        drawPath(path, colors[i], style = Stroke(6f, cap = StrokeCap.Round))
                    }
                    drawCircle(Ink.surfaceTop, 13f, Offset(22f, 115f)); drawCircle(Ink.text, 13f, Offset(22f, 115f), style = Stroke(2f)); drawCircle(Ink.text, 4f, Offset(22f, 115f))
                    listOf(34f, 115f, 196f).forEachIndexed { i, y -> drawCircle(colors[i], 5f, Offset(336f, y)) }
                }
            }
        }
        listOf(Triple(34, "NL", "Everything else"), Triple(115, "US", "Streaming"), Triple(196, null, "Your bank, direct")).forEachIndexed { i, (y, code, label) ->
            Row(
                Modifier.align(Alignment.TopEnd).offset(y = ((y - 34) * s).dp).graphicsLayer { alpha = ((reveal.value - 0.6f) / 0.4f).coerceIn(0f, 1f) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(label, style = Type.caption, color = Ink.text, modifier = Modifier.weight(1f, fill = false))
                RouteMark(code, colors[i], direct = code == null)
            }
        }
    }
}

/** Strand paths in a 342 × 230 box: (path, colour index, a crossing's front piece). */
private fun braid(): List<Triple<Path, Int, Boolean>> {
    fun smooth(a: Float, b: Float, x: Float) = ((x - a) / (b - a)).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    val x0 = 22f; val xb = 150f; val k = (2 * PI / 76).toFloat(); val cy = 115f
    val phase = FloatArray(3) { (it * 2 * PI / 3).toFloat() }
    fun raw(i: Int, x: Float) = 30f * smooth(x0, x0 + 30, x) * sin(k * (x - x0) + phase[i])
    // Each strand ends where it leaves the braid: top, middle, bottom.
    val order = (0..2).sortedBy { raw(it, xb + 12) }
    val target = FloatArray(3).also { t -> order.forEachIndexed { r, s -> t[s] = floatArrayOf(34f, 115f, 196f)[r] } }
    val xs = (0..320).map { x0 + it }
    val ys = Array(3) { i -> FloatArray(xs.size) { j -> val x = xs[j]; cy + (1 - smooth(xb - 10, xb + 34, x)) * raw(i, x) + smooth(xb, xb + 150, x) * (target[i] - cy) } }
    fun path(i: Int, from: Int, to: Int) = Path().apply {
        val a = from.coerceAtLeast(0); val b = to.coerceAtMost(xs.lastIndex)
        moveTo(xs[a], ys[i][a]); for (j in a + 1..b) lineTo(xs[j], ys[i][j])
    }
    val colour = IntArray(3).also { c -> order.forEachIndexed { r, s -> c[s] = r } }
    val out = (0..2).map { Triple(path(it, 0, xs.lastIndex), colour[it], false) }.toMutableList()
    // At every crossing the strand moving towards the third goes over: a real three-strand plait.
    for ((a, b) in listOf(0 to 1, 0 to 2, 1 to 2)) {
        val c = 3 - a - b
        for (j in 0 until xs.lastIndex) {
            val d0 = ys[a][j] - ys[b][j]; val d1 = ys[a][j + 1] - ys[b][j + 1]
            if (d0 == 0f || d0 * d1 < 0) {
                val f = if ((ys[c][j] - ys[a][j]) * (ys[a][j + 1] - ys[a][j]) > 0) a else b
                out += Triple(path(f, j - 10, j + 10), colour[f], true)
            }
        }
    }
    return out
}

/** The four tabs, each with what lives there, above the bar they live in. */
@Composable
private fun Places() {
    val what = listOf("Your exits and the power switch", "Which app goes where", "Data, flows and speed, if you want", "Providers, servers, home, protection")
    Column {
        Row(Modifier.fillMaxWidth().height(260.dp)) {
            Tab.entries.forEachIndexed { i, t ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height((i * 36).dp))
                    Column(Modifier.padding(horizontal = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("0${i + 1}", style = Type.small.copy(fontSize = 11.sp), color = Ink.dim)
                        Text(t.label, style = Type.title.copy(fontSize = 15.sp), color = Ink.text)
                        Text(what[i], style = Type.caption.copy(fontSize = 12.sp, lineHeight = 16.sp), color = Ink.dim)
                    }
                    Gap(8.dp)
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Ink.dim))
                    Box(Modifier.width(2.dp).weight(1f).background(Ink.line))
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(24.dp)).background(Ink.bar).border(1.dp, Ink.line, RoundedCornerShape(24.dp))) {
            Tab.entries.forEach { t ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(Ink.surfaceTop), Alignment.Center) { Icon(t.icon, null, Modifier.size(24.dp), tint = Ink.text) }
                    Text(t.label, style = Type.caption.copy(fontSize = 12.sp), color = Ink.text)
                }
            }
        }
        Gap(16.dp)
        Text("Always at the bottom of the screen.", style = Type.caption, color = Ink.dim, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Mandatory: accept the terms and acknowledge the privacy policy. Kept apart from the Play disclosures, which have their own sheets. */
@Composable
private fun Terms(fresh: Boolean, agreed: Boolean, toggle: () -> Unit) {
    val context = LocalContext.current
    if (!fresh) { Lead("$APP now has Terms of use. Accept them once to go on; your routes and your connection stay as they are."); Gap(24.dp) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(
            Icons.vpnKey to "You bring your own VPN accounts and servers. Their own terms still apply.",
            Icons.block to "$APP has no servers and collects nothing.",
            Icons.file to "Free and open source under the GPL v3, with no warranty.",
        ).forEach { (icon, text) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Ink.surfaceTop), Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = Ink.text) }
                Text(text, style = Type.sub.copy(fontSize = 15.sp, lineHeight = 21.sp), color = Ink.text)
            }
        }
    }
    Gap(20.dp)
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
    Gap(8.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).toggleable(agreed, role = Role.Checkbox, onValueChange = { toggle() }).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).then(if (agreed) Modifier.background(Ink.accent) else Modifier.border(2.dp, Ink.dim, RoundedCornerShape(7.dp))),
            Alignment.Center,
        ) { if (agreed) Icon(Icons.check, null, Modifier.size(20.dp), tint = Ink.onAccent) }
        Text("I accept the Terms of use and have read the Privacy policy", style = Type.sub.copy(fontSize = 15.sp, lineHeight = 22.sp), color = Ink.text)
    }
    Row(Modifier.padding(start = 38.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Terms of use" to TERMS_URL, "Privacy policy" to PRIVACY).forEach { (label, url) ->
            Row(
                Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, Ink.line, RoundedCornerShape(20.dp))
                    .tap({ context.openUrl(url) }, label, Role.Button).padding(start = 14.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) { Text(label, style = Type.sub, color = Ink.text); Icon(Icons.external, null, Modifier.size(16.dp), tint = Ink.dim) }
        }
    }
    Gap(10.dp)
    Text("Needed to use $APP. Both open in your browser.", style = Type.caption, color = Ink.dim, modifier = Modifier.padding(start = 38.dp))
}
