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

sealed interface ImportState {
    data class Running(val step: Int, val fraction: Float?) : ImportState
    data class Done(val plays: Long, val tracks: Long, val hours: Long) : ImportState
    data class Failed(val notHistoryFile: Boolean) : ImportState
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
        if (!hasColumn(database, "track", "thumb")) database.execSQL("ALTER TABLE track ADD COLUMN thumb TEXT")
        if (!hasColumn(database, "artist", "yt_id")) database.execSQL("ALTER TABLE artist ADD COLUMN yt_id TEXT")
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
            val imported = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                val valid = runCatching { meta(imported, "formato") == FORMAT }.getOrDefault(false)
                if (!valid) {
                    _importState.value = ImportState.Failed(notHistoryFile = true)
                    return@withContext
                }
                ensureSchema(imported)
                fillLocalTime(imported)
                fillKeys(imported)
                _importState.value = ImportState.Running(STEP_INDEX, null)
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
            _importState.value = ImportState.Done(summary.plays, summary.tracks, summary.totalMs / 3_600_000)
        } catch (e: Exception) {
            Timber.e(e, "Spotify history import failed")
            _importState.value = ImportState.Failed(notHistoryFile = e is android.database.sqlite.SQLiteException)
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
            val updateArtist = database.compileStatement("UPDATE artist SET artist_key = ? WHERE id = ?")
            database.rawQuery("SELECT id, name FROM artist WHERE artist_key IS NULL", null).use { cursor ->
                while (cursor.moveToNext()) {
                    updateArtist.bindString(1, keyOf(cursor.getString(1)))
                    updateArtist.bindLong(2, cursor.getLong(0))
                    updateArtist.executeUpdateDelete()
                }
            }
            val updateTrack = database.compileStatement("UPDATE track SET track_key = ? WHERE id = ?")
            var done = 0L
            database.rawQuery(
                "SELECT t.id, t.name, a.artist_key FROM track t JOIN artist a ON a.id = t.artist_id WHERE t.track_key IS NULL",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    updateTrack.bindString(1, keyOf(cursor.getString(1)) + "|" + cursor.getString(2))
                    updateTrack.bindLong(2, cursor.getLong(0))
                    updateTrack.executeUpdateDelete()
                    if (++done % 2000 == 0L) _importState.value = ImportState.Running(STEP_NAMES, done.toFloat() / total)
                }
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
            } finally {
                imported.execSQL("DETACH DATABASE old")
            }
        }.onFailure { Timber.w(it, "Could not keep previous matches") }
    }

    suspend fun clearSpotify(database: MusicDatabase) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            db.close()
            deleteDatabaseFiles(context.getDatabasePath(FILE))
            db = open()
        }
        syncMetrolist(database, force = true)
        bump()
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
                        "WHERE s.isEpisode = 0",
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

    private fun where(filter: HistoryFilter, extra: String? = null): String = buildString {
        append("p.ts_local >= ${filter.from} AND p.ts_local < ${filter.to}")
        filter.source?.let { append(" AND p.source = $it") }
        extra?.let { append(" AND ").append(it) }
    }

    private inline fun <T> query(sql: String, block: (Cursor) -> T): T = db.rawQuery(sql, null).use(block)

    private inline fun <T> rows(sql: String, crossinline map: (Cursor) -> T): List<T> = query(sql) { cursor ->
        val list = ArrayList<T>(cursor.count.coerceAtLeast(0))
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
        "SELECT t.track_key, MAX(t.name), MAX(a.name), SUM(p.ms), SUM(p.ms >= $PLAY_MS) plays, MAX(t.thumb), MAX(t.yt_id), MIN(t.id) " +
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
        val (skipRate, shuffleRate) = if (filter.source == SOURCE_METROLIST) {
            null to null
        } else {
            query(
                "SELECT COUNT(*), SUM(p.skipped = 1 OR p.ms < $PLAY_MS), SUM(p.shuffle = 1), SUM(p.shuffle IS NOT NULL) " +
                    "FROM play p WHERE ${where(spotify)}",
            ) {
                it.moveToFirst()
                val count = it.getLong(0)
                val shuffleKnown = it.getLong(3)
                (if (count > 0) it.getLong(1).toDouble() / count else null) to
                    (if (shuffleKnown > 0) it.getLong(2).toDouble() / shuffleKnown else null)
            }
        }

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

        return Habits(skipRate, shuffleRate, longest, longestStart?.times(DAY_MS), current, platforms)
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

    // ---------------------------------------------------------------- YouTube matching

    /** Finds the YouTube song for a track, searching only the first time. */
    suspend fun resolve(trackId: Long): ResolvedTrack? = withContext(Dispatchers.IO) {
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
            if (runCatching { resolve(trackId) }.getOrNull()?.item != null) changed = true
        }
        changed
    }

    fun artistYtId(key: String): String? =
        db.rawQuery("SELECT yt_id FROM artist WHERE artist_key = ? AND yt_id IS NOT NULL LIMIT 1", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else null }
}
