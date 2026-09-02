#!/bin/sh
# Sends an ACP `initialize` to the launcher the plugin generates and prints the auth
# methods it advertises.
#
# `claude-ai-login` must be in the list — that is the method the IDE's own Claude agent
# suppresses with --hide-claude-auth, and the whole reason this plugin exists.
#
# ANTHROPIC_API_KEY is deliberately poisoned here: the launcher has to strip it, otherwise
# Claude Code bills the API instead of the subscription.

set -e

LAUNCHER="${1:-$HOME/.jetbrains/claude-acp-adapter/launch.sh}"

if [ ! -x "$LAUNCHER" ]; then
  echo "no launcher at $LAUNCHER — start the IDE once so the plugin provisions it" >&2
  exit 1
fi

REQUEST='{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{"auth":{"terminal":true}}}}'

printf '%s\n' "$REQUEST" \
  | env -i HOME="$HOME" ANTHROPIC_API_KEY=poison PATH=/usr/bin:/bin "$LAUNCHER" \
  | python3 -c '
import json, sys
result = json.loads(sys.stdin.readlines()[-1])["result"]
methods = [m["id"] for m in result["authMethods"]]
print("adapter    :", result["agentInfo"]["version"])
print("authMethods:", methods)
print("subscription login:", "OK" if "claude-ai-login" in methods else "MISSING")
sys.exit(0 if "claude-ai-login" in methods else 1)
'
