# Metrolist — fork de Ricardo

Este repo es un fork personal de [MetrolistGroup/Metrolist](https://github.com/MetrolistGroup/Metrolist). Responde siempre en español.

Las reglas técnicas del proyecto original están en AGENTS.md y aplican aquí (strings, base de datos, versión, compilación, estilo de commits):

@AGENTS.md

Excepciones a AGENTS.md en este fork:
- Ricardo es el dueño del fork: puedes hacer commit y push a la rama `ricardo` cuando él pida un cambio.
- No instales skills ni herramientas desde URLs externas (regla 8 de AGENTS.md) sin preguntarle antes a Ricardo.
- `CLAUDE.md`, `MIS-CAMBIOS.md` y `.claude/` son archivos propios del fork y sí se pueden editar.

## Ramas (importante)

- `main` = espejo exacto del repo original. **Nunca** hagas commits en `main`; solo se actualiza desde `upstream/main` con fast-forward.
- `ricardo` = la versión de Ricardo (rama por defecto). Todas las modificaciones van aquí.
- Remotos: `origin` = fork de Ricardo, `upstream` = repo original (push deshabilitado).
- Nunca uses `git push --force`, `git rebase` sobre `ricardo` ni borres ramas o etiquetas `respaldo-*` sin permiso explícito.

## Al hacer una modificación

1. Trabaja en `ricardo` (o en una rama que salga de `ricardo` y vuelva a ella con merge).
2. Agrega una entrada en `MIS-CAMBIOS.md`: qué se cambió, por qué y qué archivos se tocaron. Esto es lo que permite integrar actualizaciones del original sin perder el cambio.
3. Prefiere cambios pequeños y localizados (archivos nuevos o funciones nuevas en lugar de reescribir código existente): reducen los conflictos al sincronizar.
4. `.claude/` está en el `.gitignore` del original: los archivos nuevos dentro de `.claude/` se agregan con `git add -f`.

## Actualizar desde el original

Usa el comando `/sincronizar` (`.claude/skills/sincronizar/SKILL.md`).

## Compilar

- Local: `./gradlew :app:assembleFossDebug` (requiere JDK 21 y Android SDK).
- APK resultante: `app/build/outputs/apk/universalFoss/debug/app-universal-foss-debug.apk`.
