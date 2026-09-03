package dev.rudami.claudeacp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdapterInstallerTest {

    private lateinit var root: Path
    private val installer = AdapterInstaller()

    @BeforeTest
    fun redirectToTempDirectory() {
        root = Files.createTempDirectory("adapter-installer-test")
        installer.rootOverride = root
    }

    @AfterTest
    fun restore() {
        installer.rootOverride = null
        root.toFile().deleteRecursively()
    }

    /** Fakes a completed install of [version] by creating the file `entryPoint` looks for. */
    private fun fakeInstall(version: String) {
        val entryPoint = installer.versionDir(version)
            .resolve("node_modules/${ClaudeAcpSettings.PACKAGE_NAME}/dist/index.js")
        entryPoint.parent.createDirectories()
        entryPoint.createFile()
    }

    @Test
    fun `lists installed versions newest first`() {
        listOf("0.9.0", "0.73.0", "1.0.0").forEach(::fakeInstall)
        assertEquals(listOf("1.0.0", "0.73.0", "0.9.0"), installer.installedVersions())
    }

    @Test
    fun `a directory without an entry point does not count as installed`() {
        installer.versionDir("0.70.0").createDirectories()
        assertTrue(installer.installedVersions().isEmpty())
    }

    /**
     * The bug this exists for: pruning by recency alone deleted the active adapter after a
     * rollback, because the version in use was then the oldest copy on disk.
     */
    @Test
    fun `pruning keeps the active version even when it is the oldest`() {
        listOf("0.73.0", "0.72.0", "0.71.0").forEach(::fakeInstall)

        installer.pruneOldVersions(active = "0.71.0", keep = 2)

        assertTrue(installer.isInstalled("0.71.0"), "the running adapter must survive pruning")
        assertEquals(listOf("0.73.0", "0.71.0"), installer.installedVersions())
    }

    @Test
    fun `pruning keeps the newest when nothing is active yet`() {
        listOf("0.73.0", "0.72.0", "0.71.0").forEach(::fakeInstall)

        installer.pruneOldVersions(active = null, keep = 2)

        assertEquals(listOf("0.73.0", "0.72.0"), installer.installedVersions())
    }

    @Test
    fun `removing a version leaves the others alone`() {
        listOf("0.73.0", "0.72.0", "0.71.0").forEach(::fakeInstall)

        installer.removeVersion("0.72.0")

        assertEquals(listOf("0.73.0", "0.71.0"), installer.installedVersions())
    }

    @Test
    fun `removing a version that is not installed does nothing`() {
        fakeInstall("0.73.0")

        installer.removeVersion("0.70.0")

        assertEquals(listOf("0.73.0"), installer.installedVersions())
    }
}
