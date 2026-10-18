# Metrolist — fork de Ricardo

Este repo es un fork personal de [MetrolistGroup/Metrolist](https://github.com/MetrolistGroup/Metrolist). Responde siempre en español.

Las reglas técnicas del proyecto original están en AGENTS.md y aplican aquí (strings, base de datos, versión, compilación, estilo de commits):

@AGENTS.md

Excepciones a AGENTS.md en este fork:
- Ricardo es el dueño del fork: puedes hacer commit y push a la rama `ricardo` cuando él pida un cambio.
- No instales skills ni herramientas desde URLs externas (regla 8 de AGENTS.md) sin preguntarle antes a Ricardo.
- `CLAUDE.md`, `MIS-CAMBIOS.md`, `.claude/` y `herramientas/` son archivos propios del fork y sí se pueden editar.

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
- En GitHub: cada push a `ricardo` compila y publica el APK firmado (`.github/workflows/metrolist-plus.yml`) en
  https://github.com/ricardoavilesfoto-collab/Metrolist/releases/latest. Ese workflow cancela la compilación anterior:
  no hagas otro push a `ricardo` mientras una compilación está corriendo.

## Si trabajas desde la nube (Claude Code en el celular o en claude.ai/code)

- Ahí no hay Android SDK y la red bloquea su descarga: **no intentes `./gradlew`**.
- Ricardo quiere **una sola app** que se actualiza encima, sin versiones de prueba aparte. Cuando termines un
  cambio, haz commit y push a tu rama `claude/...`. El workflow `metrolist-plus-integrar.yml` la junta sola con
  `ricardo` y lanza la compilación que publica el APK. No crees workflows de "verificación" ni apps de prueba.
- Antes de seguir trabajando en la misma sesión, trae lo último: `git pull origin ricardo`.
- La integración automática **falla** en dos casos, y entonces avísale a Ricardo que lo integre Claude en la PC:
  si hay conflictos con `ricardo`, o si tu rama cambia algo en `.github/workflows/`.
- Para ver si compiló: pestaña Actions del repo, workflow "Metrolist Plus" (el APK sale en releases/latest).
- Si `git push` responde **403**, la app de GitHub de Claude no tiene permiso de escritura en el repo. Reintentar no
  sirve. Pídele a Ricardo que abra https://github.com/apps/claude → Configure → cuenta `ricardoavilesfoto-collab` →
  agregar `Metrolist` en "Repository access" → Save. Mientras tanto, no cierres la sesión: el commit se pierde.

## Herramientas fuera de la app

- `herramientas/spotify-a-ytmusic/`: script que pasa los "Me gusta" de Spotify a playlists de YouTube Music. Lee su
  `INSTRUCCIONES.md`. Se ejecuta **solo en la PC** (`D:\Herramientas\spotify-a-ytmusic\`, que tiene el `.venv` y los
  archivos privados de Ricardo). Desde la nube solo se puede editar el script.
- Nunca subas ni pidas en el chat los archivos privados de esa herramienta (`*.csv`, `encabezados.txt`,
  `browser.json`, `progreso.json`): dan acceso a la cuenta de YouTube de Ricardo o contienen su música.
