package com.joshuamandel.excalibur.ui.screens

import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.joshuamandel.excalibur.data.Book
import com.joshuamandel.excalibur.ui.ViewerState
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    book: Book?,
    viewer: ViewerState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val title = book?.title ?: "Preview"
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        book?.author?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                book == null -> LoadingPreview("Loading book...")
                !book.isReady -> ErrorPreview("This book is not ready to preview.", onRetry)
                viewer is ViewerState.Ready && viewer.bookId == book.id -> ReaderContent(book, File(viewer.entryPath))
                viewer is ViewerState.Error && viewer.bookId == book.id -> ErrorPreview(viewer.message, onRetry)
                viewer is ViewerState.Preparing && viewer.bookId == book.id -> LoadingPreview(viewer.message)
                else -> LoadingPreview("Preparing preview...")
            }
        }
    }
}

@Composable
private fun ReaderContent(book: Book, entry: File) {
    var webView by remember(entry.absolutePath) { mutableStateOf<WebView?>(null) }
    val cs = MaterialTheme.colorScheme
    val themeCss = remember(
        cs.background,
        cs.surface,
        cs.onBackground,
        cs.onSurfaceVariant,
        cs.primary,
        cs.outline,
    ) { viewerThemeCss(cs) }
    val backgroundColor = cs.background.toArgb()
    Column(Modifier.fillMaxSize()) {
        ReaderMetadata(book)
        Box(Modifier.weight(1f)) {
            ReaderWebView(
                entry = entry,
                themeCss = themeCss,
                backgroundColor = backgroundColor,
                onReady = { webView = it },
            )
        }
        ReaderControls(
            canPage = webView != null,
            onPrevious = { webView?.jumpPreviewPage(-1) },
            onNext = { webView?.jumpPreviewPage(1) },
        )
    }
}

@Composable
private fun ReaderMetadata(book: Book) {
    val details = buildList {
        add(book.originalName)
        if (book.tags.isNotBlank()) add("Tags: ${book.tagSet.joinToString(", ")}")
        if (book.azw3Size > 0) add("${book.azw3Size / 1024} KB")
    }.joinToString(" - ")
    if (details.isNotBlank()) {
        Text(
            details,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ReaderWebView(
    entry: File,
    themeCss: String,
    backgroundColor: Int,
    onReady: (WebView) -> Unit,
) {
    val url = remember(entry.absolutePath) { entry.toURI().toString() }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            writeThemeCss(entry, themeCss)
            WebView(context).apply {
                webViewClient = WebViewClient()
                setBackgroundColor(backgroundColor)
                setTag(themeCss.hashCode())
                overScrollMode = View.OVER_SCROLL_NEVER
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
                settings.javaScriptEnabled = false
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = false
                settings.useWideViewPort = false
                loadUrl(url)
                onReady(this)
            }
        },
        update = { view ->
            onReady(view)
            val themeHash = themeCss.hashCode()
            if (view.getTag() != themeHash) {
                writeThemeCss(entry, themeCss)
                view.setTag(themeHash)
                view.setBackgroundColor(backgroundColor)
                if (view.url == url) view.reload()
            }
            if (view.url != url) {
                writeThemeCss(entry, themeCss)
                view.loadUrl(url)
            }
        },
    )
}

private fun WebView.jumpPreviewPage(direction: Int) {
    val stepX = width.takeIf { it > 0 } ?: return
    if (canScrollHorizontally(direction) || scrollX != 0) {
        scrollTo((scrollX + direction * stepX).coerceAtLeast(0), 0)
        return
    }
    val stepY = height.takeIf { it > 0 } ?: return
    scrollTo(0, (scrollY + direction * stepY).coerceAtLeast(0))
}

private fun writeThemeCss(entry: File, css: String) {
    val dir = entry.parentFile ?: return
    runCatching { File(dir, "excalibur-theme.css").writeText(css) }
}

private fun viewerThemeCss(cs: ColorScheme): String {
    val scheme = if (cs.background.luminance() < 0.5f) "dark" else "light"
    return """
        :root {
          color-scheme: $scheme;
          --excalibur-background: ${cs.background.css()};
          --excalibur-surface: ${cs.surface.css()};
          --excalibur-on-background: ${cs.onBackground.css()};
          --excalibur-on-surface-variant: ${cs.onSurfaceVariant.css()};
          --excalibur-primary: ${cs.primary.css()};
          --excalibur-outline: ${cs.outline.css()};
        }
        html, body {
          background: var(--excalibur-background) !important;
          color: var(--excalibur-on-background) !important;
        }
        ::selection {
          background: var(--excalibur-primary);
          color: var(--excalibur-background);
        }
    """.trimIndent()
}

private fun Color.css(): String {
    val argb = toArgb()
    return String.format(
        Locale.US,
        "#%02X%02X%02X",
        (argb shr 16) and 0xff,
        (argb shr 8) and 0xff,
        argb and 0xff,
    )
}

@Composable
private fun ReaderControls(
    canPage: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onPrevious, enabled = canPage, modifier = Modifier.weight(1f)) {
            Text("Previous")
        }
        Button(onClick = onNext, enabled = canPage, modifier = Modifier.weight(1f)) {
            Text("Next")
        }
    }
}

@Composable
private fun LoadingPreview(message: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun ErrorPreview(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Couldn't open preview", style = MaterialTheme.typography.headlineSmall)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        Button(onClick = onRetry) { Text("Try again") }
    }
}
