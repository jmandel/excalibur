package dev.exe.kindleconverter.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: String): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): Book?

    @Query("SELECT * FROM books WHERE status = 'READY' AND azw3Path IS NOT NULL ORDER BY convertedAt DESC")
    suspend fun ready(): List<Book>

    /** Synchronous variants for the HTTP server thread. */
    @Query("SELECT * FROM books WHERE status = 'READY' AND azw3Path IS NOT NULL ORDER BY convertedAt DESC")
    fun readySync(): List<Book>

    @Query("SELECT * FROM books WHERE id = :id")
    fun getSync(id: String): Book?

    @Query("SELECT * FROM books WHERE status IN ('QUEUED','CONVERTING') ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextPending(): Book?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(book: Book)
    @Update suspend fun update(book: Book)
    @Query("DELETE FROM books WHERE id = :id") suspend fun delete(id: String)
}

@Database(entities = [Book::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}
