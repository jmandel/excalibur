package com.joshuamandel.excalibur.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.joshuamandel.excalibur.convert.ActiveProgress
import com.joshuamandel.excalibur.convert.stageBaseFraction
import com.joshuamandel.excalibur.ui.components.LocalIcons
import com.joshuamandel.excalibur.data.Book
import com.joshuamandel.excalibur.data.BookStatus
import com.joshuamandel.excalibur.data.profileName
import com.joshuamandel.excalibur.service.ServerBus
import com.joshuamandel.excalibur.ui.components.AddressCard
import com.joshuamandel.excalibur.ui.components.StageRail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    book: Book,
    active: ActiveProgress?,
    server: ServerBus.Info,
    onBack: () -> Unit,
    onViewLibrary: () -> Unit,
    onAddMore: () -> Unit,
    onPreview: () -> Unit,
    onReconvert: (Book) -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
) {
    val live = active?.takeIf { it.id == book.id }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(book.title, style = MaterialTheme.typography.displaySmall)
            if (book.author.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(book.author, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(32.dp))

            when {
                book.status == BookStatus.READY -> ReadyView(
                    server = server,
                    onToggleServer = { on -> if (on) onStartServer() else onStopServer() },
                    onViewLibrary = onViewLibrary,
                    onAddMore = onAddMore,
                    onPreview = onPreview,
                )
                book.status == BookStatus.ERROR -> ErrorView(book, onReconvert)
                else -> ConvertingView(book, live)
            }
        }
    }
}

@Composable
private fun ConvertingView(book: Book, live: ActiveProgress?) {
    val stage = live?.stage ?: book.stage
    val label = live?.label?.ifBlank { null } ?: book.stageLabel.ifBlank { "Preparing…" }
    StageRail(stage, fraction = live?.fraction ?: stageBaseFraction(stage))
    Spacer(Modifier.height(20.dp))
    Text(label, style = MaterialTheme.typography.bodyLarge)
    live?.lastLine?.takeIf { it.isNotBlank() && it != label }?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
    Spacer(Modifier.height(28.dp))
    Text("Target: ${profileName(book.profile)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ReadyView(
    server: ServerBus.Info,
    onToggleServer: (Boolean) -> Unit,
    onViewLibrary: () -> Unit,
    onAddMore: () -> Unit,
    onPreview: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text("Ready", style = MaterialTheme.typography.headlineSmall)
    }
    Spacer(Modifier.height(20.dp))
    ServerToggleRow(server, onToggleServer)
    Spacer(Modifier.height(16.dp))
    if (server.running && server.port > 0) {
        AddressCard(server.port)
        Spacer(Modifier.height(16.dp))
        Text("Your book is at the top of the Kindle page.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    } else {
        Text("Turn on the web server to use the Kindle browser.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
    Spacer(Modifier.height(28.dp))
    Button(onClick = onPreview, modifier = Modifier.fillMaxWidth()) { Text("Generate preview") }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onViewLibrary, modifier = Modifier.weight(1f)) { Text("View library") }
        OutlinedButton(onClick = onAddMore, modifier = Modifier.weight(1f)) { Text("Add more") }
    }
}

@Composable
private fun ServerToggleRow(server: ServerBus.Info, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .background(cs.primaryContainer.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Web server", style = MaterialTheme.typography.titleMedium)
            Text(
                if (server.running) "On, port ${server.port}" else "Off",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
        Switch(checked = server.running, onCheckedChange = onToggle)
    }
}

@Composable
private fun ErrorView(book: Book, onReconvert: (Book) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(LocalIcons.ErrorOutline, null, tint = cs.error, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text("Couldn't convert", style = MaterialTheme.typography.headlineSmall)
    }
    Spacer(Modifier.height(14.dp))
    Text(
        book.error.ifBlank { "Something went wrong during conversion." },
        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = { onReconvert(book) }) { Text("Try again") }
}
