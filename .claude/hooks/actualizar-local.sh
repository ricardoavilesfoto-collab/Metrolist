#!/usr/bin/env bash
# Al abrir Claude Code: trae a la copia local los cambios subidos a GitHub desde otro lugar (nube, celular).
# Nunca sobrescribe trabajo local: si no puede avanzar en limpio, solo avisa.

cd "$(dirname "$0")/../.." || exit 0
[ -n "$CLAUDE_CODE_REMOTE" ] && exit 0

avisar() {
  printf '{"systemMessage": "%s", "hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": "%s"}}\n' "$1" "$1"
  exit 0
}

rama=$(git rev-parse --abbrev-ref HEAD 2>/dev/null) || exit 0
[ "$rama" = "ricardo" ] || avisar "La copia local está en la rama $rama (no en ricardo); no se actualizó automáticamente."

git fetch origin ricardo --quiet 2>/dev/null || avisar "Sin conexión con GitHub; la copia local no se pudo actualizar."

pendientes=$(git rev-list --count HEAD..origin/ricardo)
[ "$pendientes" -eq 0 ] && exit 0

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  avisar "Hay $pendientes cambios nuevos en GitHub, pero tienes cambios locales sin guardar. Pídele a Claude que los combine."
fi

git merge --ff-only --quiet origin/ricardo 2>/dev/null ||
  avisar "Hay $pendientes cambios nuevos en GitHub que no se pueden aplicar directo porque la copia local tiene commits propios. Pídele a Claude que los combine."

avisar "Copia local actualizada: se trajeron $pendientes cambios desde GitHub."
