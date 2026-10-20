package app.unbraid.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.unbraid.R

/** Design tokens; change here, never inline. */
object Ink {
    val ground = Color(0xFF0F1214)
    val surface = Color(0xFF171B1E)
    val surfaceHigh = Color(0xFF1E2428)
    val surfaceTop = Color(0xFF232A2E)
    val bar = Color(0xFF13171A)
    val line = Color(0xFF343C42)
    val text = Color(0xFFECEFF1)
    val dim = Color(0xFF9AA5AD)
    val faint = Color(0xFF7D8890)
    val accent = Color(0xFFFFA66B)
    val onAccent = Color(0xFF2A1A0F)
    val accentContainer = Color(0xFF3A2A1F)
    val good = Color(0xFF8EE0A8)
    val bad = Color(0xFFFF8A80)
    val direct = Color(0xFF7D8890)
    /** The home network row: a green-tinted surface, set apart from internet exits. */
    val homeSurface = Color(0xFF13201A)

    /** One color per route, equal lightness so none shouts. */
    val routes = listOf(
        Color(0xFFFFA66B), Color(0xFF7CC4FF), Color(0xFFC9A7FF), Color(0xFF8EE0A8),
        Color(0xFFFFD36B), Color(0xFFFF9EBB), Color(0xFF6BE3DC),
    )

    fun route(hue: Int) = routes[hue.mod(routes.size)]
}

val Display = FontFamily(Font(R.font.bigshoulders_black, FontWeight.Black))
val Sans = FontFamily(Font(R.font.onest_regular), Font(R.font.onest_medium, FontWeight.Medium), Font(R.font.onest_semibold, FontWeight.SemiBold))
val Mono = FontFamily(Font(R.font.martian_regular), Font(R.font.martian_medium, FontWeight.Medium))

object Type {
    val headline = TextStyle(fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 44.sp, lineHeight = 44.sp)
    val overline = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp)
    val code = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
    val small = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp)
    val title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
    val sub = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp)
    val caption = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UnbraidTheme(content: @Composable () -> Unit) = MaterialExpressiveTheme(
    colorScheme = darkColorScheme(
        primary = Ink.accent, onPrimary = Ink.onAccent,
        primaryContainer = Ink.accentContainer, onPrimaryContainer = Ink.text,
        secondary = Ink.route(1), tertiary = Ink.route(2),
        background = Ink.ground, onBackground = Ink.text,
        surface = Ink.ground, onSurface = Ink.text,
        surfaceVariant = Ink.surfaceHigh, onSurfaceVariant = Ink.dim,
        surfaceContainerLowest = Ink.ground, surfaceContainerLow = Ink.surface,
        surfaceContainer = Ink.surface, surfaceContainerHigh = Ink.surfaceHigh, surfaceContainerHighest = Ink.surfaceTop,
        secondaryContainer = Ink.accentContainer, onSecondaryContainer = Ink.text,
        outline = Ink.line, outlineVariant = Ink.surfaceTop, error = Ink.bad,
    ),
    motionScheme = MotionScheme.expressive(),
    typography = Typography(
        bodyLarge = Type.body, bodyMedium = Type.sub, bodySmall = Type.caption,
        titleMedium = Type.title, labelLarge = Type.title.copy(fontSize = 15.sp), labelMedium = Type.caption.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
        headlineLarge = Type.headline,
    ),
    shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
    content = content,
)
