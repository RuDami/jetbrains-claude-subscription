package dev.rudami.claudeacp

/**
 * The settings page's logic, with no Swing in it.
 *
 * Split out because the page had grown to the point where the parts that can actually be
 * wrong — how a version row is labelled and read back, whether two overlapping operations
 * have both finished, whether a field differs from what is stored — were only reachable by
 * clicking. Both bugs that shipped from this file were in exactly that logic. Here they are
 * ordinary functions with ordinary tests.
 */
object ClaudeAcpPageModel {

    /** What the page shows for a version that is running, and for one merely downloaded. */
    private const val ACTIVE_SUFFIX = " - active"
    private const val DOWNLOADED_SUFFIX = " - downloaded"

    const val AUTOMATIC_NODE = "Automatic"
    const val LOADING_ITEM = "Loading..."

    /** Every field the page can change, as one value that can be compared and copied. */
    data class Form(
        val version: String?,
        val policy: UpdatePolicy,
        val intervalHours: Int,
        val registry: String?,
        val nodePath: String?,
        val useIdeaMcp: Boolean,
        val useCustomMcp: Boolean,
    )

    fun formOf(state: ClaudeAcpSettings.State): Form = Form(
        version = state.installedVersion,
        policy = state.updatePolicy,
        intervalHours = state.checkIntervalHours,
        registry = state.registryUrl,
        nodePath = state.nodePathOverride,
        useIdeaMcp = state.useIdeaMcp,
        useCustomMcp = state.useCustomMcp,
    )

    /**
     * Whether [edited] asks for anything [stored] does not already say.
     *
     * The version is compared only when the agent is managed: with it removed the combo is
     * empty and would otherwise read as a pending change forever.
     */
    fun isModified(edited: Form, stored: Form, managed: Boolean): Boolean = when {
        !managed -> edited.copy(version = stored.version) != stored
        else -> edited != stored
    }

    /**
     * The rows for the version picker: everything published, plus anything downloaded that
     * the registry did not mention, newest first.
     */
    fun versionRows(published: List<String>, installed: List<String>, active: String?): List<String> =
        (published + installed)
            .distinct()
            .sortedWith(VersionOrder.reversed())
            .map { version ->
                when {
                    version == active -> version + ACTIVE_SUFFIX
                    version in installed -> version + DOWNLOADED_SUFFIX
                    else -> version
                }
            }

    /**
     * The version a row stands for, or null when the row is a placeholder.
     *
     * Paired with [versionRows] deliberately: a label and the code that reads it back are one
     * decision, and splitting them across two files is how a decoration change silently turns
     * into an install request for a version named "0.73.0 -".
     */
    fun versionOf(row: String?): String? = row
        ?.takeIf { it != LOADING_ITEM }
        ?.removeSuffix(ACTIVE_SUFFIX)
        ?.removeSuffix(DOWNLOADED_SUFFIX)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /** Null when the field holds the default, so settings store an override or nothing. */
    fun registryChoice(text: String?): String? =
        text?.trim()?.takeIf { it.isNotEmpty() && it != ClaudeAcpSettings.DEFAULT_REGISTRY }

    fun nodeChoice(selection: String?): String? = selection?.takeIf { it != AUTOMATIC_NODE }

    fun describeFreshness(newest: String?, active: String?): String = when {
        newest == null -> "The registry returned no versions."
        active == null -> "Newest release is $newest. Nothing is installed yet."
        VersionOrder.compare(newest, active) > 0 -> "Update available: $newest."
        else -> "Up to date on $active."
    }

    fun describeDisk(versions: Int, bytes: Long): String = when (versions) {
        0 -> "nothing downloaded"
        1 -> "1 copy, ${bytes / MEGABYTE} MB"
        else -> "$versions copies, ${bytes / MEGABYTE} MB"
    }

    fun describeStatus(installedVersion: String?, nodePath: String?, home: String): String =
        "Adapter " + (installedVersion ?: "not installed") +
            ", node " + (nodePath?.let { abbreviatePath(it, home) } ?: "not found")

    /**
     * A path with `$HOME` folded away and the middle dropped if it is still long.
     *
     * A label is as wide as its text wants to be and a path has no space to wrap at, so an
     * unabbreviated one sets a floor under the whole dialog's width.
     */
    fun abbreviatePath(path: String, home: String, limit: Int = MAX_PATH_CHARS): String {
        val shortened = if (home.isNotEmpty() && path.startsWith(home)) "~" + path.removePrefix(home) else path
        return if (shortened.length <= limit) shortened else "..." + shortened.takeLast(limit)
    }

    /**
     * Tracks how many operations are running.
     *
     * A flag was wrong: two overlapping operations had the first to finish re-enable every
     * control while the second was still going. Counting was right and its call sites were
     * not, which turned a cosmetic bug into a page that never came back — so the transitions
     * are values here, and a caller cannot forget to check them.
     */
    class BusyCounter {
        private var running = 0

        val isBusy: Boolean get() = running > 0

        /** @return true when this began the busy state rather than joining it. */
        fun begin(): Boolean {
            running++
            return running == 1
        }

        /** @return true when this ended it. An unmatched end is ignored. */
        fun end(): Boolean {
            if (running == 0) return false
            running--
            return running == 0
        }

        fun reset() {
            running = 0
        }
    }

    private const val MEGABYTE = 1024L * 1024L
    private const val MAX_PATH_CHARS = 40
}
