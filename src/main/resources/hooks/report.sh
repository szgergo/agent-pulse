#!/bin/sh
# MONITORING HOOK — MUST ALWAYS EXIT 0
# A non-zero exit blocks agent Bash operations (hooks-observability #30).
# Never use `set -e`. trap EXIT forces exit 0; commands also guarded with `|| exit 0`.
trap 'exit 0' EXIT
EVENTS_DIR="$HOME/.agent-pulse/events"
mkdir -p "$EVENTS_DIR" || exit 0
[ "$(find "$EVENTS_DIR" -name '*.json' -maxdepth 1 | head -1001 | wc -l)" -gt 1000 ] && exit 0
T=$(mktemp "$EVENTS_DIR/.tmp.XXXXXX") || exit 0
SUFFIX=${T##*.tmp.}
cat > "$T" || { rm -f "$T" 2>/dev/null; exit 0; }
mv "$T" "$EVENTS_DIR/$2-$1-$PPID-$SUFFIX.json" || { rm -f "$T" 2>/dev/null; exit 0; }
