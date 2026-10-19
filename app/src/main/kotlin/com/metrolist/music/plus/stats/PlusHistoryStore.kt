/**
 * Metrolist Plus — historial combinado (Spotify importado + reproducciones de Metrolist) en una base
 * SQLite propia del fork (filesDir/plus_historial.db). No toca el esquema de MusicDatabase.
 *
 * El archivo de Spotify lo genera herramientas/historial-spotify/preparar_historial.py en la PC.
 * Las horas se guardan en ts_local con la misma convención que db/Converters.kt: hora local
 * guardada como si fuera UTC, para que strftime() dé la hora del reloj en ambas fuentes.
 */

package com.metrolist.music.plus.stats

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.plus.transfer.PlusMatcher
import com.metrolist.music.plus.transfer.TransferTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.ZoneId

const val SOURCE_SPOTIFY = 0
const val SOURCE_METROLIST = 1

/** A play counts when it lasted at least this long, same default as HistoryDuration. */
const val PLAY_MS = 30_000L

data class HistoryFilter(val from: Long, val to: Long, val source: Int?)

data class StatsSummary(
    val totalMs: Long,
    val plays: Long,
    val tracks: Long,
    val artists: Long,
    val firstTs: Long?,
    val lastTs: Long?,
)

data class TopEntry(
    val key: String,
    val title: String,
    val subtitle: String?,
    val ms: Long,
    val plays: Long,
    val thumb: String?,
    val ytId: String?,
    val trackId: Long?,
)

data class YearArtist(val year: Int, val key: String, val name: String, val ms: Long)

data class Habits(
    val skipRate: Double?,
    val shuffleRate: Double?,
    val longestStreak: Int,
    val streakStart: Long?,
    val currentStreak: Int,
    val platforms: List<Pair<String, Long>>,
    val outputs: List<Pair<String, Long>>,
    val loggingSince: Long?,
)

data class HistoryRow(
    val id: Long,
    val ts: Long,
    val ms: Long,
    val source: Int,
    val trackId: Long,
    val title: String,
    val artist: String,
    val thumb: String?,
)

data class ResolvedTrack(val ytId: String, val item: SongItem?)

data class TeachTrack(
    val key: String,
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val ytId: String?,
    val thumb: String?,
    val match: Int?,
    val taught: Boolean,
    val plays: Long,
)

/** A song for a "Para ti" list: [ytId] is set when it was already found on YouTube. */
data class PickTrack(val trackId: Long, val title: String, val artist: String, val ytId: String?, val thumb: String?)

sealed interface ImportState {
    data class Running(val step: Int, val fraction: Float?) : ImportState
    data class Done(val plays: Long, val tracks: Long, val hours: Long) : ImportState
    data class Failed(val notHistoryFile: Boolean, val detail: String? = null) : ImportState
}

class PlusHistoryStore private constructor(private val context: Context) {
    companion object {
        private const val FILE = "plus_historial.db"
        private const val IMPORT_FILE = "plus_historial_import.db"
        private const val FORMAT = "1"
        private const val DAY_MS = 86_400_000L

        const val STEP_COPY = 0
        const val STEP_TIME = 1
        const val STEP_NAMES = 2
        const val STEP_INDEX = 3

        @Volatile
        private var instance: PlusHistoryStore? = null

        fun get(context: Context): PlusHistoryStore =
            instance ?: synchronized(this) {
                instance ?: PlusHistoryStore(context.applicationContext).also { instance = it }
            }

        private const val HAS_SPOTIFY = "has_spotify"

        private fun prefs(context: Context) = context.getSharedPreferences("plus_stats", Context.MODE_PRIVATE)

        /** Remembered so Home can reserve the "Para ti" row without opening the database. */
        fun hasSpotifyHint(context: Context): Boolean = prefs(context).getBoolean(HAS_SPOTIFY, false)

        /**
         * Spotify plays copied into Metrolist's event table carry this marker: whole-second Spotify times + 1 ms
         * and play times ending in 001 ms. Live plays almost never match both, so they can be told apart.
         */
        const val TAUGHT_EVENT = "timestamp % 1000 = 1 AND playTime % 1000 = 1"

        fun keyOf(text: String): String = PlusMatcher.normalize(text).ifEmpty { text.trim().lowercase() }
    }

    private val writeMutex = Mutex()
    private val attemptedThumbs = HashSet<String>()

    @Volatile
    private var db: SQLiteDatabase = open()

    private val _importState = MutableStateFlow<ImportState?>(null)
    val importState: StateFlow<ImportState?> = _importState.asStateFlow()

    private val _version = MutableStateFlow(0)

    /** Changes every time the data changes, so screens know when to reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun open(): SQLiteDatabase {
        val file = context.getDatabasePath(FILE).also { it.parentFile?.mkdirs() }
        val database = SQLiteDatabase.openDatabase(
            file.path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
        )
        // Lets the stats queries run in parallel while the Metrolist mirror is being written.
        database.enableWriteAheadLogging()
        ensureSchema(database)
        return database
    }

    private fun ensureSchema(database: SQLiteDatabase) {
        database.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS artist (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, artist_key TEXT, thumb TEXT)",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS album (id INTEGER PRIMARY KEY, name TEXT NOT NULL, artist_id INTEGER NOT NULL, UNIQUE(name, artist_id))",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS track (id INTEGER PRIMARY KEY, uri TEXT NOT NULL UNIQUE, name TEXT NOT NULL, " +
                "artist_id INTEGER NOT NULL, album_id INTEGER, track_key TEXT, yt_id TEXT)",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS play (id INTEGER PRIMARY KEY, source INTEGER NOT NULL DEFAULT 0, ts_utc INTEGER NOT NULL, " +
                "ts_local INTEGER, ms INTEGER NOT NULL, track_id INTEGER NOT NULL, skipped INTEGER, shuffle INTEGER, " +
                "platform TEXT, reason_end INTEGER)",
        )
        database.execSQL("INSERT OR IGNORE INTO meta(key, value) VALUES ('formato', '$FORMAT')")
        if (!hasColumn(database, "track", "thumb")) database.execSQL("ALTER TABLE track ADD COLUMN thumb TEXT")
        if (!hasColumn(database, "artist", "yt_id")) database.execSQL("ALTER TABLE artist ADD COLUMN yt_id TEXT")
        // Teaching (phase 2): yt_match = 1 found / -1 not found on YouTube; taught = 1 when its plays are in Metrolist.
        if (!hasColumn(database, "track", "yt_match")) database.execSQL("ALTER TABLE track ADD COLUMN yt_match INTEGER")
        if (!hasColumn(database, "track", "taught")) database.execSQL("ALTER TABLE track ADD COLUMN taught INTEGER")
        createIndexes(database)
    }

    private fun createIndexes(database: SQLiteDatabase) {
        database.execSQL("CREATE INDEX IF NOT EXISTS play_ts ON play(ts_local)")
        database.execSQL("CREATE INDEX IF NOT EXISTS play_track ON play(track_id)")
        database.execSQL("CREATE INDEX IF NOT EXISTS play_source ON play(source)")
        database.execSQL("CREATE INDEX IF NOT EXISTS track_key_idx ON track(track_key)")
        database.execSQL("CREATE INDEX IF NOT EXISTS track_artist ON track(artist_id)")
        database.execSQL("CREATE INDEX IF NOT EXISTS artist_key_idx ON artist(artist_key)")
    }

    private fun hasColumn(database: SQLiteDatabase, table: String, column: String): Boolean =
        database.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.any { it == column }
        }

    private fun meta(database: SQLiteDatabase, key: String): String? =
        database.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun setMeta(database: SQLiteDatabase, key: String, value: String) {
        database.execSQL("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)", arrayOf(key, value))
    }

    private fun bump() {
        _version.value += 1
    }

    // ---------------------------------------------------------------- Import

    fun clearImportState() {
        if (_importState.value !is ImportState.Running) _importState.value = null
    }

    suspend fun import(uri: Uri, database: MusicDatabase) = withContext(Dispatchers.IO) {
        if (_importState.value is ImportState.Running) return@withContext
        _importState.value = ImportState.Running(STEP_COPY, null)
        val tmp = context.getDatabasePath(IMPORT_FILE)
        deleteDatabaseFiles(tmp)
        try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            val size = "%.1f MB".format(tmp.length() / 1_000_000.0)
            val header = tmp.inputStream().use { input -> ByteArray(16).also { input.read(it) } }
            if (!String(header, Charsets.ISO_8859_1).startsWith("SQLite format 3")) {
                val start = String(header, Charsets.ISO_8859_1).filter { it in ' '..'~' }
                _importState.value = ImportState.Failed(notHistoryFile = true, detail = "$size, \"$start\"")
                return@withContext
            }
            val imported = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                val format = runCatching { meta(imported, "formato") }
                if (format.getOrNull()?.trim() != FORMAT) {
                    val detail = format.exceptionOrNull()?.message ?: "formato=${format.getOrNull()}"
                    _importState.value = ImportState.Failed(notHistoryFile = true, detail = "$size, $detail")
                    return@withContext
                }
                ensureSchema(imported)
                fillLocalTime(imported)
                fillKeys(imported)
                _importState.value = ImportState.Running(STEP_INDEX, null)
                runCatching { PlusListenLog.mergeFrom(context, imported) }.onFailure { Timber.w(it, "Could not merge habits") }
                keepResolvedSongs(imported)
            } finally {
                imported.close()
            }

            writeMutex.withLock {
                db.close()
                try {
                    val target = context.getDatabasePath(FILE)
                    deleteDatabaseFiles(target)
                    if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
                } finally {
                    db = open()
                }
            }
            syncMetrolist(database, force = true)

            val summary = summary(HistoryFilter(0, Long.MAX_VALUE, SOURCE_SPOTIFY))
            rememberHasSpotify(summary.plays > 0)
            _importState.value = ImportState.Done(summary.plays, summary.tracks, summary.totalMs / 3_600_000)
        } catch (e: Exception) {
            Timber.e(e, "Spotify history import failed")
            _importState.value = ImportState.Failed(notHistoryFile = false, detail = e.toString().take(300))
        } finally {
            deleteDatabaseFiles(tmp)
            bump()
        }
    }

    /** Converts Spotify's UTC timestamps to the phone's wall clock, with each period's real offset (DST included). */
    private fun fillLocalTime(database: SQLiteDatabase) {
        _importState.value = ImportState.Running(STEP_TIME, null)
        val (min, max) = database.rawQuery(
            "SELECT MIN(ts_utc), MAX(ts_utc) FROM play WHERE source = $SOURCE_SPOTIFY",
            null,
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) to it.getLong(1) else return }
        val rules = ZoneId.systemDefault().rules
        database.beginTransaction()
        try {
            var start = min
            while (start <= max) {
                val instant = Instant.ofEpochMilli(start)
                val offsetMs = rules.getOffset(instant).totalSeconds * 1000L
                val next = rules.nextTransition(instant)?.instant?.toEpochMilli() ?: Long.MAX_VALUE
                val end = minOf(next, max + 1)
                database.execSQL(
                    "UPDATE play SET ts_local = ts_utc + ? WHERE source = $SOURCE_SPOTIFY AND ts_utc >= ? AND ts_utc < ?",
                    arrayOf<Any>(offsetMs, start, end),
                )
                start = end
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun fillKeys(database: SQLiteDatabase) {
        val total = database.rawQuery("SELECT COUNT(*) FROM track WHERE track_key IS NULL", null)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
            .coerceAtLeast(1)
        _importState.value = ImportState.Running(STEP_NAMES, 0f)
        database.beginTransaction()
        try {
            // Read first, then write: updating rows a filtered cursor is still reading makes it skip rows.
            val artists = rows("SELECT id, name FROM artist WHERE artist_key IS NULL", database) { it.getLong(0) to it.getString(1) }
            val artistKeys = HashMap<Long, String>()
            database.rawQuery("SELECT id, artist_key FROM artist WHERE artist_key IS NOT NULL", null).use { cursor ->
                while (cursor.moveToNext()) artistKeys[cursor.getLong(0)] = cursor.getString(1)
            }
            val updateArtist = database.compileStatement("UPDATE artist SET artist_key = ? WHERE id = ?")
            artists.forEach { (id, name) ->
                val key = keyOf(name)
                artistKeys[id] = key
                updateArtist.bindString(1, key)
                updateArtist.bindLong(2, id)
                updateArtist.executeUpdateDelete()
            }
            val updateTrack = database.compileStatement("UPDATE track SET track_key = ? WHERE id = ?")
            var done = 0L
            val tracks = rows("SELECT id, name, artist_id FROM track WHERE track_key IS NULL", database) {
                Triple(it.getLong(0), it.getString(1), it.getLong(2))
            }
            tracks.forEach { (id, name, artistId) ->
                updateTrack.bindString(1, keyOf(name) + "|" + artistKeys[artistId].orEmpty())
                updateTrack.bindLong(2, id)
                updateTrack.executeUpdateDelete()
                if (++done % 2000 == 0L) _importState.value = ImportState.Running(STEP_NAMES, done.toFloat() / total)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** Keeps the YouTube matches and pictures already found, so re-importing doesn't search them again. */
    private fun keepResolvedSongs(imported: SQLiteDatabase) {
        val old = context.getDatabasePath(FILE)
        if (!old.exists()) return
        runCatching {
            imported.execSQL("ATTACH DATABASE ? AS old", arrayOf(old.path))
            try {
                imported.execSQL(
                    "UPDATE track SET yt_id = (SELECT o.yt_id FROM old.track o WHERE o.uri = track.uri), " +
                        "thumb = (SELECT o.thumb FROM old.track o WHERE o.uri = track.uri) " +
                        "WHERE uri IN (SELECT uri FROM old.track WHERE yt_id IS NOT NULL AND uri NOT LIKE 'yt:%')",
                )
                imported.execSQL(
                    "UPDATE artist SET yt_id = (SELECT o.yt_id FROM old.artist o WHERE o.name = artist.name), " +
                        "thumb = (SELECT o.thumb FROM old.artist o WHERE o.name = artist.name) " +
                        "WHERE name IN (SELECT name FROM old.artist WHERE thumb IS NOT NULL)",
                )
                runCatching {
                    imported.execSQL(
                        "UPDATE track SET yt_match = (SELECT o.yt_match FROM old.track o WHERE o.uri = track.uri), " +
                            "taught = (SELECT o.taught FROM old.track o WHERE o.uri = track.uri) " +
                            "WHERE uri IN (SELECT uri FROM old.track WHERE yt_match IS NOT NULL OR taught IS NOT NULL)",
                    )
                }
            } finally {
                imported.execSQL("DETACH DATABASE old")
            }
        }.onFailure { Timber.w(it, "Could not keep previous matches") }
    }

    suspend fun clearSpotify(database: MusicDatabase) = withContext(Dispatchers.IO) {
        // Also takes back the Spotify plays that were taught to Metrolist.
        runCatching {
            database.withTransaction {
                val main = openHelper.writableDatabase
                main.execSQL(
                    "UPDATE song SET totalPlayTime = MAX(0, totalPlayTime - COALESCE((SELECT SUM(e.playTime) FROM event e " +
                        "WHERE e.songId = song.id AND ${TAUGHT_EVENT.replace("timestamp", "e.timestamp").replace("playTime", "e.playTime")}), 0)) " +
                        "WHERE id IN (SELECT songId FROM event WHERE $TAUGHT_EVENT)",
                )
                main.execSQL("DELETE FROM event WHERE $TAUGHT_EVENT")
            }
        }.onFailure { Timber.e(it, "Could not remove taught plays") }
        writeMutex.withLock {
            db.close()
            deleteDatabaseFiles(context.getDatabasePath(FILE))
            db = open()
        }
        rememberHasSpotify(false)
        syncMetrolist(database, force = true)
        bump()
    }

    /** Copies the whole history (and the habit log) to a file the user picked. */
    suspend fun export(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val copy = context.getDatabasePath("plus_historial_export.db")
        deleteDatabaseFiles(copy)
        try {
            writeMutex.withLock {
                // Closing merges the WAL into the main file, so the copy is complete.
                db.close()
                try {
                    context.getDatabasePath(FILE).copyTo(copy, overwrite = true)
                } finally {
                    db = open()
                }
            }
            val exported = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                PlusListenLog.copyInto(context, exported)
            } finally {
                exported.close()
            }
            context.contentResolver.openOutputStream(uri, "wt")!!.use { output ->
                copy.inputStream().use { it.copyTo(output, 1 shl 16) }
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "History export failed")
            false
        } finally {
            deleteDatabaseFiles(copy)
        }
    }

    fun rememberHasSpotify(value: Boolean) {
        val prefs = prefs(context)
        if (prefs.getBoolean(HAS_SPOTIFY, false) != value) prefs.edit().putBoolean(HAS_SPOTIFY, value).apply()
    }

    private fun deleteDatabaseFiles(file: File) {
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    fun hasSpotify(): Boolean =
        db.rawQuery("SELECT 1 FROM play WHERE source = $SOURCE_SPOTIFY LIMIT 1", null).use { it.moveToFirst() }

    // ---------------------------------------------------------------- Metrolist mirror

    /**
     * Copies Metrolist's listening history into this database. It's rebuilt entirely when it changed,
     * because clearing history, time transfer or deleting songs remove events in place.
     */
    suspend fun syncMetrolist(database: MusicDatabase, force: Boolean = false) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val source = database.openHelper.readableDatabase
            val fingerprint = source.query("SELECT COUNT(*), COALESCE(MAX(id), 0), COALESCE(SUM(playTime), 0) FROM event").use {
                it.moveToFirst()
                "${it.getLong(0)}:${it.getLong(1)}:${it.getLong(2)}"
            }
            if (!force && fingerprint == meta(db, "metrolist")) return@withLock

            val artistIds = HashMap<String, Long>()
            val albumIds = HashMap<String, Long>()
            val trackIds = HashMap<String, Long>()
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM play WHERE source = $SOURCE_METROLIST")
                val insertPlay = db.compileStatement(
                    "INSERT INTO play(source, ts_utc, ts_local, ms, track_id, platform) VALUES ($SOURCE_METROLIST, ?, ?, ?, ?, 'metrolist')",
                )
                source.query(
                    "SELECT e.timestamp, e.playTime, s.id, s.title, s.thumbnailUrl, s.albumName, a.id, a.name, a.thumbnailUrl " +
                        "FROM event e JOIN song s ON s.id = e.songId " +
                        "LEFT JOIN song_artist_map m ON m.songId = s.id AND m.position = 0 " +
                        "LEFT JOIN artist a ON a.id = m.artistId " +
                        "WHERE s.isEpisode = 0 AND NOT ($TAUGHT_EVENT)",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val songId = cursor.getString(2)
                        val trackId = trackIds.getOrPut(songId) {
                            val artistName = cursor.getStringOrNull(7)?.takeIf { it.isNotBlank() } ?: "?"
                            val artistYtId = cursor.getStringOrNull(6)?.takeIf { it.startsWith("UC") }
                            val artistId = artistIds.getOrPut(artistName) {
                                upsertArtist(artistName, artistYtId, cursor.getStringOrNull(8))
                            }
                            val albumId = cursor.getStringOrNull(5)?.takeIf { it.isNotBlank() }?.let { album ->
                                albumIds.getOrPut("$album|$artistId") { upsertAlbum(album, artistId) }
                            }
                            upsertTrack(
                                uri = "yt:$songId",
                                name = cursor.getString(3),
                                artistId = artistId,
                                artistKey = keyOf(artistName),
                                albumId = albumId,
                                ytId = songId,
                                thumb = cursor.getStringOrNull(4),
                            )
                        }
                        val ts = cursor.getLong(0)
                        insertPlay.bindLong(1, ts)
                        insertPlay.bindLong(2, ts)
                        insertPlay.bindLong(3, cursor.getLong(1))
                        insertPlay.bindLong(4, trackId)
                        insertPlay.executeInsert()
                    }
                }
                setMeta(db, "metrolist", fingerprint)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            bump()
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun upsertArtist(name: String, ytId: String?, thumb: String?): Long {
        db.execSQL(
            "INSERT OR IGNORE INTO artist(name, artist_key) VALUES (?, ?)",
            arrayOf(name, keyOf(name)),
        )
        val id = db.rawQuery("SELECT id FROM artist WHERE name = ?", arrayOf(name)).use { it.moveToFirst(); it.getLong(0) }
        if (ytId != null || thumb != null) {
            db.execSQL(
                "UPDATE artist SET yt_id = COALESCE(yt_id, ?), thumb = COALESCE(thumb, ?) WHERE id = ?",
                arrayOf<Any?>(ytId, thumb, id),
            )
        }
        return id
    }

    private fun upsertAlbum(name: String, artistId: Long): Long {
        db.execSQL("INSERT OR IGNORE INTO album(name, artist_id) VALUES (?, ?)", arrayOf<Any>(name, artistId))
        return db.rawQuery("SELECT id FROM album WHERE name = ? AND artist_id = ?", arrayOf(name, artistId.toString()))
            .use { it.moveToFirst(); it.getLong(0) }
    }

    private fun upsertTrack(uri: String, name: String, artistId: Long, artistKey: String, albumId: Long?, ytId: String, thumb: String?): Long {
        db.execSQL(
            "INSERT OR IGNORE INTO track(uri, name, artist_id, album_id, track_key, yt_id, thumb) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(uri, name, artistId, albumId, keyOf(name) + "|" + artistKey, ytId, thumb),
        )
        return db.rawQuery("SELECT id FROM track WHERE uri = ?", arrayOf(uri)).use { it.moveToFirst(); it.getLong(0) }
    }

    // ---------------------------------------------------------------- Queries

    /** The track to play for a group of versions of the same song: a matched one if there is any. */
    private val REPRESENTATIVE = "COALESCE(MIN(CASE WHEN t.yt_id IS NOT NULL THEN t.id END), MIN(t.id))"

    private fun where(filter: HistoryFilter, extra: String? = null): String = buildString {
        append("p.ts_local >= ${filter.from} AND p.ts_local < ${filter.to}")
        filter.source?.let { append(" AND p.source = $it") }
        extra?.let { append(" AND ").append(it) }
    }

    private inline fun <T> query(sql: String, block: (Cursor) -> T): T = db.rawQuery(sql, null).use(block)

    private inline fun <T> rows(sql: String, database: SQLiteDatabase = db, map: (Cursor) -> T): List<T> =
        database.rawQuery(sql, null).use { cursor ->
            val list = ArrayList<T>()
            while (cursor.moveToNext()) list += map(cursor)
            list
        }

    fun bounds(source: Int?): Pair<Long, Long>? = query(
        "SELECT MIN(ts_local), MAX(ts_local) FROM play p" + (source?.let { " WHERE p.source = $it" } ?: ""),
    ) { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) to it.getLong(1) else null }

    fun summary(filter: HistoryFilter): StatsSummary = query(
        "SELECT COALESCE(SUM(p.ms), 0), COALESCE(SUM(p.ms >= $PLAY_MS), 0), COUNT(DISTINCT t.track_key), " +
            "COUNT(DISTINCT a.artist_key), MIN(p.ts_local), MAX(p.ts_local) " +
            "FROM play p JOIN track t ON t.id = p.track_id JOIN artist a ON a.id = t.artist_id WHERE ${where(filter)}",
    ) {
        it.moveToFirst()
        StatsSummary(
            totalMs = it.getLong(0),
            plays = it.getLong(1),
            tracks = it.getLong(2),
            artists = it.getLong(3),
            firstTs = if (it.isNull(4)) null else it.getLong(4),
            lastTs = if (it.isNull(5)) null else it.getLong(5),
        )
    }

    /** Listening time grouped by a strftime() pattern over the local time. */
    fun timeBy(filter: HistoryFilter, pattern: String): Map<String, Long> = rows(
        "SELECT strftime('$pattern', p.ts_local / 1000, 'unixepoch') b, SUM(p.ms) FROM play p WHERE ${where(filter)} GROUP BY b",
    ) { it.getString(0) to it.getLong(1) }.toMap()

    /** 7×24 matrix: [day of week, 0 = Monday][hour]. */
    fun heatmap(filter: HistoryFilter): Array<LongArray> {
        val grid = Array(7) { LongArray(24) }
        query(
            "SELECT CAST(strftime('%w', p.ts_local / 1000, 'unixepoch') AS INTEGER), " +
                "CAST(strftime('%H', p.ts_local / 1000, 'unixepoch') AS INTEGER), SUM(p.ms) " +
                "FROM play p WHERE ${where(filter)} GROUP BY 1, 2",
        ) { cursor ->
            while (cursor.moveToNext()) {
                val day = (cursor.getInt(0) + 6) % 7
                grid[day][cursor.getInt(1)] = cursor.getLong(2)
            }
        }
        return grid
    }

    fun topArtists(filter: HistoryFilter, limit: Int): List<TopEntry> = rows(
        "SELECT a.artist_key, MAX(a.name), SUM(p.ms) total, SUM(p.ms >= $PLAY_MS), MAX(a.thumb), MAX(a.yt_id) " +
            "FROM play p JOIN track t ON t.id = p.track_id JOIN artist a ON a.id = t.artist_id " +
            "WHERE ${where(filter)} GROUP BY a.artist_key ORDER BY total DESC LIMIT $limit",
    ) { TopEntry(it.getString(0), it.getString(1), null, it.getLong(2), it.getLong(3), it.getStringOrNull(4), it.getStringOrNull(5), null) }

    fun topTracks(filter: HistoryFilter, limit: Int): List<TopEntry> = rows(
        "SELECT t.track_key, MAX(t.name), MAX(a.name), SUM(p.ms), SUM(p.ms >= $PLAY_MS) plays, MAX(t.thumb), MAX(t.yt_id), $REPRESENTATIVE " +
            "FROM play p JOIN track t ON t.id = p.track_id JOIN artist a ON a.id = t.artist_id " +
            "WHERE ${where(filter)} GROUP BY t.track_key ORDER BY plays DESC, SUM(p.ms) DESC LIMIT $limit",
    ) {
        TopEntry(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4), it.getStringOrNull(5), it.getStringOrNull(6), it.getLong(7))
    }

    fun topAlbums(filter: HistoryFilter, limit: Int): List<TopEntry> = rows(
        "SELECT lower(al.name) || '|' || a.artist_key k, MAX(al.name), MAX(a.name), SUM(p.ms) total, SUM(p.ms >= $PLAY_MS), MAX(t.thumb) " +
            "FROM play p JOIN track t ON t.id = p.track_id JOIN album al ON al.id = t.album_id JOIN artist a ON a.id = al.artist_id " +
            "WHERE ${where(filter)} GROUP BY k ORDER BY total DESC LIMIT $limit",
    ) { TopEntry(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4), it.getStringOrNull(5), null, null) }

    /** Time per artist and year, only artists with at least one hour that year. */
    fun artistsByYear(source: Int?): List<YearArtist> = rows(
        "SELECT CAST(strftime('%Y', p.ts_local / 1000, 'unixepoch') AS INTEGER) y, a.artist_key, MAX(a.name), SUM(p.ms) total " +
            "FROM play p JOIN track t ON t.id = p.track_id JOIN artist a ON a.id = t.artist_id " +
            "WHERE ${where(HistoryFilter(0, Long.MAX_VALUE, source))} GROUP BY y, a.artist_key HAVING total >= 3600000",
    ) { YearArtist(it.getInt(0), it.getString(1), it.getString(2), it.getLong(3)) }

    /** Songs heard for the first time each year. */
    fun discoveredByYear(source: Int?): Map<Int, Long> = rows(
        "SELECT CAST(strftime('%Y', first / 1000, 'unixepoch') AS INTEGER) y, COUNT(*) FROM (" +
            "SELECT MIN(p.ts_local) first FROM play p JOIN track t ON t.id = p.track_id " +
            "WHERE ${where(HistoryFilter(0, Long.MAX_VALUE, source), "p.ms >= $PLAY_MS")} GROUP BY t.track_key) GROUP BY y",
    ) { it.getInt(0) to it.getLong(1) }.toMap()

    fun habits(filter: HistoryFilter, today: Long): Habits {
        // Skips and shuffle only exist in Spotify's export.
        val spotify = filter.copy(source = SOURCE_SPOTIFY)
        val spotifyCounts = if (filter.source == SOURCE_METROLIST) {
            longArrayOf(0, 0, 0, 0)
        } else {
            query(
                "SELECT COUNT(*), COALESCE(SUM(p.skipped = 1 OR p.ms < $PLAY_MS), 0), COALESCE(SUM(p.shuffle = 1), 0), " +
                    "COALESCE(SUM(p.shuffle IS NOT NULL), 0) FROM play p WHERE ${where(spotify)}",
            ) {
                it.moveToFirst()
                longArrayOf(it.getLong(0), it.getLong(1), it.getLong(2), it.getLong(3))
            }
        }
        val log = if (filter.source == SOURCE_SPOTIFY) null else runCatching { PlusListenLog.summary(context, filter.from, filter.to) }.getOrNull()
        val count = spotifyCounts[0] + (log?.count ?: 0)
        val shuffleKnown = spotifyCounts[3] + (log?.count ?: 0)
        val skipRate = if (count > 0) (spotifyCounts[1] + (log?.skipped ?: 0)).toDouble() / count else null
        val shuffleRate = if (shuffleKnown > 0) (spotifyCounts[2] + (log?.shuffled ?: 0)).toDouble() / shuffleKnown else null

        val days = rows("SELECT DISTINCT p.ts_local / $DAY_MS FROM play p WHERE ${where(filter, "p.ms >= $PLAY_MS")} ORDER BY 1") { it.getLong(0) }
        var longest = 0
        var longestStart: Long? = null
        var run = 0
        var runStart = 0L
        var previous = Long.MIN_VALUE
        for (day in days) {
            if (day == previous + 1) run++ else { run = 1; runStart = day }
            if (run > longest) { longest = run; longestStart = runStart }
            previous = day
        }
        val todayIndex = today / DAY_MS
        val current = if (days.isNotEmpty() && days.last() >= todayIndex - 1) {
            var count = 1
            for (i in days.size - 1 downTo 1) if (days[i] - days[i - 1] == 1L) count++ else break
            count
        } else {
            0
        }

        val platforms = rows(
            "SELECT COALESCE(p.platform, 'otro'), SUM(p.ms) total FROM play p WHERE ${where(filter)} GROUP BY 1 ORDER BY total DESC",
        ) { it.getString(0) to it.getLong(1) }

        return Habits(
            skipRate,
            shuffleRate,
            longest,
            longestStart?.times(DAY_MS),
            current,
            platforms,
            log?.outputs.orEmpty(),
            runCatching { PlusListenLog.firstRecord(context) }.getOrNull(),
        )
    }

    /** One page of the full history, newest first. Pass the last row to get the next page. */
    fun history(source: Int?, after: HistoryRow?, limit: Int = 100): List<HistoryRow> {
        val cursorCondition = after?.let { "(p.ts_local < ${it.ts} OR (p.ts_local = ${it.ts} AND p.id < ${it.id}))" }
        return rows(
            "SELECT p.id, p.ts_local, p.ms, p.source, t.id, t.name, a.name, t.thumb FROM play p " +
                "JOIN track t ON t.id = p.track_id JOIN artist a ON a.id = t.artist_id " +
                "WHERE ${where(HistoryFilter(0, Long.MAX_VALUE, source), listOfNotNull("p.ms >= $PLAY_MS", cursorCondition).joinToString(" AND "))} " +
                "ORDER BY p.ts_local DESC, p.id DESC LIMIT $limit",
        ) { HistoryRow(it.getLong(0), it.getLong(1), it.getLong(2), it.getInt(3), it.getLong(4), it.getString(5), it.getString(6), it.getStringOrNull(7)) }
    }

    // ---------------------------------------------------------------- "Para ti"

    private inline fun picks(sql: String): List<PickTrack> = rows(sql) {
        PickTrack(it.getLong(0), it.getString(1), it.getString(2), it.getStringOrNull(3), it.getStringOrNull(4))
    }

    private fun pickSelect(score: String) =
        "SELECT $REPRESENTATIVE, MAX(t.name), MAX(a.name), MAX(t.yt_id), MAX(t.thumb), $score score " +
            "FROM play p JOIN track t ON t.id = p.track_id JOIN artist a ON a.id = t.artist_id "

    /** Songs usually played around [hour] on weekdays or weekends since [since]. */
    fun forHour(hour: Int, weekend: Boolean, since: Long, limit: Int): List<PickTrack> {
        val hours = listOf((hour + 23) % 24, hour, (hour + 1) % 24).joinToString { "'%02d'".format(it) }
        val days = if (weekend) "('0', '6')" else "('1', '2', '3', '4', '5')"
        return picks(
            pickSelect("COUNT(*)") +
                "WHERE p.ms >= $PLAY_MS AND p.ts_local >= $since " +
                "AND strftime('%H', p.ts_local / 1000, 'unixepoch') IN ($hours) " +
                "AND strftime('%w', p.ts_local / 1000, 'unixepoch') IN $days " +
                "GROUP BY t.track_key ORDER BY score DESC LIMIT $limit",
        )
    }

    /** Favorites from before [oldBefore] that haven't been played since [notSince]. */
    fun rediscover(oldBefore: Long, notSince: Long, limit: Int): List<PickTrack> = picks(
        pickSelect("SUM(p.ts_local < $oldBefore)") +
            "WHERE p.ms >= $PLAY_MS GROUP BY t.track_key " +
            "HAVING score >= 10 AND MAX(p.ts_local) < $notSince ORDER BY score DESC LIMIT $limit",
    )

    fun topPicks(filter: HistoryFilter, limit: Int): List<PickTrack> = picks(
        pickSelect("SUM(p.ms >= $PLAY_MS)") + "WHERE ${where(filter)} GROUP BY t.track_key ORDER BY score DESC LIMIT $limit",
    )

    /** The most played song of the most played artist in [filter], to start a radio from. */
    fun radioSeed(filter: HistoryFilter): Pair<String, PickTrack>? {
        val artist = topArtists(filter, 1).firstOrNull() ?: return null
        val key = artist.key.replace("'", "''")
        val track = picks(
            pickSelect("SUM(p.ms >= $PLAY_MS)") +
                "WHERE ${where(filter)} AND a.artist_key = '$key' GROUP BY t.track_key ORDER BY score DESC LIMIT 1",
        ).firstOrNull() ?: return null
        return artist.title to track
    }

    // ---------------------------------------------------------------- Teaching (phase 2)

    /** Songs with at least [minPlays] Spotify plays, most played first. */
    fun teachList(minPlays: Int): List<TeachTrack> = rows(
        "SELECT t.track_key, MIN(t.id), MAX(t.name), MAX(a.name), MAX(al.name), MAX(t.yt_id), MAX(t.thumb), MAX(t.yt_match), " +
            "MIN(COALESCE(t.taught, 0)), COUNT(*) c FROM play p JOIN track t ON t.id = p.track_id " +
            "JOIN artist a ON a.id = t.artist_id LEFT JOIN album al ON al.id = t.album_id " +
            "WHERE p.source = $SOURCE_SPOTIFY AND p.ms >= $PLAY_MS GROUP BY t.track_key HAVING c >= $minPlays ORDER BY c DESC",
    ) {
        TeachTrack(
            key = it.getString(0),
            trackId = it.getLong(1),
            title = it.getString(2),
            artist = it.getString(3),
            album = it.getStringOrNull(4),
            ytId = it.getStringOrNull(5),
            thumb = it.getStringOrNull(6),
            match = if (it.isNull(7)) null else it.getInt(7),
            taught = it.getInt(8) == 1,
            plays = it.getLong(9),
        )
    }

    /** Points every version of the song to the same YouTube video (or marks it not found). */
    suspend fun setMatch(key: String, ytId: String?, thumb: String?) = writeMutex.withLock {
        if (ytId != null) {
            db.execSQL(
                "UPDATE track SET yt_id = ?, thumb = COALESCE(thumb, ?), yt_match = 1 WHERE track_key = ?",
                arrayOf<Any?>(ytId, thumb, key),
            )
        } else {
            db.execSQL("UPDATE track SET yt_match = -1 WHERE track_key = ? AND yt_id IS NULL", arrayOf(key))
        }
    }

    suspend fun setTaught(ytId: String) = writeMutex.withLock {
        db.execSQL("UPDATE track SET taught = 1 WHERE yt_id = ?", arrayOf(ytId))
    }

    suspend fun resetTaught() = writeMutex.withLock {
        db.execSQL("UPDATE track SET taught = NULL")
    }

    /** Spotify plays (30 s or more) of every track matched to [ytId]: local time and length. */
    fun spotifyPlaysFor(ytId: String): List<Pair<Long, Long>> =
        db.rawQuery(
            "SELECT p.ts_local, p.ms FROM play p JOIN track t ON t.id = p.track_id " +
                "WHERE t.yt_id = ? AND p.source = $SOURCE_SPOTIFY AND p.ms >= $PLAY_MS",
            arrayOf(ytId),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getLong(1)) }
        }

    // ---------------------------------------------------------------- YouTube matching

    /** Finds the YouTube song for a track, searching only the first time. */
    suspend fun resolve(trackId: Long, database: MusicDatabase): ResolvedTrack? = withContext(Dispatchers.IO) {
        val (title, artist, album, ytId) = query(
            "SELECT t.name, a.name, al.name, t.yt_id FROM track t JOIN artist a ON a.id = t.artist_id " +
                "LEFT JOIN album al ON al.id = t.album_id WHERE t.id = $trackId",
        ) {
            if (!it.moveToFirst()) return@withContext null
            listOf(it.getString(0), it.getString(1), it.getStringOrNull(2), it.getStringOrNull(3))
        }
        if (ytId != null) return@withContext ResolvedTrack(ytId, null)
        val match = PlusMatcher.findOnYouTubeMusic(TransferTrack(title!!, listOf(artist!!), album)) ?: return@withContext null
        val item = match.item
        writeMutex.withLock {
            db.execSQL(
                "UPDATE track SET yt_id = ?, thumb = COALESCE(thumb, ?) WHERE id = ?",
                arrayOf<Any?>(item.id, item.thumbnail, trackId),
            )
        }
        runCatching { database.insert(item.toMediaMetadata()) }.onFailure { Timber.w(it, "Could not save ${item.id}") }
        ResolvedTrack(item.id, item)
    }

    /** Looks up pictures for the visible top entries. Returns true when something new was found. */
    suspend fun fillThumbnails(artists: List<TopEntry>, tracks: List<TopEntry>, database: MusicDatabase): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        for (artist in artists) {
            if (artist.thumb != null || !attemptedThumbs.add("a:${artist.key}")) continue
            val local = database.openHelper.readableDatabase.query(
                "SELECT id, thumbnailUrl FROM artist WHERE name = ? COLLATE NOCASE AND thumbnailUrl IS NOT NULL LIMIT 1",
                arrayOf<Any>(artist.title),
            ).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
            val found = local ?: runCatching { PlusMatcher.findArtist(artist.title) }.getOrNull()?.let { it.id to it.thumbnail } ?: continue
            val thumb = found.second ?: continue
            writeMutex.withLock {
                db.execSQL(
                    "UPDATE artist SET thumb = ?, yt_id = COALESCE(yt_id, ?) WHERE artist_key = ?",
                    arrayOf<Any?>(thumb, found.first.takeIf { it.startsWith("UC") }, artist.key),
                )
            }
            changed = true
        }
        for (track in tracks) {
            val trackId = track.trackId ?: continue
            if (track.thumb != null || !attemptedThumbs.add("t:${track.key}")) continue
            if (runCatching { resolve(trackId, database) }.getOrNull()?.item != null) changed = true
        }
        changed
    }

    fun artistYtId(key: String): String? =
        db.rawQuery("SELECT yt_id FROM artist WHERE artist_key = ? AND yt_id IS NOT NULL LIMIT 1", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else null }
}
