package dev.rudami.claudeacp

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
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
                row("Installed versions:") {
                    cell(versionCombo)
                    button("Activate Selected") {
                        val chosen = versionCombo.selectedItem as? String
                        if (chosen != null) manager.updateTo(chosen, null) { refreshStatus() }
                    }
                }.comment(
                    "Rolling back means activating the older version listed here. The two most " +
                        "recent installs are kept on disk.",
                )

                row("Pin to version:") { cell(pinnedField) }.comment(
                    "Leave empty to follow the registry. Pinning freezes the adapter and stops " +
                        "update prompts — the subscription-auth behaviour lives in this package " +
                        "and can change between releases.",
                )

                row("When a new version is released:") { cell(policyCombo) }
                row("Check every (hours):") { cell(intervalSpinner) }
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
                }.comment("Other agents and your MCP settings in that file are left untouched.")
            }
        }
    }

    override fun reset() {
        val state = settings.state
        policyCombo.selectedItem = state.updatePolicy
        intervalSpinner.value = state.checkIntervalHours
        pinnedField.text = state.pinnedVersion.orEmpty()
        nodeField.text = state.nodePathOverride.orEmpty()
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
