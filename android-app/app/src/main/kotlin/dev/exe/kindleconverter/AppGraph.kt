package dev.exe.kindleconverter

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.exe.kindleconverter.convert.ConversionManager
import dev.exe.kindleconverter.data.AppDatabase
import dev.exe.kindleconverter.data.LibraryRepository
import dev.exe.kindleconverter.data.MIGRATION_1_2
import dev.exe.kindleconverter.data.MIGRATION_2_3
import dev.exe.kindleconverter.data.SettingsStore
import dev.exe.kindleconverter.data.Storage
import dev.exe.kindleconverter.wasmtime.WasmtimeRuntime

/** Tiny service locator — one shared object graph for both the Activity and the Service. */
class AppGraph private constructor(context: Context) {
    private val app = context.applicationContext
    val db = Room.databaseBuilder(app, AppDatabase::class.java, "library.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    val storage = Storage(app)
    val repo = LibraryRepository(app, db.bookDao(), storage)
    val settings = SettingsStore(app)
    val runtime = WasmtimeRuntime(app)
    val conversion = ConversionManager(repo, runtime)

    companion object {
        @Volatile private var instance: AppGraph? = null
        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}

class KindleApp : Application() {
    val graph by lazy { AppGraph.get(this) }
}

val Context.graph: AppGraph get() = AppGraph.get(this)
