package dev.exe.kindleconverter

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Serves a converted book over content:// for Share — without copying it. The URI is
 *   content://<pkg>.books/<bookId>/<NiceName>.azw3
 * It streams filesDir/library/<bookId>/book.azw3 and reports the last path segment as the
 * display name, so the recipient gets "<Title>.azw3" with zero extra disk use.
 */
class BookFileProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File? {
        val id = uri.pathSegments.getOrNull(0)?.takeIf { it.matches(Regex("[a-zA-Z0-9-]+")) } ?: return null
        return File(context!!.filesDir, "library/$id/book.azw3").takeIf { it.exists() }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map { col ->
                when (col) {
                    OpenableColumns.DISPLAY_NAME -> uri.lastPathSegment ?: "book.azw3"
                    OpenableColumns.SIZE -> f?.length() ?: 0L
                    else -> null
                }
            })
        }
    }

    override fun getType(uri: Uri) = "application/x-mobipocket-ebook"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
