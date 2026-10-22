package dev.rudami.claudeacp

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginStateListener

/**
 * Takes our entry back out of `acp.json` when the plugin is unloaded without being updated.
 *
 * That covers disabling as well as uninstalling, and the two want different things. An
 * entry left behind keeps the agent in the picker pointing at a launcher nothing maintains,
 * so it goes either way — enabling the plugin again provisions it back. The downloads do
 * not: this used to delete them here too, so switching the plugin off and on again
 * re-downloaded a hundred megabytes. Deleting them is [PluginUninstallWatcher]'s job.
 */
class AgentCleanupListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (isUpdate) return
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return

        runCatching { ClaudeAcpManager.getInstance().removeAgentEntry() }
    }
}

/**
 * Fires when this plugin is uninstalled, whether or not that needs a restart.
 *
 * [DynamicPluginListener] cannot tell an uninstall from a disable, and is not called at all
 * for an uninstall that waits for a restart — which left the agent entry and every download
 * behind for good. The plugin manager announces an uninstall to [PluginStateListener]s while
 * the plugin is still loaded, in both cases.
 *
 * Registered by [ClaudeAcpManager] and removed again when it is disposed: the listener list
 * is static, so an entry left in it would hold this class loader after an unload.
 */
internal class PluginUninstallWatcher(private val onUninstall: () -> Unit) : PluginStateListener {

    override fun install(descriptor: IdeaPluginDescriptor) = Unit

    override fun uninstall(descriptor: IdeaPluginDescriptor) {
        if (descriptor.pluginId.idString == PLUGIN_ID) onUninstall()
    }
}

private const val PLUGIN_ID = "dev.rudami.claudeacp"
