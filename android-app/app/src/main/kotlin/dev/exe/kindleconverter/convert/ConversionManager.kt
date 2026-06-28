package dev.exe.kindleconverter.convert

import dev.exe.kindleconverter.data.Book
import dev.exe.kindleconverter.data.BookStatus
import dev.exe.kindleconverter.data.LibraryRepository
import dev.exe.kindleconverter.data.Stage
import dev.exe.kindleconverter.wasmtime.WasmtimeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Live progress for the book currently converting (null when idle). */
data class ActiveProgress(
    val id: String,
    val title: String,
    val stage: Stage,
    val fraction: Float,
    val label: String,
    val lastLine: String = "",
)

/**
 * Drains the QUEUED books one at a time (parallelism 1 — conversion is memory-heavy),
 * running the native Wasmtime/calibre pipeline and translating its log stream into
 * Room status updates + a smooth [active] progress flow for the UI.
 */
class ConversionManager(
    private val repo: LibraryRepository,
    private val runtime: WasmtimeRuntime,
) {
    private val _active = MutableStateFlow<ActiveProgress?>(null)
    val active = _active.asStateFlow()
    private val drainLock = Mutex()

    /** Convert pending books until the queue is empty. Safe to call repeatedly/concurrently. */
    suspend fun drain() = drainLock.withLock {
        while (true) {
            val book = repo.nextPending() ?: break
            convertOne(book)
        }
        _active.value = null
    }

    private suspend fun convertOne(book: Book) = coroutineScope {
        val storage = repo.storage()
        val input = storage.original(book.id, book.ext)
        val output = storage.converted(book.id)
        repo.update(book.copy(status = BookStatus.CONVERTING, stage = Stage.IMPORT, stageLabel = "Starting up", error = ""))
        _active.value = ActiveProgress(book.id, book.title, Stage.IMPORT, 0.04f, "Starting up")

        // Persist coarse stage to Room (so the library list shows progress too) from a real
        // coroutine — never from the native callback thread, which isn't safe for DB I/O.
        val persist = launch {
            var lastStage: Stage? = null
            active.collect { p ->
                if (p != null && p.id == book.id && p.stage != lastStage) {
                    lastStage = p.stage
                    repo.update(book.copy(status = BookStatus.CONVERTING, stage = p.stage, stageLabel = p.label))
                }
            }
        }

        var maxFraction = 0.04f
        var stage = Stage.IMPORT
        var label = "Starting up"

        val result = withContext(Dispatchers.IO) {
            runtime.convert(input, output, storage.workDir(book.id), book.profile) { line ->
                val hint = matchStage(line) ?: return@convert
                if (hint.fraction >= maxFraction) {
                    maxFraction = hint.fraction; stage = hint.stage; label = hint.label
                }
                _active.value = ActiveProgress(book.id, book.title, stage, maxFraction, label, line.trim())
            }
        }
        persist.cancel()

        // Reclaim the per-conversion scratch dir (held the input + a duplicate of the
        // output). The converted file is already copied to the library.
        runCatching { storage.workDir(book.id).deleteRecursively() }

        if (result.ok && output.exists()) {
            repo.update(
                book.copy(
                    status = BookStatus.READY, stage = Stage.DONE, stageLabel = "Ready",
                    azw3Path = output.absolutePath, azw3Size = output.length(),
                    convertedAt = System.currentTimeMillis(), error = "",
                )
            )
            _active.value = ActiveProgress(book.id, book.title, Stage.DONE, 1f, "Ready")
        } else {
            val msg = result.error.ifBlank { result.stderr.takeLast(400) }.ifBlank { "Conversion failed" }
            repo.update(book.copy(status = BookStatus.ERROR, stageLabel = "Failed", error = msg))
            _active.value = null
        }
    }
}
