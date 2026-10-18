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
