package dev.exe.kindleconverter.data

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
) {
    val isReady get() = status == BookStatus.READY && azw3Path != null
}

class Converters {
    @TypeConverter fun statusToString(s: BookStatus) = s.name
    @TypeConverter fun statusFromString(s: String) = BookStatus.valueOf(s)
    @TypeConverter fun stageToString(s: Stage) = s.name
    @TypeConverter fun stageFromString(s: String) = Stage.valueOf(s)
}
