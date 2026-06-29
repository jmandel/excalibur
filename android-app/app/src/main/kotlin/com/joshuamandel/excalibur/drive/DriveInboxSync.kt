package com.joshuamandel.excalibur.drive

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
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
import kotlinx.coroutines.delay
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
    val visibleFiles: Int = 0,
    val loadingRetries: Int = 0,
    val message: String = "",
) {
    val changed get() = imported > 0
    fun summary(): String = message.ifBlank {
        val loading = if (loadingRetries > 0) " Drive was still loading; retried $loadingRetries time${if (loadingRetries == 1) "" else "s"}." else ""
        "Drive sync done: $imported imported, $duplicates already in library, $skipped already seen, $ignored ignored, $failed failed. Scanned $visibleFiles visible file${if (visibleFiles == 1) "" else "s"}.$loading"
    }
}

object DriveInboxSync {
    private val supported = setOf("epub", "mobi", "azw", "azw3", "prc", "pobi")
    private const val MAX_LOADING_QUERIES = 5
    private const val LOADING_RETRY_MS = 1_500L

    fun folderName(context: Context, uri: Uri): String =
        DocumentFile.fromTreeUri(context, uri)?.name?.takeIf { it.isNotBlank() } ?: "Drive inbox"

    suspend fun sync(
        context: Context,
        convertQueued: Boolean,
        requestProviderRefresh: Boolean = true,
        onLog: (String) -> Unit = {},
    ): DriveInboxSyncResult =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val graph = AppGraph.get(app)
            val settings = graph.settings.settings.first()
            if (settings.drivePublicFolderUrl.isNotBlank()) {
                return@withContext syncPublicFolder(
                    app = app,
                    url = settings.drivePublicFolderUrl,
                    seen = settings.driveImportedDocumentKeys,
                    profile = settings.lastProfile,
                    convertQueued = convertQueued,
                    onLog = onLog,
                )
            }
            if (settings.driveInboxUri.isBlank()) return@withContext DriveInboxSyncResult(message = "No Drive inbox folder or public link selected.")

            val rootUri = Uri.parse(settings.driveInboxUri)
            val root = DocumentFile.fromTreeUri(app, rootUri)
                ?: return@withContext DriveInboxSyncResult(failed = 1, message = "Drive inbox folder could not be opened.")
            if (!root.canRead()) return@withContext DriveInboxSyncResult(failed = 1, message = "Drive inbox permission is no longer available.")
            if (requestProviderRefresh) {
                val refreshed = requestRefresh(app, rootUri)
                onLog(if (refreshed) "Asked Drive to refresh the folder." else "Scanning Drive inbox.")
            }

            var imported = 0
            var duplicates = 0
            var skipped = 0
            var ignored = 0
            var failed = 0
            val importedKeys = mutableListOf<String>()
            val seen = settings.driveImportedDocumentKeys
            val profile = settings.lastProfile
            val listing = listChildren(app, rootUri) { onLog(it) }
            onLog("Drive returned ${listing.files.size} visible file${if (listing.files.size == 1) "" else "s"}.")

            for (doc in listing.files.sortedBy { it.name.lowercase(Locale.US) }) {
                val name = doc.name
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

            DriveInboxSyncResult(
                imported = imported,
                duplicates = duplicates,
                skipped = skipped,
                ignored = ignored,
                failed = failed,
                visibleFiles = listing.files.size,
                loadingRetries = listing.loadingRetries,
            ).also {
                graph.settings.setDriveLastSyncSummary(it.summary())
            }
        }

    private suspend fun syncPublicFolder(
        app: Context,
        url: String,
        seen: Set<String>,
        profile: String,
        convertQueued: Boolean,
        onLog: (String) -> Unit,
    ): DriveInboxSyncResult {
        val graph = AppGraph.get(app)
        val listing = runCatching {
            DrivePublicFolder.list(url, onLog)
        }.getOrElse { error ->
            val result = DriveInboxSyncResult(
                failed = 1,
                message = "Drive sync failed: ${error.message ?: error.javaClass.simpleName}",
            )
            graph.settings.setDriveLastSyncSummary(result.summary())
            return result
        }
        onLog("Public Drive link returned ${listing.files.size} visible file${if (listing.files.size == 1) "" else "s"}.")

        var imported = 0
        var duplicates = 0
        var skipped = 0
        var ignored = 0
        var failed = 0
        val importedKeys = mutableListOf<String>()

        for (doc in listing.files.sortedBy { it.displayName.lowercase(Locale.US) }) {
            val name = doc.name
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            if (ext !in supported) { ignored++; continue }
            val key = publicDocumentKey(doc)
            if (key in seen) { skipped++; continue }
            var temp: java.io.File? = null
            runCatching {
                onLog("Downloading ${doc.displayName}")
                temp = DrivePublicFolder.download(app, doc)
                onLog("Importing ${doc.displayName}")
                val result = graph.repo.importAndQueueDetailed(Uri.fromFile(requireNotNull(temp)), profile)
                if (result.created) imported++ else duplicates++
                importedKeys += key
            }.getOrElse {
                failed++
                onLog("Failed to import ${doc.displayName}: ${it.message ?: it.javaClass.simpleName}")
            }
            temp?.parentFile?.deleteRecursively()
        }

        graph.settings.addDriveImportedDocumentKeys(importedKeys)
        if (convertQueued && imported > 0) graph.conversion.drain()

        return DriveInboxSyncResult(
            imported = imported,
            duplicates = duplicates,
            skipped = skipped,
            ignored = ignored,
            failed = failed,
            visibleFiles = listing.files.size,
        ).also {
            graph.settings.setDriveLastSyncSummary(it.summary())
        }
    }

    private fun requestRefresh(context: Context, treeUri: Uri): Boolean {
        val resolver = context.contentResolver
        val rootDocUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        }.getOrNull()
        return listOfNotNull(rootDocUri, treeUri).distinct().any { uri ->
            runCatching { resolver.refresh(uri, null, null) }.getOrDefault(false)
        }
    }

    private suspend fun listChildren(
        context: Context,
        treeUri: Uri,
        onLog: (String) -> Unit,
    ): DriveListing {
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        var latest = DriveListing()
        repeat(MAX_LOADING_QUERIES) { attempt ->
            latest = queryChildren(context, treeUri, childrenUri).copy(loadingRetries = attempt)
            if (!latest.loading) return latest
            if (attempt < MAX_LOADING_QUERIES - 1) {
                onLog("Drive is still loading folder contents...")
                delay(LOADING_RETRY_MS)
            }
        }
        return latest
    }

    private fun queryChildren(context: Context, treeUri: Uri, childrenUri: Uri): DriveListing {
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        val files = mutableListOf<DriveDocument>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.string(Document.COLUMN_DOCUMENT_ID) ?: continue
                val mime = cursor.string(Document.COLUMN_MIME_TYPE).orEmpty()
                if (mime == Document.MIME_TYPE_DIR) continue
                val name = cursor.string(Document.COLUMN_DISPLAY_NAME).orEmpty().ifBlank { "book" }
                files += DriveDocument(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    name = name,
                    size = cursor.long(Document.COLUMN_SIZE),
                    lastModified = cursor.long(Document.COLUMN_LAST_MODIFIED),
                )
            }
            return DriveListing(files, loading = cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false))
        }
        return DriveListing(files)
    }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.long(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

    private fun documentKey(doc: DriveDocument): String =
        listOf(doc.uri.toString(), doc.size.toString(), doc.lastModified.toString()).joinToString("|")

    private fun publicDocumentKey(doc: PublicDriveFile): String =
        listOf("public", doc.id, doc.resourceKey.orEmpty()).joinToString("|")

    private data class DriveListing(
        val files: List<DriveDocument> = emptyList(),
        val loading: Boolean = false,
        val loadingRetries: Int = 0,
    )

    private data class DriveDocument(
        val uri: Uri,
        val name: String,
        val size: Long,
        val lastModified: Long,
    )
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
