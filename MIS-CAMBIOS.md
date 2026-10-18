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
