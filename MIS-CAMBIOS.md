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

## 2026-10-20 — "Guardar en playlists": varias a la vez, buscador, carpetas y recientes
- Qué: la ventana de "Agregar a playlist" ahora es "Guardar en playlists".
  - Casillas para marcar varias playlists y botón "Guardar (N)" que guarda la canción (o canciones) en todas de un jalón.
  - Buscador por nombre y los mismos chips de orden del original (tocar el chip activo invierte el orden).
  - Sin búsqueda, la lista va así: Usadas recientemente (las últimas 5), Fijadas, Carpetas (se abren al tocarlas y dicen cuántas marcaste dentro) y Otras playlists.
  - Las playlists que ya tienen la canción salen marcadas y bloqueadas. Con varias canciones, las repetidas se omiten sin preguntar.
  - Solo muestra playlists editables. Botón "Nueva" para crear una playlist ahí mismo.
- Por qué: Ricardo tiene muchas playlists y quería encontrarlas rápido y guardar una canción en varias en un solo movimiento.
- Archivos propios: `plus/library/SaveToPlaylists.kt` (`PlusSaveToPlaylistsDialog`; recientes en DataStore `plusRecentAddToPlaylist`); textos `plus_save_*`, `plus_search_playlists`, `plus_recent_playlists`, `plus_pinned_playlists`, `plus_folders`, `plus_other_playlists`, `plus_n_selected`, plural `plus_saved_to_playlists`.
- Archivos del original modificados (marcados `// Metrolist Plus`):
  - `ui/menu/AddToPlaylistDialog.kt`: 2 imports y un `if (plusSaveToPlaylistsEnabled())` después de `val playlists` que muestra el diálogo nuevo y sale. El código original queda intacto debajo.
- Notas para sincronizar: si el original cambia los parámetros de `AddToPlaylistDialog`, pasarlos también a `PlusSaveToPlaylistsDialog`. `AddToPlaylistDialogOnline` (importar CSV / selección de YouTube) no se tocó.

## 2026-10-20 — Aleatorio Plus (aleatorio con recomendaciones) en las playlists
- Qué: botón "Aleatorio Plus", verde Spotify (aleatorio con destellos), junto al de aleatorio en playlists locales y automáticas (Me gusta, Descargadas…).
  - Reproduce la playlist en aleatorio y alterna una y una: canción de la playlist, canción recomendada.
  - La recomendada sale de la radio de YouTube Music (`RDAMVM<id>`) de una canción de la misma playlist, así que va con el género o vibe.
  - Se arma por bloques de 8 canciones de la playlist (+8 recomendadas), con a lo más 3 consultas a YouTube por bloque. El siguiente bloque se pide cuando faltan pocas canciones.
  - Las recomendadas dicen "Recomendada" (en verde, antes del artista) en la cola (campo `suggestedBy` de `MediaMetadata`, el mismo que usa Escuchar juntos).
    En Reproduciendo ahora: diseño Plus, línea verde "✦ Recomendada" arriba del título (`plus/player/SpotifyControls.kt`); diseño original, junto al artista en `ui/player/Player.kt`, una línea marcada `// Metrolist Plus`).
  - No repite recomendadas ni mete versiones de canciones que ya están en la playlist (compara título + primer artista).
  - Sin internet suena la playlist en aleatorio normal.
- Por qué: Ricardo quería descubrir música parecida a sus playlists sin dejar de oírlas.
- Archivos propios: `plus/queue/MixShuffleQueue.kt` (`PlusMixShuffleQueue`, `PlusMixShuffleButton`); `res/drawable/plus_shuffle_mix.xml`; textos `plus_mix_shuffle` y `plus_mix_recommended`.
- Archivos del original modificados (una línea + imports, marcados `// Metrolist Plus`):
  - `ui/screens/playlist/LocalPlaylistScreen.kt` y `AutoPlaylistScreen.kt`: `PlusMixShuffleButton(...)` antes del botón Play.
  - `ui/component/Items.kt` (`MediaMetadataListItem`): `suggestedBy` va al inicio del subtítulo y en verde, en vez de al final.
- Notas para sincronizar: depende de "Cargar más automáticamente" (activo por defecto). Al reiniciar la app la cola guardada sigue como lista normal, sin nuevas recomendadas.

## 2026-10-19 — Fase 2: enseñarle el historial de Spotify a Metrolist y YouTube Music
- Qué: tarjeta "Enseñarle tu historial a YouTube Music" en Estadísticas Plus (`plus/stats/PlusTeacher.kt`).
  - Busca en YouTube las ~7,700 canciones con 10+ reproducciones en Spotify, de la más escuchada a la menos. Las versiones de una misma canción cuentan como una.
  - Copia sus reproducciones al historial de Metrolist (tabla `event` + `song.totalPlayTime`) y da "Me gusta" (local, y en YouTube si hay sesión y sincronización) a las 300 más escuchadas.
  - La primera vez se detiene después de 50 para revisar. Se puede pausar y continuar.
  - Si YouTube no responde 4 veces seguidas, se detiene sin marcar canciones como "no encontradas" (`PlusMatcher.findOnYouTubeMusicOrThrow`).
- Por qué: que Selección rápida, Favoritos olvidados, Historial y Estadísticas clásicas conozcan los 12 años de Spotify, y que YouTube Music reciba señal de sus favoritas.
- Marca: cada reproducción copiada lleva `timestamp % 1000 = 1 AND playTime % 1000 = 1` (`PlusHistoryStore.TAUGHT_EVENT`).
  - Es idempotente: cada canción borra sus filas marcadas, resta su tiempo y las vuelve a escribir.
  - El espejo de Estadísticas Plus excluye esas filas, así que sus totales no cambian.
  - "Borrar historial de Spotify" también las quita de Metrolist.
  - "Volver a empezar" borra las banderas `taught` (útil tras restaurar una copia de Metrolist).
- Archivos propios: `PlusTeacher.kt`; `PlusHistoryStore.kt` (columnas `track.yt_match` y `track.taught`, `teachList`, `setMatch`, `setTaught`, `spotifyPlaysFor`, exclusión en el espejo, limpieza en `clearSpotify`); `PlusStatsScreen.kt` (`TeachCard`); `plus/transfer/Matcher.kt`; textos `plus_teach_*`.
- Base de datos de Metrolist: no cambia el esquema, solo agrega filas a `event` con el DAO dentro de `withTransaction`. No se crean índices nuevos.
- Efecto esperado: durante y justo después de enseñar, Selección rápida se basa en las últimas canciones enseñadas, porque usa `ORDER BY ROWID DESC`. Se corrige solo con unas cuantas reproducciones reales.

## 2026-10-19 — "Reproduciendo desde…" lleva al álbum, mix, radio o pantalla de origen
- Qué: el encabezado del reproductor ahora recuerda desde qué pantalla se inició la cola y vuelve ahí (álbum, playlist, artista, búsqueda, historial…).
  - Si se inició desde una pestaña principal (Inicio, Biblioteca, Buscar), usa la playlist o mix de YouTube de la cola (`online_playlist/<id>`, salvo radios `RDAMVM…`).
  - Si no hay nada de eso, busca el nombre en la biblioteca como antes y, al final, abre el artista de la canción.
  - El origen se guarda en SharedPreferences `plus_playing_from` para que sobreviva a reiniciar la app.
- Por qué: Ricardo quería que tocar el título lo lleve siempre al lugar de lo que suena, no solo con playlists guardadas.
- Archivos propios: `plus/player/PlayingFromRoute.kt` (`PlusPlayingFrom`).
- Archivos del original modificados (una línea cada uno, marcados `// Metrolist Plus`):
  - `MainActivity.kt`: `PlusPlayingFrom.Track(navController)` después de `rememberNavController()`.
  - `playback/PlayerConnection.kt`: `PlusPlayingFrom.onPlayQueue(queue)` en `playQueue`.
  - `playback/queues/YouTubeQueue.kt`: `val plusEndpoint`.
  - `playback/queues/YouTubePlaylistQueue.kt`: `val plusPlaylistId`.
- Notas para sincronizar: si el original renombra `endpoint` o `playlistId` en esas colas, ajustar las dos propiedades.

## 2026-10-19 — Hábitos de Metrolist, exportar historial y "Para ti" en el Inicio
- Qué:
  1. **Registro de hábitos**: cada canción que suena en Metrolist guarda cuánto se escuchó, si se saltó (menos de 30 s o menos de la mitad), si estaba en aleatorio y la salida de audio (bocina, audífonos, Bluetooth con nombre). Va a `databases/plus_habitos.db`, aparte, para que importar o borrar Spotify nunca lo toque. Respeta "Pausar historial" y omite podcasts. En Estadísticas Plus, "Saltadas" y "Aleatorio" combinan Spotify con este registro, y hay una sección nueva "Cómo escuchas en Metrolist".
  2. **Exportar historial** (⋮ en Estadísticas Plus): guarda `historial_metrolist_plus.db` con todo (Spotify + espejo de Metrolist + hábitos). Ese archivo se puede volver a importar: los hábitos se mezclan sin duplicar.
  3. **Fila "Para ti" hasta arriba del Inicio**, solo si se importó Spotify:
     - Para esta hora: lo que sueles oír ±1 h, entre semana o fin de semana, últimos 3 años.
     - Redescubre: 10+ reproducciones de hace más de 2 años y nada en 9 meses.
     - Radio de tu artista #1 de los últimos 90 días: `YouTubeQueue` se vuelve radio.
     - Tu top de siempre, y Tu <año> para cada año.
     - Las listas usan las canciones ya emparejadas y buscan a lo más 15 en el momento. Nunca agregan a la cola después de empezar.
- Por qué: Ricardo quiere dejar Spotify y que Metrolist Plus registre sus hábitos y le recomiende con base en toda su historia.
- Archivos propios:
  - `plus/stats/PlusListenLog.kt`
  - `plus/stats/PlusForYou.kt` (ViewModel + fila)
  - cambios en `PlusHistoryStore.kt`: export, consultas `forHour`, `rediscover`, `topPicks`, `radioSeed`; `resolve` ahora guarda la canción en Metrolist; marca `has_spotify` en SharedPreferences `plus_stats`.
  - `PlusStatsScreen.kt`, `PlusStatsViewModel.kt`
  - `plus/player/AudioOutput.kt`: `currentAudioOutput` pasa de private a internal.
  - textos `plus_stats_*` y `plus_foryou_*`
- Archivos del original modificados (marcados `// Metrolist Plus`):
  - `playback/MusicService.kt`: al inicio de `onPlaybackStatsReady`, llamada a `PlusListenLog.record(...)` si no está pausado el historial.
  - `ui/screens/HomeScreen.kt`: `item(key = "plus_for_you")` antes de `homeSections.forEach`.
- Notas para sincronizar: si el original mueve `onPlaybackStatsReady` o el `LazyColumn` del Inicio, volver a poner esas dos llamadas.

## 2026-10-19 — Estadísticas Plus con gráficas e historial de Spotify
- Qué: pantalla nueva "Estadísticas Plus" (ruta `plus/stats`). Junta el historial extendido de Spotify (2014–2026, ~466 mil reproducciones) con el historial de Metrolist. Trae:
  - Resumen: horas, días, reproducciones, canciones, artistas y promedio diario.
  - Barras de horas por año → mes → día (se toca una barra dos veces para entrar).
  - Mapa de calor día × hora.
  - Tops de artistas, canciones y álbumes (50).
  - Evolución: artista #1 por año, líneas del top 5 y canciones descubiertas por año.
  - Hábitos: % saltadas, % aleatorio, rachas y plataformas.
  - Historial completo día por día (`plus/stats/history`).
  - Filtros por periodo (Todo / 30 días / año / mes) y por fuente (Todo / Spotify / Metrolist).
  - El ícono de estadísticas de Inicio abre esta pantalla. La original sigue en ⋮ → "Estadísticas clásicas".
- Por qué: Ricardo quiere ver toda su vida de escucha de Spotify en Metrolist, con gráficas.
- Cómo funciona:
  - En la PC, `herramientas/historial-spotify/preparar_historial.py` convierte los JSON en `historial_spotify.db` (SQLite, sin IP), que se importa desde el menú ⋮.
  - La app lo guarda como base propia `databases/plus_historial.db`. Al importar:
    - convierte las horas UTC a hora local con la convención de `db/Converters.kt` (hora local guardada como UTC), con el horario de verano histórico;
    - calcula claves normalizadas (`PlusMatcher.normalize`) para juntar la misma canción o artista de ambas fuentes.
  - El historial de Metrolist se copia a esa base (filas `source = 1`) cada vez que cambia: se compara COUNT, MAX(id) y SUM(playTime) de `event`.
  - Las canciones de Spotify no se emparejan con YouTube al importar. Al tocarlas se buscan y el resultado queda guardado en `track.yt_id`. Las fotos del top 10 se buscan solas.
- Archivos propios:
  - `plus/stats/PlusHistoryStore.kt`, `PlusStatsViewModel.kt`, `PlusStatsScreen.kt`, `PlusCharts.kt` (gráficas con Canvas, sin librerías), `PlusHistoryListScreen.kt`
  - `plus/transfer/Matcher.kt`: búsqueda y normalización sacadas de `TransferManager.kt`, que ahora las usa desde ahí.
  - `res/values/plus_strings.xml` y `values-es/plus_strings.xml` (`plus_stats_*`)
  - `herramientas/historial-spotify/`
- Archivos del original modificados (marcados `// Metrolist Plus`):
  - `MainActivity.kt`: el ícono de estadísticas navega a `PlusStatsRoute`.
  - `ui/screens/NavigationBuilder.kt`: dos `composable` nuevos junto a `PlusTransferRoute`.
- Base de datos: **no** toca el esquema de `MusicDatabase`. Solo lee `event`, `song`, `song_artist_map` y `artist` con SQL directo. `plus_historial.db` no entra en la copia de seguridad de Metrolist: hay que conservar `historial_spotify.db` para reimportar.
- Notas para sincronizar:
  - Si el original renombra columnas de `event`, `song`, `song_artist_map` o `artist`, ajustar el query del espejo en `PlusHistoryStore.syncMetrolist`.
  - Si cambia `Converters.kt` (cómo guarda `LocalDateTime`), ajustar `fillLocalTime` y `toStoredMillis`.
- Pendiente (fase 2): emparejar en segundo plano las ~8,400 canciones con 10+ reproducciones y meterlas como `Event` en el historial real de Metrolist. Hay que registrar sus IDs para que el espejo no las cuente doble.

## 2026-10-19 — Menos batería sin quitar animaciones
- Qué: (1) "Descarga de audio" (*audio offload*) encendida por defecto: el chip de audio reproduce y el procesador duerme, como Spotify. Se sigue apagando sola con crossfade y se puede desactivar en Ajustes → Reproductor si en algún teléfono hay cortes. (2) Con el reproductor cerrado, la posición de la mini barra se actualiza cada 1 s en lugar de cada 100 ms (10 redibujados por segundo menos); con el reproductor abierto sigue a 100 ms. (3) El sondeo de Discord cada 5 s solo trabaja si hay sesión de Discord (el original lo trae activado por defecto aunque no se use).
- Por qué: en el teléfono de Ricardo el 80 % del consumo era en primer plano (2 h) y 20 % en segundo plano (8 h). Ricardo pidió mantener las animaciones.
- Archivos del original modificados (marcados `// Metrolist Plus`): `playback/MusicService.kt` (default `AudioOffload` = true en 3 lugares; condición en el bucle de Discord), `ui/screens/settings/PlayerSettings.kt` (default del interruptor), `ui/player/Player.kt` (`delay(if (state.isExpanded) 100 else 1000)` en la actualización de posición).
- Notas para sincronizar: si el original cambia esos defaults o el bucle de posición, conservar los valores de Plus.

## 2026-10-18 — Tocar "Reproduciendo desde…" abre la playlist o el álbum
- Qué: en el encabezado del reproductor (vertical), el nombre de la playlist/álbum que se está reproduciendo ahora se puede tocar y abre esa pantalla (el reproductor se minimiza). La cola solo guarda su nombre, así que se busca en la biblioteca: listas automáticas ("Me gusta", "Descargadas", "Subidas", "Mi top N", caché), luego playlists por nombre (prefiere las guardadas y usa la misma regla que la Biblioteca para elegir `local_playlist/` u `online_playlist/`), luego álbumes y artistas. Si no hay título de cola, abre el álbum de la canción. Si no se encuentra (playlists de YouTube no guardadas, radios, mixes), el nombre no se puede tocar.
- Por qué: Ricardo esperaba que al tocar el nombre de la playlist lo llevara a ella, como en Spotify.
- Archivos propios: `plus/player/PlayingFromRoute.kt` (`rememberPlusPlayingFromRoute`).
- Archivos del original modificados (marcados `Metrolist Plus`):
  - `ui/player/Thumbnail.kt`: parámetro `onPlayingFromClick` en `Thumbnail` y `ThumbnailHeader`; `.clickable` en el texto del nombre; import de `clickable`.
  - `ui/player/Player.kt`: en la llamada vertical a `Thumbnail`, `onPlayingFromClick = rememberPlusPlayingFromRoute()?.let { … navController.navigate(route); state.collapseSoft() }`; import.
- Notas para sincronizar: si el original cambia los títulos de cola de las listas automáticas o las rutas de navegación, actualizar el `when` y las rutas de `PlayingFromRoute.kt`.

## 2026-10-18 — Guardar una canción descargada como archivo .m4a
- Qué: en el menú de una canción descargada (estado completo) aparece "Guardar como archivo de audio (M4A)". Copia la descarga desde la caché privada de ExoPlayer a un archivo temporal, la convierte con Media3 Transformer a un mp4 normal con AAC (si el stream ya era AAC solo se re-empaqueta, sin pérdida; si era opus/webm se re-codifica a 256 kbps) y la guarda con MediaStore en `Música/Metrolist/Artista - Título.m4a`. Avisa con Toasts; sigue aunque se cierre el menú. Solo Android 10+ (sin pedir permisos).
- Por qué: Ricardo quiere usar canciones específicas que elige en videos de CapCut/Edits; las descargas normales están en la carpeta privada de la app y en formato de caché que ningún editor abre. Es en `.m4a` y no `.mp3` porque Android no trae codificador MP3.
- Archivos propios: `plus/export/SaveAudioFile.kt` (`plusSaveAudioMenuItem`, `PlusAudioSaver`), textos `plus_save_audio*` en `values/plus_strings.xml` y `values-es/plus_strings.xml`.
- Archivos del original modificados (marcados `Metrolist Plus`):
  - `gradle/libs.versions.toml`: `media3-transformer` con `version.ref = "media3"`.
  - `app/build.gradle.kts`: `implementation(libs.media3.transformer)`.
  - `ui/menu/SongMenu.kt`: el grupo de descarga usa `listOfNotNull(...)` y agrega `plusSaveAudioMenuItem(context, song, downloadUtil.downloadCache, onDismiss)` si la descarga está completa; import de `plusSaveAudioMenuItem`.
- Notas para sincronizar: mantener la dependencia con la misma versión de media3 que use el original. Si cambia el grupo de descarga de `SongMenu`, volver a agregar el elemento. Si el original cifra la caché de descargas (`secretKey` en `AppModule.provideDownloadCache`), `copyFromCache` debe usar la misma clave.

## 2026-10-18 — Biblioteca sin la pestaña "Canciones"
- Qué: se quitó el chip "Canciones" (`LibraryFilter.SONGS`) de la fila de pestañas de la Biblioteca. La pantalla `LibrarySongsScreen` y su rama en el `when` siguen existiendo (si la preferencia guardada era SONGS, se abre y su botón regresa a la Biblioteca).
- Por qué: Ricardo nunca la usa; busca las canciones con el buscador o las ve en "Me gusta"/"Descargadas".
- Archivo del original modificado: `ui/screens/library/LibraryScreen.kt` (línea del chip reemplazada por un comentario `// Metrolist Plus`).
- Notas para sincronizar: si el original cambia la lista de chips, no volver a agregar el de canciones.

## 2026-10-18 — Biblioteca: listas al instante y sin cargar todas las canciones
- Qué:
  - Vista principal de la Biblioteca (mezcla): `LibraryMixViewModel.songs`, que juntaba todas las canciones de la biblioteca y de las playlists guardadas (`songsInBookmarkedPlaylists`, con artistas y álbumes), ahora solo se carga mientras hay texto en el buscador. Antes se consultaba en cada visita y se repetía con cada escritura de la sincronización.
  - Las listas de la Biblioteca (mezcla: playlists, álbumes, artistas; pantallas de playlists y de álbumes) empiezan con la última lista vista en esta sesión (`PlusLibraryListCache`, en memoria) en lugar de vacías, así aparecen al instante al volver.
  - Álbumes y artistas sincronizan al entrar como máximo cada 15 minutos (`PlusLibrarySyncThrottle.shouldSync("albums" / "artists")`), igual que las playlists.
- Por qué: Ricardo notaba que playlists y álbumes seguían tardando en aparecer al entrar a la Biblioteca.
- Archivos propios: `plus/library/LibraryListCache.kt` (nuevo), `plus/library/LibrarySyncThrottle.kt` (ahora con clave por pantalla).
- Archivos del original modificados (marcados `// Metrolist Plus`):
  - `viewmodels/LibraryViewModels.kt`: `.rememberedAs("…")` + `stateIn(…, PlusLibraryListCache.get("…"))` en `allAlbums`, `allPlaylists` y en `artists`/`albums`/`playlists` de la mezcla; `songs` de la mezcla envuelto en `_searchQuery.map { it.isNotBlank() }…flatMapLatest { if (!searching) flowOf(emptyList()) else … }`.
  - `ui/screens/library/LibraryAlbumsScreen.kt` y `LibraryArtistsScreen.kt`: `if (ytmSync && PlusLibrarySyncThrottle.shouldSync(…))` en el `LaunchedEffect(Unit)`.
- Notas para sincronizar: si el original cambia esos `stateIn` o la carga de canciones de la mezcla, conservar la idea (lista inicial desde la caché; canciones solo al buscar).

## 2026-10-18 — Se quitó la Dynamic Island
- Qué: se eliminó la Dynamic Island propia (píldora flotante y servicio de accesibilidad). `MusicService.kt`, `AppearanceSettings.kt` y `AndroidManifest.xml` volvieron a quedar como en el original; se borraron `plus/island/*`, `res/xml/plus_island_accessibility.xml`, `res/drawable/plus_island.xml` y los textos `plus_island*`.
- Por qué: en el vivo X100 Pro de Ricardo (OriginOS 6) la isla del sistema (Origin Island) solo muestra apps de música de una lista fija de vivo (por nombre de paquete, p. ej. `com.spotify.music`). Una píldora dibujada encima no puede mover los íconos del sistema ni coordinarse con la isla del sistema (se encimaba con Google Maps). Ricardo prefirió quitarla. La única forma de entrar a la Origin Island sería instalar la app con el paquete de una app de esa lista; quedó descartado.

## 2026-10-18 — Integración automática del trabajo hecho desde la nube
- Qué: `.github/workflows/metrolist-plus-integrar.yml` hace merge en `ricardo` de cada push a una rama `claude/**` y lanza `metrolist-plus.yml`, que compila y publica el APK. Si hay conflictos, o si la rama toca `.github/workflows/` (GITHUB_TOKEN no puede subir eso), falla sin tocar `ricardo`, y Claude en la PC lo integra a mano. Se eliminó `metrolist-plus-check.yml`, que compilaba una app de prueba aparte. La guía para la nube está en `CLAUDE.md`.
- Por qué: Ricardo quiere una sola app que se actualice encima ("Actualizar"), sin apps de prueba ni volver a iniciar sesión, haga el cambio desde la PC o desde el celular.
- Archivos: `.github/workflows/metrolist-plus-integrar.yml` (propio), `CLAUDE.md`.
- Notas para sincronizar: nada del original se toca. Respaldo previo: etiqueta `respaldo-2026-10-18-antes-celular`.

## 2026-10-18 — Transferir música (Spotify, Deezer, CSV)
- Qué: opción "Transferir música" en el menú de la foto de perfil (arriba de "Integraciones"). Abre una pantalla donde se pegan enlaces de playlists/álbumes/canciones de Spotify o Deezer, o se eligen archivos CSV (Exportify para toda la biblioteca de Spotify incl. "Me gusta"; TuneMyMusic/Soundiiz para Apple Music, Tidal, Amazon, etc.; una columna "Playlist name" divide el archivo en varias playlists). Cada fuente va a "Playlist nueva" o a "Me gusta". Cada canción se busca en YouTube Music (elige el mejor resultado por título, artista y duración), se guarda en la biblioteca y, si hay sesión y sincronización activa, también se crea en la cuenta de YouTube Music (`YouTube.createPlaylist` + `SyncUtils.scheduleAddToPlaylist`, o `toggleLike` para "Me gusta"). Muestra progreso y las canciones no encontradas; sigue en segundo plano aunque se salga de la pantalla.
- Por qué: reemplaza el script externo que Ricardo tenía en pausa; la transferencia ahora es parte de la app.
- Criterio de búsqueda copiado de `herramientas/spotify-a-ytmusic`: busca en canciones y, si no está segura, también en videos; puntaje 55 % título · 35 % artista · 10 % duración (covers castigados); ≥0.75 segura, 0.55–0.75 "dudosa" (se agrega y se lista para revisar), <0.55 no se agrega. Playlists de más de 4,900 canciones se dividen en partes "(1/2)", "(2/2)" (límite de YouTube Music), en el mismo orden. Los "Me gusta" se envían a YouTube uno por uno.
- Limitaciones: los enlaces de Spotify se leen de la página pública "embed" (sin API key), que solo trae las primeras 100 canciones; para playlists más grandes o "Me gusta" se usa CSV. Las playlists privadas de Spotify no se pueden leer por enlace.
- Archivos propios: `plus/transfer/TransferSources.kt` (Spotify embed `__NEXT_DATA__`, API pública de Deezer, CSV), `plus/transfer/TransferManager.kt` (`PlusTransferManager`: búsqueda y guardado), `plus/transfer/TransferScreen.kt` (pantalla, `PlusTransferRoute`, `plusTransferAccountItem`), `res/drawable/plus_transfer.xml`, textos `plus_transfer_*`.
- Archivos del original modificados (marcados `// Metrolist Plus`):
  - `ui/screens/settings/AccountSettings.kt`: primer elemento del grupo de Integraciones/Ajustes = `plusTransferAccountItem { onClose(); navController.navigate(PlusTransferRoute) }`.
  - `ui/screens/NavigationBuilder.kt`: `composable(PlusTransferRoute) { PlusTransferScreen(navController) }` después de `settings/integrations`.
- Notas para sincronizar: si cambia el grupo de opciones de `AccountSettings`, mantener el elemento de transferir.

## 2026-10-18 — Trabajar desde el celular (nube) y herramienta de Spotify en el repo
- Qué: la herramienta Spotify→YouTube Music (`spotify_a_ytmusic.py`, `INSTRUCCIONES.md` y su `.gitignore`, que excluye los archivos privados) se copió a `herramientas/spotify-a-ytmusic/`. Se sigue ejecutando en la PC (`D:\Herramientas\spotify-a-ytmusic\`). `CLAUDE.md` ahora explica cómo trabajar desde la nube (no compilar ahí, subir a `claude/...` y que compile GitHub; qué hacer si el push da 403) y dónde vive la herramienta.
- Por qué: Claude Code desde el celular no veía el script (estaba solo en la PC), no podía compilar (no hay Android SDK en la nube) y no tenía permiso para subir a GitHub.
- Archivos: `herramientas/spotify-a-ytmusic/*` (propios), `CLAUDE.md`, `.github/workflows/metrolist-plus.yml` (`herramientas/**` en `paths-ignore`, para no publicar un APK al cambiar el script).
- Notas para sincronizar: nada del original se toca.

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
