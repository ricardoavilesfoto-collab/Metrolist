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
