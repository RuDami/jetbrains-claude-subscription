package dev.rudami.claudeacp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name


/**
 * Owns the on-disk copy of `@agentclientprotocol/claude-agent-acp`.
 *
 * The adapter is installed with npm into a version-named directory rather than launched
 * through `npx -y pkg@version`. `npx` would hit the registry on every single agent start,
 * which breaks offline and makes startup latency a network variable; a real install also
 * leaves the previous version on disk, which is what makes rollback possible.
 *
 * The root lives next to `acp.json` instead of under `PathManager.getSystemDir()` so it
 * survives an IDE upgrade — the config pointing at it is user-global, not per-IDE-version.
 */
@Service(Service.Level.APP)
class AdapterInstaller {

    /** Overridden by tests so they never touch the real home directory. */
    @Volatile
    internal var rootOverride: Path? = null

    val root: Path
        get() = rootOverride ?: Paths.get(System.getProperty("user.home"), ".jetbrains", "claude-acp-adapter")

    private val versionsRoot: Path get() = root.resolve("versions")

    /** Where npm writes before the result is moved into [versionsRoot]; see [install]. */
    private val stagingRoot: Path get() = root.resolve("staging")

    fun versionDir(version: String): Path = versionsRoot.resolve(version)

    /** The adapter entry point npm unpacks, or null when [version] is not installed. */
    fun entryPoint(version: String): Path? = entryPointIn(versionDir(version)).takeIf { it.isRegularFile() }

    fun isInstalled(version: String): Boolean = entryPoint(version) != null

    /** Installed versions, newest first. */
    fun installedVersions(): List<String> =
        runCatching {
            versionsRoot.listDirectoryEntries()
                .filter { it.isDirectory() }
                .map { it.name }
                .filter { isInstalled(it) }
                .sortedWith(VersionOrder.reversed())
        }.getOrElse { emptyList() }

    /**
     * Installs [version] and returns its entry point.
     *
     * Blocking — callers run it under a background task. npm writes into a staging directory
     * that is moved into place only once it holds a complete adapter, so the version
     * directory either does not exist or is whole. Installing in place, as this used to,
     * meant an IDE quit or killed half way through npm left a tree that either reported
     * itself installed with dependencies missing, or — with no entry point yet — was not
     * listed anywhere and so was never pruned: tens of megabytes nobody would ever delete.
     *
     * Cancelling [indicator] stops npm and surfaces as [ProcessCanceledException], not as a
     * failure, so the caller does not report an error the user asked for.
     */
    fun install(version: String, runtime: NodeRuntime, indicator: ProgressIndicator? = null): Result<Path> {
        entryPoint(version)?.let { return Result.success(it) }

        val target = versionDir(version)
        // Unique per attempt, not just per process: a plugin reload can leave the old copy's
        // install of the same version running beside the new one's.
        val staging = stagingRoot.resolve("$version-${ProcessHandle.current().pid()}-${System.nanoTime()}")
        indicator?.text = "Installing ${ClaudeAcpSettings.PACKAGE_NAME}@$version"

        return runCatching {
            withInstallLock(indicator) {
                // Another IDE may have installed it while this one waited for the lock.
                entryPoint(version)?.let { return@withInstallLock it }

                // Holding the lock means nothing else is installing, so whatever is left in
                // staging, or any version directory without an adapter in it, is debris.
                sweepIncompleteInstalls()
                staging.createDirectories()

                val command = GeneralCommandLine(
                    runtime.node.toString(),
                    runtime.npmCli.toString(),
                    "install",
                    "--no-audit",
                    "--no-fund",
                    "--no-package-lock",
                    // The adapter needs no build step, and this install runs unattended on a
                    // timer — executing dependencies' lifecycle scripts here buys nothing and
                    // hands arbitrary code a shell.
                    "--ignore-scripts",
                    "--registry",
                    ClaudeAcpSettings.getInstance().registry,
                    "--prefix",
                    staging.toString(),
                    "${ClaudeAcpSettings.PACKAGE_NAME}@$version",
                ).withWorkDirectory(staging.toFile())

                // npm shells out to node for its own helpers, and behind a corporate proxy it
                // needs the same variables the user's shell has.
                command.environment["PATH"] = pathWith(runtime)
                proxyEnvironment().forEach { (key, value) -> command.environment[key] = value }

                // With an indicator, cancelling it kills npm; without one this used to be the
                // only way, and the Cancel button on an install did nothing for three minutes.
                val handler = CapturingProcessHandler(command)
                val output = if (indicator != null) {
                    handler.runProcessWithProgressIndicator(indicator, INSTALL_TIMEOUT_MS)
                } else {
                    handler.runProcess(INSTALL_TIMEOUT_MS)
                }
                if (output.isCancelled) throw ProcessCanceledException()
                if (output.isTimeout) error("npm install did not finish within ${INSTALL_TIMEOUT_MS / 1000} s")
                if (output.exitCode != 0) {
                    val detail = output.stderr.trim().ifEmpty { output.stdout.trim() }.takeLast(MAX_ERROR_CHARS)
                    error("npm install exited with ${output.exitCode}: $detail")
                }

                if (!entryPointIn(staging).isRegularFile()) {
                    error("npm reported success but installed no adapter")
                }

                target.parent.createDirectories()
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                entryPoint(version) ?: error("$target holds no adapter after the move")
            }
        }.onFailure { failure ->
            // The lock is already released here, but the staging name is unique to this
            // attempt, so nothing else is using it.
            runCatching { FileUtil.delete(staging.toFile()) }
            if (failure is ProcessCanceledException) {
                LOG.info("Install of adapter $version cancelled")
                throw failure
            }
            LOG.warn("Failed to install adapter $version", failure)
        }
    }

    private fun entryPointIn(prefix: Path): Path =
        prefix.resolve("node_modules/${ClaudeAcpSettings.PACKAGE_NAME}/dist/index.js")

    /**
     * Deletes what interrupted installs left behind. Only called under the install lock.
     *
     * Covers both generations: staging directories from this code, and version directories
     * an in-place install from an earlier plugin version abandoned before npm wrote the
     * entry point. Neither is listed by [installedVersions], so pruning never reached them.
     */
    private fun sweepIncompleteInstalls() {
        runCatching { stagingRoot.listDirectoryEntries() }.getOrElse { emptyList() }.forEach { leftover ->
            LOG.info("Removing interrupted install ${leftover.name}")
            runCatching { FileUtil.delete(leftover.toFile()) }
        }

        runCatching { versionsRoot.listDirectoryEntries() }.getOrElse { emptyList() }
            .filter { it.isDirectory() && !isInstalled(it.name) }
            .forEach { leftover ->
                LOG.info("Removing incomplete adapter directory ${leftover.name}")
                runCatching { FileUtil.delete(leftover.toFile()) }
            }
    }

    /**
     * Versions a running process is using right now.
     *
     * Every open chat holds two processes out of one version directory: the adapter itself
     * and the native Claude Code binary the SDK spawns beside it. Deleting that directory
     * does not kill them — an unlinked file stays alive while it is open — but the moment
     * that adapter needs something it has not loaded yet, most obviously the binary for a
     * new session, it is gone. So a version in use is not a candidate for deletion, whether
     * the deletion is automatic or asked for.
     *
     * Two sources, unioned. Command lines are exact where readable but are unavailable for
     * other users' processes and commonly empty on Windows; the launcher's own markers cover
     * that gap. Both under-report rather than over-report, which is the safe direction: a
     * missed version can be deleted, a wrongly reported one merely survives.
     */
    fun versionsInUse(): Set<String> = fromCommandLines() + fromMarkers()

    private fun fromCommandLines(): Set<String> {
        val prefix = versionsRoot.toString() + File.separator

        return runCatching {
            ProcessHandle.allProcesses().iterator().asSequence()
                .mapNotNull { it.info().commandLine().orElse(null) }
                .filter { it.contains(prefix) }
                .map { it.substringAfter(prefix).substringBefore(File.separatorChar) }
                .filter { it.isNotBlank() }
                .toSet()
        }.getOrElse {
            LOG.info("Could not inspect running processes: ${it.message}")
            emptySet()
        }
    }

    /**
     * Versions whose launcher left a live marker behind.
     *
     * Command lines are the better source where they are readable, but they are unavailable
     * for other users' processes and commonly empty on Windows — which turned the guard into
     * no guard at all there. Each marker is named by the pid the launcher runs as, so a
     * marker whose pid is gone is stale and swept.
     */
    private fun fromMarkers(): Set<String> = installedVersions().filterTo(mutableSetOf()) { version ->
        val markers = runCatching {
            versionDir(version).listDirectoryEntries("${LauncherScript.MARKER_PREFIX}*")
        }.getOrElse { emptyList() }

        // Partitioned rather than `any { ... }`: that short-circuits on the first live marker
        // and leaves every dead one behind it unswept.
        // A pid alone is not enough: pids are reused, and a marker whose chat ended long ago
        // otherwise pinned its version for as long as some unrelated process held that number.
        // Where the command line is readable it has to name this version; where it is not,
        // the marker is believed, which errs toward keeping.
        val directory = versionDir(version).toString()
        val (alive, stale) = markers.partition { marker ->
            val pid = marker.name.removePrefix(LauncherScript.MARKER_PREFIX).toLongOrNull()
            val process = pid?.let { ProcessHandle.of(it).orElse(null) }
            process != null && process.info().commandLine().map { it.contains(directory) }.orElse(true)
        }

        stale.forEach { runCatching { Files.deleteIfExists(it) } }
        alive.isNotEmpty()
    }

    /**
     * Keeps [active] plus the newest [keep] - 1 others, so a rollback target survives.
     *
     * [active] is excluded explicitly rather than trusted to be newest: after a rollback it
     * is precisely the oldest copy on disk, and dropping by recency alone deleted the very
     * adapter the launcher points at. Versions [inUse] are spared for the same reason — an
     * update installs a new version and prunes immediately, which would otherwise cut the
     * ground from under a chat still running on the old one.
     */
    fun pruneOldVersions(
        active: String?,
        keep: Int = KEEP_VERSIONS,
        inUse: Set<String> = versionsInUse(),
    ) {
        val disposable = installedVersions().filter { it != active && it !in inUse }
        val survivors = if (active == null) keep else keep - 1

        disposable.drop(survivors.coerceAtLeast(0)).forEach { stale ->
            LOG.info("Removing superseded adapter version $stale")
            delete(stale)
        }
    }

    /** Bytes held by all downloaded adapters. */
    fun diskUsage(): Long = sizeOf(versionsRoot)

    /** Bytes held by one downloaded adapter. */
    fun diskUsage(version: String): Long = sizeOf(versionDir(version))

    private fun sizeOf(directory: Path): Long =
        runCatching {
            Files.walk(directory).use { paths ->
                paths.filter { it.isRegularFile() }
                    .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
                    .sum()
            }
        }.getOrDefault(0L)

    fun removeVersion(version: String) {
        delete(version)
    }

    private fun delete(version: String) {
        runCatching { FileUtil.delete(versionDir(version).toFile()) }
    }

    /**
     * Serialises installs across processes.
     *
     * Two IDEs starting at once would otherwise run npm into the same directory
     * simultaneously and leave a half-written tree that [entryPoint] happily reports as
     * usable. An OS file lock is what works across processes; a JVM lock would not.
     */
    private fun <T> withInstallLock(indicator: ProgressIndicator?, body: () -> T): T {
        root.createDirectories()
        val lockFile = root.resolve(".install.lock").toFile()

        RandomAccessFile(lockFile, "rw").use { handle ->
            var lock: FileLock? = null
            try {
                lock = acquire(handle, indicator)
                return body()
            } finally {
                runCatching { lock?.release() }
            }
        }
    }

    /**
     * Polls for the lock rather than blocking on it.
     *
     * A blocking `lock()` cannot be cancelled, so an install queued behind another IDE's
     * ignored its Cancel button. And inside one JVM a second lock on the file does not wait
     * at all — it throws [OverlappingFileLockException] — which is exactly what happens when
     * a plugin reload leaves the old copy's install running while the new copy provisions:
     * the two have separate monitors, so nothing in this process serialised them.
     */
    private fun acquire(handle: RandomAccessFile, indicator: ProgressIndicator?): FileLock {
        while (true) {
            indicator?.checkCanceled()
            val lock = try {
                handle.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock != null) return lock
            indicator?.text2 = "Waiting for another install to finish"
            Thread.sleep(LOCK_POLL_MS)
        }
    }

    /** Proxy variables as the user's shell has them; npm reads these directly. */
    private fun proxyEnvironment(): Map<String, String> {
        val environment = com.intellij.util.EnvironmentUtil.getEnvironmentMap()
        return PROXY_VARIABLES.mapNotNull { name ->
            environment[name]?.takeIf { it.isNotBlank() }?.let { name to it }
        }.toMap()
    }

    /** `PATH` an npm or adapter child process needs to find its own node. */
    private fun pathWith(runtime: NodeRuntime): String {
        val inherited = com.intellij.util.EnvironmentUtil.getEnvironmentMap()["PATH"]
            ?: System.getenv("PATH")
            ?: ""
        return if (inherited.isEmpty()) runtime.binDir.toString()
        else runtime.binDir.toString() + java.io.File.pathSeparator + inherited
    }

    companion object {
        private const val INSTALL_TIMEOUT_MS = 180_000
        private const val MAX_ERROR_CHARS = 800
        private const val KEEP_VERSIONS = 2
        private const val LOCK_POLL_MS = 250L

        private val PROXY_VARIABLES = listOf(
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
            "http_proxy", "https_proxy", "no_proxy",
        )

        private val LOG = logger<AdapterInstaller>()

        fun getInstance(): AdapterInstaller = pluginService()
    }
}
