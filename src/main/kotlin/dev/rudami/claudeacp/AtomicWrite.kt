package dev.rudami.claudeacp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/**
 * Replaces a file's contents in one step.
 *
 * Every file this plugin writes has a watcher: the IDE re-reads `acp.json` as soon as it
 * changes, the adapter re-resolves `.claude/settings.json` the same way, and the launcher is
 * read by the shell at the moment a chat starts. A plain write truncates the file and then
 * fills it, so a watcher that looks in between sees an empty or half-written file — for the
 * two JSON ones that means "not valid JSON", which is exactly the state both readers treat as
 * "drop what you had".
 *
 * Writing a sibling temporary file and moving it over the target closes that window: readers
 * see either the old contents or the new ones. The move is atomic on every platform when
 * source and target sit in the same directory, which is why the temporary is created there
 * rather than in the system temp directory.
 */
fun Path.writeAtomically(text: String, executable: Boolean = false) {
    createParentDirectories()

    val temporary = resolveSibling("$fileName.tmp${ProcessHandle.current().pid()}")
    try {
        temporary.writeText(text)
        if (executable) temporary.toFile().setExecutable(true, true)

        runCatching {
            Files.move(
                temporary,
                this,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            // Some filesystems refuse an atomic move; a plain replace is still better than
            // truncating the original in place.
            Files.move(temporary, this, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.deleteIfExists()
    }
}
