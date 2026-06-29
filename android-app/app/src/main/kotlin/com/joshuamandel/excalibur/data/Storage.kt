package com.joshuamandel.excalibur.data

import android.content.Context
import java.io.File

/**
 * The app-managed library on disk — the single source of truth. Every import (open-with
 * or the picker) copies the file in here, so the library is never scattered.
 *
 *   files/library/<id>/original.<ext>   the imported book
 *   files/library/<id>/book.azw3        the converted result
 *   files/library/<id>/viewer/          lazy local HTML preview cache
 */
class Storage(private val context: Context) {
    private val root = File(context.filesDir, "library").apply { mkdirs() }

    fun dir(id: String) = File(root, id).apply { mkdirs() }
    fun original(id: String, ext: String) = File(dir(id), "original.$ext")
    fun converted(id: String) = File(dir(id), "book.azw3")
    fun viewerDir(id: String) = File(dir(id), "viewer").apply { mkdirs() }
    fun workDir(id: String) = File(context.filesDir, "work/$id").apply { mkdirs() }
    fun viewerWorkDir(id: String) = File(context.filesDir, "work/viewer-$id").apply { mkdirs() }
    fun purge(id: String) {
        dir(id).deleteRecursively()
        File(context.filesDir, "work/$id").deleteRecursively()
        File(context.filesDir, "work/viewer-$id").deleteRecursively()
    }
}
