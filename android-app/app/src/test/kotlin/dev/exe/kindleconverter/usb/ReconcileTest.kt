package dev.exe.kindleconverter.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An in-memory MTP responder modeled on real device semantics — the behavioral reference we
 * test [reconcile] against without a Kindle or a USB stack:
 *  - every object has a handle, a parent handle, a name, and a size
 *  - folders are associations (isFolder = true)
 *  - listing is scoped to a parent
 *  - pushing a name that already exists under a parent overwrites it (delete + recreate),
 *    matching how the real MtpDevice path handles a re-push
 *
 * This mirrors the contract of the on-device [KindleMtp] without any android.mtp types.
 */
private class FakeKindleStore : KindleStore {
    private class Obj(var entry: RemoteEntry, val parent: Int)
    private val objects = LinkedHashMap<Int, Obj>()
    private var nextHandle = 1000

    val pushedNames = mutableListOf<String>()
    val deletedHandles = mutableListOf<Int>()

    /** Seed a pre-existing object (a book file or an .sdr folder); returns its handle. */
    fun seed(parent: Int, name: String, size: Long, isFolder: Boolean): Int {
        val h = nextHandle++
        objects[h] = Obj(RemoteEntry(h, name, size, isFolder), parent)
        return h
    }

    override fun list(parent: Int): List<RemoteEntry> =
        objects.values.filter { it.parent == parent }.map { it.entry }

    override fun ensureFolder(parent: Int, name: String): Int =
        objects.values.firstOrNull { it.parent == parent && it.entry.isFolder && it.entry.name == name }
            ?.entry?.handle
            ?: seed(parent, name, 0, isFolder = true)

    override fun push(parent: Int, name: String, file: File): Boolean {
        objects.values.firstOrNull { it.parent == parent && it.entry.name == name }?.let { delete(it.entry.handle) }
        seed(parent, name, file.length(), isFolder = false)
        pushedNames += name
        return true
    }

    override fun delete(handle: Int): Boolean {
        if (objects.remove(handle) == null) return false
        deletedHandles += handle
        return true
    }

    /** A phone's documents/Excalibur/<device> handle, created the way reconcile() would. */
    fun deviceFolder(device: String): Int =
        ensureFolder(ensureFolder(ensureFolder(MTP_ROOT, KINDLE_DOCS_FOLDER), KINDLE_OURS_FOLDER), device)
}

private const val DEVICE = "phoneA"

class ReconcileTest {
    private fun fileOf(bytes: Int): File =
        File.createTempFile("book", ".azw3").apply { writeBytes(ByteArray(bytes)); deleteOnExit() }

    @Test fun pushesMissingBooks() {
        val store = FakeKindleStore()
        val r = reconcile(store, DEVICE, mapOf("a" to fileOf(10), "b" to fileOf(20))) {}
        assertEquals(2, r.pushed)
        assertEquals(0, r.skipped)
        assertEquals(0, r.deleted)
        assertEquals(setOf("a.azw3", "b.azw3"), store.pushedNames.toSet())
    }

    @Test fun skipsUnchangedSameSize() {
        val store = FakeKindleStore()
        store.seed(store.deviceFolder(DEVICE), "a.azw3", 10, isFolder = false)
        val r = reconcile(store, DEVICE, mapOf("a" to fileOf(10))) {}
        assertEquals(0, r.pushed)
        assertEquals(1, r.skipped)
        assertTrue(store.pushedNames.isEmpty())
    }

    @Test fun repushesWhenSizeChanged() {
        val store = FakeKindleStore()
        store.seed(store.deviceFolder(DEVICE), "a.azw3", 10, isFolder = false)
        val r = reconcile(store, DEVICE, mapOf("a" to fileOf(99))) {}
        assertEquals(1, r.pushed)
        assertEquals(0, r.skipped)
        assertEquals(listOf("a.azw3"), store.pushedNames)
    }

    @Test fun deletesUnwantedBookAndItsSidecar() {
        val store = FakeKindleStore()
        val ours = store.deviceFolder(DEVICE)
        val book = store.seed(ours, "gone.azw3", 10, isFolder = false)
        val sidecar = store.seed(ours, "gone.sdr", 0, isFolder = true)
        val r = reconcile(store, DEVICE, emptyMap()) {}
        assertEquals(1, r.deleted)
        assertEquals(0, r.pushed)
        assertTrue("book handle deleted", book in store.deletedHandles)
        assertTrue("orphaned .sdr sidecar deleted", sidecar in store.deletedHandles)
        assertTrue("folder is empty afterwards", store.list(ours).isEmpty())
    }

    @Test fun keepsSidecarOfAWantedBook() {
        val store = FakeKindleStore()
        val ours = store.deviceFolder(DEVICE)
        store.seed(ours, "keep.azw3", 10, isFolder = false)
        val sidecar = store.seed(ours, "keep.sdr", 0, isFolder = true)
        reconcile(store, DEVICE, mapOf("keep" to fileOf(10))) {}
        assertFalse("a kept book's reading-state sidecar must survive", sidecar in store.deletedHandles)
    }

    @Test fun mixedAddSkipDeleteInOnePass() {
        val store = FakeKindleStore()
        val ours = store.deviceFolder(DEVICE)
        store.seed(ours, "keep.azw3", 10, isFolder = false)   // unchanged -> skip
        store.seed(ours, "stale.azw3", 5, isFolder = false)   // not wanted -> delete
        val r = reconcile(store, DEVICE, mapOf("keep" to fileOf(10), "new" to fileOf(7))) {}
        assertEquals(1, r.pushed)   // new.azw3
        assertEquals(1, r.skipped)  // keep.azw3
        assertEquals(1, r.deleted)  // stale.azw3
        assertEquals(listOf("new.azw3"), store.pushedNames)
    }

    /** Two phones, one Kindle: syncing phoneA must never touch phoneB's folder. */
    @Test fun leavesAnotherPhonesFolderAlone() {
        val store = FakeKindleStore()
        val others = store.seed(store.deviceFolder("phoneB"), "theirs.azw3", 10, isFolder = false)
        // phoneA syncs with an empty library — it would delete its own books, but not phoneB's.
        val r = reconcile(store, DEVICE, emptyMap()) {}
        assertEquals(0, r.deleted)
        assertFalse("phoneB's book must survive phoneA's sync", others in store.deletedHandles)
        assertEquals(1, store.list(store.deviceFolder("phoneB")).size)
    }
}
