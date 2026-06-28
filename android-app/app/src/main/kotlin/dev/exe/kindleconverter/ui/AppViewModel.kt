package dev.exe.kindleconverter.ui

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.exe.kindleconverter.AppGraph
import dev.exe.kindleconverter.data.AppSettings
import dev.exe.kindleconverter.data.Book
import dev.exe.kindleconverter.data.ThemeMode
import dev.exe.kindleconverter.service.ConverterService
import dev.exe.kindleconverter.service.ServerBus
import dev.exe.kindleconverter.usb.SyncOutcome
import dev.exe.kindleconverter.usb.syncLibraryToKindle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = AppGraph.get(app)

    val books = graph.repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    val active = graph.conversion.active
    val server = ServerBus.state

    /** Emits a book id to navigate to (after an import). */
    val openBook = MutableSharedFlow<String>(replay = 1)

    fun bookFlow(id: String) = graph.repo.observe(id)

    /** Import one or many ebooks, queue them, start converting, and open the first. */
    fun importAndConvert(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val profile = settings.value.lastProfile
            val ids = uris.mapNotNull { runCatching { graph.repo.importAndQueue(it, profile) }.getOrNull() }
            ConverterService.startAndConvert(getApplication())
            ids.firstOrNull()?.let { openBook.emit(it) }
        }
    }

    fun reconvert(book: Book) = viewModelScope.launch {
        graph.repo.requeue(book.id, settings.value.lastProfile)
        ConverterService.startAndConvert(getApplication())
    }

    fun delete(id: String) = viewModelScope.launch { graph.repo.delete(id) }
    fun deleteMany(ids: Set<String>) = viewModelScope.launch { graph.repo.deleteMany(ids) }
    fun tagMany(ids: Set<String>, tag: String) = viewModelScope.launch { graph.repo.addTag(ids, tag) }

    fun setProfile(id: String) = viewModelScope.launch { graph.settings.setProfile(id) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { graph.settings.setThemeMode(mode) }
    fun setDynamicColor(on: Boolean) = viewModelScope.launch { graph.settings.setDynamicColor(on) }

    fun startServer() = ConverterService.startAndConvert(getApplication())
    fun exitServer() = ConverterService.exit(getApplication())
    fun ensureServer() = ConverterService.startAndConvert(getApplication())

    fun setPort(port: Int) = viewModelScope.launch {
        graph.settings.setPort(port)
        if (ServerBus.state.value.running) ConverterService.restart(getApplication())
    }

    // --- USB/MTP sync to a connected Kindle (experimental spike) ---
    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus = _syncStatus.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()

    fun syncToKindle() = viewModelScope.launch {
        if (_syncing.value) return@launch
        _syncing.value = true
        _syncStatus.value = "Looking for a connected Kindle…"
        val device = settings.value.deviceTag.ifBlank {
            makeDeviceTag().also { graph.settings.setDeviceTag(it) }
        }
        val wantById = graph.repo.ready().mapNotNull { b -> b.azw3Path?.let { b.id to File(it) } }.toMap()
        val outcome = syncLibraryToKindle(getApplication(), device, wantById) { line -> _syncStatus.value = line }
        _syncStatus.value = when (outcome) {
            SyncOutcome.NoDevice -> "No Kindle found. Connect it with a USB-OTG cable, unlock it, and allow file transfer."
            SyncOutcome.NoPermission -> "USB permission was denied."
            SyncOutcome.OpenFailed -> "Couldn't open the Kindle over MTP."
            SyncOutcome.NoStorage -> "The Kindle's storage wasn't available."
            is SyncOutcome.Failed -> "Sync failed: ${outcome.message}"
            is SyncOutcome.Done -> outcome.result.let { "Done — ${it.pushed} added, ${it.deleted} removed, ${it.skipped} already there." }
        }
        _syncing.value = false
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
