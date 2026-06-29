package com.joshuamandel.excalibur.drive

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.joshuamandel.excalibur.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DriveInboxSyncResult(
    val imported: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val ignored: Int = 0,
    val failed: Int = 0,
    val message: String = "",
) {
    val changed get() = imported > 0
    fun summary(): String = message.ifBlank {
        "Drive sync done: $imported imported, $duplicates already in library, $skipped already seen, $ignored ignored, $failed failed."
    }
}

object DriveInboxSync {
    private val supported = setOf("epub", "mobi", "azw", "azw3", "prc", "pobi")

    fun folderName(context: Context, uri: Uri): String =
        DocumentFile.fromTreeUri(context, uri)?.name?.takeIf { it.isNotBlank() } ?: "Drive inbox"

    suspend fun sync(context: Context, convertQueued: Boolean, onLog: (String) -> Unit = {}): DriveInboxSyncResult =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val graph = AppGraph.get(app)
            val settings = graph.settings.settings.first()
            if (settings.driveInboxUri.isBlank()) return@withContext DriveInboxSyncResult(message = "No Drive inbox folder selected.")

            val rootUri = Uri.parse(settings.driveInboxUri)
            val root = DocumentFile.fromTreeUri(app, rootUri)
                ?: return@withContext DriveInboxSyncResult(failed = 1, message = "Drive inbox folder could not be opened.")
            if (!root.canRead()) return@withContext DriveInboxSyncResult(failed = 1, message = "Drive inbox permission is no longer available.")

            var imported = 0
            var duplicates = 0
            var skipped = 0
            var ignored = 0
            var failed = 0
            val importedKeys = mutableListOf<String>()
            val seen = settings.driveImportedDocumentKeys
            val profile = settings.lastProfile

            for (doc in root.listFiles().filter { it.isFile }.sortedBy { it.name.orEmpty().lowercase(Locale.US) }) {
                val name = doc.name.orEmpty()
                val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                if (ext !in supported) { ignored++; continue }
                val key = documentKey(doc)
                if (key in seen) { skipped++; continue }
                runCatching {
                    onLog("Importing $name")
                    val result = graph.repo.importAndQueueDetailed(doc.uri, profile)
                    if (result.created) imported++ else duplicates++
                    importedKeys += key
                }.getOrElse {
                    failed++
                    onLog("Failed to import $name: ${it.message ?: it.javaClass.simpleName}")
                }
            }

            graph.settings.addDriveImportedDocumentKeys(importedKeys)
            if (convertQueued && imported > 0) graph.conversion.drain()

            DriveInboxSyncResult(imported, duplicates, skipped, ignored, failed).also {
                graph.settings.setDriveLastSyncSummary(it.summary())
            }
        }

    private fun documentKey(doc: DocumentFile): String =
        listOf(doc.uri.toString(), doc.length().toString(), doc.lastModified().toString()).joinToString("|")
}

object DriveInboxWork {
    private const val UNIQUE_DAILY = "drive-inbox-daily-sync"

    fun configure(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            manager.cancelUniqueWork(UNIQUE_DAILY)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<DriveInboxWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(UNIQUE_DAILY, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class DriveInboxWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        val result = DriveInboxSync.sync(applicationContext, convertQueued = true) { Log.i("drive-inbox", it) }
        Log.i("drive-inbox", result.summary())
        Result.success()
    }.getOrElse {
        Log.e("drive-inbox", "sync failed", it)
        AppGraph.get(applicationContext).settings.setDriveLastSyncSummary("Drive sync failed: ${it.message ?: it.javaClass.simpleName}")
        Result.retry()
    }
}
