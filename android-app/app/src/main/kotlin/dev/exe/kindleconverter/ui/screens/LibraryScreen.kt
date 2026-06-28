package dev.exe.kindleconverter.ui.screens

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.exe.kindleconverter.convert.stageBaseFraction
import dev.exe.kindleconverter.data.Book
import dev.exe.kindleconverter.data.BookStatus
import dev.exe.kindleconverter.service.ServerBus
import dev.exe.kindleconverter.ui.components.LocalIcons
import dev.exe.kindleconverter.ui.components.PortDialog
import dev.exe.kindleconverter.ui.components.QrDialog
import dev.exe.kindleconverter.ui.components.StageRail
import dev.exe.kindleconverter.ui.components.rememberAddresses

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    books: List<Book>,
    server: ServerBus.Info,
    configuredPort: Int,
    onOpenBook: (String) -> Unit,
    onAddBooks: () -> Unit,
    onOpenSettings: () -> Unit,
    onReconvert: (Book) -> Unit,
    onDelete: (String) -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onSetPort: (Int) -> Unit,
) {
    var showPortDialog by remember { mutableStateOf(false) }
    if (showPortDialog) {
        PortDialog(
            current = if (server.running) server.port else configuredPort,
            onDismiss = { showPortDialog = false },
            onConfirm = { showPortDialog = false; onSetPort(it) },
        )
    }

    // Per-book export. Share goes through a FileProvider content URI; "Save to…" uses the
    // system create-document picker, so the user chooses the destination — no permissions.
    val context = LocalContext.current
    var pendingSave by remember { mutableStateOf<Book?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val path = pendingSave?.azw3Path; pendingSave = null
        if (uri != null && path != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out -> File(path).inputStream().use { it.copyTo(out) } }
        }
    }
    val shareBook: (Book) -> Unit = { book ->
        if (book.isReady) runCatching {
            // Zero-copy: BookFileProvider streams the original file and reports a nice name.
            val name = safeFileName(book.title) + ".azw3"
            val uri = Uri.parse("content://${context.packageName}.books/${book.id}/${Uri.encode(name)}")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/x-mobipocket-ebook"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(name, uri) // carries the grant to the share-sheet preview too
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share \"${book.title}\""))
        }
    }
    val saveBook: (Book) -> Unit = { book ->
        pendingSave = book
        saveLauncher.launch(safeFileName(book.title) + ".azw3")
    }

    // To another device over Wi-Fi: a QR / copyable link pointing at this server's
    // /download/<id> route. Only meaningful while the server is running.
    val addresses = rememberAddresses()
    val clipboard = LocalClipboardManager.current
    var qrUrl by remember { mutableStateOf<String?>(null) }
    fun downloadUrl(book: Book): String? {
        val ip = addresses.firstOrNull()?.ip ?: return null
        return if (server.running) "http://$ip:${server.port}/download/${book.id}" else null
    }
    qrUrl?.let { QrDialog(it) { qrUrl = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Excalibur", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddBooks,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add books") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ServerBanner(
                server = server,
                configuredPort = configuredPort,
                onToggle = { on -> if (on) onStartServer() else onStopServer() },
                onEditPort = { showPortDialog = true },
            )
            if (books.isEmpty()) {
                EmptyLibrary(onAddBooks)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(books, key = { it.id }) { book ->
                        BookRow(
                            book,
                            serverRunning = server.running,
                            onClick = { onOpenBook(book.id) },
                            onReconvert = { onReconvert(book) },
                            onDelete = { onDelete(book.id) },
                            onShare = { shareBook(book) },
                            onSave = { saveBook(book) },
                            onShowQr = { downloadUrl(book)?.let { qrUrl = it } },
                            onCopyLink = { downloadUrl(book)?.let { clipboard.setText(AnnotatedString(it)) } },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerBanner(
    server: ServerBus.Info,
    configuredPort: Int,
    onToggle: (Boolean) -> Unit,
    onEditPort: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val addresses = rememberAddresses()
    val ip = addresses.firstOrNull()?.ip
    val port = if (server.running) server.port else configuredPort
    Row(
        Modifier.fillMaxWidth()
            .background(cs.primaryContainer.copy(alpha = 0.4f))
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (server.running) cs.primary else cs.outline))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (server.running) "Send to Kindle" else "Send to Kindle is off",
                style = MaterialTheme.typography.titleSmall,
            )
            if (server.running) {
                // ip:port, with the port a tap target for changing it.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${ip ?: "…"}:",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = cs.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onEditPort).padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$port",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = cs.primary,
                        )
                        Spacer(Modifier.width(3.dp))
                        Icon(Icons.Rounded.Edit, "Change port", tint = cs.primary, modifier = Modifier.size(13.dp))
                    }
                }
            } else {
                Text(
                    "Turn on to serve books to your Kindle",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        Switch(checked = server.running, onCheckedChange = onToggle)
    }
}

@Composable
private fun BookRow(
    book: Book,
    serverRunning: Boolean,
    onClick: () -> Unit,
    onReconvert: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onShowQr: () -> Unit,
    onCopyLink: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            LocalIcons.MenuBook, null,
            tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            when (book.status) {
                BookStatus.CONVERTING -> Column {
                    StageRail(book.stage, stageBaseFraction(book.stage), showLabels = false)
                    Spacer(Modifier.height(4.dp))
                    Text(book.stageLabel.ifBlank { "Converting…" }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                BookStatus.READY -> StatusLine("Ready · AZW3 · ${mb(book.azw3Size)}", cs.primary)
                BookStatus.QUEUED -> StatusLine("Queued", cs.onSurfaceVariant)
                BookStatus.IMPORTED -> StatusLine("Imported", cs.onSurfaceVariant)
                BookStatus.NEEDS_RECONVERT -> StatusLine("Needs reconvert", cs.secondary)
                BookStatus.ERROR -> StatusLine("Couldn't convert — tap for details", cs.error)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (book.isReady) {
                    // Share works to anyone across apps (no network needed) — the general
                    // option, so it's first. QR/link only reach devices on the same Wi-Fi.
                    DropdownMenuItem(text = { Text("Share…") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("Save a copy…") }, onClick = { menu = false; onSave() })
                    if (serverRunning) {
                        HorizontalDivider()
                        Text(
                            "Same Wi-Fi only",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        DropdownMenuItem(text = { Text("QR code") }, onClick = { menu = false; onShowQr() })
                        DropdownMenuItem(text = { Text("Copy link") }, onClick = { menu = false; onCopyLink() })
                    }
                    HorizontalDivider()
                }
                DropdownMenuItem(text = { Text("Convert again") }, onClick = { menu = false; onReconvert() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)

@Composable
private fun EmptyLibrary(onAddBooks: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(LocalIcons.MenuBook, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text("Your library is empty", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add an EPUB or MOBI — or open one from your browser — and it'll be converted for your Kindle.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onAddBooks) { Text("Add books") }
    }
}

private fun mb(bytes: Long) = if (bytes <= 0) "—" else "%.1f MB".format(bytes / 1_048_576.0)

private fun safeFileName(title: String) =
    title.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().ifBlank { "book" }.take(60)
