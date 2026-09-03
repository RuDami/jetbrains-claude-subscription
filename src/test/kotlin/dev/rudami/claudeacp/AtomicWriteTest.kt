package dev.rudami.claudeacp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AtomicWriteTest {

    private lateinit var directory: Path

    @BeforeTest
    fun createTempDirectory() {
        directory = Files.createTempDirectory("atomic-write-test")
    }

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `creates a file and its parent directories`() {
        val target = directory.resolve("nested/deeper/file.json")

        target.writeAtomically("{}\n")

        assertEquals("{}\n", target.readText())
    }

    @Test
    fun `replaces existing contents`() {
        val target = directory.resolve("file.json")
        target.writeText("old")

        target.writeAtomically("new")

        assertEquals("new", target.readText())
    }

    /** A leftover temporary would be picked up by anything globbing the directory. */
    @Test
    fun `leaves no temporary behind`() {
        val target = directory.resolve("file.json")

        target.writeAtomically("content")

        assertEquals(listOf("file.json"), directory.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun `marks the file executable when asked`() {
        val target = directory.resolve("launch.sh")

        target.writeAtomically("#!/bin/sh\n", executable = true)

        assertTrue(target.isExecutable())
    }

    @Test
    fun `a plain write is not executable`() {
        val target = directory.resolve("plain.json")

        target.writeAtomically("{}")

        assertTrue(target.exists())
        assertTrue(!target.isExecutable() || System.getProperty("os.name").startsWith("Windows"))
    }
}
