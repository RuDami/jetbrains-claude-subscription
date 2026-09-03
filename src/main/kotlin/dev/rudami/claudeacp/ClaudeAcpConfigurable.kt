package dev.rudami.claudeacp

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.DEFAULT_COMMENT_WIDTH
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
 * state keeps the "what is installed right now" label refreshable after the buttons run.
 *
 * Two layout rules worth keeping. Every field is `AlignX.FILL`, so the panel follows the
 * dialog instead of sizing itself to its widest label. And every explanation is a cell
 * comment with an explicit wrap width: comments default to growing the panel to fit their
 * longest line, which shoves the labelled fields leftward and makes the dialog unreadable
 * at any window size.
 */
class ClaudeAcpConfigurable : Configurable {

    private val settings get() = ClaudeAcpSettings.getInstance()
    private val manager get() = ClaudeAcpManager.getInstance()
    private val installer get() = AdapterInstaller.getInstance()

    private val statusLabel = JBLabel()
    private val versionCombo = JComboBox<String>()
    private val policyCombo = JComboBox(UpdatePolicy.entries.toTypedArray())
    private val intervalSpinner = JSpinner(SpinnerNumberModel(24, 1, 24 * 14, 1))
    private val registryField = JBTextField()
    private val nodeField = JBTextField()
    private val manageCheckBox = JBCheckBox("Keep the agent registered in acp.json")
    private val ideaMcpCheckBox = JBCheckBox("Expose the IDE's MCP server to the agent")
    private val customMcpCheckBox = JBCheckBox("Expose your own MCP servers to the agent")

    override fun getDisplayName(): String = "Claude Code ACP Bridge"

    override fun createComponent(): JComponent {
        manageCheckBox.addActionListener { syncEnabled() }

        return panel {
            row("Status:") {
                cell(statusLabel).align(AlignX.FILL)
            }

            group("Adapter") {
                row("Version:") {
                    cell(versionCombo)
                        .align(AlignX.FILL)
                        .comment(
                            "Every release published to the registry, newest first; the ones " +
                                "already downloaded are marked. Choosing an older build is how " +
                                "you roll back.",
                            DEFAULT_COMMENT_WIDTH,
                        )
                }

                row {
                    button("Use This Version") {
                        selectedVersion()?.let { version ->
                            manager.updateTo(version, null) { reloadVersions(refresh = false) }
                        }
                    }
                    button("Check for Updates") {
                        inBackground("Checking for Claude ACP adapter updates") {
                            manager.checkForUpdates(manual = true)
                            reloadVersions(refresh = true)
                        }
                    }
                    button("Reinstall") {
                        inBackground("Reprovisioning the Claude Code agent") {
                            manager.provision()
                            reloadVersions(refresh = false)
                        }
                    }
                }
            }

            group("Updates") {
                row("On a new release:") {
                    cell(policyCombo)
                        .align(AlignX.FILL)
                        .comment(
                            "The adapter is where subscription authentication lives, so an " +
                                "unattended upgrade can change how logging in behaves.",
                            DEFAULT_COMMENT_WIDTH,
                        )
                }

                row("Check every:") {
                    cell(intervalSpinner)
                    label("hours")
                }

                row("Registry:") {
                    cell(registryField)
                        .align(AlignX.FILL)
                        .comment(
                            "Leave empty for " + ClaudeAcpSettings.DEFAULT_REGISTRY + ". Point " +
                                "it at a mirror when npmjs.org is not reachable directly.",
                            DEFAULT_COMMENT_WIDTH,
                        )
                }
            }

            group("Agent") {
                row {
                    cell(manageCheckBox)
                        .comment(
                            "Turn off to manage " + AcpConfigFile.path + " by hand.",
                            DEFAULT_COMMENT_WIDTH,
                        )
                }
                row { cell(ideaMcpCheckBox) }
                row { cell(customMcpCheckBox) }

                row("Node.js:") {
                    cell(nodeField)
                        .align(AlignX.FILL)
                        .comment(
                            "Leave empty to search your shell PATH and then the IDE's own ACP " +
                                "runtimes. Node " + NodeRuntimeResolver.MINIMUM_MAJOR + " or " +
                                "newer is required.",
                            DEFAULT_COMMENT_WIDTH,
                        )
                }

                row {
                    button("Remove Agent from acp.json") {
                        manager.removeAgentEntry()
                        refreshStatus()
                    }
                    button("Delete Downloaded Adapters") {
                        manager.removeAdapterFiles()
                        reloadVersions(refresh = false)
                    }
                }
            }
        }
    }

    override fun reset() {
        val state = settings.state
        policyCombo.selectedItem = state.updatePolicy
        intervalSpinner.value = state.checkIntervalHours
        registryField.text = state.registryUrl.orEmpty()
        nodeField.text = state.nodePathOverride.orEmpty()
        manageCheckBox.isSelected = state.manageAgent
        ideaMcpCheckBox.isSelected = state.useIdeaMcp
        customMcpCheckBox.isSelected = state.useCustomMcp
        syncEnabled()
        refreshStatus()

        // Fill the list without being asked. A version picker that starts empty until a
        // button is pressed is just a button.
        inBackground("Listing Claude ACP adapter versions") { reloadVersions(refresh = false) }
    }

    override fun isModified(): Boolean {
        val state = settings.state
        return policyCombo.selectedItem != state.updatePolicy ||
            intervalSpinner.value != state.checkIntervalHours ||
            registryField.text.trim() != state.registryUrl.orEmpty() ||
            nodeField.text.trim() != state.nodePathOverride.orEmpty() ||
            manageCheckBox.isSelected != state.manageAgent ||
            ideaMcpCheckBox.isSelected != state.useIdeaMcp ||
            customMcpCheckBox.isSelected != state.useCustomMcp
    }

    override fun apply() {
        val state = settings.state
        state.updatePolicy = policyCombo.selectedItem as? UpdatePolicy ?: UpdatePolicy.NOTIFY
        state.checkIntervalHours = intervalSpinner.value as? Int ?: DEFAULT_INTERVAL_HOURS
        state.registryUrl = registryField.text.trim().ifEmpty { null }
        state.nodePathOverride = nodeField.text.trim().ifEmpty { null }
        state.manageAgent = manageCheckBox.isSelected
        state.useIdeaMcp = ideaMcpCheckBox.isSelected
        state.useCustomMcp = customMcpCheckBox.isSelected

        // The MCP flags and the node override only take effect once the entry is rewritten.
        inBackground("Applying Claude Code agent settings") {
            manager.provision()
            invokeLater { refreshStatus() }
        }
    }

    /** The version behind the selected row, without its status decoration. */
    private fun selectedVersion(): String? =
        (versionCombo.selectedItem as? String)?.substringBefore(' ')?.takeIf { it.isNotBlank() }

    /**
     * Rebuilds the version list from the registry, falling back to whatever is on disk when
     * it cannot be reached.
     *
     * The two sources are merged rather than shown side by side: "installed" and "available"
     * are one list with a column of extra information, and two separate combo boxes left the
     * user to work out which one to use.
     */
    private fun reloadVersions(refresh: Boolean) {
        val installed = installer.installedVersions()
        val published = manager.availableVersions(refresh).getOrNull().orEmpty()
        val active = manager.status().installedVersion

        val rows = (published + installed)
            .distinct()
            .sortedWith(VersionOrder.reversed())
            .map { version ->
                when {
                    version == active -> "$version — active"
                    version in installed -> "$version — downloaded"
                    else -> version
                }
            }

        invokeLater {
            versionCombo.removeAllItems()
            rows.forEach { versionCombo.addItem(it) }
            rows.firstOrNull { it.substringBefore(' ') == active }?.let { versionCombo.selectedItem = it }
            refreshStatus()
        }
    }

    private fun syncEnabled() {
        ideaMcpCheckBox.isEnabled = manageCheckBox.isSelected
        customMcpCheckBox.isEnabled = manageCheckBox.isSelected
    }

    private fun refreshStatus() {
        val status = manager.status()
        statusLabel.text = "Adapter " + (status.installedVersion ?: "not installed") +
            ", node: " + (status.nodePath ?: "none found")
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
