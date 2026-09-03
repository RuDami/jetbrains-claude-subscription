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
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.AsyncProcessIcon
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

    /** Spinner and error line, so a slow registry looks like waiting rather than nothing. */
    private val busyIcon = AsyncProcessIcon("claude-acp-loading").apply { isVisible = false }
    private val messageLabel = JBLabel().apply { isVisible = false }

    private val versionCombo = ComboBox<String>()
    private val policyCombo = ComboBox(UpdatePolicy.entries.toTypedArray())
    private val intervalSpinner = JSpinner(SpinnerNumberModel(24, 1, 24 * 14, 1))
    private val registryCombo = ComboBox(ClaudeAcpSettings.KNOWN_REGISTRIES.toTypedArray()).apply {
        isEditable = true
    }
    // Not editable: "Automatic" is a sentinel, and letting it be typed over meant a
    // half-deleted word became a node path. Custom interpreters arrive through Browse.
    private val nodeCombo = ComboBox<String>()
    private val ideaMcpCheckBox = JBCheckBox("Expose the IDE's MCP server to the agent")
    private val customMcpCheckBox = JBCheckBox("Expose your own MCP servers to the agent")

    /** Everything that is meaningless while no agent is installed. */
    private val managedControls = mutableListOf<JComponent>()
    private lateinit var toggleButton: JButton

    override fun getDisplayName(): String = "Claude Code ACP Bridge"

    override fun createComponent(): JComponent {
        managedControls.clear()

        return panel {
            row("Status:") {
                cell(busyIcon)
                cell(statusLabel).align(AlignX.FILL)
            }

            row { cell(messageLabel).align(AlignX.FILL) }

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
                    button("Check for Updates") {
                        beginBusy("Checking the registry")
                        inBackground("Checking for Claude ACP adapter updates") {
                            manager.checkForUpdates(manual = true)
                            reloadVersions(refresh = true, report = true)
                        }
                    }.explain("Asks the registry whether a newer adapter has been published.")

                    button("Repair") {
                        beginBusy("Repairing the installation")
                        inBackground("Repairing the Claude Code agent") {
                            val result = manager.provision()
                            reloadVersions(refresh = false)
                            invokeLater {
                                if (result.isSuccess) {
                                    showInfo(
                                        "Repaired. Adapter " +
                                            (manager.status().installedVersion ?: "unknown") +
                                            " is installed and the agent is registered.",
                                    )
                                } else {
                                    showError("Repair failed. See the IDE log for details.")
                                }
                            }
                        }
                    }.explain(
                        "Re-downloads the current adapter if its files are missing and rewrites " +
                            "the agent entry. Use it when the agent stops appearing in the chat.",
                    )

                }

                // Its own row. Three buttons abreast set the panel's minimum width, and the
                // sentence explaining all three set it again — the tooltips say the same
                // thing without costing any width at all.
                row {
                    button("Free Up Space") { openCleanupDialog() }
                        .explain("Choose which downloaded adapters to delete.")
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
                            COMMENT_WRAP,
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

    /** A tooltip plus [managed], since every explained control here is also a managed one. */
    private fun <T : JComponent> Cell<T>.explain(text: String): Cell<T> {
        component.toolTipText = text
        return managed()
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
            beginBusy("Installing " + desired)
            manager.updateTo(desired, null) { reloadVersions(refresh = false) }
        } else {
            beginBusy("Applying settings")
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
            beginBusy("Removing the agent")
            inBackground("Removing the Claude Code agent") {
                manager.removeAgentEntry()
                manager.removeAdapterFiles()
                invokeLater {
                    versionCombo.removeAllItems()
                    diskLabel.text = "nothing downloaded"
                    endBusy()
                }
            }
        } else {
            state.manageAgent = true
            state.announced = false
            syncManaged()
            // A first install downloads the adapter, which is the longest wait this page
            // ever produces.
            beginBusy("Downloading the adapter")
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
            // The chooser can return an interpreter the scan never found, so it is added to
            // the list rather than merely selected.
            if ((0 until nodeCombo.itemCount).none { nodeCombo.getItemAt(it) == chosen.path }) {
                nodeCombo.addItem(chosen.path)
            }
            nodeCombo.selectedItem = chosen.path
        }
    }

    // ---------------------------------------------------------------- state helpers

    /** Null when the default is chosen, so stored settings hold an override or nothing. */
    private fun registryChoice(): String? =
        (registryCombo.editor.item as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != ClaudeAcpSettings.DEFAULT_REGISTRY }

    private fun nodeChoice(): String? =
        (nodeCombo.selectedItem as? String)?.takeIf { it != AUTOMATIC_NODE }

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
        (versionCombo.selectedItem as? String)
            ?.takeIf { it != LOADING_ITEM }
            ?.substringBefore(' ')
            ?.takeIf { it.isNotBlank() }

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

    private fun reloadVersions(refresh: Boolean, report: Boolean = false) {
        invokeLater { beginBusy("Loading adapter versions") }

        val installed = installer.installedVersions()
        val fetched = manager.availableVersions(refresh)
        val published = fetched.getOrNull().orEmpty()
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

            endBusy()
            when {
                // Not fatal: what is already downloaded still runs, and the list falls back
                // to it. Saying so beats a combo that is silently short.
                fetched.isFailure ->
                    showError("Could not reach the registry. Showing downloaded versions only.")

                report -> showInfo(describeFreshness(published.firstOrNull(), active))
            }
        }
    }

    // ---------------------------------------------------------------- busy state

    /**
     * Startup work here is slow enough to look broken — the registry round trip, and on a
     * first run an npm install — and all of it happens on a background thread while the
     * page sits there empty. The spinner and this label are the only sign it is working.
     */
    private fun beginBusy(message: String) {
        messageLabel.isVisible = false
        busyIcon.isVisible = true
        busyIcon.resume()
        statusLabel.text = message + "..."
        statusLabel.toolTipText = null

        // The controls say it too. A spinner at the top of a page whose buttons still look
        // clickable invites a second click, which is how the same install got requested and
        // announced twice.
        managedControls.forEach { it.isEnabled = false }
        toggleButton.isEnabled = false

        if (versionCombo.itemCount == 0) versionCombo.addItem(LOADING_ITEM)
    }

    private fun endBusy() {
        busyIcon.suspend()
        busyIcon.isVisible = false
        toggleButton.isEnabled = true
        syncManaged()
        refreshStatus()
    }

    private fun showError(message: String) = showMessage(message, JBColor.RED)

    private fun showInfo(message: String) = showMessage(message, JBLabel().foreground)

    /**
     * Says what an action did.
     *
     * Every button here works on files and a config the user cannot see, so without a line
     * like this the only difference between "repaired" and "did nothing" is that the dialog
     * closed.
     */
    private fun showMessage(message: String, color: java.awt.Color) {
        messageLabel.text = message
        messageLabel.foreground = color
        messageLabel.isVisible = true
    }

    private fun describeFreshness(newest: String?, active: String?): String = when {
        newest == null -> "The registry returned no versions."
        active == null -> "Newest release is " + newest + ". Nothing is installed yet."
        VersionOrder.compare(newest, active) > 0 -> "Update available: " + newest + "."
        else -> "Up to date on " + active + "."
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
        const val LOADING_ITEM = "Loading..."

        /**
         * Characters before a comment wraps.
         *
         * Without a limit a comment is laid out on one line however long it is, and since
         * the panel must fit its widest child, one long sentence pushes every labelled
         * field leftwards and produces a horizontal scrollbar. Short comments are unaffected
         * either way; this is only for the ones that run past a line.
         */
        const val COMMENT_WRAP = 60
    }
}
