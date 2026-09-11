package ovh.battistella.ondes.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.PI
import kotlin.math.sin

/**
 * The five-bar Ondes launcher mark, animated. Marks the episode that is loaded
 * in the player inside a list: the bars pulse while [playing] and settle into
 * the static icon shape when paused, so the row reads as "this is the one in
 * the player" in both states.
 *
 * Bar geometry mirrors `ic_launcher_foreground.xml` (heights 24/40/56/40/24 on
 * a 108 grid, 8-wide rounded caps), scaled to whatever size the caller gives.
 * Purely decorative — callers describe the state in text for TalkBack.
 */
@Composable
fun PlayingEqualizer(
    playing: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val motion by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "equalizer-motion",
    )
    val t by rememberInfiniteTransition(label = "equalizer").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "equalizer-phase",
    )

    Canvas(modifier) {
        val unit = size.minDimension / 108f
        val stroke = 8f * unit
        val centerY = size.height / 2
        val startX = (size.width - 4 * BAR_PITCH * unit) / 2
        BAR_HEIGHTS.forEachIndexed { i, rest ->
            // Each bar bobs on its own phase between ~60% and ~130% of its
            // resting height; at motion == 0 they sit exactly on the icon shape.
            val wobble = 1f + 0.35f * sin(t + i * 1.3f) * motion
            val half = rest * unit * wobble / 2
            val x = startX + i * BAR_PITCH * unit
            drawLine(
                color = color,
                start = Offset(x, centerY - half),
                end = Offset(x, centerY + half),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Resting bar heights from the launcher mark, on its 108-unit grid. */
private val BAR_HEIGHTS = floatArrayOf(24f, 40f, 56f, 40f, 24f)

/** Horizontal distance between bar centres on the same grid. */
private const val BAR_PITCH = 13f
