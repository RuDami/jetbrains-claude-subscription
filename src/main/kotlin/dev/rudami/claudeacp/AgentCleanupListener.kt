package dev.rudami.claudeacp

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor

/**
 * Takes our entry back out of `acp.json` when the plugin is uninstalled.
 *
 * Without this the agent stays in the picker forever, pointing at a launcher script that
 * nothing maintains any more, and the downloaded adapters — a hundred megabytes of
 * node_modules — sit in the home directory with nothing left to run them.
 */
class AgentCleanupListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (isUpdate) return
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return

        val manager = ClaudeAcpManager.getInstance()
        manager.removeAgentEntry()
        manager.removeAdapterFiles()
    }

    private companion object {
        const val PLUGIN_ID = "dev.rudami.claudeacp"
    }
}
