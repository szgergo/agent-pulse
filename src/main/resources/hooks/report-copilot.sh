#!/bin/sh
# Copilot hook — detects copilot-cli / copilot-vscode / copilot-intellij via $PPID inspection.
# MUST always exit 0 — failure must never block the Copilot session.
trap 'exit 0' ERR

EVENTS_DIR="$HOME/.agent-pulse/events"
mkdir -p "$EVENTS_DIR" || exit 0
[ "$(find "$EVENTS_DIR" -maxdepth 1 -name '*.json' | head -1001 | wc -l)" -gt 1000 ] && exit 0

# Two-level process tree detection:
#   $PPID  = direct parent (the agent process that spawned this hook)
#   GPID   = grandparent ($PPID's parent)
# We check both because IntelliJ ACP mode wraps copilot behind a neutral binary,
# so the IntelliJ marker only appears in the grandparent.
# See: planning/research/agent-research.md → "Copilot Hook-Based Client Detection"
PARENT_ARGS=$(ps -p "$PPID" -o args= 2>/dev/null || true)
if echo "$PARENT_ARGS" | grep -q "github-copilot-intellij"; then
    AGENT_TYPE="copilot-intellij"   # IntelliJ plugin direct
else
    GPID=$(ps -p "$PPID" -o ppid= 2>/dev/null | tr -d '[:space:]' || true)
    GRANDPARENT_ARGS=$(ps -p "$GPID" -o args= 2>/dev/null || true)
    if echo "$GRANDPARENT_ARGS" | grep -q "github-copilot-intellij"; then
        AGENT_TYPE="copilot-intellij"   # IntelliJ ACP mode
    elif echo "$GRANDPARENT_ARGS" | grep -qE "copilotCLIShim|copilotCli/copilot"; then
        AGENT_TYPE="copilot-vscode"     # VS Code extension shim
    else
        AGENT_TYPE="copilot-cli"        # Homebrew/npm CLI or CLI in any IDE terminal
    fi
fi

T=$(mktemp "$EVENTS_DIR/.tmp.XXXXXX") || exit 0
cat > "$T" || { rm -f "$T" 2>/dev/null; exit 0; }
mv "$T" "$EVENTS_DIR/$(date +%s)-${AGENT_TYPE}-$1-$PPID.json" || { rm -f "$T" 2>/dev/null; exit 0; }
