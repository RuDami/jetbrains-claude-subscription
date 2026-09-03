package dev.rudami.claudeacp

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings | Tools | Claude Code ACP Bridge.
 *
 * Plain components rather than the UI DSL's `bind*` helpers: applying a change here has to
 * rewrite `acp.json` as a side effect, so the apply step is explicit anyway, and hand-held
 * state keeps the "what is installed right now" labels refreshable after the action buttons
 * run.
 */
class ClaudeAcpConfigurable : Configurable {

    private val settings get() = ClaudeAcpSettings.getInstance()
    private val manager get() = ClaudeAcpManager.getInstance()
    private val installer get() = AdapterInstaller.getInstance()

    private val statusLabel = JBLabel()
    private val versionCombo = JComboBox<String>()
    private val policyCombo = JComboBox(UpdatePolicy.entries.toTypedArray())
    private val intervalSpinner = JSpinner(SpinnerNumberModel(24, 1, 24 * 14, 1))
    private val pinnedField = JBTextField(14)
    private val nodeField = JBTextField(40)
    private val registryField = JBTextField(40)
    private val availableCombo = JComboBox<String>()
    private val manageCheckBox = JBCheckBox("Keep the agent registered in acp.json")
    private val ideaMcpCheckBox = JBCheckBox("Expose the IDE's MCP server to the agent")
    private val customMcpCheckBox = JBCheckBox("Expose your own MCP servers to the agent")

    override fun getDisplayName(): String = "Claude Code ACP Bridge"

    override fun createComponent(): JComponent {
        manageCheckBox.addActionListener { syncEnabled() }

        return panel {
            row("Status:") { cell(statusLabel) }

            row {
                button("Check for Updates") {
                    inBackground("Checking for Claude ACP adapter updates") {
                        manager.checkForUpdates(manual = true)
                        refreshStatus()
                    }
                }
                button("Reinstall / Repair") {
                    inBackground("Reprovisioning the Claude ACP agent") {
                        manager.provision()
                        refreshStatus()
                    }
                }
            }

            group("Adapter") {
                row("Installed:") {
                    cell(versionCombo)
                    button("Activate Selected") {
                        val chosen = versionCombo.selectedItem as? String
                        if (chosen != null) manager.updateTo(chosen, null) { refreshStatus() }
                    }
                }.comment(
                    "Rolling back is activating the older build listed here; the two most recent " +
                        "installs are kept on disk.",
                )

                row("In registry:") {
                    cell(availableCombo)
                    button("Load Versions") {
                        inBackground("Listing Claude ACP adapter versions") {
                            val versions = manager.availableVersions()
                            invokeLater { showAvailable(versions) }
                        }
                    }
                    button("Install Selected") {
                        val chosen = availableCombo.selectedItem as? String
                        if (chosen != null) manager.updateTo(chosen, null) { refreshStatus() }
                    }
                }.comment(
                    "Every published release, newest first. Installing one makes it the active " +
                        "adapter — the previous build stays on disk.",
                )

                row("Pin to version:") { cell(pinnedField) }.comment(
                    "Leave empty to follow the registry. A pin freezes the adapter and silences " +
                        "update prompts — the subscription-auth behaviour lives in this package " +
                        "and can change between releases.",
                )

                row("When a new version is released:") { cell(policyCombo) }
                row("Check every (hours):") { cell(intervalSpinner) }

                row("Registry:") { cell(registryField) }.comment(
                    "Leave empty for ${ClaudeAcpSettings.DEFAULT_REGISTRY}. Set it to a mirror " +
                        "when npmjs.org is not reachable directly.",
                )
            }

            group("Agent") {
                row { cell(manageCheckBox) }
                    .comment("Turn off to manage ${AcpConfigFile.path} by hand.")
                row { cell(ideaMcpCheckBox) }
                row { cell(customMcpCheckBox) }

                row("Node.js binary:") { cell(nodeField) }.comment(
                    "Leave empty to search your shell PATH and then the IDE's own ACP runtimes. " +
                        "Node ${NodeRuntimeResolver.MINIMUM_MAJOR} or newer is required.",
                )

                row {
                    button("Remove Agent from acp.json") {
                        manager.removeAgentEntry()
                        refreshStatus()
                    }
                    button("Delete Downloaded Adapters") {
                        manager.removeAdapterFiles()
                        refreshStatus()
                    }
                }.comment(
                    "Other agents and your MCP settings in acp.json are left untouched. Deleting " +
                        "the adapters frees the node_modules trees under ~/.jetbrains; the next " +
                        "start downloads the version you need again.",
                )
            }
        }
    }

    override fun reset() {
        val state = settings.state
        policyCombo.selectedItem = state.updatePolicy
        intervalSpinner.value = state.checkIntervalHours
        pinnedField.text = state.pinnedVersion.orEmpty()
        nodeField.text = state.nodePathOverride.orEmpty()
        registryField.text = state.registryUrl.orEmpty()
        manageCheckBox.isSelected = state.manageAgent
        ideaMcpCheckBox.isSelected = state.useIdeaMcp
        customMcpCheckBox.isSelected = state.useCustomMcp
        syncEnabled()
        refreshStatus()
    }

    override fun isModified(): Boolean {
        val state = settings.state
        return policyCombo.selectedItem != state.updatePolicy ||
            intervalSpinner.value != state.checkIntervalHours ||
            pinnedField.text.trim() != state.pinnedVersion.orEmpty() ||
            nodeField.text.trim() != state.nodePathOverride.orEmpty() ||
            registryField.text.trim() != state.registryUrl.orEmpty() ||
            manageCheckBox.isSelected != state.manageAgent ||
            ideaMcpCheckBox.isSelected != state.useIdeaMcp ||
            customMcpCheckBox.isSelected != state.useCustomMcp
    }

    override fun apply() {
        val state = settings.state
        state.updatePolicy = policyCombo.selectedItem as? UpdatePolicy ?: UpdatePolicy.NOTIFY
        state.checkIntervalHours = intervalSpinner.value as? Int ?: DEFAULT_INTERVAL_HOURS
        state.pinnedVersion = pinnedField.text.trim().ifEmpty { null }
        state.nodePathOverride = nodeField.text.trim().ifEmpty { null }
        state.registryUrl = registryField.text.trim().ifEmpty { null }
        state.manageAgent = manageCheckBox.isSelected
        state.useIdeaMcp = ideaMcpCheckBox.isSelected
        state.useCustomMcp = customMcpCheckBox.isSelected

        // The MCP flags, the node override and the pin only take effect once the entry is
        // rewritten — and a pin may mean installing a different version.
        inBackground("Applying Claude ACP agent settings") {
            manager.provision()
            refreshStatus()
        }
    }

    private fun syncEnabled() {
        ideaMcpCheckBox.isEnabled = manageCheckBox.isSelected
        customMcpCheckBox.isEnabled = manageCheckBox.isSelected
    }

    /** Fills the registry combo, or reports why it is empty. */
    private fun showAvailable(versions: Result<List<String>>) {
        availableCombo.removeAllItems()
        versions.onSuccess { list ->
            list.forEach { availableCombo.addItem(it) }
            availableCombo.selectedItem = manager.status().installedVersion ?: list.firstOrNull()
        }.onFailure {
            Messages.showErrorDialog(
                "Could not read the version list from the registry.\n\n${it.message}",
                "Claude Code ACP Bridge",
            )
        }
    }

    private fun refreshStatus() {
        val status = manager.status()
        statusLabel.text = "Adapter ${status.installedVersion ?: "not installed"} · " +
            "node: ${status.nodePath ?: "none found"}"

        val selected = versionCombo.selectedItem
        versionCombo.removeAllItems()
        installer.installedVersions().forEach { versionCombo.addItem(it) }
        if (selected != null) versionCombo.selectedItem = selected
    }

    private fun inBackground(title: String, body: () -> Unit) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(null, title, false) {
                override fun run(indicator: ProgressIndicator) = body()
            },
        )
    }

    private companion object {
        const val DEFAULT_INTERVAL_HOURS = 24
    }
}
