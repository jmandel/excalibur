package dev.exe.kindleconverter.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.exe.kindleconverter.AppGraph
import dev.exe.kindleconverter.data.AppSettings
import dev.exe.kindleconverter.data.Book
import dev.exe.kindleconverter.data.ThemeMode
import dev.exe.kindleconverter.service.ConverterService
import dev.exe.kindleconverter.service.ServerBus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun setProfile(id: String) = viewModelScope.launch { graph.settings.setProfile(id) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { graph.settings.setThemeMode(mode) }
    fun setDynamicColor(on: Boolean) = viewModelScope.launch { graph.settings.setDynamicColor(on) }

    fun exitServer() = ConverterService.exit(getApplication())
    fun ensureServer() = ConverterService.startAndConvert(getApplication())
}
