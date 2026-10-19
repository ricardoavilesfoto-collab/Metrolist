/**
 * Metrolist Plus — búsqueda de una canción externa (Spotify) en YouTube Music y normalización de nombres.
 * La usan la transferencia de playlists y las Estadísticas Plus.
 */

package com.metrolist.music.plus.transfer

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import java.text.Normalizer
import kotlin.math.abs

internal object PlusMatcher {
    const val ACCEPT_SCORE = 0.55
    const val SURE_SCORE = 0.75

    class Match(val item: SongItem, val score: Double)

    /** Same criteria as herramientas/spotify-a-ytmusic: songs first, then videos if unsure. */
    suspend fun findOnYouTubeMusic(track: TransferTrack): Match? = find(track, strict = false)

    /** Like [findOnYouTubeMusic], but throws when a request fails, so "offline" is not taken as "not found". */
    suspend fun findOnYouTubeMusicOrThrow(track: TransferTrack): Match? = find(track, strict = true)

    private suspend fun find(track: TransferTrack, strict: Boolean): Match? {
        val query = "${track.title} ${track.artists.firstOrNull().orEmpty()}".trim()
        var best: Match? = null
        for (filter in listOf(YouTube.SearchFilter.FILTER_SONG, YouTube.SearchFilter.FILTER_VIDEO)) {
            search(query, filter, strict).forEach { item ->
                val score = score(track, item)
                if (score > (best?.score ?: 0.0)) best = Match(item, score)
            }
            if ((best?.score ?: 0.0) >= SURE_SCORE) break
        }
        return best?.takeIf { it.score >= ACCEPT_SCORE }
    }

    suspend fun findArtist(name: String): ArtistItem? {
        val wanted = normalize(name)
        return YouTube.search(name, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
            ?.items
            ?.filterIsInstance<ArtistItem>()
            ?.firstOrNull { normalize(it.title) == wanted }
    }

    private suspend fun search(query: String, filter: YouTube.SearchFilter, strict: Boolean): List<SongItem> =
        YouTube.search(query, filter).let { if (strict) it.getOrThrow() else it.getOrNull() }
            ?.items
            ?.filterIsInstance<SongItem>()
            ?.distinctBy { it.id }
            ?.take(5)
            .orEmpty()

    private fun score(track: TransferTrack, item: SongItem): Double {
        val titleScore = similarity(track.title, item.title)
        val resultArtists = item.artists.joinToString(" ") { it.name }
        var artistScore = track.artists.maxOfOrNull { similarity(it, resultArtists) } ?: 0.0
        val normalizedArtists = normalize(resultArtists)
        if (track.artists.any { normalize(it).let { a -> a.isNotEmpty() && normalizedArtists.contains(a) } }) {
            artistScore = maxOf(artistScore, 0.9)
        }

        val itemDuration = item.duration
        val durationScore = if (track.durationSec != null && itemDuration != null) {
            val diff = abs(track.durationSec - itemDuration)
            if (diff <= 3) 1.0 else if (diff <= 10) 0.6 else 0.0
        } else {
            0.5
        }
        val score = 0.55 * titleScore + 0.35 * artistScore + 0.10 * durationScore
        // A matching title by another artist is usually a cover or a different song.
        return if (artistScore >= 0.5) score else score * 0.6
    }

    private fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        return 1.0 - levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private val bracketsRegex = Regex("""[(\[].*?[)\]]""")
    private val suffixRegex = Regex("""\s-\s.*(remaster|version|versión|live|en vivo|mono|stereo|edit|mix).*$""", RegexOption.IGNORE_CASE)
    private val accentsRegex = Regex("""\p{M}+""")
    private val symbolsRegex = Regex("""[^\p{L}\p{N} ]""")

    fun normalize(text: String) =
        Normalizer.normalize(text.replace(suffixRegex, "").replace(bracketsRegex, " ").lowercase(), Normalizer.Form.NFD)
            .replace(accentsRegex, "")
            .replace(symbolsRegex, " ")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
