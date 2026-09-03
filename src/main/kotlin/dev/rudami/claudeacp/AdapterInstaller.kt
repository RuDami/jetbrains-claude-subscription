package dev.rudami.claudeacp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
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

    fun versionDir(version: String): Path = versionsRoot.resolve(version)

    /** The adapter entry point npm unpacks, or null when [version] is not installed. */
    fun entryPoint(version: String): Path? =
        versionDir(version)
            .resolve("node_modules/${ClaudeAcpSettings.PACKAGE_NAME}/dist/index.js")
            .takeIf { it.isRegularFile() }

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
     * Blocking — callers run it under a background task. A failed install leaves nothing
     * behind: the half-written version directory is removed so [isInstalled] cannot report
     * a broken tree as usable.
     */
    fun install(version: String, runtime: NodeRuntime, indicator: ProgressIndicator? = null): Result<Path> {
        entryPoint(version)?.let { return Result.success(it) }

        val target = versionDir(version)
        indicator?.text = "Installing ${ClaudeAcpSettings.PACKAGE_NAME}@$version"

        return runCatching {
            withInstallLock {
                // Another IDE may have installed it while this one waited for the lock.
                entryPoint(version)?.let { return@withInstallLock it }

                target.createDirectories()

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
                    target.toString(),
                    "${ClaudeAcpSettings.PACKAGE_NAME}@$version",
                ).withWorkDirectory(target.toFile())

                // npm shells out to node for its own helpers, and behind a corporate proxy it
                // needs the same variables the user's shell has.
                command.environment["PATH"] = pathWith(runtime)
                proxyEnvironment().forEach { (key, value) -> command.environment[key] = value }

                val output = CapturingProcessHandler(command).runProcess(INSTALL_TIMEOUT_MS)
                if (output.exitCode != 0) {
                    val detail = output.stderr.trim().ifEmpty { output.stdout.trim() }.takeLast(MAX_ERROR_CHARS)
                    error("npm install exited with ${output.exitCode}: $detail")
                }

                entryPoint(version) ?: error("npm reported success but $target holds no adapter")
            }
        }.onFailure {
            LOG.warn("Failed to install adapter $version", it)
            runCatching { FileUtil.delete(target.toFile()) }
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
     * Best effort: `commandLine()` is unavailable for other users' processes and commonly
     * empty on Windows, so this can under-report and never over-reports.
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

        markers.any { marker ->
            val pid = marker.name.removePrefix(LauncherScript.MARKER_PREFIX).toLongOrNull()
            val alive = pid != null && ProcessHandle.of(pid).isPresent

            if (!alive) runCatching { Files.deleteIfExists(marker) }
            alive
        }
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
    private fun <T> withInstallLock(body: () -> T): T {
        root.createDirectories()
        val lockFile = root.resolve(".install.lock").toFile()

        RandomAccessFile(lockFile, "rw").use { handle ->
            var lock: FileLock? = null
            try {
                lock = handle.channel.lock()
                return body()
            } finally {
                runCatching { lock?.release() }
            }
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
    fun pathWith(runtime: NodeRuntime): String {
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

        private val PROXY_VARIABLES = listOf(
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
            "http_proxy", "https_proxy", "no_proxy",
        )

        private val LOG = logger<AdapterInstaller>()

        fun getInstance(): AdapterInstaller = com.intellij.openapi.components.service()
    }
}
