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
import com.intellij.ui.dsl.builder.AlignX
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
    private val diskLabel = JBLabel()
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
                        .comment("Pick one and press OK to switch. An older build is a rollback.")
                }

                row("Downloaded:") {
                    cell(diskLabel)
                        .align(AlignX.FILL)
                        .comment("Clean Up keeps only the copy in use.")
                    button("Clean Up") {
                        inBackground("Removing unused Claude ACP adapters") {
                            val removed = manager.cleanUpInactiveVersions()
                            reloadVersions(refresh = false)
                            invokeLater { announceCleanup(removed) }
                        }
                    }
                }

                row {
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
                        .comment("Updates can change how subscription login behaves.")
                }

                row("Check every:") {
                    cell(intervalSpinner)
                    label("hours")
                }

                row("Registry:") {
                    cell(registryField)
                        .align(AlignX.FILL)
                        .comment("Empty uses the public npm registry.")
                }
            }

            group("Agent") {
                row {
                    cell(manageCheckBox)
                        .comment("Turn off to edit the ACP config by hand.")
                }
                row { cell(ideaMcpCheckBox) }
                row { cell(customMcpCheckBox) }

                row("Node.js:") {
                    cell(nodeField)
                        .align(AlignX.FILL)
                        .comment("Empty searches PATH, then the IDE's own runtimes. Needs Node " +
                            NodeRuntimeResolver.MINIMUM_MAJOR + "+.")
                }

                row {
                    button("Remove Agent from acp.json") {
                        manager.removeAgentEntry()
                        refreshStatus()
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
        return versionChanged() ||
            policyCombo.selectedItem != state.updatePolicy ||
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

        // Switching version is an install; everything else just needs the entry rewritten
        // for the change to reach the agent.
        val desired = selectedVersion()
        if (versionChanged() && desired != null) {
            manager.updateTo(desired, null) { reloadVersions(refresh = false) }
        } else {
            inBackground("Applying Claude Code agent settings") {
                manager.provision()
                reloadVersions(refresh = false)
            }
        }
    }

    private fun versionChanged(): Boolean {
        val desired = selectedVersion() ?: return false
        return desired != manager.status().installedVersion
    }

    private fun announceCleanup(removed: List<String>) {
        val message = if (removed.isEmpty()) {
            "Nothing to remove: the only adapter on disk is the one in use."
        } else {
            "Removed " + removed.joinToString() + "."
        }
        Messages.showInfoMessage(message, "Claude Code ACP Bridge")
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

        // Walking a couple of node_modules trees is thousands of stat calls; it belongs on
        // the caller's background thread, not on the EDT with the label update.
        val summary = describeDisk(installed.size, manager.diskUsage())

        invokeLater {
            versionCombo.removeAllItems()
            rows.forEach { versionCombo.addItem(it) }
            rows.firstOrNull { it.substringBefore(' ') == active }?.let { versionCombo.selectedItem = it }
            diskLabel.text = summary
            refreshStatus()
        }
    }

    private fun describeDisk(versions: Int, bytes: Long): String = when (versions) {
        0 -> "nothing downloaded yet"
        else -> {
            val copies = if (versions == 1) "1 copy" else "$versions copies"
            "$copies, ${bytes / MEGABYTE} MB"
        }
    }

    private fun syncEnabled() {
        ideaMcpCheckBox.isEnabled = manageCheckBox.isSelected
        customMcpCheckBox.isEnabled = manageCheckBox.isSelected
    }

    /**
     * A label is as wide as its text wants to be, and an absolute path to a node binary has
     * no spaces to wrap at — printing one here stretched the whole dialog and put a
     * horizontal scrollbar under it. The path is shortened for display and kept in full in
     * the tooltip.
     */
    private fun refreshStatus() {
        val status = manager.status()
        val node = status.nodePath

        statusLabel.text = "Adapter " + (status.installedVersion ?: "not installed") +
            ", node " + (node?.let(::abbreviate) ?: "not found")
        statusLabel.toolTipText = node
    }

    private fun abbreviate(path: String): String {
        val home = System.getProperty("user.home")
        val shortened = if (path.startsWith(home)) "~" + path.removePrefix(home) else path
        return if (shortened.length <= MAX_PATH_CHARS) shortened
        else "..." + shortened.takeLast(MAX_PATH_CHARS)
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
        const val MEGABYTE = 1024L * 1024L
        const val MAX_PATH_CHARS = 40
    }
}
