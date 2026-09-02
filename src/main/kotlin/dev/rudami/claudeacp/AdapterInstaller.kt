package dev.rudami.claudeacp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Path
import java.nio.file.Paths
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

    val root: Path = Paths.get(System.getProperty("user.home"), ".jetbrains", "claude-acp-adapter")

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
            target.createDirectories()

            val command = GeneralCommandLine(
                runtime.node.toString(),
                runtime.npmCli.toString(),
                "install",
                "--no-audit",
                "--no-fund",
                "--no-package-lock",
                "--prefix",
                target.toString(),
                "${ClaudeAcpSettings.PACKAGE_NAME}@$version",
            ).withWorkDirectory(target.toFile())

            // npm shells out to node for its own lifecycle helpers.
            command.environment["PATH"] = pathWith(runtime)

            val output = CapturingProcessHandler(command).runProcess(INSTALL_TIMEOUT_MS)
            if (output.exitCode != 0) {
                val detail = output.stderr.trim().ifEmpty { output.stdout.trim() }.takeLast(MAX_ERROR_CHARS)
                error("npm install exited with ${output.exitCode}: $detail")
            }

            entryPoint(version) ?: error("npm reported success but $target holds no adapter")
        }.onFailure {
            LOG.warn("Failed to install adapter $version", it)
            runCatching { FileUtil.delete(target.toFile()) }
        }
    }

    /** Drops every installed version except the newest [keep], so rollback stays possible. */
    fun pruneOldVersions(keep: Int = KEEP_VERSIONS) {
        installedVersions().drop(keep).forEach { stale ->
            LOG.info("Removing superseded adapter version $stale")
            runCatching { FileUtil.delete(versionDir(stale).toFile()) }
        }
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

        private val LOG = logger<AdapterInstaller>()

        fun getInstance(): AdapterInstaller = com.intellij.openapi.components.service()
    }
}
