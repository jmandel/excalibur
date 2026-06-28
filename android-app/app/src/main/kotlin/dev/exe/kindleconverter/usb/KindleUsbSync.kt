package dev.exe.kindleconverter.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/*
 * SPIKE: manage a slice of the library on a USB-connected Kindle over MTP.
 *
 * Newer Kindles expose MTP (not USB Mass Storage). Android can be the MTP *host* via
 * android.mtp.MtpDevice + USB-OTG. We keep our books in a dedicated folder on the
 * Kindle — that folder IS the "ours" tag — and reconcile it against the local library:
 * push books that aren't there yet, and delete files we previously put there that are no
 * longer in the library. Each file is named <bookId>.azw3 so we can map it back; the
 * Kindle shows the book's own metadata title, not the filename.
 *
 * This is intentionally a thin model. It needs a physical Kindle + OTG cable to verify.
 */

private const val TAG = "kindle-usb"
private const val ACTION_USB_PERMISSION = "dev.exe.kindleconverter.USB_PERMISSION"
private const val AMAZON_VENDOR_ID = 0x1949
internal const val MTP_ROOT = -1 // 0xFFFFFFFF: the storage root parent in MTP
const val KINDLE_DOCS_FOLDER = "documents"
const val KINDLE_OURS_FOLDER = "Excalibur"

data class SyncResult(val pushed: Int, val deleted: Int, val skipped: Int)

sealed interface SyncOutcome {
    data object NoDevice : SyncOutcome
    data object NoPermission : SyncOutcome
    data object OpenFailed : SyncOutcome
    data object NoStorage : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
    data class Done(val result: SyncResult) : SyncOutcome
}

/**
 * Detect a Kindle purely by vendor id (Lab126/Amazon = 0x1949), exactly like calibre.
 * We deliberately do NOT match on USB interface class: e-ink Kindles expose MTP through a
 * *vendor-specific* interface (0xFF), not the clean still-image/PTP class 6, so a class check
 * both misses real Kindles and risks matching unrelated PTP cameras.
 */
fun findKindle(usb: UsbManager): UsbDevice? =
    usb.deviceList.values.firstOrNull { it.vendorId == AMAZON_VENDOR_ID }

/** One object in a Kindle folder, decoupled from android.mtp types so [reconcile] is plain-JVM testable. */
data class RemoteEntry(val handle: Int, val name: String, val size: Long, val isFolder: Boolean)

/**
 * The folder operations [reconcile] needs. Backed by MTP on-device ([KindleMtp]); a small
 * in-memory fake stands in for it in unit tests, so the sync decision logic can be exercised
 * with no Kindle and no USB stack.
 */
interface KindleStore {
    fun list(parent: Int): List<RemoteEntry>
    fun ensureFolder(parent: Int, name: String): Int
    fun push(parent: Int, name: String, file: File): Boolean
    fun delete(handle: Int): Boolean
}

/** [KindleStore] over a live MTP device + storage. All calls block, so run on a background thread. */
private class KindleMtp(val device: MtpDevice, val storageId: Int) : KindleStore {
    override fun list(parent: Int): List<RemoteEntry> =
        (device.getObjectHandles(storageId, 0, parent) ?: IntArray(0)).asList()
            .mapNotNull { device.getObjectInfo(it) }
            .map { RemoteEntry(it.objectHandle, it.name, it.compressedSize.toLong(), it.format == MtpConstants.FORMAT_ASSOCIATION) }

    private fun child(parent: Int, name: String): RemoteEntry? =
        list(parent).firstOrNull { it.name.equals(name, ignoreCase = true) }

    override fun ensureFolder(parent: Int, name: String): Int {
        child(parent, name)?.takeIf { it.isFolder }?.let { return it.handle }
        val info = MtpObjectInfo.Builder()
            .setStorageId(storageId).setName(name).setFormat(MtpConstants.FORMAT_ASSOCIATION).setParent(parent).build()
        return device.sendObjectInfo(info)?.objectHandle ?: error("Kindle: couldn't create folder \"$name\"")
    }

    override fun push(parent: Int, name: String, file: File): Boolean {
        child(parent, name)?.let { device.deleteObject(it.handle) } // overwrite
        val info = MtpObjectInfo.Builder()
            .setStorageId(storageId).setName(name).setFormat(MtpConstants.FORMAT_UNDEFINED)
            .setParent(parent).setCompressedSize(file.length()).build()
        val created = device.sendObjectInfo(info) ?: return false
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            device.sendObject(created.objectHandle, file.length(), pfd)
        }
    }

    override fun delete(handle: Int): Boolean = device.deleteObject(handle)
}

/**
 * Reconcile the Kindle's documents/Excalibur folder against [wantById]:
 *  - push books whose <id>.azw3 is missing or a different size
 *  - delete any book in the folder whose id is no longer wanted, plus its <id>.sdr sidecar
 *
 * The Kindle drops a sibling "<book>.sdr" folder next to each book (reading position,
 * bookmarks, page index). We preserve it across re-pushes, but remove it when the book
 * itself goes, so orphaned sidecars don't pile up.
 */
internal fun reconcile(store: KindleStore, wantById: Map<String, File>, onLog: (String) -> Unit): SyncResult {
    val docs = store.ensureFolder(MTP_ROOT, KINDLE_DOCS_FOLDER)
    val ours = store.ensureFolder(docs, KINDLE_OURS_FOLDER)
    val children = store.list(ours)
    val books = children.filter { !it.isFolder }.associateBy { it.name }
    // <id>.sdr sidecar folders, keyed by the book id they belong to.
    val sidecars = children.filter { it.isFolder && it.name.endsWith(".sdr") }
        .associateBy { it.name.removeSuffix(".sdr") }

    var pushed = 0; var skipped = 0; var deleted = 0
    for ((id, file) in wantById) {
        val name = "$id.azw3"
        val ex = books[name]
        if (ex != null && ex.size == file.length()) { skipped++; continue }
        onLog("→ pushing ${file.name}")
        if (store.push(ours, name, file)) pushed++ else onLog("  ! push failed for $name")
    }
    for ((name, entry) in books) {
        val id = name.removeSuffix(".azw3")
        if (id !in wantById) {
            onLog("✗ removing $name")
            if (store.delete(entry.handle)) deleted++
            sidecars[id]?.let { store.delete(it.handle) } // drop the orphaned reading-state folder too
        }
    }
    return SyncResult(pushed, deleted, skipped)
}

private suspend fun ensurePermission(context: Context, usb: UsbManager, device: UsbDevice): Boolean {
    if (usb.hasPermission(device)) return true
    return suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { context.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(usb.hasPermission(device))
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val exported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.RECEIVER_NOT_EXPORTED else 0
        ContextCompat.registerReceiver(context, receiver, filter, exported)
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        usb.requestPermission(device, pi)
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
    }
}

/** Full flow: detect → permission → open MTP → reconcile → close. */
suspend fun syncLibraryToKindle(
    context: Context,
    wantById: Map<String, File>,
    onLog: (String) -> Unit,
): SyncOutcome = withContext(Dispatchers.IO) {
    val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val device = findKindle(usb) ?: return@withContext SyncOutcome.NoDevice
    onLog("Found ${device.productName ?: device.deviceName}")
    if (!ensurePermission(context, usb, device)) return@withContext SyncOutcome.NoPermission
    val connection = usb.openDevice(device) ?: return@withContext SyncOutcome.OpenFailed
    val mtp = MtpDevice(device)
    if (!mtp.open(connection)) { connection.close(); return@withContext SyncOutcome.OpenFailed }
    try {
        val storageId = mtp.storageIds?.firstOrNull() ?: return@withContext SyncOutcome.NoStorage
        onLog("Syncing ${wantById.size} books…")
        SyncOutcome.Done(reconcile(KindleMtp(mtp, storageId), wantById, onLog))
    } catch (e: Exception) {
        Log.e(TAG, "sync failed", e)
        SyncOutcome.Failed(e.message ?: e.javaClass.simpleName)
    } finally {
        runCatching { mtp.close() }
        runCatching { connection.close() }
    }
}
