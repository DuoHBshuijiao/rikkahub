package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import me.rerere.rikkahub.data.model.SearchSnippet

@Composable
fun SearchSnippetText(
    snippet: SearchSnippet,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val background = MaterialTheme.colorScheme.tertiaryContainer
    val foreground = MaterialTheme.colorScheme.onTertiaryContainer
    val text = remember(snippet, background, foreground) {
        buildAnnotatedString {
            append(snippet.text)
            addStyle(
                SpanStyle(background = background, color = foreground),
                snippet.highlight.start,
                snippet.highlight.end
            )
        }
    }
    Text(text = text, modifier = modifier, style = style, maxLines = 3, overflow = TextOverflow.Ellipsis)
}
