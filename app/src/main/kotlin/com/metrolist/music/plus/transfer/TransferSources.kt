/**
 * Metrolist Plus — leer playlists de otras plataformas (Spotify, Deezer, archivos CSV).
 */

package com.metrolist.music.plus.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class TransferTrack(
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationSec: Int? = null,
)

enum class TransferPlatform { SPOTIFY, DEEZER, FILE }

enum class TransferDestination { NEW_PLAYLIST, LIKED_SONGS }

data class TransferSource(
    val key: String,
    val name: String,
    val platform: TransferPlatform,
    val tracks: List<TransferTrack>,
    val destination: TransferDestination = TransferDestination.NEW_PLAYLIST,
    // Spotify's public embed only lists the first 100 songs.
    val maybeTruncated: Boolean = false,
)

class TransferSourceException(message: String) : Exception(message)

object TransferSources {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val spotifyRegex = Regex("""open\.spotify\.com/(?:[a-z-]+/)?(playlist|album|track)/([A-Za-z0-9]+)""")
    private val spotifyUriRegex = Regex("""spotify:(playlist|album|track):([A-Za-z0-9]+)""")
    private val deezerRegex = Regex("""deezer\.com/(?:[a-z]{2}/)?(playlist|album|track)/(\d+)""")
    private val nextDataRegex = Regex(
        """<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun extractLinks(text: String): List<String> =
        Regex("""(https?://\S+|spotify:(?:playlist|album|track):[A-Za-z0-9]+)""")
            .findAll(text)
            .map { it.value.trimEnd(',', ';', ')', '.') }
            .distinct()
            .toList()

    suspend fun fromLink(link: String): TransferSource = withContext(Dispatchers.IO) {
        resolveSpotify(link)?.let { (type, id) -> return@withContext spotify(type, id) }
        resolveDeezer(link)?.let { (type, id) -> return@withContext deezer(type, id) }

        // Short links (spotify.link, deezer.page.link, link.deezer.com): follow them and look again.
        val (finalUrl, body) = get(link)
        val target = finalUrl + "\n" + body
        resolveSpotify(target)?.let { (type, id) -> return@withContext spotify(type, id) }
        resolveDeezer(target)?.let { (type, id) -> return@withContext deezer(type, id) }
        throw TransferSourceException("unsupported")
    }

    private fun resolveSpotify(text: String) =
        (spotifyRegex.find(text) ?: spotifyUriRegex.find(text))?.let { it.groupValues[1] to it.groupValues[2] }

    private fun resolveDeezer(text: String) =
        deezerRegex.find(text)?.let { it.groupValues[1] to it.groupValues[2] }

    private fun get(url: String): Pair<String, String> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw TransferSourceException("http ${response.code}")
            return response.request.url.toString() to (response.body?.string().orEmpty())
        }
    }

    private fun spotify(type: String, id: String): TransferSource {
        val (_, html) = get("https://open.spotify.com/embed/$type/$id")
        val data = nextDataRegex.find(html)?.groupValues?.get(1)
            ?: throw TransferSourceException("spotify")
        val root = json.parseToJsonElement(data)
        val entity = root.path("props", "pageProps", "state", "data", "entity") as? JsonObject
            ?: findObjectWithKey(root, "trackList")
            ?: findObjectWithKey(root, "subtitle")
            ?: throw TransferSourceException("spotify")

        val name = entity.string("name") ?: entity.string("title") ?: "Spotify"
        val trackList = entity["trackList"] as? JsonArray
        val tracks = if (trackList != null) {
            trackList.mapNotNull { (it as? JsonObject)?.toSpotifyTrack() }
        } else {
            listOfNotNull(entity.toSpotifyTrack())
        }
        if (tracks.isEmpty()) throw TransferSourceException("empty")
        return TransferSource(
            key = "spotify:$type:$id",
            name = name,
            platform = TransferPlatform.SPOTIFY,
            tracks = tracks,
            maybeTruncated = trackList != null && tracks.size >= 100,
        )
    }

    private fun JsonObject.toSpotifyTrack(): TransferTrack? {
        val title = string("title") ?: string("name") ?: return null
        val artists = (string("subtitle") ?: "")
            .split(",", " •", "•")
            .map { it.replace(' ', ' ').trim() }
            .filter { it.isNotEmpty() }
        val durationMs = (this["duration"] as? JsonPrimitive)?.longOrNull
        return TransferTrack(title = title, artists = artists, durationSec = durationMs?.let { (it / 1000).toInt() })
    }

    private fun deezer(type: String, id: String): TransferSource {
        val info = json.parseToJsonElement(get("https://api.deezer.com/$type/$id").second).jsonObject
        info["error"]?.let { throw TransferSourceException("deezer") }

        val tracks = mutableListOf<TransferTrack>()
        if (type == "track") {
            info.toDeezerTrack(null)?.let(tracks::add)
        } else {
            var next: String? = "https://api.deezer.com/$type/$id/tracks?limit=100&index=0"
            val albumTitle = if (type == "album") info.string("title") else null
            while (next != null && tracks.size < 10_000) {
                val page = json.parseToJsonElement(get(next).second).jsonObject
                page["data"]?.jsonArray?.forEach { item ->
                    (item as? JsonObject)?.toDeezerTrack(albumTitle)?.let(tracks::add)
                }
                next = page.string("next")
            }
        }
        if (tracks.isEmpty()) throw TransferSourceException("empty")
        return TransferSource(
            key = "deezer:$type:$id",
            name = info.string("title") ?: "Deezer",
            platform = TransferPlatform.DEEZER,
            tracks = tracks,
        )
    }

    private fun JsonObject.toDeezerTrack(albumTitle: String?): TransferTrack? {
        val title = string("title") ?: return null
        val artist = (this["artist"] as? JsonObject)?.string("name")
        val album = albumTitle ?: (this["album"] as? JsonObject)?.string("title")
        return TransferTrack(
            title = title,
            artists = listOfNotNull(artist),
            album = album,
            durationSec = (this["duration"] as? JsonPrimitive)?.intOrNull,
        )
    }

    /**
     * Reads CSV exports from Exportify (Spotify, incl. liked songs), TuneMyMusic, Soundiiz, etc.
     * A "Playlist name" column splits the file into several playlists.
     */
    suspend fun fromCsv(context: Context, uri: Uri): List<TransferSource> = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw TransferSourceException("file")
        val rows = parseCsv(text.removePrefix("﻿")).filter { row -> row.any { it.isNotBlank() } }
        if (rows.size < 2) throw TransferSourceException("empty")

        val header = rows.first().map { it.trim().lowercase() }
        fun column(vararg names: String) = names.firstNotNullOfOrNull { name ->
            header.indexOf(name).takeIf { it >= 0 }
        } ?: header.indexOfFirst { h -> names.any { h.contains(it) } }.takeIf { it >= 0 }

        val titleCol = column("track name", "track title", "song name", "title", "name", "song", "canción", "cancion", "nombre")
            ?: throw TransferSourceException("columns")
        val artistCol = column("artist name(s)", "artist name", "artists", "artist", "artista", "artistas")
            ?: throw TransferSourceException("columns")
        val albumCol = column("album name", "album", "álbum")
        val playlistCol = column("playlist name", "playlist")
        val durationMsCol = column("duration (ms)", "track duration (ms)", "duration_ms")

        val fileName = displayName(context, uri)?.substringBeforeLast('.') ?: "CSV"
        val grouped = linkedMapOf<String, MutableList<TransferTrack>>()
        rows.drop(1).forEach { row ->
            val title = row.getOrNull(titleCol)?.trim().orEmpty()
            if (title.isEmpty()) return@forEach
            val artists = row.getOrNull(artistCol).orEmpty()
                .split(";", ",", " & ")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val playlist = playlistCol?.let { row.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() } ?: fileName
            grouped.getOrPut(playlist) { mutableListOf() } += TransferTrack(
                title = title,
                artists = artists,
                album = albumCol?.let { row.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() },
                durationSec = durationMsCol?.let { row.getOrNull(it)?.trim()?.toLongOrNull() }?.let { (it / 1000).toInt() },
            )
        }
        if (grouped.isEmpty()) throw TransferSourceException("empty")
        grouped.map { (name, tracks) ->
            TransferSource(
                key = "file:$uri:$name",
                name = name,
                platform = TransferPlatform.FILE,
                tracks = tracks,
                destination = if (name.contains("liked", ignoreCase = true) || name.contains("me gusta", ignoreCase = true)) {
                    TransferDestination.LIKED_SONGS
                } else {
                    TransferDestination.NEW_PLAYLIST
                },
            )
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()

    /** RFC 4180: quoted fields may contain commas, line breaks and "" escapes. */
    internal fun parseCsv(text: String): List<List<String>> {
        val delimiter = text.lineSequence().firstOrNull()?.let { first ->
            if (first.count { it == ';' } > first.count { it == ',' }) ';' else ','
        } ?: ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> { row += field.toString(); field.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += field.toString(); field.clear()
                    rows += row; row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows
    }

    private fun JsonElement.path(vararg keys: String): JsonElement? =
        keys.fold(this as JsonElement?) { acc, key -> (acc as? JsonObject)?.get(key) }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun findObjectWithKey(element: JsonElement, key: String): JsonObject? = when (element) {
        is JsonObject -> if (key in element) element else element.values.firstNotNullOfOrNull { findObjectWithKey(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findObjectWithKey(it, key) }
        else -> null
    }
}
