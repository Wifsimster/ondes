package ovh.battistella.ondes.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * A `track` slot for [androidx.compose.material3.Slider] that draws the played
 * portion as a rippling wave — the "ondes" of the app name, in the player.
 *
 * The wave only moves while [playing]; on pause its amplitude eases down to a
 * flat line (the same cue Android's own media controls use), so the motion
 * doubles as a playback-state indicator rather than being pure decoration. The
 * unplayed portion stays flat, with Material 3 Expressive's thumb gap and end
 * stop. Material3 ships wavy *progress indicators* but no wavy slider, hence
 * the hand-drawn track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WavySliderTrack(
    sliderState: SliderState,
    playing: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SliderColors = SliderDefaults.colors(),
    strokeWidth: Dp = 4.dp,
    waveAmplitude: Dp = 3.dp,
    wavelength: Dp = 22.dp,
    thumbGap: Dp = 6.dp,
) {
    val amplitudeFraction by animateFloatAsState(
        targetValue = if (playing && enabled) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "wave-amplitude",
    )
    // Scroll the wave to the left while playing. The phase state is only read
    // during draw, so it invalidates the canvas without recomposing the slider.
    val phase by rememberInfiniteTransition(label = "wave-phase").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave-phase",
    )
    val activeColor = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor
    val inactiveColor = if (enabled) colors.inactiveTrackColor else colors.disabledInactiveTrackColor

    Canvas(
        modifier
            .fillMaxWidth()
            // Tall enough for the wave crests plus the stroke; the thumb is
            // centred on the same axis by the slider.
            .height(waveAmplitude * 2 + strokeWidth + 8.dp),
    ) {
        val stroke = strokeWidth.toPx()
        val amplitude = waveAmplitude.toPx() * amplitudeFraction
        val gap = thumbGap.toPx()
        val centerY = size.height / 2
        val thumbX = sliderState.coercedValueAsFraction * size.width
        val activeEnd = (thumbX - gap).coerceAtLeast(0f)
        val inactiveStart = (thumbX + gap).coerceAtMost(size.width)

        // Played portion: a sine wave anchored at the left edge. A straight line
        // when the amplitude has eased to zero (paused).
        if (activeEnd > 0f) {
            if (amplitude < 0.5f) {
                drawLine(
                    color = activeColor,
                    start = Offset(0f, centerY),
                    end = Offset(activeEnd, centerY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            } else {
                val k = (2 * PI / wavelength.toPx()).toFloat()
                val path = Path().apply {
                    moveTo(0f, centerY + amplitude * sin(-phase))
                    var x = 2f
                    while (x < activeEnd) {
                        lineTo(x, centerY + amplitude * sin(k * x - phase))
                        x += 2f
                    }
                    lineTo(activeEnd, centerY + amplitude * sin(k * activeEnd - phase))
                }
                drawPath(
                    path = path,
                    color = activeColor,
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

        // Unplayed portion: flat, with the Expressive end-stop dot.
        if (inactiveStart < size.width) {
            drawLine(
                color = inactiveColor,
                start = Offset(inactiveStart, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = activeColor,
                radius = stroke / 2,
                center = Offset(size.width - stroke / 2, centerY),
            )
        }
    }
}
