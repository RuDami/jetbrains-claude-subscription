package dev.rudami.claudeacp

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor

/**
 * Takes our entry back out of `acp.json` when the plugin is uninstalled.
 *
 * Without this the agent stays in the picker forever, pointing at a launcher script that
 * a later cleanup may well have deleted.
 */
class AgentCleanupListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (isUpdate) return
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return

        ClaudeAcpManager.getInstance().removeAgentEntry()
    }

    private companion object {
        const val PLUGIN_ID = "dev.rudami.claudeacp"
    }
}
