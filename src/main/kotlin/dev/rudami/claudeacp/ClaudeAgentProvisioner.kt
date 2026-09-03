package dev.rudami.claudeacp

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provisions the agent on startup and starts the update loop.
 *
 * [ProjectActivity] fires once per opened project while `acp.json` is application-wide,
 * hence the once-per-process guard.
 */
class ClaudeAgentProvisioner : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!provisionedThisSession.compareAndSet(false, true)) return

        warnAboutConflictingPlugin()

        val manager = ClaudeAcpManager.getInstance()
        // Spawns node and may run npm on a first install, so it goes to a background task
        // with a progress bar rather than blocking startup or working invisibly.
        manager.provisionInBackground(project)
        manager.startUpdateLoop()
    }

    /**
     * The upstream plugin writes the same `agent_servers` key from its own startup
     * activity, so with both installed the entry flips between the two on every launch
     * depending on which ran last.
     */
    private fun warnAboutConflictingPlugin() {
        val upstreamId = PluginId.getId(UPSTREAM_PLUGIN_ID)
        val conflicting = PluginManagerCore.getPlugin(upstreamId) ?: return
        if (PluginManagerCore.isDisabled(upstreamId)) return

        NotificationGroupManager.getInstance()
            .getNotificationGroup(ClaudeAcpManager.NOTIFICATION_GROUP)
            .createNotification(
                "Two plugins manage the same ACP agent",
                "\"${conflicting.name}\" is still enabled and writes the same entry in " +
                    "${AcpConfigFile.path}. Disable or uninstall it, otherwise the agent " +
                    "configuration changes on every IDE start.",
                NotificationType.WARNING,
            )
            .notify(null)
    }

    private companion object {
        const val UPSTREAM_PLUGIN_ID = "dev.vanssa.claudeacp"

        val provisionedThisSession = AtomicBoolean(false)
    }
}
