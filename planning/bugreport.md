Same issue here. Copilot Pro+ (personal), CLI v1.0.15, macOS.

I dug into the CLI source (`app.js`) and found the chain of events:

1. There's a feature flag `copilot_cli_mcp_allowlist` that gates an org registry check
2. CLI calls `GET /copilot/mcp_registry` — returns 404 for individual plans
3. The catch block goes fail-closed: all non-default MCP servers get blocked

What's interesting in my case: I'm a member of a few orgs I created for open source projects. Copilot is **not enabled** on any of them — I've never configured it. But `gh api /orgs/<my-org>/copilot/billing` returns a phantom `plan_type: "business"` with 0 seats and everything set to `"unconfigured"`. I suspect the CLI sees these and tries to enforce registry policies that don't exist.

Weirdly, the same context7 server works fine in:
- Copilot Chat extension in IntelliJ IDEA (starts normally, tools exposed)
- Copilot CLI from VS Code's terminal (even resuming the same session works, all MCP servers load)

But in IntelliJ IDEA's terminal, the CLI blocks it — even for the same session that works from VS Code. One more thing: in VS Code's terminal, the default shell is zsh, but as soon as I start copilot it switches to bash. Not sure if that's related but worth noting. `copilot --resume <session-id>` works fine with MCP servers loaded. But if I start a fresh `copilot`, then `/resume`, then pick the session by name — MCP servers get blocked. Same session, different resume path, different result.

I also confirmed that `GITHUB_COPILOT_3P_MCP_ENABLED=true` passes the first check (the code does `=== "true"` strict comparison), but the registry fetch failure downstream still blocks everything.

**env:**
- CLI 1.0.15, macOS 26.4 (arm64)
- Copilot Pro+ (`api.individual.githubcopilot.com`)
- Orgs with phantom Business plans, Copilot never enabled on them
