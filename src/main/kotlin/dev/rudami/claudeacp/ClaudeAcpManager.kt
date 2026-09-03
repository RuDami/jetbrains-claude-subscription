package dev.rudami.claudeacp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.io.HttpRequests
import com.intellij.util.io.RequestBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Everything the plugin actually does: install the adapter, keep `acp.json` pointing at
 * it, and watch the npm registry for newer versions.
 */
@Service(Service.Level.APP)
class ClaudeAcpManager(private val scope: CoroutineScope) {

    private val settings get() = ClaudeAcpSettings.getInstance()
    private val installer get() = AdapterInstaller.getInstance()

    private var updateLoop: Job? = null

    /** The registry list is stable enough that reopening settings need not refetch it. */
    @Volatile
    private var cachedVersions: List<String>? = null

    /** Which registry [cachedVersions] came from; see [availableVersions]. */
    @Volatile
    private var cachedRegistry: String? = null

    /** Versions with an install running, so a repeated request is not honoured twice. */
    private val installsInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Human-readable state for the settings page. */
    data class Status(val installedVersion: String?, val nodePath: String?)

    fun status(): Status {
        val installed = settings.state.installedVersion?.takeIf { installer.isInstalled(it) }
        return Status(
            installedVersion = installed,
            nodePath = NodeRuntimeResolver.resolve()?.node?.toString(),
        )
    }

    // ---------------------------------------------------------------- provisioning

    /**
     * Brings the on-disk state in line with the settings: a usable adapter installed, a
     * launcher pointing at it, and our entry in `acp.json`.
     *
     * Safe to call repeatedly — [AcpConfigFile.upsertAgent] rewrites nothing when the entry
     * already matches, which matters because this runs on every IDE start.
     */
    fun provision(indicator: ProgressIndicator? = null): Result<Unit> {
        if (!settings.state.manageAgent) {
            LOG.info("Agent management disabled in settings; leaving ${AcpConfigFile.path} alone")
            return Result.success(Unit)
        }

        val runtime = NodeRuntimeResolver.resolve()
        if (runtime == null) {
            notifyNoNode()
            return Result.failure(IllegalStateException("no node runtime"))
        }

        val version = resolveTargetVersion() ?: return Result.failure(
            IllegalStateException("no adapter version installed and the registry is unreachable"),
        )

        val entryPoint = installer.entryPoint(version)
            ?: installer.install(version, runtime, indicator).getOrElse { failure ->
                notify(
                    NotificationType.ERROR,
                    "Could not install the Claude ACP adapter",
                    failure.message ?: "npm install failed.",
                )
                return Result.failure(failure)
            }

        // Order matters. Pruning first would delete the old version while the launcher still
        // points at it, and recording the new version before the launcher is rewritten would
        // leave the settings claiming a version the agent is not actually running.
        val launcher = LauncherScript.write(installer.root, runtime, entryPoint, installer.versionDir(version))
        val result = writeAgentEntry(launcher.toString(), version)

        settings.state.installedVersion = version
        installer.pruneOldVersions(active = version)
        return result
    }

    /**
     * Which version we should be running: whatever is already installed, and only a first
     * run reaches out to the registry.
     *
     * Staying on the installed version is what makes "pinning" unnecessary — nothing moves
     * it except an update the user accepted or a version they picked in settings.
     */
    private fun resolveTargetVersion(): String? =
        settings.state.installedVersion?.takeIf { installer.isInstalled(it) }
            ?: installer.installedVersions().firstOrNull()
            ?: latestVersion().getOrNull()

    private fun writeAgentEntry(command: String, version: String): Result<Unit> {
        dropRenamedEntries()

        // No `env`: the launcher puts node's directory on PATH itself. Writing the IDE's
        // whole inherited PATH here — which is what this used to do — baked in whatever
        // ephemeral directories happened to be in the environment of the shell that started
        // the IDE, and rewrote acp.json every time they changed.
        val entry = JsonObject().apply {
            addProperty("command", command)
            add("args", JsonArray())
            addProperty("use_idea_mcp", settings.state.useIdeaMcp)
            addProperty("use_custom_mcp", settings.state.useCustomMcp)
        }

        return when (AcpConfigFile.upsertAgent(settings.displayName, entry)) {
            AcpConfigFile.Outcome.UNCHANGED -> {
                LOG.info("Claude ACP agent already current in ${AcpConfigFile.path}")
                Result.success(Unit)
            }

            AcpConfigFile.Outcome.WRITTEN -> {
                LOG.info("Registered Claude ACP agent $version in ${AcpConfigFile.path}")

                // Every adapter update and every node path change rewrites this entry; only
                // the first one is news.
                if (!settings.state.announced) {
                    settings.state.announced = true
                    notify(
                        NotificationType.INFORMATION,
                        "Claude Code (Subscription) agent ready",
                        "Added \"${settings.displayName}\" (adapter $version) to the AI chat agent list. " +
                            "Pick it there and log in with your Claude subscription.",
                    )
                }
                Result.success(Unit)
            }

            AcpConfigFile.Outcome.REFUSED_UNPARSEABLE -> {
                LOG.warn("Refusing to rewrite unparseable ${AcpConfigFile.path}")
                notify(
                    NotificationType.ERROR,
                    "Could not register the Claude Code agent",
                    "${AcpConfigFile.path} is not valid JSON. It was left untouched so nothing else " +
                        "in it is lost — fix or delete the file, then restart.",
                )
                Result.failure(IllegalStateException("acp.json is unparseable"))
            }
        }
    }

    /**
     * Removes entries this plugin wrote under a name it no longer uses.
     *
     * The IDE keys agents by display name, so renaming one — in settings, or because the
     * default changed between plugin versions — otherwise leaves the old key behind and the
     * picker lists the agent twice, both pointing at the same launcher. Only entries whose
     * command is inside our adapter directory are touched, so an agent someone else defined
     * under the old name survives.
     */
    private fun dropRenamedEntries() {
        AcpConfigFile.agentsCommandedFrom(installer.root)
            .filter { it != settings.displayName }
            .forEach { stale ->
                LOG.info("Removing agent entry '$stale' left behind by a rename")
                AcpConfigFile.removeAgent(stale)
            }
    }

    /**
     * Runs [provision] under a progress bar.
     *
     * A first run downloads the adapter, which takes ten seconds or so; doing that silently
     * looks like the plugin did nothing.
     */
    fun provisionInBackground(project: Project?) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Setting up the Claude Code agent", false) {
                override fun run(indicator: ProgressIndicator) {
                    provision(indicator)
                }
            },
        )
    }

    /**
     * Deletes everything this plugin downloaded, including the running adapter. Only for
     * uninstall — anywhere else it would leave the agent pointing at a missing launcher.
     */
    fun removeAdapterFiles() {
        LOG.info("Removing adapter directory ${installer.root}")
        runCatching { FileUtil.delete(installer.root.toFile()) }
        settings.state.installedVersion = null
    }

    /** Bytes the downloaded adapters occupy. */
    fun diskUsage(): Long = installer.diskUsage()

    fun removeAgentEntry() {
        AcpConfigFile.removeAgent(settings.displayName)
    }

    // ---------------------------------------------------------------- updates

    /**
     * Reads `latest` off the registry.
     *
     * The `dist-tags` endpoint returns `{"latest":"0.73.0"}` and nothing else — the full
     * packument for this package is several hundred KB, which is a lot to pull once a day
     * for one string. [HttpRequests] is used rather than a raw HTTP client because it
     * honours the IDE's proxy settings.
     */
    fun latestVersion(): Result<String> = runCatching {
        val body = fetch("${settings.registry}/-/package/${encodedPackage()}/dist-tags")
        JsonParser.parseString(body).asJsonObject.get("latest")?.asString
            ?: error("registry response has no 'latest' tag")
    }.onFailure { LOG.info("Update check failed: ${it.message}") }

    /**
     * Every published version, newest first, for the version picker in settings.
     *
     * Asks for the abbreviated packument — the full one carries every version's complete
     * manifest and runs to hundreds of kilobytes.
     */
    fun availableVersions(refresh: Boolean = false): Result<List<String>> {
        // Keyed by registry: the cached list belongs to the registry it came from, and
        // pointing the plugin at a mirror used to leave the old registry's versions on
        // screen until the IDE restarted.
        val registry = settings.registry
        if (!refresh && cachedRegistry == registry) {
            cachedVersions?.let { return Result.success(it) }
        }

        return fetchVersions().onSuccess {
            cachedVersions = it
            cachedRegistry = registry
        }
    }

    private fun fetchVersions(): Result<List<String>> = runCatching {
        val body = fetch("${settings.registry}/${encodedPackage()}") { request ->
            request.tuner { it.setRequestProperty("Accept", ABBREVIATED_PACKUMENT) }
        }

        JsonParser.parseString(body).asJsonObject
            .getAsJsonObject("versions")
            .keySet()
            .sortedWith(VersionOrder.reversed())
    }.onFailure { LOG.info("Listing versions failed: ${it.message}") }

    private fun encodedPackage(): String =
        URLEncoder.encode(ClaudeAcpSettings.PACKAGE_NAME, StandardCharsets.UTF_8)

    /** [HttpRequests] rather than a raw client: it honours the IDE's proxy configuration. */
    private fun fetch(url: String, configure: (RequestBuilder) -> Unit = {}): String =
        HttpRequests.request(url)
            .connectTimeout(HTTP_TIMEOUT_MS)
            .readTimeout(HTTP_TIMEOUT_MS)
            .also(configure)
            .readString()

    /**
     * @param manual true when the user pressed the button, which means silence is not an
     *   acceptable answer — "already up to date" and failures are reported too.
     */
    fun checkForUpdates(manual: Boolean) {
        // Nothing runs the adapter while the agent is removed, so offering to upgrade it is
        // an interruption about software the user has already opted out of.
        if (!settings.state.manageAgent) return
        if (!manual && settings.state.updatePolicy == UpdatePolicy.OFF) return

        val latest = latestVersion().getOrElse { failure ->
            if (manual) {
                notify(
                    NotificationType.WARNING,
                    "Could not reach the npm registry",
                    failure.message ?: "Unknown error.",
                )
            }
            return
        }

        // The verified version, not the recorded one: settings can name a build whose files
        // were deleted, and answering "up to date" for something that is not on disk sends
        // the user looking for a problem elsewhere.
        val installed = status().installedVersion
        if (installed != null && VersionOrder.compare(latest, installed) <= 0) {
            if (manual) {
                notify(NotificationType.INFORMATION, "Claude ACP adapter is up to date", "Version $installed.")
            }
            return
        }

        if (!manual && latest == settings.state.skippedVersion) return

        when {
            settings.state.updatePolicy == UpdatePolicy.AUTO && !manual -> updateTo(latest, null)
            else -> notifyUpdateAvailable(latest, installed)
        }
    }

    private fun notifyUpdateAvailable(latest: String, installed: String?) {
        val from = installed?.let { "You have $it. " }.orEmpty()
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Claude ACP adapter $latest is available",
                from + "Updating replaces the adapter the Claude Code (Subscription) agent runs.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Update now") { updateTo(latest, null) })
            .addAction(
                NotificationAction.createSimpleExpiring("Skip $latest") {
                    settings.state.skippedVersion = latest
                },
            )
            .notify(null)
    }

    /**
     * Installs [version], repoints the launcher at it and refreshes `acp.json`.
     *
     * A second request for a version already being installed is dropped. The settings page
     * decides whether to switch by comparing against the installed version, which does not
     * change until this background task finishes — so pressing Apply and then OK asked for
     * the same install twice and announced it twice.
     *
     * [onFinished] is called exactly once on every path, dropped requests included, because
     * callers use it to close a progress indicator.
     */
    fun updateTo(version: String, project: Project?, onFinished: (Result<Unit>) -> Unit = {}) {
        if (!installsInFlight.add(version)) {
            LOG.info("Install of $version already running; ignoring the repeat request")
            // Still reported: [onFinished] is how the settings page learns it may stop
            // showing a spinner, and a silent return left it spinning forever.
            onFinished(Result.failure(IllegalStateException("install of $version already running")))
            return
        }

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Installing Claude ACP adapter $version", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        install(indicator)
                    } finally {
                        installsInFlight.remove(version)
                    }
                }

                private fun install(indicator: ProgressIndicator) {
                    val runtime = NodeRuntimeResolver.resolve()
                    if (runtime == null) {
                        notifyNoNode()
                        onFinished(Result.failure(IllegalStateException("no node runtime")))
                        return
                    }

                    val previous = settings.state.installedVersion
                    val installed = installer.install(version, runtime, indicator)
                    installed.onFailure { failure ->
                        notify(
                            NotificationType.ERROR,
                            "Could not install adapter $version",
                            failure.message ?: "npm install failed.",
                        )
                        onFinished(Result.failure(failure))
                    }

                    val entryPoint = installed.getOrNull() ?: return

                    // Point the launcher at the new build and publish the entry before
                    // recording anything: settings that claim a version the agent is not
                    // running are worse than settings that lag by a moment. Pruning comes
                    // last for the same reason — doing it first deletes the old version
                    // while the launcher still points at it.
                    val launcher = LauncherScript.write(installer.root, runtime, entryPoint, installer.versionDir(version))
                    val result = writeAgentEntry(launcher.toString(), version)

                    settings.state.installedVersion = version

                    // Activating an older build is a rollback: clearing `skippedVersion`
                    // here would let tomorrow's check offer back the very version the user
                    // just walked away from.
                    val newerOnDisk = installer.installedVersions()
                        .firstOrNull { VersionOrder.compare(it, version) > 0 }
                    settings.state.skippedVersion = newerOnDisk

                    installer.pruneOldVersions(active = version)

                    if (result.isSuccess) {
                        notify(
                            NotificationType.INFORMATION,
                            describeSwitch(previous, version),
                            "Open a new AI chat to use it — a chat that is already running keeps " +
                                "the adapter process it started with.",
                        )
                    }
                    onFinished(result)
                }
            },
        )
    }

    /**
     * Wording for the balloon after a version switch.
     *
     * Choosing an older build and being told it was "updated" is worse than unhelpful — it
     * reads as the plugin having done the opposite of what was asked.
     */
    private fun describeSwitch(previous: String?, current: String): String = when {
        previous == null -> "Claude ACP adapter $current installed"
        VersionOrder.compare(current, previous) > 0 -> "Claude ACP adapter updated to $current"
        VersionOrder.compare(current, previous) < 0 -> "Claude ACP adapter rolled back to $current"
        else -> "Claude ACP adapter $current reinstalled"
    }

    /**
     * Deletes the named versions, refusing to touch the active one or any that a process is
     * running from right now.
     *
     * The guard is here rather than in the dialog because every caller would otherwise have
     * to remember it, and forgetting leaves either the launcher pointing at nothing or an
     * open chat without the binary it is about to need.
     */
    fun removeVersions(versions: List<String>, force: Boolean = false): List<String> {
        // The active version is never negotiable: deleting it leaves the launcher pointing
        // at nothing. A version merely held by an open chat is the user's call, since the
        // alternative is having no way to reclaim that space short of closing the chat.
        val spared = setOfNotNull(status().installedVersion) +
            if (force) emptySet() else installer.versionsInUse()

        return versions.filter { it !in spared }.onEach {
            installer.removeVersion(it)
            LOG.info("Removed adapter version $it")
        }
    }

    /** Starts the periodic check. Idempotent: a second call does not add a second loop. */
    @Synchronized
    fun startUpdateLoop() {
        if (updateLoop?.isActive == true) return

        updateLoop = scope.launch {
            delay(STARTUP_GRACE)
            while (isActive) {
                runCatching { checkForUpdates(manual = false) }
                    .onFailure { LOG.warn("Periodic update check failed", it) }
                delay(settings.state.checkIntervalHours.coerceAtLeast(1).hours)
            }
        }
    }

    private fun notifyNoNode() {
        LOG.warn("No node runtime found; cannot provision the Claude ACP agent")
        notify(
            NotificationType.WARNING,
            "No usable Node.js runtime found",
            "The Claude Code (Subscription) agent needs Node.js ${NodeRuntimeResolver.MINIMUM_MAJOR} or newer. " +
                "Install one, or run a bundled ACP agent once so the IDE downloads its own runtime, " +
                "then restart. You can also set an explicit path in Settings | Tools | Claude Code ACP Bridge.",
        )
    }

    private fun notify(type: NotificationType, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(null)
    }

    companion object {
        const val NOTIFICATION_GROUP: String = "Claude Code ACP Bridge"

        private const val HTTP_TIMEOUT_MS = 10_000
        private const val ABBREVIATED_PACKUMENT = "application/vnd.npm.install-v1+json"
        private val STARTUP_GRACE = 2.minutes

        private val LOG = logger<ClaudeAcpManager>()

        fun getInstance(): ClaudeAcpManager = service()
    }
}
