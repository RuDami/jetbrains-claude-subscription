package dev.rudami.claudeacp

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.panel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings | Tools | Claude Code ACP Bridge.
 *
 * Plain components rather than the UI DSL's `bind*` helpers: applying a change here has to
 * rewrite `acp.json` as a side effect, so the apply step is explicit anyway, and hand-held
 * state keeps the live labels refreshable after the buttons run.
 *
 * Two layout rules worth keeping. Every field is `AlignX.FILL` and sits alone on its row, so
 * the panel follows the dialog instead of splitting a row's width with a button. And no
 * comment or label may contain a path or a URL: neither has a space to wrap at, so each one
 * sets a floor under the panel's width and produces a horizontal scrollbar at any size.
 */
class ClaudeAcpConfigurable : Configurable {

    private val settings get() = ClaudeAcpSettings.getInstance()
    private val manager get() = ClaudeAcpManager.getInstance()
    private val installer get() = AdapterInstaller.getInstance()

    private val statusLabel = JBLabel()
    private val diskLabel = JBLabel()

    private val versionCombo = ComboBox<String>()
    private val policyCombo = ComboBox(UpdatePolicy.entries.toTypedArray())
    private val intervalSpinner = JSpinner(SpinnerNumberModel(24, 1, 24 * 14, 1))
    private val registryCombo = ComboBox(ClaudeAcpSettings.KNOWN_REGISTRIES.toTypedArray()).apply {
        isEditable = true
    }
    private val nodeCombo = ComboBox<String>().apply { isEditable = true }
    private val ideaMcpCheckBox = JBCheckBox("Expose the IDE's MCP server to the agent")
    private val customMcpCheckBox = JBCheckBox("Expose your own MCP servers to the agent")

    /** Everything that is meaningless while no agent is installed. */
    private val managedControls = mutableListOf<JComponent>()
    private lateinit var toggleButton: JButton

    override fun getDisplayName(): String = "Claude Code ACP Bridge"

    override fun createComponent(): JComponent {
        managedControls.clear()

        return panel {
            row("Status:") { cell(statusLabel).align(AlignX.FILL) }

            row {
                toggleButton = button("") { toggleAgent() }.component
            }

            group("Adapter") {
                row("Version:") {
                    cell(versionCombo)
                        .align(AlignX.FILL)
                        .comment("Newest first. Pick one and press OK to switch.")
                        .managed()
                }

                row("Downloaded:") {
                    cell(diskLabel).align(AlignX.FILL)
                }

                row {
                    button("Clean Up") { openCleanupDialog() }.managed()
                    button("Check for Updates") {
                        inBackground("Checking for Claude ACP adapter updates") {
                            manager.checkForUpdates(manual = true)
                            reloadVersions(refresh = true)
                        }
                    }.managed()
                    button("Reinstall") {
                        inBackground("Reprovisioning the Claude Code agent") {
                            manager.provision()
                            reloadVersions(refresh = false)
                        }
                    }.managed()
                }
            }

            group("Updates") {
                row("On a new release:") {
                    cell(policyCombo)
                        .align(AlignX.FILL)
                        .comment("Updates can change how subscription login behaves.")
                        .managed()
                }

                row("Check every:") {
                    cell(intervalSpinner).managed()
                    label("hours")
                }

                row("Registry:") {
                    cell(registryCombo)
                        .align(AlignX.FILL)
                        .comment("Pick a mirror or type your own.")
                        .managed()
                }
            }

            group("Agent") {
                row { cell(ideaMcpCheckBox).managed() }
                row { cell(customMcpCheckBox).managed() }

                // The field owns its row. Sharing one with the browse button squeezed the
                // combo down to a stub too narrow to read a path in.
                row("Node.js:") {
                    cell(nodeCombo)
                        .align(AlignX.FILL)
                        .comment(
                            "Detected interpreters. Automatic searches PATH, then the IDE's " +
                                "own runtimes. Needs Node " +
                                NodeRuntimeResolver.MINIMUM_MAJOR + " or newer.",
                        )
                        .managed()
                }

                row {
                    button("Browse...") { browseForNode() }.managed()
                }
            }

            row {
                button("Restore Defaults") { restoreDefaults() }.managed()
            }
        }
    }

    /** Marks a control as one to switch off while no agent is installed. */
    private fun <T : JComponent> Cell<T>.managed(): Cell<T> {
        managedControls += component
        return this
    }

    // ---------------------------------------------------------------- Configurable

    override fun reset() {
        val state = settings.state
        policyCombo.selectedItem = state.updatePolicy
        intervalSpinner.value = state.checkIntervalHours
        registryCombo.selectedItem = state.registryUrl ?: ClaudeAcpSettings.DEFAULT_REGISTRY
        ideaMcpCheckBox.isSelected = state.useIdeaMcp
        customMcpCheckBox.isSelected = state.useCustomMcp
        fillDetectedNodes(state.nodePathOverride)

        syncManaged()
        refreshStatus()
        inBackground("Listing Claude ACP adapter versions") { reloadVersions(refresh = false) }
    }

    override fun isModified(): Boolean {
        val state = settings.state
        return versionChanged() ||
            policyCombo.selectedItem != state.updatePolicy ||
            intervalSpinner.value != state.checkIntervalHours ||
            registryChoice() != state.registryUrl ||
            nodeChoice() != state.nodePathOverride ||
            ideaMcpCheckBox.isSelected != state.useIdeaMcp ||
            customMcpCheckBox.isSelected != state.useCustomMcp
    }

    override fun apply() {
        val state = settings.state
        state.updatePolicy = policyCombo.selectedItem as? UpdatePolicy ?: UpdatePolicy.NOTIFY
        state.checkIntervalHours = intervalSpinner.value as? Int ?: DEFAULT_INTERVAL_HOURS
        state.registryUrl = registryChoice()
        state.nodePathOverride = nodeChoice()
        state.useIdeaMcp = ideaMcpCheckBox.isSelected
        state.useCustomMcp = customMcpCheckBox.isSelected

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

    // ---------------------------------------------------------------- actions

    /**
     * Adds or removes the agent outright.
     *
     * Removing used to take the entry out of the config and change nothing anyone could
     * see: the next apply or the next startup put it straight back, so the button looked
     * broken. It now switches the whole feature off — entry gone, downloads deleted, page
     * disabled — and Add turns it back on. That is the only reading of "remove" that
     * survives contact with a plugin which reprovisions on every start.
     */
    private fun toggleAgent() {
        val state = settings.state

        if (state.manageAgent) {
            val confirmed = Messages.showYesNoDialog(
                "Remove the agent from the chat list and delete the downloaded adapters?",
                "Claude Code ACP Bridge",
                Messages.getQuestionIcon(),
            )
            if (confirmed != Messages.YES) return

            state.manageAgent = false
            syncManaged()
            inBackground("Removing the Claude Code agent") {
                manager.removeAgentEntry()
                manager.removeAdapterFiles()
                invokeLater {
                    versionCombo.removeAllItems()
                    diskLabel.text = "nothing downloaded"
                    refreshStatus()
                }
            }
        } else {
            state.manageAgent = true
            state.announced = false
            syncManaged()
            inBackground("Setting up the Claude Code agent") {
                manager.provision()
                reloadVersions(refresh = false)
            }
        }
    }

    /**
     * Back to what a fresh install would have: newest adapter, public registry, automatic
     * interpreter, notify on updates.
     *
     * "Automatic" rather than the project's configured Node.js: reading that means depending
     * on the NodeJS plugin, which is absent from several IDEs that speak ACP, and the
     * automatic search already prefers the interpreter a project would use — the one on
     * your PATH.
     */
    private fun restoreDefaults() {
        policyCombo.selectedItem = UpdatePolicy.NOTIFY
        intervalSpinner.value = DEFAULT_INTERVAL_HOURS
        registryCombo.selectedItem = ClaudeAcpSettings.DEFAULT_REGISTRY
        ideaMcpCheckBox.isSelected = true
        customMcpCheckBox.isSelected = true
        fillDetectedNodes(null)

        // reloadVersions sorts descending, so the first row is the newest release.
        if (versionCombo.itemCount > 0) versionCombo.selectedIndex = 0
    }

    private fun openCleanupDialog() {
        inBackground("Measuring downloaded Claude ACP adapters") {
            val active = manager.status().installedVersion
            val removable = installer.installedVersions()
                .filter { it != active }
                .map { AdapterCleanupDialog.VersionEntry(it, installer.diskUsage(it)) }

            invokeLater {
                if (removable.isEmpty()) {
                    Messages.showInfoMessage(
                        "The only adapter on disk is the one in use.",
                        "Claude Code ACP Bridge",
                    )
                    return@invokeLater
                }

                val dialog = AdapterCleanupDialog(active, removable)
                if (!dialog.showAndGet()) return@invokeLater

                val chosen = dialog.selected
                inBackground("Deleting Claude ACP adapters") {
                    val removed = manager.removeVersions(chosen)
                    reloadVersions(refresh = false)
                    invokeLater {
                        Messages.showInfoMessage(
                            "Removed " + removed.joinToString() + ".",
                            "Claude Code ACP Bridge",
                        )
                    }
                }
            }
        }
    }

    private fun browseForNode() {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withTitle("Select Node.js Interpreter")
        val start = nodeChoice()?.let { LocalFileSystem.getInstance().findFileByPath(it) }

        FileChooser.chooseFile(descriptor, null, start) { chosen ->
            nodeCombo.selectedItem = chosen.path
            nodeCombo.editor.item = chosen.path
        }
    }

    // ---------------------------------------------------------------- state helpers

    /** Null when the default is chosen, so stored settings hold an override or nothing. */
    private fun registryChoice(): String? =
        (registryCombo.editor.item as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != ClaudeAcpSettings.DEFAULT_REGISTRY }

    private fun nodeChoice(): String? =
        (nodeCombo.editor.item as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != AUTOMATIC_NODE }

    /**
     * Lists what the machine has, with the default spelled out rather than left blank.
     *
     * A blank field means "something was chosen for you", which is invisible — an unset
     * field and a broken one look identical. The first entry says so in words, and the
     * status line above shows what it resolved to.
     */
    private fun fillDetectedNodes(current: String?) {
        nodeCombo.removeAllItems()
        nodeCombo.addItem(AUTOMATIC_NODE)

        val detected = NodeRuntimeResolver.detectAll().map { it.toString() }
        (detected + listOfNotNull(current)).distinct().forEach { nodeCombo.addItem(it) }
        nodeCombo.selectedItem = current ?: AUTOMATIC_NODE
    }

    private fun selectedVersion(): String? =
        (versionCombo.selectedItem as? String)?.substringBefore(' ')?.takeIf { it.isNotBlank() }

    private fun versionChanged(): Boolean {
        if (!settings.state.manageAgent) return false
        val desired = selectedVersion() ?: return false
        return desired != manager.status().installedVersion
    }

    private fun syncManaged() {
        val managed = settings.state.manageAgent
        managedControls.forEach { it.isEnabled = managed }
        toggleButton.text = if (managed) "Remove Agent" else "Add Agent"
    }

    private fun reloadVersions(refresh: Boolean) {
        val installed = installer.installedVersions()
        val published = manager.availableVersions(refresh).getOrNull().orEmpty()
        val active = manager.status().installedVersion

        val rows = (published + installed)
            .distinct()
            .sortedWith(VersionOrder.reversed())
            .map { version ->
                when {
                    version == active -> version + " - active"
                    version in installed -> version + " - downloaded"
                    else -> version
                }
            }

        // Walking a couple of node_modules trees is thousands of stat calls, so it stays on
        // this background thread rather than riding along with the label update on the EDT.
        val summary = describeDisk(installed.size, manager.diskUsage())

        invokeLater {
            versionCombo.removeAllItems()
            rows.forEach { versionCombo.addItem(it) }
            rows.firstOrNull { it.substringBefore(' ') == active }
                ?.let { versionCombo.selectedItem = it }
            diskLabel.text = summary
            refreshStatus()
        }
    }

    private fun describeDisk(versions: Int, bytes: Long): String = when (versions) {
        0 -> "nothing downloaded"
        1 -> "1 copy, " + (bytes / MEGABYTE) + " MB"
        else -> versions.toString() + " copies, " + (bytes / MEGABYTE) + " MB"
    }

    /**
     * A label is as wide as its text wants to be, and a path to a node binary has no space
     * to wrap at — printing one in full stretched the dialog and put a scrollbar under it.
     */
    private fun refreshStatus() {
        if (!settings.state.manageAgent) {
            statusLabel.text = "No agent installed"
            statusLabel.toolTipText = null
            return
        }

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
        const val AUTOMATIC_NODE = "Automatic"
    }
}
