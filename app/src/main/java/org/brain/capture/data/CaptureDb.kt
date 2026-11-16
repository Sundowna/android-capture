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
    const val DISCARDED = "discarded"
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
    /** "voice", "link", "text" or "file". */
    val kind: String = "voice",
    /** URL or file SHA-256 of a share, to spot the same thing being shared twice. */
    val fingerprint: String? = null,
    /** For shares: JSON with source app name and saved files (name, type, size, pages, thumbnail). */
    val details: String? = null,
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
    /** Voice cleanup outcome: null = not tried yet, else "done", "rejected: …", "skipped: …" or "failed: …". */
    val cleanup: String? = null,
    /** Capture type: "idea" (tidy into bullets), "task" (checklist + due date) or "private". */
    val type: String = "idea",
    /** Clean version from the cleanup step (markdown bullets, with a "# Title" first line). */
    val clean: String? = null,
) {
    /** Private captures stay inside the app: never written to the vault. */
    val isPrivate: Boolean get() = type == CaptureTypes.PRIVATE
}

object CaptureTypes {
    const val IDEA = "idea"
    const val TASK = "task"
    const val PRIVATE = "private"
}

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
    @Query("""SELECT * FROM captures WHERE state = 'saved' AND appendTo IS NULL AND (notePath IS NOT NULL OR type = 'private')
              ORDER BY lastActivityAt DESC LIMIT :limit""")
    fun cards(limit: Int): Flow<List<Capture>>

    @Query("SELECT COUNT(*) FROM captures WHERE state = 'saved' AND appendTo IS NULL AND (notePath IS NOT NULL OR type = 'private')")
    fun cardCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM captures") suspend fun count(): Int

    /** Voice captures not yet in the vault: shown as live cards above the list. */
    @Query("SELECT * FROM captures WHERE kind = 'voice' AND state IN ('recording','recorded','transcribed','failed') ORDER BY createdAt DESC")
    fun inProgress(): Flow<List<Capture>>
    @Query("DELETE FROM captures WHERE state = 'discarded'") suspend fun deleteDiscarded()
    @Query("DELETE FROM captures WHERE id = :id OR appendTo = :id") suspend fun deleteWithAdditions(id: String)
    @Query("SELECT * FROM captures WHERE appendTo = :id") suspend fun additions(id: String): List<Capture>

    /** A saved share of the same URL or file since [since], if any. */
    @Query("SELECT * FROM captures WHERE fingerprint = :fp AND state = 'saved' AND createdAt >= :since ORDER BY createdAt DESC LIMIT 1")
    suspend fun recentShare(fp: String, since: Long): Capture?

    /** Shares staged but not yet in the vault (interrupted or failed). */
    @Query("SELECT * FROM captures WHERE kind != 'voice' AND state != 'saved' ORDER BY createdAt")
    suspend fun pendingShares(): List<Capture>
    @Query("UPDATE captures SET lastActivityAt = :at WHERE id = :id") suspend fun touch(id: String, at: Long)
    @Query("UPDATE captures SET notePath = :path WHERE id = :id") suspend fun moveNote(id: String, path: String)

    /** Recent voice notes and additions to notes that have not been through the cleanup step yet. */
    @Query("""SELECT * FROM captures WHERE kind = 'voice' AND state = 'saved' AND cleanup IS NULL
              AND ((appendTo IS NULL AND (notePath IS NOT NULL OR type = 'private')) OR (appendTo IS NOT NULL AND appended = 1))
              AND createdAt >= :since ORDER BY createdAt""")
    suspend fun pendingCleanup(since: Long): List<Capture>
    @Query("UPDATE captures SET cleanup = :result WHERE id = :id") suspend fun setCleanup(id: String, result: String)
    @Query("UPDATE captures SET clean = :clean WHERE id = :id") suspend fun setClean(id: String, clean: String)
    @Query("UPDATE captures SET type = :type WHERE id = :id") suspend fun setType(id: String, type: String)
    /** Live view of one capture, for the capture screen. */
    @Query("SELECT * FROM captures WHERE id = :id") fun watch(id: String): Flow<Capture?>
    /** The newest saved capture, for the widget and the quick settings tile. */
    @Query("SELECT * FROM captures WHERE state = 'saved' ORDER BY createdAt DESC LIMIT 1") suspend fun latest(): Capture?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEdit(e: PendingEdit)
    @Query("DELETE FROM pending_edits WHERE captureId = :id") suspend fun clearEdit(id: String)
    @Query("SELECT * FROM pending_edits") suspend fun pendingEdits(): List<PendingEdit>
}

@Database(entities = [Capture::class, PendingEdit::class], version = 5, exportSchema = false)
abstract class CaptureDb : RoomDatabase() {
    abstract fun dao(): CaptureDao

    companion object {
        private val V1_TO_V2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE captures ADD COLUMN kind TEXT NOT NULL DEFAULT 'voice'")
                db.execSQL("ALTER TABLE captures ADD COLUMN fingerprint TEXT")
            }
        }

        private val V2_TO_V3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE captures ADD COLUMN details TEXT")
            }
        }

        private val V3_TO_V4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE captures ADD COLUMN cleanup TEXT")
            }
        }

        private val V4_TO_V5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE captures ADD COLUMN type TEXT NOT NULL DEFAULT 'idea'")
                db.execSQL("ALTER TABLE captures ADD COLUMN clean TEXT")
            }
        }

        @Volatile private var instance: CaptureDb? = null
        fun get(ctx: Context): CaptureDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, CaptureDb::class.java, "captures.db")
                .addMigrations(V1_TO_V2, V2_TO_V3, V3_TO_V4, V4_TO_V5).build()
                .also { instance = it }
        }
    }
}
