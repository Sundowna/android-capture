package org.brain.capture.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

object CaptureStates {
    const val RECORDING = "recording"
    const val RECORDED = "recorded"
    const val TRANSCRIBED = "transcribed"
    const val SAVED = "saved"
    const val FAILED = "failed"
}

/**
 * One capture (for now: one voice recording). [id] is the `capture_id` written into the note's
 * frontmatter, which lets the app find a note again after it was moved or renamed in Obsidian.
 * Each step's result is stored, so a retry never repeats finished work (no second transcription,
 * no duplicate audio file, no second note).
 */
@Entity(tableName = "captures")
data class Capture(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val state: String,
    val stagingPath: String? = null,
    /** Id of the capture whose note this recording is added to. */
    val appendTo: String? = null,
    val transcript: String? = null,
    val audioSeconds: Double = 0.0,
    val modelLoadMs: Long = 0,
    val transcribeMs: Long = 0,
    val chunks: Int = 0,
    val warning: String? = null,
    val audioVaultName: String? = null,
    /** Vault-relative path of this capture's own note (null for additions). */
    val notePath: String? = null,
    /** True once this addition has been appended to its target note. */
    val appended: Boolean = false,
    val error: String? = null,
    val lastActivityAt: Long = createdAt,
)

/** Backup taken before the app rewrites a note, so an interrupted edit can be undone. */
@Entity(tableName = "pending_edits")
data class PendingEdit(
    @PrimaryKey val captureId: String,
    val notePath: String,
    val original: String,
    val replacement: String,
)

@Dao
interface CaptureDao {
    @Upsert suspend fun upsert(c: Capture)
    @Query("SELECT * FROM captures WHERE id = :id") suspend fun get(id: String): Capture?
    @Query("SELECT * FROM captures WHERE stagingPath = :path LIMIT 1") suspend fun byStaging(path: String): Capture?
    @Query("SELECT * FROM captures WHERE notePath = :path LIMIT 1") suspend fun byNote(path: String): Capture?

    /** Notes shown as cards: saved captures with their own note, most recently active first. */
    @Query("""SELECT * FROM captures WHERE state = 'saved' AND appendTo IS NULL AND notePath IS NOT NULL
              ORDER BY lastActivityAt DESC LIMIT :limit""")
    fun cards(limit: Int): Flow<List<Capture>>

    @Query("SELECT COUNT(*) FROM captures WHERE state = 'saved' AND appendTo IS NULL AND notePath IS NOT NULL")
    fun cardCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM captures") suspend fun count(): Int
    @Query("UPDATE captures SET lastActivityAt = :at WHERE id = :id") suspend fun touch(id: String, at: Long)
    @Query("UPDATE captures SET notePath = :path WHERE id = :id") suspend fun moveNote(id: String, path: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEdit(e: PendingEdit)
    @Query("DELETE FROM pending_edits WHERE captureId = :id") suspend fun clearEdit(id: String)
    @Query("SELECT * FROM pending_edits") suspend fun pendingEdits(): List<PendingEdit>
}

@Database(entities = [Capture::class, PendingEdit::class], version = 1, exportSchema = false)
abstract class CaptureDb : RoomDatabase() {
    abstract fun dao(): CaptureDao

    companion object {
        @Volatile private var instance: CaptureDb? = null
        fun get(ctx: Context): CaptureDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, CaptureDb::class.java, "captures.db").build()
                .also { instance = it }
        }
    }
}
