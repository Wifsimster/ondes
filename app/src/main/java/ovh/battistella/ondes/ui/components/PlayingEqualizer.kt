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

private val BAR_HEIGHTS = floatArrayOf(24f, 40f, 56f, 40f, 24f)

private const val BAR_PITCH = 13f
