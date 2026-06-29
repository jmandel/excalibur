package com.joshuamandel.excalibur.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class BookStatus { IMPORTED, QUEUED, CONVERTING, READY, NEEDS_RECONVERT, ERROR }

/** Coarse conversion stages shown on the "stage rail". DONE = finished. */
enum class Stage { IMPORT, PARSE, LAYOUT, WRITE, DONE }

@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String,
    val title: String,
    val author: String = "",
    val originalName: String,
    val ext: String,
    val originalSize: Long = 0,
    val status: BookStatus = BookStatus.IMPORTED,
    val profile: String = "kindle_oasis",
    val azw3Path: String? = null,
    val azw3Size: Long = 0,
    val error: String = "",
    val stage: Stage = Stage.IMPORT,
    val stageLabel: String = "",
    val createdAt: Long = 0,
    val convertedAt: Long = 0,
    /** Comma-separated user tags (for organizing the library / future Kindle collections). */
    val tags: String = "",
    /** SHA-256 of the imported source file — used to dedupe re-imports of the same file. */
    val contentHash: String = "",
) {
    val isReady get() = status == BookStatus.READY && azw3Path != null
    val tagSet get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

class Converters {
    @TypeConverter fun statusToString(s: BookStatus) = s.name
    @TypeConverter fun statusFromString(s: String) = BookStatus.valueOf(s)
    @TypeConverter fun stageToString(s: Stage) = s.name
    @TypeConverter fun stageFromString(s: String) = Stage.valueOf(s)
}
