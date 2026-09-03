# Notes for working on this plugin

Things that were got wrong here at least once. Read before changing the matching area.

## Settings UI (IntelliJ UI DSL)

**Nothing in a label or comment may contain a path or a URL.** They have no spaces, so no
wrap applies, and a panel must fit its widest child — one absolute path sets a floor under
the dialog's width and produces a horizontal scrollbar at any window size. Abbreviate paths
against `$HOME`, truncate, and put the full value in a tooltip.

**A comment longer than a line needs an explicit wrap width** (`COMMENT_WRAP` in
`ClaudeAcpConfigurable`). Without one it is laid out on a single line however long it is,
with the same consequence as above. Short comments do not need it.

**One field per row, `AlignX.FILL`.** A field sharing a row with a button gets squeezed to a
stub. Put the button on the next row.

**Sentinel values must not be editable.** An editable combo whose first entry was
`"Automatic"` let someone delete a letter and end up with a value that is neither a path nor
the default. Use a non-editable list, and add discovered values as real entries.

**`apply()` is not synchronous here.** It starts a background install, so `isModified()` —
which compares against live state — still reports a change until that finishes. Apply
followed by OK therefore asks twice. The guard lives in `ClaudeAcpManager.updateTo`, keyed by
version; do not rely on the UI to debounce.

**Disable controls while busy.** A spinner above live-looking buttons invites the second
click that caused the duplicate install above.

**Logic that can be wrong belongs in `ClaudeAcpPageModel`, not in the page.** Row labels and
the code that reads them back, the busy counter, what counts as modified, every bit of
wording — all of it is plain functions with tests, because the two bugs that shipped from
this file were exactly there and were only reachable by clicking. When adding a control, put
its rule in the model and let the Configurable hold Swing.

**Every button must say what it did.** These act on files and a config the user cannot see;
without a message line, "repaired" and "did nothing" look identical.

**Never do slow work on the EDT, and watch what "slow" hides behind.** Resolving the Node
interpreter runs `node -v` on every candidate, with a five-second timeout each. That sat
behind `ClaudeAcpManager.status()`, which the settings page called from `refreshStatus()`,
from `isModified()` — which the dialog calls on every keystroke — and from filling the
interpreter list while opening. Slow calls are named in their KDoc now; the page paints from
a snapshot taken on a background thread.

**Busy state is a counter, not a flag** — two overlapping operations otherwise have the first
one to finish re-enable every control while the second is still running. **Pair it in one
place.** Making it a counter turned scattered `beginBusy` calls from harmless into a hang:
six begins against two ends, and every action left the page spinning while its result
appeared below. `inBackground(title, busy) { }` owns the pair, in a `finally`. A callback API
used to close a busy state must fire on every path — `updateTo` returning silently on a
duplicate request was the same hang by another route.

## Files everything else is watching

**Every file this plugin writes is written atomically** — `Path.writeAtomically` — because
each has a reader watching it: the IDE re-reads `acp.json` on change, the adapter re-resolves
`.claude/settings.json`, and the shell reads the launcher when a chat starts. A plain write
truncates first, so a watcher looking in between sees an empty file; for the JSON pair that
reads as "not valid JSON", which is exactly the state both readers treat as "drop what you
had".

## Version management

**Never delete the active version.** `pruneOldVersions` kept the newest N, which after a
rollback is exactly wrong — the running adapter is then the oldest copy on disk. Pass the
active version and exclude it explicitly. Regression test in `AdapterInstallerTest`.

**Never delete a version a process is running from.** Each open chat runs two processes out
of one version directory — the adapter, and the native Claude Code binary the SDK spawns
beside it. Unlinking that directory does not kill them, but the first thing they have not
already loaded, above all the binary for a new session, is then gone. `versionsInUse()`
reads live command lines through `ProcessHandle`; it is best effort (unavailable for other
users' processes, commonly empty on Windows) so it under-reports and never over-reports.
This bites automatically, not just on the cleanup dialog: an update installs and prunes
immediately, while an old chat is still running.

**Do not word a downgrade as an update.** The balloon compares against the previous version
and says installed / updated to / rolled back to / reinstalled.

**The npm cache is not ours.** Re-installing a version is fast because npm caches tarballs in
`~/.npm/_cacache`, shared with every project on the machine (several GB here). Do not offer
to clear it from this plugin. Only the version directories under
`~/.jetbrains/claude-acp-adapter/versions` belong to us.

## The agent config

`~/.jetbrains/acp.json` is shared with every other locally defined ACP agent and with
`default_mcp_settings`. Always merge, never rewrite; refuse to write when the file does not
parse, or the user's other agents are silently dropped. `AcpConfigFileTest` covers this.

**`env` there can only set variables, never unset them.** `ANTHROPIC_API_KEY` and friends
must be stripped by the generated launcher script, otherwise Claude Code bills the API
instead of the subscription.

**Do not write a full inherited `PATH` into the entry.** It bakes in whatever ephemeral
directories the launching shell had and rewrites the file whenever they change. The launcher
sets `PATH` itself.

**Renaming the agent orphans its entry.** The IDE keys agents by display name, so
provisioning sweeps entries whose command is inside our adapter directory but whose name is
no longer the configured one.

## Node resolution

Use `EnvironmentUtil.getEnvironmentMap()`, not `System.getenv()`: an IDE started from the
Dock inherits `/usr/bin:/bin:/usr/sbin:/sbin` with no Homebrew and no nvm.

Prefer the running IDE's own ACP runtime before other IDEs' caches. Sorting every IDE's
runtimes by node version picks a 2026.1 runtime while running under 2026.2, which breaks when
those caches are cleaned.

Do not depend on the NodeJS plugin to read the project interpreter: it is absent from several
IDEs that speak ACP, and a hard dependency would block installation there.

## Build

**The build must not require a local IDE or AI Assistant.** The platform falls back to the
published artifact, and `AgentIconService` — internal AI Assistant API that ships only inside
an IDE installation — is a compileOnly stub in `src/stub` that is never packaged. Verified
against 262.9437.276, where that interface has exactly one method; if it gains another, our
implementation stops satisfying it, which is why it sits behind an optional descriptor.

**Internal API goes in an optional descriptor** (`claude-agent-icon.xml`). That is what lets
the plugin declare no upper build bound: a future IDE that renames the extension point costs
the icon, not the installation.

**JVM target is 25**, because the platform's own Kotlin inline functions (`BaseState.enum()`
among them) are compiled at 25 and Kotlin will not inline newer bytecode into an older
target. Build with the JetBrains Runtime:
`JAVA_HOME=/Applications/WebStorm.app/Contents/jbr/Contents/Home`.

**Internal platform API fails the build, not just the review.** `verifyPlugin` reports
`INTERNAL_API_USAGES` as a failure even when the verdict line says "Compatible" —
`PluginManagerCore.getPlugin` cost one CI run. Anything annotated `@ApiStatus.Internal` or
`@IntellijInternalApi` is off limits; look for a public route, or for evidence somewhere else
entirely. Detecting a rival agent by reading `acp.json` turned out better than asking the
platform which plugins are installed.

Deprecated and experimental usages are reported but do not fail. The current ones come from
implementing `DynamicPluginListener`: Kotlin generates overrides for a Java interface's
default methods, so the verifier counts members the code never mentions.

**Check for deprecation before using a platform API.** Found the hard way:
`FileChooserDescriptorFactory.createSingleFileDescriptor()` and
`createSingleLocalFileDescriptor()` are both deprecated — the current one is `singleFile()`.
`Notification.setListener` is deprecated in favour of `addAction`.
`IdeaPluginDescriptor.isEnabled` is deprecated in favour of `PluginManagerCore.isDisabled`.

## Verifying a change

```bash
JAVA_HOME=/Applications/WebStorm.app/Contents/jbr/Contents/Home ./gradlew test buildPlugin
./test/handshake.sh     # claude-ai-login must be present
```

Installing locally means quitting the IDE first — replacing a loaded plugin directory under a
running WebStorm is not safe:

```bash
osascript -e 'quit app "WebStorm"'
rm -rf ~/Library/Application\ Support/JetBrains/WebStorm2026.2/plugins/claude-code-acp-bridge
unzip -q build/distributions/claude-code-acp-bridge-*.zip \
  -d ~/Library/Application\ Support/JetBrains/WebStorm2026.2/plugins
open -a WebStorm
```

`verifyPlugin` downloads a whole IDE, so it runs on CI rather than locally.

## Things not to break

The point of the plugin is one absent flag: the adapter must be launched **without**
`--hide-claude-auth`. With it, `claude-ai-login` disappears from `authMethods` and any account
with a `subscriptionType` is rejected at session start. `test/handshake.sh` exists to catch
that regression.

## Kotlin gotcha

Block comments nest in Kotlin. A glob such as a slash followed by a star inside a KDoc
opens a nested comment, and everything to the end of the file becomes part of it — the
compiler reports "unclosed comment" at the last line, nowhere near the cause.

## Claude Code settings

`.claude/settings.json` and `.claude/settings.local.json` belong to the user and to Claude
Code, not to this plugin. Merge the keys being edited and leave the rest — those files also
carry hooks, MCP servers and environment settings. Refuse to write when the file does not
parse. `ClaudeSettingsFileTest` covers it.

An escalating `permissions.defaultMode` coming from a repo-committed source is filtered out
by the CLI's trust policy, so it has to be set in personal settings to take effect.

**These files have another writer.** Picking "Always Allow" on a permission prompt in the
chat makes the agent persist a rule through the SDK's `PermissionUpdate`, whose destination
is `projectSettings` or `localSettings` — the very files this plugin's page edits. Writing
back the lists as they looked when the page opened deletes every rule approved since, so the
page applies a three-way merge: disk, minus what the user removed, plus what the user added.
`defaultMode` follows the same rule and is only written when it was actually changed.

**Rules still apply under `auto`.** The classifier only handles prompts that reach it; a deny
rule short-circuits first, and the SDK lists both as separate sources of an auto-denial. The
modes where rules genuinely decide nothing are `bypassPermissions`, which skips the checks,
and `plan`, which runs no tools.

## Scope of settings

**Do not add a setting the user cannot fill in correctly.** `availableModels` was offered and
removed: model ids cannot be enumerated from here, the chat's own picker already covers
choosing a model, and the key is documented as an administrator's control. A field that
demands knowledge the UI does not provide is worse than no field.

**Do not add a second top-level entry for the same feature.** The permissions page is nested
under the plugin's own page via `parentId`. It cannot be merged into it outright, because one
edits a project file and the other holds application-wide settings, and a Configurable is
either project- or application-scoped — but that is a reason to nest, not to sprawl.

**Offer every valid value, not a subset.** The permission-mode dropdown shipped with four of
the six modes the SDK schema accepts, which makes the two that were missing — `auto` and
`dontAsk` — look invalid. When a field mirrors an upstream enum, copy the enum.
