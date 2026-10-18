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

## 2026-10-17 — Configuración inicial del fork
- Qué: se agregaron `CLAUDE.md`, `MIS-CAMBIOS.md`, el comando `/sincronizar` y la actualización automática de la copia local al abrir Claude Code.
- Por qué: mantener el fork al día con el original sin perder las modificaciones propias.
- Archivos: `CLAUDE.md`, `MIS-CAMBIOS.md`, `.claude/settings.json`, `.claude/hooks/actualizar-local.sh`, `.claude/skills/sincronizar/SKILL.md`.
- Notas para sincronizar: archivos exclusivos del fork; el original no los tiene.

## Sincronizaciones con el original

| Fecha | Commit del original integrado | Conflictos | Respaldo |
|-------|-------------------------------|------------|----------|
| 2026-10-17 | `76fa5a78` (punto de partida del fork) | — | — |
