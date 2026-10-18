/**
 * Metrolist Plus — guardar una canción descargada como archivo .m4a en Música/Metrolist.
 *
 * Las descargas viven en la caché privada de ExoPlayer (stream DASH de YouTube: webm/opus o mp4 fragmentado), que
 * los editores de video no pueden abrir. Se copia el stream a un archivo temporal y Transformer lo convierte a un
 * mp4 normal con AAC: si ya era AAC solo se re-empaqueta (sin pérdida); si era opus se re-codifica.
 */

package com.metrolist.music.plus.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.ui.component.Material3MenuItemData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val AAC_BITRATE = 256_000

fun plusSaveAudioMenuItem(
    context: Context,
    song: Song,
    downloadCache: Cache,
    onDismiss: () -> Unit,
): Material3MenuItemData? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return Material3MenuItemData(
        title = { Text(text = stringResource(R.string.plus_save_audio)) },
        description = { Text(text = stringResource(R.string.plus_save_audio_desc)) },
        icon = {
            Icon(
                painter = painterResource(R.drawable.music_note),
                contentDescription = null,
            )
        },
        onClick = {
            onDismiss()
            PlusAudioSaver.save(context, song, downloadCache)
        },
    )
}

object PlusAudioSaver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @RequiresApi(Build.VERSION_CODES.Q)
    fun save(context: Context, song: Song, downloadCache: Cache) {
        val appContext = context.applicationContext
        val title = song.song.title
        val artists = song.orderedArtists.joinToString { it.name }
        val fileName = listOf(artists, title)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .replace(Regex("""[\\/:*?"<>|]"""), "")
            .trim()
            .ifEmpty { song.id }

        toast(appContext, appContext.getString(R.string.plus_save_audio_started, title))
        scope.launch {
            val workDir = File(appContext.cacheDir, "plus_audio_export").apply { mkdirs() }
            val source = File(workDir, "${song.id}.src")
            val output = File(workDir, "${song.id}.m4a")
            try {
                copyFromCache(downloadCache, song.id, source)
                convertToM4a(appContext, source, output)
                saveToMusic(appContext, output, fileName, title, artists)
                withContext(Dispatchers.Main) {
                    toast(appContext, appContext.getString(R.string.plus_save_audio_done, "$fileName.m4a"))
                }
            } catch (e: Exception) {
                Timber.e(e, "No se pudo guardar el audio de ${song.id}")
                withContext(Dispatchers.Main) { toast(appContext, appContext.getString(R.string.plus_save_audio_error)) }
            } finally {
                source.delete()
                output.delete()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun copyFromCache(cache: Cache, songId: String, target: File) {
        // Sin fuente de red: si la descarga no está completa en la caché, la lectura falla en lugar de bajarla.
        val dataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(null)
            .setCacheWriteDataSinkFactory(null)
            .createDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(songId))
            .setKey(songId)
            .build()
        try {
            dataSource.open(dataSpec)
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    out.write(buffer, 0, read)
                }
            }
        } finally {
            dataSource.close()
        }
        if (target.length() == 0L) throw IOException("La descarga de $songId está vacía")
    }

    @OptIn(UnstableApi::class)
    private suspend fun convertToM4a(context: Context, source: File, output: File) =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val transformer = Transformer.Builder(context)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedAudioEncoderSettings(
                                AudioEncoderSettings.Builder().setBitrate(AAC_BITRATE).build(),
                            )
                            .build(),
                    )
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                if (continuation.isActive) continuation.resumeWithException(exportException)
                            }
                        },
                    )
                    .build()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                    .setRemoveVideo(true)
                    .build()
                transformer.start(item, output.absolutePath)
                continuation.invokeOnCancellation { transformer.cancel() }
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMusic(context: Context, file: File, fileName: String, title: String, artists: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.m4a")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Metrolist")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artists)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore no creó el archivo")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("No se pudo escribir en MediaStore")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}
