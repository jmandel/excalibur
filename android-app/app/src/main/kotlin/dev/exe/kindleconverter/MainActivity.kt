package dev.exe.kindleconverter

import android.Manifest
import android.content.Intent
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
import dev.exe.kindleconverter.data.ThemeMode
import dev.exe.kindleconverter.ui.AppViewModel
import dev.exe.kindleconverter.ui.screens.BookScreen
import dev.exe.kindleconverter.ui.screens.LibraryScreen
import dev.exe.kindleconverter.ui.screens.SettingsScreen
import dev.exe.kindleconverter.ui.theme.KindleConverterTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val pickBooks = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importAndConvert(uris)
    }
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.ensureServer()
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
                AppNav(vm) { pickBooks.launch(EBOOK_MIME_TYPES) }
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
private fun AppNav(vm: AppViewModel, onPickBooks: () -> Unit) {
    val nav = rememberNavController()
    val books by vm.books.collectAsStateWithLifecycle()
    val server by vm.server.collectAsState()
    val active by vm.active.collectAsState()
    val settings by vm.settings.collectAsStateWithLifecycle()

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
                onAddBooks = onPickBooks,
                onOpenSettings = { nav.navigate("settings") },
                onReconvert = { vm.reconvert(it) },
                onDelete = { vm.delete(it) },
                onDeleteMany = { vm.deleteMany(it) },
                onTagMany = { ids, tag -> vm.tagMany(ids, tag) },
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
                    port = if (server.running) server.port else 0,
                    onBack = { nav.popBackStack() },
                    onViewLibrary = { nav.popBackStack("library", inclusive = false) },
                    onAddMore = onPickBooks,
                    onReconvert = { vm.reconvert(it) },
                )
            }
        }
        composable("settings") {
            val syncStatus by vm.syncStatus.collectAsState()
            val syncing by vm.syncing.collectAsState()
            SettingsScreen(
                settings = settings,
                serverRunningPort = if (server.running) server.port else null,
                syncStatus = syncStatus,
                syncing = syncing,
                onSetProfile = { vm.setProfile(it) },
                onSetTheme = { vm.setThemeMode(it) },
                onSetDynamic = { vm.setDynamicColor(it) },
                onSetPort = { vm.setPort(it) },
                onSyncToKindle = { vm.syncToKindle() },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
