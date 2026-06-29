package com.joshuamandel.excalibur.ui.screens

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.joshuamandel.excalibur.data.Book
import com.joshuamandel.excalibur.ui.ViewerState
import java.io.File

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
    Column(Modifier.fillMaxSize()) {
        ReaderMetadata(book)
        Box(Modifier.weight(1f)) {
            ReaderWebView(entry = entry, onReady = { webView = it })
        }
        ReaderControls(
            canPage = webView != null,
            onPrevious = { webView?.pageUp(false) },
            onNext = { webView?.pageDown(false) },
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
private fun ReaderWebView(entry: File, onReady: (WebView) -> Unit) {
    val url = remember(entry.absolutePath) { entry.toURI().toString() }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = false
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                loadUrl(url)
                onReady(this)
            }
        },
        update = { view ->
            onReady(view)
            if (view.url != url) view.loadUrl(url)
        },
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
