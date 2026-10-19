/**
 * Metrolist Plus — registro propio de hábitos de escucha en Metrolist: cada canción que empieza a sonar,
 * cuánto se escuchó, si se saltó, si estaba en aleatorio y por dónde sonó.
 *
 * Vive en su propio archivo (databases/plus_habitos.db) para que importar o borrar el historial de
 * Spotify nunca lo toque. El historial normal de Metrolist solo guarda lo que suena 30 s o más.
 */

package com.metrolist.music.plus.stats

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.plus.player.currentAudioOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDateTime

data class LogSummary(
    val count: Long,
    val skipped: Long,
    val shuffled: Long,
    val outputs: List<Pair<String, Long>>,
)

object PlusListenLog {
    private const val FILE = "plus_habitos.db"

    /** Plays shorter than this are accidental taps, not listening. */
    private const val MIN_MS = 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var database: SQLiteDatabase? = null

    private fun db(context: Context): SQLiteDatabase =
        database ?: synchronized(this) {
            database ?: run {
                val file = context.applicationContext.getDatabasePath(FILE).also { it.parentFile?.mkdirs() }
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
                    .also { ensureSchema(it) }
                    .also { database = it }
            }
        }

    internal fun ensureSchema(db: SQLiteDatabase, table: String = "listen") {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $table (id INTEGER PRIMARY KEY, ts_local INTEGER NOT NULL, ms INTEGER NOT NULL, " +
                "duration_ms INTEGER, song_id TEXT NOT NULL, skipped INTEGER NOT NULL, shuffle INTEGER NOT NULL, " +
                "output TEXT, output_name TEXT)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ${table}_unique ON $table(ts_local, song_id)")
    }

    /** Called by MusicService when a song stops playing. Never throws: it must not affect playback. */
    fun record(context: Context, mediaItem: MediaItem, playedMs: Long, shuffle: Boolean) {
        if (playedMs < MIN_MS) return
        val metadata = mediaItem.metadata
        if (metadata?.isEpisode == true) return
        val now = LocalDateTime.now().toStoredMillis()
        scope.launch {
            runCatching {
                val durationMs = metadata?.duration?.takeIf { it > 0 }?.times(1000L)
                // Spotify counts a skip when the song didn't play to the end; here: under 30 s or under half the song.
                val skipped = playedMs < PLAY_MS || (durationMs != null && playedMs < durationMs / 2)
                val output = context.currentAudioOutput()
                db(context).execSQL(
                    "INSERT OR IGNORE INTO listen(ts_local, ms, duration_ms, song_id, skipped, shuffle, output, output_name) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        now,
                        playedMs,
                        durationMs,
                        mediaItem.mediaId,
                        if (skipped) 1 else 0,
                        if (shuffle) 1 else 0,
                        output.kind.name.lowercase(),
                        output.name,
                    ),
                )
            }.onFailure { Timber.w(it, "Could not record listening habit") }
        }
    }

    fun summary(context: Context, from: Long, to: Long): LogSummary {
        val db = db(context)
        val range = "ts_local >= $from AND ts_local < $to"
        val (count, skipped, shuffled) = db.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(skipped), 0), COALESCE(SUM(shuffle), 0) FROM listen WHERE $range",
            null,
        ).use { it.moveToFirst(); Triple(it.getLong(0), it.getLong(1), it.getLong(2)) }
        val outputs = db.rawQuery(
            "SELECT CASE WHEN output = 'bluetooth' AND output_name IS NOT NULL THEN output_name ELSE output END o, SUM(ms) total " +
                "FROM listen WHERE $range GROUP BY o ORDER BY total DESC",
            null,
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add((cursor.getString(0) ?: "phone") to cursor.getLong(1)) }
        }
        return LogSummary(count, skipped, shuffled, outputs)
    }

    fun firstRecord(context: Context): Long? =
        db(context).rawQuery("SELECT MIN(ts_local) FROM listen", null)
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    /** Copies the log into [target] (an export file) as table "listen". */
    internal fun copyInto(context: Context, target: SQLiteDatabase) {
        val path = context.applicationContext.getDatabasePath(FILE).path
        db(context)
        target.execSQL("ATTACH DATABASE ? AS habits", arrayOf(path))
        try {
            ensureSchema(target)
            target.execSQL("INSERT OR IGNORE INTO main.listen SELECT * FROM habits.listen")
        } finally {
            target.execSQL("DETACH DATABASE habits")
        }
    }

    /** Merges the "listen" table of an imported export file into this log, then drops it from that file. */
    internal fun mergeFrom(context: Context, source: SQLiteDatabase) {
        val hasTable = source.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'listen'", null)
            .use { it.moveToFirst() }
        if (!hasTable) return
        val db = db(context)
        db.beginTransaction()
        try {
            source.rawQuery(
                "SELECT ts_local, ms, duration_ms, song_id, skipped, shuffle, output, output_name FROM listen",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO listen(ts_local, ms, duration_ms, song_id, skipped, shuffle, output, output_name) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        Array<Any?>(8) { i -> if (cursor.isNull(i)) null else if (i == 3 || i >= 6) cursor.getString(i) else cursor.getLong(i) },
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        source.execSQL("DROP TABLE listen")
    }
}
