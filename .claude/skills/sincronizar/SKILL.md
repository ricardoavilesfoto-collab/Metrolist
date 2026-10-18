---
name: sincronizar
description: Trae las actualizaciones del Metrolist original (upstream) al fork de Ricardo y las integra en la rama `ricardo` sin perder sus modificaciones. Úsalo cuando Ricardo diga "sincronizar", "actualizar desde el original", "trae lo nuevo de Metrolist", "el fork está atrasado" o similar.
---

# Sincronizar con el Metrolist original

Objetivo: integrar lo nuevo de `upstream/main` en `ricardo` **conservando todas las modificaciones de Ricardo**. Habla siempre en español y en términos sencillos (Ricardo no es programador).

Reglas: nunca `push --force`, nunca `rebase` de `ricardo`, nunca commits en `main`, nunca descartar cambios de Ricardo para "resolver" un conflicto. Ante una duda real de intención, pregunta.

## 1. Preparar

1. Si no existe el remoto `upstream`, créalo: `git remote add upstream https://github.com/MetrolistGroup/Metrolist.git` y `git remote set-url --push upstream DISABLE`. Activa `git config rerere.enabled true` y `git config merge.conflictstyle zdiff3`.
2. `git status`: si hay cambios sin commit, detente y pregunta qué hacer con ellos (commit en `ricardo` o guardarlos con `git stash`).
3. `git switch ricardo` y `git pull --ff-only origin ricardo`.
4. Respaldo: `git tag respaldo-AAAA-MM-DD` (si ya existe ese día, agrega `-2`, `-3`…) y `git push origin <etiqueta>`.

## 2. Analizar qué hay de nuevo

1. `git fetch upstream --tags`.
2. Si `git rev-list --count main..upstream/main` es 0: dile a Ricardo que no hay novedades y termina.
3. Resume para Ricardo, en español y agrupado por tema (funciones nuevas, arreglos, cambios visuales, traducciones, dependencias), lo que trae `git log --oneline main..upstream/main`. Omite el ruido (traducciones automáticas, bumps de dependencias) en una sola línea.
4. Cruza con sus cambios:
   - Archivos tocados por Ricardo: `git diff --name-only main...ricardo`.
   - Archivos tocados por el original: `git diff --name-only main upstream/main`.
   - La intersección son los archivos con riesgo de conflicto. Para cada uno, lee la entrada correspondiente en `MIS-CAMBIOS.md` y explica en una línea qué cambió el original ahí y si afecta a su modificación.
5. Pregunta si continúa. (Si no hay archivos en común, puedes continuar directamente avisando que no se esperan conflictos.)

## 3. Integrar

1. Actualiza el espejo: `git switch main`, `git merge --ff-only upstream/main`, `git push origin main`. Si el fast-forward falla, `main` tiene commits ajenos al original: detente y explica la situación, no la arregles por tu cuenta.
2. `git switch ricardo` y `git merge main --no-ff -m "merge: sincroniza con Metrolist original (<hash corto de upstream/main>)"`.
3. Si no hay conflictos, salta al paso 4.
4. Si hay conflictos, para cada archivo de `git diff --name-only --diff-filter=U`:
   - Lee el bloque completo (con `zdiff3` verás la base, la versión de Ricardo y la del original).
   - Revisa qué pretendía el original (`git log main@{1}..main -- <archivo>` y el diff del commit) y qué pretendía Ricardo (`MIS-CAMBIOS.md` y `git log main@{1}..ricardo -- <archivo>`).
   - Integra **ambas** intenciones: conserva la funcionalidad de Ricardo y adopta los arreglos o cambios del original alrededor de ella. Si el original reescribió o movió el código que Ricardo modificó, traslada la modificación de Ricardo a la nueva estructura.
   - Si las dos intenciones son incompatibles, no elijas tú: explica las opciones a Ricardo y pregunta.
   - Archivos de traducción (`values-*/strings.xml`, `values-*/metrolist_strings.xml`) sin cambios de Ricardo: toma la versión del original.
   - Explica a Ricardo cada resolución en 1-2 líneas.
   - Al terminar, verifica que no queden marcadores `<<<<<<<`, `=======`, `>>>>>>>` y haz `git add` de los archivos resueltos.
5. Si en cualquier momento la integración se complica demasiado: `git merge --abort` deja todo como estaba.

## 4. Verificar

1. Si hay JDK 21 y Android SDK disponibles, compila: `./gradlew :app:assembleFossDebug` (en Windows `gradlew.bat`). Si falla por algo que trajo la integración, corrígelo antes de hacer commit.
2. Si no hay entorno de Android, dilo claramente y revisa con más cuidado que los archivos resueltos sean Kotlin válido (llaves, imports, nombres que el original haya renombrado).
3. Completa el merge con `git commit --no-edit` (si hubo conflictos).

## 5. Registrar y publicar

1. Agrega una fila a la tabla "Sincronizaciones con el original" de `MIS-CAMBIOS.md` (fecha, hash corto integrado, conflictos resueltos, etiqueta de respaldo). Si alguna modificación de Ricardo tuvo que adaptarse, actualiza su entrada ("Notas para sincronizar") y la lista de archivos. Commit: `docs: registra sincronización con el original`.
2. Muestra el resumen final: qué se integró, qué conflictos hubo y cómo se resolvieron, resultado de la compilación.
3. **Pregunta antes de subir.** Con su aprobación: `git push origin ricardo`.
4. Recuérdale que si algo no funciona puede volver al estado anterior con la etiqueta de respaldo (y que tú lo haces por él).

## Volver atrás

Si Ricardo pide deshacer la última sincronización: confirma con él, crea primero otra etiqueta de respaldo del estado actual, y luego `git revert -m 1 <commit del merge>` (preferido, no reescribe historia). Solo si él lo pide explícitamente y la sincronización aún no se subió, `git reset --hard respaldo-<fecha>`.
