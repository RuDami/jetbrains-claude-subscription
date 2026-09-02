package dev.rudami.claudeacp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * A node installation able to run npm's CLI.
 *
 * [binDir] has to end up on the agent process' `PATH`, not just be used to build an
 * absolute [node] path: npm re-execs its helpers through `#!/usr/bin/env node`, so the
 * children look `node` up on `PATH` and otherwise die with
 * `env: 'node': No such file or directory`.
 */
data class NodeRuntime(val binDir: Path, val node: Path, val npmCli: Path)

/**
 * Finds a node runtime without requiring the user to install one.
 *
 * A user-installed node is preferred. The fallback is the runtime the IDE downloads for
 * its own ACP agents — on a machine whose only node is that one, this is what makes the
 * plugin work at all. Its path carries the node version (`.../.runtimes/node/24.13.0/bin`),
 * so it is resolved on every startup rather than frozen into the agent config.
 */
object NodeRuntimeResolver {

    /** The adapter's own `engines.node`. Anything older is not offered. */
    const val MINIMUM_MAJOR: Int = 22

    fun resolve(): NodeRuntime? =
        fromOverride() ?: fromUserPath() ?: fromIdeRuntimes()

    private fun fromOverride(): NodeRuntime? {
        val configured = ClaudeAcpSettings.getInstance().state.nodePathOverride?.takeIf { it.isNotBlank() }
            ?: return null
        val binDir = Paths.get(configured).parent ?: return null
        return runtimeAt(binDir).also {
            if (it == null) LOG.warn("Configured node override '$configured' is not a usable node install")
        }
    }

    /**
     * Uses [EnvironmentUtil.getEnvironmentMap] rather than `System.getenv`: an IDE started
     * from Finder or the Dock inherits a bare `/usr/bin:/bin:/usr/sbin:/sbin` with no
     * Homebrew and no nvm on it, while this map comes from a login shell.
     */
    private fun fromUserPath(): NodeRuntime? {
        val path = EnvironmentUtil.getEnvironmentMap()["PATH"]
            ?: System.getenv("PATH")
            ?: return null

        return path.splitToSequence(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { Paths.get(it) }
            .mapNotNull { runCatching { runtimeAt(it) }.getOrNull() }
            .firstOrNull()
    }

    /**
     * The runtime of the *running* IDE first. Sorting every IDE's runtimes together by
     * node version picks, say, a 2026.1 runtime while running under 2026.2 — which then
     * breaks the agent the moment those caches are cleaned up.
     */
    private fun fromIdeRuntimes(): NodeRuntime? {
        val current = runtimeRootsUnder(PathManager.getSystemDir())
        val others = ideCacheRoots()
            .flatMap { it.childDirectories() }
            .filter { it != PathManager.getSystemDir() }
            .flatMap { runtimeRootsUnder(it) }

        return (current + others).firstNotNullOfOrNull { runCatching { runtimeAt(it) }.getOrNull() }
    }

    /** Version directories under one IDE's `acp-agents/.runtimes/node`, newest first. */
    private fun runtimeRootsUnder(ideSystemDir: Path): List<Path> {
        val root = ideSystemDir.resolve("acp-agents/.runtimes/node")
        if (!root.isDirectory()) return emptyList()

        return root.childDirectories()
            .sortedWith(compareByDescending(VersionOrder) { it.name })
            .map { it.resolve("bin") }
    }

    /** Both locations are checked because the IDE cache location differs per OS. */
    private fun ideCacheRoots(): List<Path> {
        val home = Paths.get(System.getProperty("user.home"))
        return listOf(
            home.resolve(".cache/JetBrains"),
            home.resolve("Library/Caches/JetBrains"),
        ).filter { it.isDirectory() }
    }

    /** Returns a runtime rooted at [binDir], or null when it is not a usable node install. */
    private fun runtimeAt(binDir: Path): NodeRuntime? {
        val node = binDir.resolve("node")
        if (!node.isRegularFile() || !node.isExecutable()) return null

        val npmCli = binDir.parent?.resolve("lib/node_modules/npm/bin/npm-cli.js") ?: return null
        if (!npmCli.isRegularFile()) return null

        if (!satisfiesMinimum(node)) return null

        return NodeRuntime(binDir = binDir, node = node, npmCli = npmCli)
    }

    /**
     * Runs `node -v` once per binary and caches it. Reading the version out of the path
     * only works for the IDE's own runtimes, not for Homebrew or a system install.
     */
    private fun satisfiesMinimum(node: Path): Boolean = versionCache.getOrPut(node) {
        val reported = runCatching {
            val command = GeneralCommandLine(node.toString(), "-v")
            CapturingProcessHandler(command).runProcess(VERSION_TIMEOUT_MS).stdout.trim()
        }.getOrNull()

        val major = reported?.removePrefix("v")?.substringBefore('.')?.toIntOrNull()
        if (major == null) {
            LOG.warn("Could not read a version from '$node' (got '$reported'); skipping it")
            return@getOrPut false
        }

        (major >= MINIMUM_MAJOR).also {
            if (!it) LOG.info("Skipping node $reported at '$node': adapter needs >= $MINIMUM_MAJOR")
        }
    }

    private fun Path.childDirectories(): List<Path> =
        runCatching { listDirectoryEntries().filter { it.isDirectory() } }.getOrElse { emptyList() }

    private const val VERSION_TIMEOUT_MS = 5_000

    private val versionCache = mutableMapOf<Path, Boolean>()
    private val LOG = logger<NodeRuntimeResolver>()
}
