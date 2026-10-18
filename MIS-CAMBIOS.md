# Mis cambios sobre Metrolist

Bitácora de todas las modificaciones de este fork respecto al [original](https://github.com/MetrolistGroup/Metrolist).
Claude la lee al ejecutar `/sincronizar` para entender la intención de cada cambio y conservarla al integrar actualizaciones.

Formato de cada entrada (la más reciente arriba):

```
## AAAA-MM-DD — Título corto
- Qué: descripción del cambio.
- Por qué: qué quería lograr Ricardo.
- Archivos: lista de archivos tocados.
- Notas para sincronizar: qué debe conservarse si el original cambia estos archivos.
```

---

## 2026-10-18 — Transferir música (Spotify, Deezer, CSV) y Dynamic Island
- Qué (1): opción "Transferir música" en el menú de la foto de perfil (arriba de "Integraciones"). Abre una pantalla donde se pegan enlaces de playlists/álbumes/canciones de Spotify o Deezer, o se eligen archivos CSV (Exportify para toda la biblioteca de Spotify incl. "Me gusta"; TuneMyMusic/Soundiiz para Apple Music, Tidal, Amazon, etc.; una columna "Playlist name" divide el archivo en varias playlists). Cada fuente va a "Playlist nueva" o a "Me gusta". Cada canción se busca en YouTube Music (elige el mejor resultado por título, artista y duración), se guarda en la biblioteca y, si hay sesión y sincronización activa, también se crea en la cuenta de YouTube Music (`YouTube.createPlaylist` + `SyncUtils.scheduleAddToPlaylist`, o `toggleLike` para "Me gusta"). Muestra progreso y las canciones no encontradas; sigue en segundo plano aunque se salga de la pantalla.
- Por qué: reemplaza el script externo que Ricardo tenía en pausa; la transferencia ahora es parte de la app.
- Limitaciones: los enlaces de Spotify se leen de la página pública "embed" (sin API key), que solo trae las primeras 100 canciones; para playlists más grandes o "Me gusta" se usa CSV. Las playlists privadas de Spotify no se pueden leer por enlace.
- Qué (2): "Dynamic Island": píldora negra arriba de la pantalla (sobre la cámara) mientras suena música con la app en segundo plano. Toque → tarjeta con portada, título, progreso y anterior/play-pausa/siguiente; toque en la tarjeta → abre la app; toque fuera → se encoge; se encoge sola a los 6 s; toque largo → se oculta hasta la siguiente canción; se oculta tras 30 s en pausa. Interruptor en Ajustes → Apariencia → Reproductor (encendido por defecto, pide el permiso "Mostrar sobre otras apps").
- Archivos propios: `plus/transfer/TransferSources.kt` (Spotify embed `__NEXT_DATA__`, API pública de Deezer, CSV), `plus/transfer/TransferManager.kt` (`PlusTransferManager`: búsqueda y guardado), `plus/transfer/TransferScreen.kt` (pantalla, `PlusTransferRoute`, `plusTransferAccountItem`), `plus/island/DynamicIsland.kt` (`PlusDynamicIsland` ventana superpuesta con Compose, `plusDynamicIslandSettingsItem`, preferencia `plusDynamicIsland`), `res/drawable/plus_transfer.xml`, `res/drawable/plus_island.xml`, textos `plus_transfer_*` y `plus_island*`.
- Archivos del original modificados (marcados `// Metrolist Plus`):
  - `ui/screens/settings/AccountSettings.kt`: primer elemento del grupo de Integraciones/Ajustes = `plusTransferAccountItem { onClose(); navController.navigate(PlusTransferRoute) }`.
  - `ui/screens/NavigationBuilder.kt`: `composable(PlusTransferRoute) { PlusTransferScreen(navController) }` después de `settings/integrations`.
  - `playback/MusicService.kt`: campo `plusDynamicIsland`; se crea con `PlusDynamicIsland(this, player, scope).also { it.start() }` justo después de construir `mediaSession`; `release()` al inicio de `onDestroy()`.
  - `ui/screens/settings/AppearanceSettings.kt`: `plusDynamicIslandSettingsItem()` después de `plusSpotifyPlayerSettingsItem()`.
  - `AndroidManifest.xml`: permiso `SYSTEM_ALERT_WINDOW`.
- Notas para sincronizar: si el original cambia la construcción de `mediaSession` u `onDestroy` de `MusicService`, conservar las dos llamadas de la isla. Si cambia el grupo de opciones de `AccountSettings`, mantener el elemento de transferir.

## 2026-10-18 — Eliminar descargas de una playlist sin borrar las compartidas con otras playlists descargadas
- Qué: al eliminar las descargas de una playlist se conservan las canciones que también están en otra playlist de la Biblioteca (o en "Me gusta") que esté descargada. Una playlist Q cuenta como descargada si ≥80 % de sus canciones que NO están en la playlist que se borra tienen descarga (evita contar las bajadas solo por esa playlist). Nuestro diálogo muestra cuántas se eliminan y cuántas se conservan; los botones del original muestran un aviso con las conservadas.
- Por qué: Metrolist descarga por canción (un archivo por canción); antes, borrar una playlist borraba también canciones de otras playlists descargadas.
- Archivos propios: `plus/library/PlaylistDownloadRemoval.kt` (`planPlaylistDownloadRemoval`, `removeDownloads`, `CoroutineScope.removePlaylistDownloadsKeepingShared`; lee `playlist_song_map` + `playlist.bookmarkedAt` y `song.liked` con `openHelper.readableDatabase`); `plus/library/LibraryOrganizationUi.kt` (diálogo con el plan); textos `plus_remove_downloads_plan/_nothing/_kept_toast`.
- Archivos del original modificados (marcados `// Metrolist Plus`): el botón OK del diálogo de eliminar descargas en `ui/menu/PlaylistMenu.kt`, `ui/screens/playlist/LocalPlaylistScreen.kt` (se conserva su `clearPlaylist` para no editables) y `ui/menu/YouTubePlaylistMenu.kt` ahora llaman a `coroutineScope.removePlaylistDownloadsKeepingShared(...)` en lugar de `songs.forEach { DownloadService.sendRemoveDownload(...) }`.
- No cambian: borrar una canción suelta (`LocalPlaylistScreen` ~línea 1406) ni "Descargadas"/"Me gusta" (`AutoPlaylistScreen`).
- Notas para sincronizar: si el original cambia esos diálogos, mantener la llamada a `removePlaylistDownloadsKeepingShared`. Si cambia el nombre de las tablas/columnas `playlist_song_map(playlistId, songId)`, `playlist(id, bookmarkedAt)` o `song(id, liked)`, actualizar las consultas.

## 2026-10-18 — Biblioteca más rápida y "Eliminar descargas" por playlist
- Qué (1): la sincronización de playlists que se dispara al entrar a Biblioteca → Playlists ahora corre como máximo cada 15 minutos (antes en cada entrada, descargando la lista de canciones de las ~136 playlists y ocupando la base de datos; las playlists tardaban ~10 s en aparecer). La sincronización completa al abrir la app (`tryAutoSync`) no cambia.
- Qué (2): botón rápido "Eliminar descargas" arriba del menú ⋮ de cada playlist en la Biblioteca, visible si al menos una canción tiene descarga (completa, en curso o pausada). El menú original solo permitía borrar si la playlist estaba descargada al 100%.
- Archivos propios: `plus/library/LibrarySyncThrottle.kt`; `plus/library/LibraryOrganizationUi.kt` (botón en `PlusPlaylistQuickActions`, `RemoveDownloadsRequest`, diálogo en `PlusLibraryDialogs` con `DownloadService.sendRemoveDownload`); textos `plus_remove_downloads*`.
- Archivo del original modificado: `ui/screens/library/LibraryPlaylistsScreen.kt` → `LaunchedEffect(Unit)`: `if (ytmSync && PlusLibrarySyncThrottle.shouldSyncSavedPlaylists())` (marcado `// Metrolist Plus`).
- Notas para sincronizar: si el original agrega su propio límite a esa sincronización, quitar el nuestro.

## 2026-10-18 — Reproductor Plus sin barra inferior: tarjeta de letra deslizable, ⋮ arriba, temporizador y repetir junto al dispositivo
- Qué: con el Reproductor Plus activo se oculta la barra de íconos de abajo (cola, temporizador, aleatorio, letra, repetir, ⋮) porque duplicaba los controles. En su lugar: tarjeta "Letra" asomada abajo que al deslizarla hacia arriba muestra la letra sincronizada (y hacia abajo vuelve al reproductor); ⋮ en la esquina superior derecha junto a "Reproduciendo desde…"; temporizador y repetir a la derecha de la línea del dispositivo de salida. La cola se abre con su botón de la fila de controles.
- Archivos propios: `plus/player/LyricsSheet.kt` (`PlusLyricsSheet` con `BottomSheet` + `InlineLyricsView` del original, `PlusPlayerMenuButton` con `PlayerMenu`, `PlusLyricsPeekHeight`); `plus/player/SpotifyControls.kt` (fila dispositivo · temporizador · repetir; parámetros `sleepTimerActive`, `onSleepTimerClick`).
- Archivos del original modificados (marcados con `// Metrolist Plus`):
  - `ui/player/Player.kt`: `plusSpotifyPlayer` se lee arriba (junto a `queueSheetState`); con Plus, `queueSheetState` usa bounds 0 (barra oculta) y existe `plusLyricsSheetState`; el `bottomPadding` del contenido usa la altura de la tarjeta; `PlusLyricsSheet` se dibuja antes de `Queue` (solo vertical, no en pantalla completa); el `Thumbnail` vertical recibe `headerAction = PlusPlayerMenuButton(...)`; `activeControls` pasa los parámetros del temporizador.
  - `ui/player/Thumbnail.kt`: parámetro opcional `headerAction` en `Thumbnail` → `ThumbnailHeader(action = ...)`, dibujado a la derecha (`Alignment.CenterEnd`).
- Notas para sincronizar: si el original cambia la construcción de `queueSheetState`, el cálculo de `bottomPadding` o el encabezado de `Thumbnail`, conservar estas ramas para el modo Plus.

## 2026-10-18 — Cola estilo Spotify ("Siguiente en la cola") y deslizar para agregar a la cola
- Qué: las canciones que agrega el usuario forman su propia cola, que suena antes del resto de la playlist. "Agregar a la cola" las pone al final de esa cola (ya no al final de toda la lista); "Reproducir a continuación" al inicio. Al poner otra playlist/álbum la cola se conserva. La pantalla de cola muestra secciones "Reproduciendo ahora", "Siguiente en la cola" (con "Borrar cola") y "Siguiente desde: <playlist>". Deslizar una canción a la derecha = agregar a la cola, a la izquierda = reproducir a continuación, activado por defecto.
- Por qué: Ricardo está acostumbrado a la cola de Spotify.
- Cómo: cada canción agregada se marca con el extra `plus_user_queue` en `MediaItem.mediaMetadata.extras` (el `tag` ya lo usa la app). La cola del usuario = canciones marcadas que siguen a la actual en el orden de reproducción (respeta aleatorio).
- Archivos propios: `plus/queue/UserQueue.kt` (`markedAsUserQueued`, `isUserQueued`, `upcomingUserQueue`, `clearUpcomingUserQueue`, `addToUserQueue`, `PlusQueueSectionHeader`), textos `plus_next_*`, `plus_up_next`, `plus_clear_queue`, `plus_swipe_song_to_add`.
- Archivos del original modificados (marcados con `// Metrolist Plus`):
  - `playback/MusicService.kt`: `addToQueue` usa `player.addToUserQueue(items)` (si la cola está vacía se comporta como antes); `playNext` marca lo insertado con `markedAsUserQueued()`; `playQueue` guarda `pendingUserQueue` al inicio y lo reinserta con `addToUserQueue` al final del `scope.launch`.
  - `ui/player/Queue.kt`: dentro del `ReorderableItem` de cada canción, `Column { PlusQueueSectionHeader(...); <if locked / SwipeToDismissBox> }`.
  - `ui/component/Items.kt`: `SwipeToSongBox` con acciones/íconos invertidos (derecha = cola) y `SwipeToSongKey` por defecto `true` (2 lugares).
  - `ui/screens/settings/AppearanceSettings.kt`: `SwipeToSongKey` por defecto `true` y título `plus_swipe_song_to_add`.
- Limitaciones: las canciones de la cola quedan en el historial al sonar; tocar una canción más abajo en la cola salta las encoladas intermedias; la marca no sobrevive a cerrar la app (la cola restaurada pierde la sección).
- Notas para sincronizar: si el original reescribe `addToQueue`/`playNext`/`playQueue` o la lista de `Queue.kt`, conservar: marcar con `markedAsUserQueued`, insertar con `addToUserQueue`, conservar `pendingUserQueue` y el encabezado de sección por canción.

## 2026-10-18 — Ícono verde de "descargado" (estilo Spotify)
- Qué: el indicador de descarga completa (`Icon.Download` con `STATE_COMPLETED`) ahora es un círculo verde con flecha en lugar del ícono gris `offline`. Aplica en toda la app: playlists de la Biblioteca, álbumes y canciones.
- Por qué: que se note de un vistazo qué playlists están descargadas.
- Archivos: `res/drawable/plus_downloaded.xml` (propio); `ui/component/Items.kt` (`object Icon` → `fun Download`, rama `STATE_COMPLETED`: painter y `tint = Color.Unspecified`, marcado `// Metrolist Plus`).
- Notas para sincronizar: si el original cambia `Icon.Download`, conservar el painter `plus_downloaded` sin tinte.
- Verificado (sin cambios): la reproducción usa primero la descarga (`MusicService.createDataSourceFactory` revisa `downloadCache` antes que la red), aunque haya datos. Excepción del original: cambiar la calidad de audio mientras suena una canción borra la descarga de esa canción.

## 2026-10-18 — Reproductor Plus (híbrido Metrolist + Spotify) y selector de salida de audio
- Qué (versión 2, reemplaza al "estilo Spotify" puro): título y artistas con la tipografía del original; a la derecha compartir (ícono de tres puntos, sin fondo) y agregar a playlist (`AddToPlaylistDialog`; el ♥ se quitó a pedido de Ricardo, "Me gusta" queda en ⋮ y notificación). Barra de progreso según el ajuste del usuario: onda (`WavySlider`/`SquigglySlider`) si es "Ondulada", barra delgada estilo Spotify en otro caso. Fila compacta aleatorio · anterior · play (círculo 64 dp) · siguiente · cola (abre la hoja de cola). Debajo, botón de dispositivo de salida con el nombre del Bluetooth/audífonos/teléfono (verde si no es el teléfono): toque → selector de salida del sistema; toque largo → ajustes de Bluetooth. Interruptor en Ajustes → Apariencia → Reproductor ("Reproductor Plus (estilo Spotify)", encendido por defecto).
- Por qué: a Ricardo le gusta el diseño original (onda, título) pero quiere los controles compactos de Spotify, la cola a la mano y poder cambiar entre lentes Oakley Meta / coche / Alexa / teléfono.
- Archivos propios: `plus/player/SpotifyControls.kt` (controles), `plus/player/AudioOutput.kt` (selector: `MediaRouter2.showSystemOutputSwitcher()` en Android 14+, broadcast `com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG` en 11–13, ajustes de Bluetooth como respaldo; nombre de salida con `MediaRouter2.systemController.selectedRoutes` + `AudioManager.getDevices`), íconos `plus_check_circle.xml`, `plus_share.xml`, `plus_smartphone.xml`, `plus_headphones.xml`, textos `plus_spotify_player*`, `plus_share`, `plus_add_to_playlist`, `plus_output_*`.
- Limitaciones conocidas: Android reproduce en un dispositivo Bluetooth a la vez (el selector cambia entre los conectados); Mac no es posible (no hay AirPlay/receptor Bluetooth); Chromecast va por el botón de Cast de la portada.
- Archivos del original modificados (marcados con `// Metrolist Plus`):
  - `ui/player/Player.kt`: bloque `activeControls` justo antes de `when (LocalConfiguration.current.orientation)`, y las dos llamadas `controlsContent(it)` cambiadas a `activeControls(it)` (horizontal y vertical). Reutiliza el estado del original: `sliderPosition`, `effectivePosition`, `duration`, casting (`castHandler`, `lastManualSeekTime`), `sliderStyle`, `squigglySlider`, `PlayerSliderColors.getSliderColors(...)`, `queueSheetState.expandSoft()`, colores `TextBackgroundColor`/`textButtonColor`/`iconButtonColor`.
  - `ui/screens/settings/AppearanceSettings.kt`: `plusSpotifyPlayerSettingsItem()` como primer elemento del grupo "Reproductor".
- No se tocan: portada (`Thumbnail.kt`, ya muestra "Reproduciendo desde…"), fondo y barra de la cola (`Queue.kt`).
- Notas para sincronizar: si el original renombra alguna de esas variables de `Player.kt` o cambia cómo hace seek/play-pause, actualizar el bloque `activeControls` con la lógica nueva. Si agrega llamadas nuevas a `controlsContent(...)`, cambiarlas también a `activeControls(...)`.

## 2026-10-18 — Carpetas y playlists fijadas en la Biblioteca (estilo Spotify)
- Qué: en la vista "Playlists" de la Biblioteca se pueden fijar playlists y carpetas arriba, crear carpetas, mover playlists a carpetas, renombrar y eliminar carpetas (eliminar no borra playlists). El botón "+" ofrece crear playlist o carpeta. El menú de cada playlist muestra arriba los botones Fijar / Mover a carpeta / Sacar de la carpeta.
- Por qué: organizar ~136 playlists como en Spotify.
- Archivos propios (sin riesgo al sincronizar): `app/src/main/kotlin/com/metrolist/music/plus/library/LibraryOrganization.kt` (datos y orden), `.../plus/library/LibraryOrganizationUi.kt` (componentes), `res/values/plus_strings.xml`, `res/values-es/plus_strings.xml`, `res/drawable/plus_folder.xml`, `res/drawable/plus_create_new_folder.xml`.
- Archivos del original modificados (marcados con `// Metrolist Plus`):
  - `ui/component/Library.kt`: `LibraryPlaylistListItem` y `LibraryPlaylistGridItem` reciben `extraMenuContent` (se invoca al inicio de `menuState.show { }`) y la lista recibe `isPinned` (ícono de pin antes del ⋮).
  - `ui/screens/library/LibraryPlaylistsScreen.kt`: campos extra en `VisiblePlaylistItem`; estado de organización/carpeta abierta/diálogos; `buildList { }.organizedWith(...)`; contador `playlistCount`; encabezado de carpeta y ramas `folder != null` en lista y cuadrícula; el FAB abre `PlusCreateMenu`; funciones `organizedWith` y `toVisibleItem` al final del archivo.
- Datos: JSON en la preferencia `plusLibraryOrganization` del DataStore de ajustes (entra en los respaldos). Las playlists se identifican por `browseId` (o `id` si son locales), así sobreviven a re-sincronizaciones y re-login. No toca la base de datos Room.
- Notas para sincronizar: si el original cambia cómo arma `visibleResults` o los menús de `Library.kt`, conservar: (1) aplicar `organizedWith` al resultado final, (2) pasar `extraMenuContent`/`isPinned` a los items de playlist, (3) renderizar filas con `folder != null` como carpeta. Pendiente: la vista general "Biblioteca" (`LibraryMixScreen`) no muestra carpetas todavía.

## 2026-10-17 — Arreglo: playlists duplicadas que aparecen y desaparecen
- Qué: en `executeSyncSavedPlaylists`, las playlists locales cuyo `browseId` ya no está en la biblioteca de YouTube Music ahora se ocultan (`bookmarkedAt = null`, solo si estaban visibles) en lugar de alternar con `localToggleLike()`.
- Por qué: el toggle volvía a mostrar las copias huérfanas en cada sincronización alterna, por eso aparecían duplicadas (ícono genérico) y luego desaparecían. No se borra nada de la base de datos.
- Archivos: `app/src/main/kotlin/com/metrolist/music/utils/SyncUtils.kt` (bloque al inicio de `executeSyncSavedPlaylists`).
- Notas para sincronizar: si el original corrige esto mismo (deja de usar `localToggleLike()` ahí), quedarse con la versión del original y borrar esta entrada. Si reescribe la función, conservar la regla: "nunca re-marcar como guardada una playlist que no está en la biblioteca remota".

## 2026-10-17 — Compilación de Metrolist Plus en GitHub Actions
- Qué: workflow propio que compila la variante GMS release como "Metrolist Plus" (`com.metrolist.music.plus`), la firma con la llave del fork y la publica en la release `metrolist-plus`.
- Por qué: tener un APK instalable que conviva con el Metrolist oficial, sin necesitar Android Studio.
- Archivos: `.github/workflows/metrolist-plus.yml`. Secrets del fork: `PLUS_KEYSTORE`, `PLUS_KEYSTORE_PASSWORD`, `PLUS_KEY_ALIAS`.
- Notas para sincronizar: archivo exclusivo del fork. Usa los overrides `METROLIST_APPLICATION_ID` / `METROLIST_APP_NAME` de `app/build.gradle.kts`; si el original los renombra o elimina, hay que adaptar el workflow. Los workflows del original están deshabilitados en el fork (ajuste de GitHub, no de código).

## 2026-10-17 — Configuración inicial del fork
- Qué: se agregaron `CLAUDE.md`, `MIS-CAMBIOS.md`, el comando `/sincronizar` y la actualización automática de la copia local al abrir Claude Code.
- Por qué: mantener el fork al día con el original sin perder las modificaciones propias.
- Archivos: `CLAUDE.md`, `MIS-CAMBIOS.md`, `.claude/settings.json`, `.claude/hooks/actualizar-local.sh`, `.claude/skills/sincronizar/SKILL.md`.
- Notas para sincronizar: archivos exclusivos del fork; el original no los tiene.

## Sincronizaciones con el original

| Fecha | Commit del original integrado | Conflictos | Respaldo |
|-------|-------------------------------|------------|----------|
| 2026-10-17 | `76fa5a78` (punto de partida del fork) | — | — |
