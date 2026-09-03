package dev.rudami.claudeacp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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

        warnAboutRivalAgent()

        val manager = ClaudeAcpManager.getInstance()
        // Spawns node and may run npm on a first install, so it goes to a background task
        // with a progress bar rather than blocking startup or working invisibly.
        manager.provisionInBackground(project)
        manager.startUpdateLoop()
    }

    /**
     * Warns when something else already registers this adapter.
     *
     * The upstream plugin writes its own `agent_servers` entry from its own startup activity,
     * so with both installed the config flips on every launch depending on which ran last.
     *
     * This looks at the config rather than at the list of installed plugins on purpose. The
     * platform's plugin lookup is internal API — the Plugin Verifier fails a build that uses
     * it — and the file is the better evidence anyway: it catches a hand-written duplicate as
     * well, and says nothing when the rival plugin is installed but disabled.
     */
    private fun warnAboutRivalAgent() {
        val settings = ClaudeAcpSettings.getInstance()
        val rivals = AcpConfigFile.agentsMentioning(
            needle = ClaudeAcpSettings.PACKAGE_NAME,
            except = settings.displayName,
        )
        if (rivals.isEmpty()) return

        NotificationGroupManager.getInstance()
            .getNotificationGroup(ClaudeAcpManager.NOTIFICATION_GROUP)
            .createNotification(
                "Another agent runs the same adapter",
                "The chat agent list also holds " + rivals.joinToString { "\"$it\"" } +
                    ", which starts the same package. If a plugin maintains that entry, " +
                    "disable it — otherwise the two rewrite each other on every IDE start.",
                NotificationType.WARNING,
            )
            .notify(null)
    }

    private companion object {
        val provisionedThisSession = AtomicBoolean(false)
    }
}
