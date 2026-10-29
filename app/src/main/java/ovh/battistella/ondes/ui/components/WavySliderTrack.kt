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
            .height(waveAmplitude * 2 + strokeWidth + 8.dp),
    ) {
        val stroke = strokeWidth.toPx()
        val amplitude = waveAmplitude.toPx() * amplitudeFraction
        val gap = thumbGap.toPx()
        val centerY = size.height / 2
        val thumbX = sliderState.coercedValueAsFraction * size.width
        val activeEnd = (thumbX - gap).coerceAtLeast(0f)
        val inactiveStart = (thumbX + gap).coerceAtMost(size.width)

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
