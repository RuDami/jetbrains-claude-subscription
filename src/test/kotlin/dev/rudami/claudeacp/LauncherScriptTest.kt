package dev.rudami.claudeacp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LauncherScriptTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createTempDirectory() {
        directory = Files.createTempDirectory("launcher-script-test")
    }

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    /**
     * Paths were spliced into double quotes, where `$`, a backtick and `"` stay live — a home
     * directory containing one broke the launcher or ran part of its name.
     */
    @Test
    fun `quoting survives every character sh treats specially`() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val nasty = "/tmp/it's \$HOME `id` \"q\" \\ end"
        val script = directory.resolve("echo.sh")
        script.writeText("printf '%s' ${LauncherScript.quote(nasty)}\n")

        val process = ProcessBuilder("/bin/sh", script.toString()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(10, TimeUnit.SECONDS)

        assertEquals(nasty, output)
    }
}
