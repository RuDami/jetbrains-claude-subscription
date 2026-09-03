# Claude Code ACP Bridge — use a Claude Pro/Max subscription in JetBrains IDEs

> **Unofficial plugin.** A personal community project — not affiliated with, endorsed by,
> or supported by JetBrains or Anthropic. It is not distributed on JetBrains Marketplace;
> install it from the release zip below.

A JetBrains IDE plugin that adds a **Claude Code (Subscription)** agent to AI Chat and signs
in with your **Claude Pro or Max subscription** instead of an Anthropic API key. It installs
and updates the official ACP adapter for you.

Works in WebStorm, IntelliJ IDEA, PhpStorm, PyCharm, GoLand, RubyMine, CLion, Rider and
DataGrip — any 2026.2+ JetBrains IDE with the AI Assistant plugin.

[Download the latest release](https://github.com/RuDami/jetbrains-claude-subscription/releases/latest)
· [Why this exists](#why-this-plugin-exists) · [Install](#install) · [Troubleshooting](#troubleshooting)

> **Fork notice.** Derived from
> [vanssata/jetbrains-claude-subscription](https://github.com/vanssata/jetbrains-claude-subscription)
> (MIT); `AcpConfigFile`, the icon extension point and the local-IDE build setup come from
> there — see [`NOTICE`](NOTICE) for the file-by-file list.

## Table of contents

- [The problem this solves](#the-problem-this-solves)
- [Why this plugin exists](#why-this-plugin-exists)
- [Requirements](#requirements)
- [Install](#install)
- [What it does](#what-it-does)
- [Settings](#settings)
- [Permissions](#permissions)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)
- [Build from source](#build-from-source)
- [Verify it works](#verify-it-works)
- [CLI fallback (Zed and other ACP clients)](#cli-fallback-zed-and-other-acp-clients)
- [Credits and links](#credits-and-links)
- [Other Claude plugins for JetBrains](#other-claude-plugins-for-jetbrains-and-how-this-one-differs)

## The problem this solves

If you have a Claude Pro or Max subscription and try to use the Claude agent that ships in
the JetBrains ACP Registry, sign-in fails with one of these:

```
This integration does not support using claude.ai subscriptions.
```

```
Authentication was reset. Please create a new chat for the change to take effect.
```

The subscription login option may not appear at all — the agent offers only **Anthropic
Console** (API billing) or JetBrains AI credits. This plugin restores the **Claude
Subscription** login.

## Why this plugin exists

The agent JetBrains ships in its ACP Registry launches
[`@agentclientprotocol/claude-agent-acp`](https://github.com/agentclientprotocol/claude-agent-acp)
with the flag `--hide-claude-auth`. In that package the flag does two things:

1. removes the `claude-ai-login` ("Claude Subscription") method from `authMethods`, leaving
   only Anthropic Console and gateway logins;
2. in `newSession`, rejects any account that has a `subscriptionType`, which is the error
   quoted above.

This plugin runs **the same official adapter without that flag**. Nothing is patched or
forked: the package supports subscription login by default.

## Requirements

| | |
|---|---|
| IDE | Any JetBrains IDE 2026.2 or newer |
| Plugin | JetBrains AI Assistant (`com.intellij.ml.llm`) installed |
| Runtime | Node.js 22+ — your own, or the one the IDE downloads for its own ACP agents |
| Account | A Claude Free, Pro or Max subscription |

No Anthropic API key is required, and none is used.

## Install

1. Download the plugin zip from
   [Releases](https://github.com/RuDami/jetbrains-claude-subscription/releases/latest).
2. In the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip.
3. Restart the IDE. The plugin downloads the adapter on first start.
4. Open AI Chat, pick the **Claude Code (Subscription)** agent.
5. **Log in → Claude Subscription**, and complete the browser sign-in.

If the upstream plugin or a hand-written agent entry is already registered, the plugin says
so — two things maintaining the same entry rewrite each other on every start.

## What it does

- Installs the adapter with `npm` into `~/.jetbrains/claude-acp-adapter/versions/<version>/`,
  rather than running `npx` on every agent start. Starting the agent needs no network, the
  version is reproducible, and the previous build stays on disk for rollback.
- Generates a launcher script that clears `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`,
  `ANTHROPIC_API_KEY_HELPER` and the Bedrock/Vertex switches. This matters: with an API key
  in the environment Claude Code bills the API instead of your subscription, and the `env`
  block in `acp.json` can only *set* variables, never remove them.
- Registers the agent in `~/.jetbrains/acp.json`, merging into the file rather than
  rewriting it — other agents and your `default_mcp_settings` are left alone, and a file
  that does not parse is refused rather than replaced.
- Polls the npm registry once a day and offers new adapter releases.
- Keeps the two most recent adapter versions on disk.

Updating the adapter needs no IDE restart: the IDE re-reads `acp.json` on the fly, and a
**new** chat picks up the new version — a chat already running keeps the process it started
with.

## Settings

**Settings → Tools → Claude Code ACP Bridge**

| Control | What it does |
|---|---|
| Version | Every release in the registry plus what is downloaded, marked *active* / *downloaded*. Pick one, press OK, and the plugin switches to it — an older build is a rollback. |
| Downloaded | How much disk the adapters use. |
| Check for Updates | Asks the registry for newer releases and answers on the page. |
| Repair | Re-downloads the current adapter if its files went missing and rewrites the agent entry. Use it when the agent stops appearing in the chat. |
| Free Up Space | Dialog listing downloaded versions with sizes; tick what to delete. The version in use is never offered. |
| On a new release | Notify and install on click, install silently, or never check. |
| Registry | Known mirrors, editable — a private Nexus or Artifactory can be typed in. |
| MCP | Whether the agent sees the IDE's MCP server and your own MCP servers. |
| Node.js | Interpreters found on this machine, or browse for one. |
| Name in the chat list | What the agent is called. The IDE derives its id from this, so renaming starts a fresh agent — the model and permission mode remembered for the old name do not follow, and the old entry is removed. |
| Add / Remove Agent | Removes the entry from `acp.json`, deletes the downloaded adapters and disables the page. Add turns it all back on. |
| Restore Defaults | Newest adapter, public registry, automatic interpreter. |

## Permissions

**Settings → Tools → Claude Code ACP Bridge → Permissions** edits the project's
`.claude/settings.json`, which Claude Code reads and the adapter watches — changes reach a
running agent immediately.

- **Applies to** — settings shared with the team (`.claude/settings.json`) or kept to this
  machine (`.claude/settings.local.json`).
- **Default mode** — `default`, `plan`, `acceptEdits`, `auto`, `dontAsk` or
  `bypassPermissions`. Claude Code ignores an escalating mode arriving from committed
  settings, so set those under *Only me*.
- **Always allow / Always ask / Never allow** — rules in Claude Code's own syntax, one per
  line: `Bash(npm run test:*)`, `Read(./.env)`, or a bare tool name such as `Edit`. Deny
  wins over allow.

Rules you approve in the chat land in the same file. Applying merges rather than overwrites,
so an "Always Allow" granted while the page was open is not lost.

## Troubleshooting

**"This integration does not support using claude.ai subscriptions."**
You are on the bundled Claude agent from the ACP Registry, not this one. Pick **Claude Code
(Subscription)** in the agent list.

**"Authentication was reset. Please create a new chat for the change to take effect."**
Same cause as above.

**The agent is missing from the chat list.**
Open the settings page and press **Repair**. If it reports no Node.js, install Node 22+ or
select an interpreter explicitly.

**"No usable Node.js runtime found."**
The plugin searches your login shell's `PATH` and then the runtimes the IDE downloaded for
its own ACP agents. Install Node 22+, or set the path in settings. An IDE started from the
Dock or Finder inherits a minimal `PATH`, which is why the login shell is consulted.

**Usage is billed to the API instead of the subscription.**
Check for `ANTHROPIC_API_KEY` in your environment. The generated launcher clears it, so this
should not happen — if it does, please open an issue.

**The agent entry keeps changing between restarts.**
Two things are managing it. Disable the other plugin, or remove its entry from `acp.json`.

## FAQ

**Does this need an Anthropic API key?** No. That is the point — it uses your Claude
subscription.

**Is Claude Code patched or reimplemented?** No. The official adapter is downloaded from npm
and launched without one flag. All model, tool and authentication behaviour is that
package's.

**Is this allowed?** Anthropic's terms state that signing in to the unmodified Claude Code
binary with your own Claude subscription is permitted; what is prohibited is a third party
reselling or intermediating Claude usage. See
[Claude Code legal and compliance](https://code.claude.com/docs/en/legal-and-compliance).
Nothing here is modified, and nobody sits between you and Anthropic.

**Which IDEs?** Any JetBrains IDE 2026.2+ with AI Assistant.

**Does it work offline?** Starting the agent does. Installing or updating the adapter needs
the npm registry.

**Where does it put things?** `~/.jetbrains/claude-acp-adapter/` for adapters and the
launcher, and one entry in `~/.jetbrains/acp.json`.

## Build from source

Needs a JDK 21+ — the JetBrains Runtime inside any installed IDE will do. The Gradle wrapper
is in the repository.

```bash
./gradlew test buildPlugin
```

The artifact is `build/distributions/claude-code-acp-bridge-<version>.zip`.

Nothing needs configuring. If a JetBrains IDE is installed, the build uses it; otherwise it
downloads the published platform — which is why the same build runs on CI. The one piece of
internal API, `AgentIconService`, is a compile-only stub in `src/stub` and never packaged: at
runtime the class comes from AI Assistant.

```bash
./gradlew verifyPlugin
```

runs the official JetBrains Plugin Verifier, the same check the Marketplace performs on
upload.

## Verify it works

```bash
./test/handshake.sh
```

Sends an ACP `initialize` to the generated launcher with a deliberately poisoned
`ANTHROPIC_API_KEY` and prints the adapter version and its auth methods. `claude-ai-login`
must be in the list — that is the method the bundled agent suppresses, and the whole reason
this plugin exists.

## CLI fallback (Zed and other ACP clients)

`bin/claude-acp-sub` runs the same adapter as a plain shell script, for use without the
plugin — another editor, Zed, or debugging. Run `npm install` in the repository root first.

## Credits and links

- **[@agentclientprotocol/claude-agent-acp](https://github.com/agentclientprotocol/claude-agent-acp)**
  ([npm](https://www.npmjs.com/package/@agentclientprotocol/claude-agent-acp)) — the ACP
  adapter that does the actual work. Apache-2.0, by Anthropic, Zed Industries and JetBrains.
  This plugin neither bundles nor modifies it; it is downloaded from npm on your machine.
- **[Agent Client Protocol](https://agentclientprotocol.com)** — the protocol IDEs and coding
  agents speak.
- **[ACP in JetBrains AI Assistant](https://www.jetbrains.com/help/ai-assistant/acp.html)** —
  how the IDE picks up custom agents, and the `~/.jetbrains/acp.json` schema.
- **[vanssata/jetbrains-claude-subscription](https://github.com/vanssata/jetbrains-claude-subscription)**
  — the upstream project this forks (MIT).
- [`CLAUDE.md`](CLAUDE.md) — notes for anyone (or any agent) changing this code.

## Keywords

Claude Code JetBrains plugin · Claude Code [Beta] alternative · Claude Code with GUI ·
Swttch · CC GUI · Claude Agent ACP registry · Claude Pro subscription IntelliJ · Claude Max
subscription WebStorm · Agent Client Protocol · ACP agent JetBrains · claude-agent-acp · use
Claude without API key in IDE · `--hide-claude-auth` · claude.ai subscription not supported ·
JetBrains AI Assistant custom agent · acp.json · Claude Code in PhpStorm, PyCharm, GoLand,
RubyMine, CLion, Rider, DataGrip

## Other Claude plugins for JetBrains, and how this one differs

The Marketplace carries several plugins with similar names. This is not one of them — it is
not on the Marketplace at all — so if you arrived looking for one of these, follow the link.

- **[Claude Code [Beta]](https://plugins.jetbrains.com/plugin/27310-claude-code-beta)** —
  Anthropic's own JetBrains plugin, with its own chat window. If it covers what you need,
  use it: it is official and supported.
- **[Claude Code with GUI / Swttch](https://plugins.jetbrains.com/plugin/30313-claude-code-with-gui)**
  and **[CC GUI](https://plugins.jetbrains.com/plugin/29342)** — third-party graphical front
  ends for the Claude Code CLI, in the spirit of the Cursor and VS Code interfaces.
- **Claude Agent** — the agent JetBrains ships in its own ACP Registry, inside AI Assistant.
  It runs the same adapter as this project but with `--hide-claude-auth`, which is what
  rejects a Claude.ai subscription.

**What this project is instead:** the smallest possible difference from that last one. It
registers the same official adapter in JetBrains AI Chat *without* that flag, so the agent
lives in the IDE's own AI Assistant chat — same interface, same MCP integration, same
permission prompts — and signs in with a Pro/Max subscription. No separate window, no second
chat UI, no API key.

Pick this one if you want Claude inside JetBrains AI Chat on a subscription. Pick one of the
others if you want a dedicated Claude window or an officially supported plugin.

## Attribution

`icons/claude.svg` is Anthropic's Claude mark, taken from the official Claude Code JetBrains
plugin so the agent is visually recognisable in the chat list. This project is a personal
integration tool, not affiliated with or endorsed by Anthropic or JetBrains, and the mark
remains Anthropic's. The plugin's own icon — the one shown for the plugin itself — is not
Anthropic's and was drawn for this project.

## License

MIT — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
