package dev.rudami.claudeacp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isSymbolicLink
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
 *
 * Two things a move does that an in-place write does not, both undone here. It replaces a
 * symlink with a regular file — `acp.json` linked from a dotfiles repository silently stopped
 * being linked — so a link is resolved and its target written instead. And the new file gets
 * default permissions, so a settings file someone had made private became world-readable;
 * the original's permissions are copied across.
 */
fun Path.writeAtomically(text: String, executable: Boolean = false) {
    val target = if (isSymbolicLink()) toRealPath() else this
    target.createParentDirectories()

    // Unique per call, not per process: two threads writing the same file used to share one
    // temporary name, so one moved the other's half-written file into place.
    val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
        temporary.writeText(text)
        copyPermissions(from = target, to = temporary)
        if (executable) temporary.toFile().setExecutable(true, true)

        runCatching {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            // Some filesystems refuse an atomic move; a plain replace is still better than
            // truncating the original in place.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.deleteIfExists()
    }
}

/**
 * Carries the target's POSIX permissions over to its replacement.
 *
 * `createTempFile` makes an owner-only file, which is right for a file that did not exist and
 * wrong for one that did: a launcher or a shared config would lose the bits it had.
 */
private fun copyPermissions(from: Path, to: Path) {
    if (!Files.exists(from)) return
    val source = Files.getFileAttributeView(from, PosixFileAttributeView::class.java) ?: return
    runCatching { Files.setPosixFilePermissions(to, source.readAttributes().permissions()) }
}
