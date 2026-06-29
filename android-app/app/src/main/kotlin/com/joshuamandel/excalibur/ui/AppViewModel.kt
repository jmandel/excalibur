package com.joshuamandel.excalibur.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.joshuamandel.excalibur.AppGraph
import com.joshuamandel.excalibur.data.AppSettings
import com.joshuamandel.excalibur.data.Book
import com.joshuamandel.excalibur.data.ThemeMode
import com.joshuamandel.excalibur.drive.DriveInboxSync
import com.joshuamandel.excalibur.drive.DriveInboxWork
import com.joshuamandel.excalibur.service.ConverterService
import com.joshuamandel.excalibur.service.ServerBus
import com.joshuamandel.excalibur.usb.SyncOutcome
import com.joshuamandel.excalibur.usb.syncLibraryToKindle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

sealed class ViewerState {
    data object Idle : ViewerState()
    data class Preparing(val bookId: String, val title: String, val message: String) : ViewerState()
    data class Ready(val bookId: String, val title: String, val entryPath: String) : ViewerState()
    data class Error(val bookId: String, val title: String, val message: String) : ViewerState()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = AppGraph.get(app)

    val books = graph.repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    val active = graph.conversion.active
    val server = ServerBus.state
    private val _viewer = MutableStateFlow<ViewerState>(ViewerState.Idle)
    val viewer = _viewer.asStateFlow()
    private var viewerJob: Job? = null

    init {
        viewModelScope.launch {
            val snapshot = graph.settings.settings.first()
            DriveInboxWork.configure(getApplication(), snapshot.driveDailySyncOnCharger && snapshot.driveInboxUri.isNotBlank())
        }
    }

    /** Emits a book id to navigate to (after an import). */
    val openBook = MutableSharedFlow<String>(replay = 1)

    fun bookFlow(id: String) = graph.repo.observe(id)

    /** Import one or many ebooks, queue them, start converting, and open the first. */
    fun importAndConvert(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val profile = settings.value.lastProfile
            val ids = uris.mapNotNull { runCatching { graph.repo.importAndQueue(it, profile) }.getOrNull() }
            ConverterService.convert(getApplication())
            // Open the detail page only for a single import; for a batch, stay on the
            // library so all of them can be watched converting at once.
            if (ids.size == 1) openBook.emit(ids.first())
        }
    }

    fun reconvert(book: Book) = viewModelScope.launch {
        graph.repo.requeue(book.id, settings.value.lastProfile)
        ConverterService.convert(getApplication())
    }

    fun delete(id: String) = viewModelScope.launch { graph.repo.delete(id) }
    fun deleteMany(ids: Set<String>) = viewModelScope.launch { graph.repo.deleteMany(ids) }
    fun tagMany(ids: Set<String>, tag: String) = viewModelScope.launch { graph.repo.addTag(ids, tag) }
    fun addTag(id: String, tag: String) = viewModelScope.launch { graph.repo.addTag(listOf(id), tag) }
    fun removeTag(id: String, tag: String) = viewModelScope.launch { graph.repo.removeTag(id, tag) }

    fun setSyncTagsIntoTitle(on: Boolean) = viewModelScope.launch { graph.settings.setSyncTagsIntoTitle(on) }
    fun setAutoSyncKindleOnConnect(on: Boolean) = viewModelScope.launch { graph.settings.setAutoSyncKindleOnConnect(on) }

    fun setProfile(id: String) = viewModelScope.launch { graph.settings.setProfile(id) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { graph.settings.setThemeMode(mode) }
    fun setDynamicColor(on: Boolean) = viewModelScope.launch { graph.settings.setDynamicColor(on) }

    fun startServer() = ConverterService.startServer(getApplication())
    fun exitServer() = ConverterService.stopServer(getApplication())

    fun setPort(port: Int) = viewModelScope.launch {
        graph.settings.setPort(port)
        if (ServerBus.state.value.running) ConverterService.restart(getApplication())
    }

    fun prepareViewer(id: String, force: Boolean = false) {
        val current = _viewer.value
        if (!force && current is ViewerState.Preparing && current.bookId == id) return
        if (!force && current is ViewerState.Ready && current.bookId == id && File(current.entryPath).exists()) return
        viewerJob?.cancel()
        viewerJob = viewModelScope.launch {
            val book = graph.repo.get(id)
            if (book == null) {
                _viewer.value = ViewerState.Error(id, "Preview", "Book not found.")
                return@launch
            }
            _viewer.value = ViewerState.Preparing(id, book.title, "Preparing preview...")
            try {
                val artifact = graph.viewerArtifacts.prepare(book) { line ->
                    _viewer.value = ViewerState.Preparing(id, book.title, viewerMessage(line))
                }
                _viewer.value = ViewerState.Ready(id, book.title, artifact.entry.absolutePath)
            } catch (e: Exception) {
                _viewer.value = ViewerState.Error(id, book.title, e.message ?: "Preview generation failed.")
            }
        }
    }

    private fun viewerMessage(line: String): String {
        val text = line.trim()
        return when {
            text.isBlank() -> "Preparing preview..."
            text.contains("viewer imported calibre", ignoreCase = true) -> "Starting calibre..."
            text.contains("viewer html ready", ignoreCase = true) -> "Opening preview..."
            text.contains("input plugin", ignoreCase = true) -> "Reading book..."
            text.contains("output plugin", ignoreCase = true) -> "Writing HTML..."
            else -> text.take(120)
        }
    }

    // --- USB/MTP sync to a connected Kindle (experimental spike) ---
    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus = _syncStatus.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()

    // --- Drive inbox sync ---
    private val _driveSyncStatus = MutableStateFlow<String?>(null)
    val driveSyncStatus = _driveSyncStatus.asStateFlow()
    private val _driveSyncing = MutableStateFlow(false)
    val driveSyncing = _driveSyncing.asStateFlow()

    fun setDriveInbox(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = DriveInboxSync.folderName(app, uri)
        graph.settings.setDriveInbox(uri.toString(), name)
        _driveSyncStatus.value = "Drive inbox selected: $name"
        DriveInboxWork.configure(app, settings.value.driveDailySyncOnCharger)
    }

    fun clearDriveInbox() = viewModelScope.launch {
        val app = getApplication<Application>()
        settings.value.driveInboxUri.takeIf { it.isNotBlank() }?.let {
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        graph.settings.clearDriveInbox()
        DriveInboxWork.configure(app, enabled = false)
        _driveSyncStatus.value = "Drive inbox cleared."
    }

    fun setDriveDailySyncOnCharger(on: Boolean) = viewModelScope.launch {
        val hasFolder = settings.value.driveInboxUri.isNotBlank()
        if (on && !hasFolder) {
            _driveSyncStatus.value = "Choose a Drive inbox folder first."
            return@launch
        }
        graph.settings.setDriveDailySyncOnCharger(on)
        DriveInboxWork.configure(getApplication(), enabled = on && hasFolder)
        _driveSyncStatus.value = if (on) "Daily Drive sync will run while charging." else "Daily Drive sync is off."
    }

    fun syncDriveInboxNow() = viewModelScope.launch {
        if (_driveSyncing.value) return@launch
        if (settings.value.driveInboxUri.isBlank()) {
            _driveSyncStatus.value = "Choose a Drive inbox folder first."
            return@launch
        }
        _driveSyncing.value = true
        _driveSyncStatus.value = "Scanning Drive inbox..."
        try {
            val result = DriveInboxSync.sync(getApplication(), convertQueued = false) { _driveSyncStatus.value = it }
            _driveSyncStatus.value = result.summary()
            if (result.changed) ConverterService.convert(getApplication())
        } finally {
            _driveSyncing.value = false
        }
    }

    fun syncToKindle() = viewModelScope.launch { runKindleSync(settings.value, automatic = false) }

    fun syncToKindleIfAutoEnabled() = viewModelScope.launch {
        val snapshot = graph.settings.settings.first()
        if (!snapshot.autoSyncKindleOnConnect) {
            _syncStatus.value = "Kindle connected. Auto-sync is off."
            return@launch
        }
        runKindleSync(snapshot, automatic = true)
    }

    private suspend fun runKindleSync(snapshot: AppSettings, automatic: Boolean) {
        if (_syncing.value) return
        _syncing.value = true
        _syncStatus.value = if (automatic) "Kindle connected. Auto-sync starting..." else "Looking for a connected Kindle..."
        try {
            syncToKindleNow(snapshot)
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun syncToKindleNow(snapshot: AppSettings) {
        val device = snapshot.deviceTag.ifBlank {
            makeDeviceTag().also { graph.settings.setDeviceTag(it) }
        }
        val suffixTags = snapshot.syncTagsIntoTitle
        // Build the id→file map off the main thread (retitling runs calibre).
        val retitleDirs = mutableListOf<File>()
        val wantById = withContext(Dispatchers.IO) {
            val map = LinkedHashMap<String, File>()
            for (b in graph.repo.ready()) {
                val src = b.azw3Path?.let { File(it) } ?: continue
                map[b.id] = if (suffixTags && b.tagSet.isNotEmpty()) {
                    _syncStatus.value = "Adding tags to “${b.title}”…"
                    val wd = graph.storage.workDir("retitle-${b.id}").also { retitleDirs += it }
                    graph.runtime.retitle(src, "${b.title} · ${b.tagSet.joinToString(", ")}", wd) ?: src
                } else src
            }
            map
        }
        val outcome = syncLibraryToKindle(getApplication(), device, wantById) { line -> _syncStatus.value = line }
        retitleDirs.forEach { runCatching { it.deleteRecursively() } } // reclaim retitle scratch
        _syncStatus.value = when (outcome) {
            SyncOutcome.NoDevice -> "No Kindle found. Connect it with a USB-OTG cable, unlock it, and allow file transfer."
            SyncOutcome.NoPermission -> "USB permission was denied."
            SyncOutcome.OpenFailed -> "Couldn't open the Kindle over MTP."
            SyncOutcome.NoStorage -> "The Kindle's storage wasn't available."
            is SyncOutcome.Failed -> "Sync failed: ${outcome.message}"
            is SyncOutcome.Done -> outcome.result.let { "Done — ${it.pushed} added, ${it.deleted} removed, ${it.skipped} already there." }
        }
    }

    /**
     * A stable, human-readable per-install id for this phone's Kindle folder, e.g.
     * "Pixel-7-a3f0c1d2". Derived from ANDROID_ID so it's the SAME after an uninstall/
     * reinstall (ANDROID_ID only changes on a factory reset or a change of signing key) —
     * so a reinstall keeps managing the same folder on the Kindle instead of orphaning it.
     * The value is still cached in settings, leaving room to rename it later.
     */
    @SuppressLint("HardwareIds")
    private fun makeDeviceTag(): String {
        val model = Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(20).ifBlank { "phone" }
        val androidId = Settings.Secure.getString(getApplication<Application>().contentResolver, Settings.Secure.ANDROID_ID)
        val suffix = androidId?.takeIf { it.isNotBlank() }?.take(8) ?: java.util.UUID.randomUUID().toString().take(8)
        return "$model-$suffix"
    }
}
