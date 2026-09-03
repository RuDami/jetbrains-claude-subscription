package dev.rudami.claudeacp

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** What to do when a newer adapter version shows up in the npm registry. */
enum class UpdatePolicy(private val label: String) {
    NOTIFY("Notify me, install on click"),
    AUTO("Install automatically"),
    OFF("Never check"),
    ;

    /** Rendered directly by the settings combo box. */
    override fun toString(): String = label
}

/**
 * Machine-local, so [RoamingType.DISABLED]: this holds absolute paths to a node binary
 * and a version installed on this disk, neither of which means anything on another host.
 */
@Service(Service.Level.APP)
@State(
    name = "ClaudeAcpManaged",
    storages = [Storage("claude-acp-managed.xml", roamingType = RoamingType.DISABLED)],
    category = SettingsCategory.TOOLS,
)
class ClaudeAcpSettings : SimplePersistentStateComponent<ClaudeAcpSettings.State>(State()) {

    class State : BaseState() {
        /** When off, the plugin stops touching `acp.json` and leaves the entry to the user. */
        var manageAgent: Boolean by property(true)

        /**
         * Also the source of the agent id the IDE derives — see [ClaudeAgent.matches].
         * Renaming it makes the IDE treat it as a different agent and drops the model,
         * effort and permission-mode choices bound to the old id.
         */
        var displayName: String? by string(DEFAULT_DISPLAY_NAME)

        /** Adapter version currently unpacked under [AdapterInstaller.root]. */
        var installedVersion: String? by string(null)

        /** Non-empty freezes the adapter at that version and stops update prompts. */
        var pinnedVersion: String? by string(null)

        /** Latest version the user chose to ignore. */
        var skippedVersion: String? by string(null)

        var updatePolicy: UpdatePolicy by enum(UpdatePolicy.NOTIFY)
        var checkIntervalHours: Int by property(24)

        var useIdeaMcp: Boolean by property(true)
        var useCustomMcp: Boolean by property(true)

        /** Absolute path to a `node` binary, when the automatic search picks the wrong one. */
        var nodePathOverride: String? by string(null)

        /** Non-empty routes npm and the update check at a private registry mirror. */
        var registryUrl: String? by string(null)

        /**
         * Set once the agent has been announced. Registering writes `acp.json` again on any
         * change — a new adapter version, a different node path — and a balloon on each of
         * those reads as noise; the introduction is only worth making once.
         */
        var announced: Boolean by property(false)
    }

    val displayName: String get() = state.displayName?.takeIf { it.isNotBlank() } ?: DEFAULT_DISPLAY_NAME

    /** The version the plugin should converge on, ignoring what the registry says. */
    val pinnedVersion: String? get() = state.pinnedVersion?.takeIf { it.isNotBlank() }

    /** Registry to install from and to poll for updates. */
    val registry: String get() = state.registryUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_REGISTRY

    companion object {
        const val DEFAULT_DISPLAY_NAME: String = "Claude Code (Subscription)"
        const val PACKAGE_NAME: String = "@agentclientprotocol/claude-agent-acp"
        const val DEFAULT_REGISTRY: String = "https://registry.npmjs.org"

        fun getInstance(): ClaudeAcpSettings = service()
    }
}

object ClaudeAgent {

    /**
     * Whether [agentId] is the agent this plugin provisions.
     *
     * The IDE derives the id from the display name — "Claude Subscription" was observed to
     * become `acp.claude-subscription`. The exact slug rule is not documented, so the
     * comparison strips the `acp.` prefix and every separator on both sides instead of
     * reimplementing a guess at it.
     */
    fun matches(agentId: String, displayName: String): Boolean =
        normalize(agentId.removePrefix("acp.")) == normalize(displayName)

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }
}

/**
 * Orders versions numerically: `0.9.0` before `0.73.0`, which a string sort gets wrong.
 *
 * Pre-releases sort *below* the release they lead to — `0.73.0-beta.1` < `0.73.0` — per
 * semver. Treating every non-numeric segment as 0 in one flat list, as the first cut did,
 * put the beta above the release and would have offered a downgrade as an update.
 */
object VersionOrder : Comparator<String> {

    override fun compare(left: String, right: String): Int {
        val (leftCore, leftPre) = split(left)
        val (rightCore, rightPre) = split(right)

        for (i in 0 until maxOf(leftCore.size, rightCore.size)) {
            val result = (leftCore.getOrNull(i) ?: 0).compareTo(rightCore.getOrNull(i) ?: 0)
            if (result != 0) return result
        }

        // Absent pre-release outranks any pre-release; otherwise compare them as text.
        return when {
            leftPre == null && rightPre == null -> 0
            leftPre == null -> 1
            rightPre == null -> -1
            else -> leftPre.compareTo(rightPre)
        }
    }

    /** `1.2.3-beta.1` -> ([1, 2, 3], "beta.1"). Build metadata after `+` is ignored. */
    private fun split(version: String): Pair<List<Int>, String?> {
        val withoutBuild = version.substringBefore('+')
        val core = withoutBuild.substringBefore('-')
        val pre = withoutBuild.substringAfter('-', "").takeIf { it.isNotEmpty() }
        return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
    }
}
