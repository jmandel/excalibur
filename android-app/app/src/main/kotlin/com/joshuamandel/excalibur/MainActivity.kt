package com.joshuamandel.excalibur

import android.Manifest
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.joshuamandel.excalibur.data.ThemeMode
import com.joshuamandel.excalibur.ui.AppViewModel
import com.joshuamandel.excalibur.ui.screens.BookScreen
import com.joshuamandel.excalibur.ui.screens.LibraryScreen
import com.joshuamandel.excalibur.ui.screens.ReaderScreen
import com.joshuamandel.excalibur.ui.screens.SettingsScreen
import com.joshuamandel.excalibur.ui.theme.KindleConverterTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val pickBooks = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importAndConvert(uris)
    }
    private val pickDriveInbox = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setDriveInbox(uri)
    }
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            KindleConverterTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                AppNav(
                    vm = vm,
                    onPickBooks = { pickBooks.launch(EBOOK_MIME_TYPES) },
                    onPickDriveInbox = { pickDriveInbox.launch(null) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.filterNotNull().orEmpty()
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                vm.syncToKindleIfAutoEnabled()
                emptyList()
            }
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.importAndConvert(uris)
    }

    companion object {
        private val EBOOK_MIME_TYPES = arrayOf(
            "application/epub+zip",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook",
            "application/octet-stream",
        )
    }
}

@Composable
private fun AppNav(vm: AppViewModel, onPickBooks: () -> Unit, onPickDriveInbox: () -> Unit) {
    val nav = rememberNavController()
    val books by vm.books.collectAsStateWithLifecycle()
    val server by vm.server.collectAsState()
    val active by vm.active.collectAsState()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val viewer by vm.viewer.collectAsState()

    LaunchedEffect(Unit) {
        vm.openBook.collect { id -> nav.navigate("book/$id") }
    }

    NavHost(nav, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                books = books,
                server = server,
                configuredPort = settings.serverPort,
                onOpenBook = { nav.navigate("book/$it") },
                onReadBook = { nav.navigate("reader/$it") },
                onAddBooks = onPickBooks,
                onOpenSettings = { nav.navigate("settings") },
                onReconvert = { vm.reconvert(it) },
                onDelete = { vm.delete(it) },
                onDeleteMany = { vm.deleteMany(it) },
                onTagMany = { ids, tag -> vm.tagMany(ids, tag) },
                onAddTag = { id, tag -> vm.addTag(id, tag) },
                onRemoveTag = { id, tag -> vm.removeTag(id, tag) },
                onStartServer = { vm.startServer() },
                onStopServer = { vm.exitServer() },
                onSetPort = { vm.setPort(it) },
            )
        }
        composable("book/{id}") { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val book by vm.bookFlow(id).collectAsStateWithLifecycle(initialValue = null)
            book?.let { b ->
                BookScreen(
                    book = b,
                    active = active,
                    server = server,
                    onBack = { nav.popBackStack() },
                    onViewLibrary = { nav.popBackStack("library", inclusive = false) },
                    onAddMore = onPickBooks,
                    onPreview = { nav.navigate("reader/${b.id}") },
                    onReconvert = { vm.reconvert(it) },
                    onStartServer = { vm.startServer() },
                    onStopServer = { vm.exitServer() },
                )
            }
        }
        composable("reader/{id}") { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val book by vm.bookFlow(id).collectAsStateWithLifecycle(initialValue = null)
            LaunchedEffect(id, book?.azw3Path, book?.convertedAt, book?.status) {
                if (book?.isReady == true) vm.prepareViewer(id)
            }
            ReaderScreen(
                book = book,
                viewer = viewer,
                onBack = { nav.popBackStack() },
                onRetry = { vm.prepareViewer(id, force = true) },
            )
        }
        composable("settings") {
            val syncStatus by vm.syncStatus.collectAsState()
            val syncing by vm.syncing.collectAsState()
            val driveSyncStatus by vm.driveSyncStatus.collectAsState()
            val driveSyncing by vm.driveSyncing.collectAsState()
            SettingsScreen(
                settings = settings,
                serverRunningPort = if (server.running) server.port else null,
                syncStatus = syncStatus,
                syncing = syncing,
                driveSyncStatus = driveSyncStatus,
                driveSyncing = driveSyncing,
                onSetProfile = { vm.setProfile(it) },
                onSetTheme = { vm.setThemeMode(it) },
                onSetDynamic = { vm.setDynamicColor(it) },
                onSetPort = { vm.setPort(it) },
                onChooseDriveInbox = onPickDriveInbox,
                onClearDriveInbox = { vm.clearDriveInbox() },
                onSetDrivePublicFolderUrl = { vm.setDrivePublicFolderUrl(it) },
                onClearDrivePublicFolderUrl = { vm.clearDrivePublicFolderUrl() },
                onSyncDriveInbox = { vm.syncDriveInboxNow() },
                onSetDriveDailySyncOnCharger = { vm.setDriveDailySyncOnCharger(it) },
                onSyncToKindle = { vm.syncToKindle() },
                onSetSyncTagsIntoTitle = { vm.setSyncTagsIntoTitle(it) },
                onSetAutoSyncKindleOnConnect = { vm.setAutoSyncKindleOnConnect(it) },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
