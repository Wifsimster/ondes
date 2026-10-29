package ovh.battistella.ondes.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow

private val TIMESTAMP_REGEX = Regex("""(?<!\d)(\d{1,2}):([0-5]\d)(?::([0-5]\d))?(?!\d)""")

@Composable
fun HtmlText(
    html: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTimestampClick: ((Long) -> Unit)? = null,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val base = remember(html, linkColor) {
        AnnotatedString.fromHtml(
            htmlString = html,
            linkStyles = TextLinkStyles(
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            ),
        )
    }

    val click by rememberUpdatedState(onTimestampClick)
    val annotated = remember(base, onTimestampClick != null) {
        if (onTimestampClick == null) {
            base
        } else {
            buildAnnotatedString {
                append(base)
                val tsStyles = TextLinkStyles(
                    style = SpanStyle(color = linkColor, fontWeight = FontWeight.Medium),
                )
                TIMESTAMP_REGEX.findAll(base.text).forEach { match ->
                    val ms = parseTimestamp(match.value) ?: return@forEach
                    addLink(
                        LinkAnnotation.Clickable(
                            tag = "timestamp",
                            styles = tsStyles,
                            linkInteractionListener = LinkInteractionListener { click?.invoke(ms) },
                        ),
                        match.range.first,
                        match.range.last + 1,
                    )
                }
            }
        }
    }

    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
    )
}

private fun parseTimestamp(raw: String): Long? {
    val parts = raw.split(":").mapNotNull { it.toLongOrNull() }
    return when (parts.size) {
        3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
        2 -> (parts[0] * 60 + parts[1]) * 1000
        else -> null
    }
}
