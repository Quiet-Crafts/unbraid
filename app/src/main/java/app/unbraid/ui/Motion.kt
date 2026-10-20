package app.unbraid.ui

import android.annotation.SuppressLint
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import kotlinx.coroutines.delay

// Unbraid's motion vocabulary. Every spec comes from MaterialTheme.motionScheme: spatial springs for
// geometry, effects springs for colour and opacity. Nothing waits for an animation, and with Android's animations off
// everything snaps.

private val revealed = mutableSetOf<String>()
private val risen = mutableSetOf<String>()

/** True the first time this process shows [screen] (and animations are on): its first rows then play the [Rise] reveal. */
@Composable
fun firstShow(screen: String): Boolean {
    val context = LocalContext.current
    val play = remember {
        screen !in revealed && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    LaunchedEffect(Unit) { revealed += screen }
    return play
}

/** First-load reveal: the first four rows rise 12 dp and fade in, 60 ms apart; each row once, even if scrolled away and back. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Rise(screen: String, index: Int, play: Boolean, content: @Composable () -> Unit) {
    val key = "$screen:$index"
    if (!play || index > 3 || key in risen) return content()
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 60L)
        shown.animateTo(1f, spec)
        risen += key
    }
    Box(Modifier.fillMaxHeight().graphicsLayer { alpha = shown.value.coerceIn(0f, 1f); translationY = (1f - shown.value) * 12.dp.toPx() }) { content() }
}

/** Press feedback for cards: a slight squish that springs back. Read it in graphicsLayer, so only the layer redraws. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun squish(source: MutableInteractionSource): State<Float> {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(if (pressed) 0.97f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "squish")
}

/** A shape on its way between two Material shapes: [progress] 0 is the morph's start, 1 its end. */
class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress).asComposePath()
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

/** The shared-element scope around the screens, and the screen being shown or left, for container transforms. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalShared = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalScreen = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Container transform: the element carrying the same [key] on the screen being left grows into this one (a route row
 * into its location picker), and shrinks back on close or a predictive back gesture.
 */
// Not a Modifier extension: it must read the composition locals, and callers chain it with then().
@SuppressLint("ModifierFactoryExtensionFunction")
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun sharedRoute(key: String, shape: Shape): Modifier {
    val shared = LocalShared.current ?: return Modifier
    val screen = LocalScreen.current ?: return Modifier
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.geometry.Rect>()
    return with(shared) {
        Modifier.sharedBounds(
            rememberSharedContentState("route:$key"), screen,
            boundsTransform = { _, _ -> spec },
            // The page is laid out once at full size and scaled, so text never reflows mid-flight.
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}
