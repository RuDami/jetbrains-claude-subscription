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

    /** Last snapshot taken off the EDT; see [refreshStatus]. */
    private var lastStatus: ClaudeAcpManager.Status? = null

    /**
     * How many background operations are running.
     *
     * A flag would be wrong: two overlapping operations — the version list loading while an
     * apply is under way — would have the first one to finish re-enable every control while
     * the second is still going.
     */
    private val busy = ClaudeAcpPageModel.BusyCounter()

    /** Set once the page is closed; see [onUi]. */
    private var disposed = false

    override fun getDisplayName(): String = "Claude Code ACP Bridge"

    override fun createComponent(): JComponent {
        managedControls.clear()
        disposed = false

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
                        inBackground("Checking for Claude ACP adapter updates", "Checking the registry") {
                            manager.checkForUpdates(manual = true)
                            reloadVersions(refresh = true, report = true)
                        }
                    }.explain("Asks the registry whether a newer adapter has been published.")

                    button("Repair") {
                        inBackground("Repairing the Claude Code agent", "Repairing the installation") {
                            val result = manager.provision()
                            reloadVersions(refresh = false)
                            onUi {
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
        inBackground("Listing Claude ACP adapter versions", "Loading adapter versions") {
            loadDetectedNodes()
            reloadVersions(refresh = false)
        }
    }

    /**
     * A background task started from this page can finish after the dialog is closed, and
     * the page is rebuilt from scratch next time it opens. Dropping the references keeps a
     * late arrival from repainting components nobody is looking at.
     */
    override fun disposeUIResources() {
        disposed = true
        managedControls.clear()
        lastStatus = null
        busy.reset()
    }

    /**
     * Applies [body] on the EDT unless the page is gone.
     *
     * A background task cannot be cancelled once the dialog closes, so it finishes and tries
     * to repaint components nobody is looking at — harmless today, but it also re-enables
     * controls and rewrites labels on a page the platform may reuse.
     */
    private fun onUi(body: () -> Unit) {
        invokeLater {
            if (!disposed) body()
        }
    }

    override fun isModified(): Boolean = ClaudeAcpPageModel.isModified(
        edited = currentForm(),
        stored = ClaudeAcpPageModel.formOf(settings.state),
        managed = settings.state.manageAgent,
    )

    /** What the controls currently say, in the shape the model compares. */
    private fun currentForm() = ClaudeAcpPageModel.Form(
        version = selectedVersion(),
        policy = policyCombo.selectedItem as? UpdatePolicy ?: UpdatePolicy.NOTIFY,
        intervalHours = intervalSpinner.value as? Int ?: DEFAULT_INTERVAL_HOURS,
        registry = registryChoice(),
        nodePath = nodeChoice(),
        useIdeaMcp = ideaMcpCheckBox.isSelected,
        useCustomMcp = customMcpCheckBox.isSelected,
    )

    override fun apply() {
        val state = settings.state

        // Skipping is a decision about one release under one policy. Changing the policy is
        // a decision about updates in general, and leaving the skip in place meant the
        // version skipped under "notify me" stayed invisible after switching to automatic.
        val chosenPolicy = policyCombo.selectedItem as? UpdatePolicy ?: UpdatePolicy.NOTIFY
        if (chosenPolicy != state.updatePolicy) state.skippedVersion = null

        state.updatePolicy = chosenPolicy
        state.checkIntervalHours = intervalSpinner.value as? Int ?: DEFAULT_INTERVAL_HOURS
        state.registryUrl = registryChoice()
        state.nodePathOverride = nodeChoice()
        state.useIdeaMcp = ideaMcpCheckBox.isSelected
        state.useCustomMcp = customMcpCheckBox.isSelected

        val desired = selectedVersion()
        if (versionChanged() && desired != null) {
            beginBusy("Installing " + desired)
            // updateTo runs its own background task, so the busy state is closed from its
            // completion callback — which it always calls, including when it drops a
            // duplicate request.
            manager.updateTo(desired, null) {
                reloadVersions(refresh = false)
                onUi { endBusy() }
            }
        } else {
            inBackground("Applying Claude Code agent settings", "Applying settings") {
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
            inBackground("Removing the Claude Code agent", "Removing the agent") {
                manager.removeAgentEntry()
                manager.removeAdapterFiles()
                onUi {
                    versionCombo.removeAllItems()
                    diskLabel.text = "nothing downloaded"
                }
            }
        } else {
            state.manageAgent = true
            state.announced = false
            syncManaged()
            // A first install downloads the adapter, which is the longest wait this page
            // ever produces.
            inBackground("Setting up the Claude Code agent", "Downloading the adapter") {
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
        // Clearing the combo drops the discovered interpreters with it, so they are put back.
        inBackground("Detecting Node.js interpreters") { loadDetectedNodes() }

        // reloadVersions sorts descending, so the first row is the newest release.
        if (versionCombo.itemCount > 0) versionCombo.selectedIndex = 0
    }

    private fun openCleanupDialog() {
        inBackground("Measuring downloaded Claude ACP adapters", "Measuring downloads") {
            val active = manager.status().installedVersion
            // A chat left open keeps running an older adapter, and that copy is not spare.
            val busy = installer.versionsInUse()
            val removable = installer.installedVersions()
                .filter { it != active }
                .map { AdapterCleanupDialog.VersionEntry(it, installer.diskUsage(it)) }

            onUi {
                if (removable.isEmpty()) {
                    Messages.showInfoMessage(
                        "Every adapter on disk is in use — by the active agent or by a chat " +
                            "that is still open.",
                        "Claude Code ACP Bridge",
                    )
                    return@onUi
                }

                val dialog = AdapterCleanupDialog(active, removable, busy)
                if (!dialog.showAndGet()) return@onUi

                val chosen = dialog.selected
                val force = dialog.forced
                inBackground("Deleting Claude ACP adapters", "Deleting adapters") {
                    val removed = manager.removeVersions(chosen, force)
                    reloadVersions(refresh = false)
                    onUi {
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
        ClaudeAcpPageModel.registryChoice(registryCombo.editor.item as? String)

    private fun nodeChoice(): String? =
        ClaudeAcpPageModel.nodeChoice(nodeCombo.selectedItem as? String)

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
        current?.let { nodeCombo.addItem(it) }
        nodeCombo.selectedItem = current ?: AUTOMATIC_NODE
    }

    /**
     * Adds the interpreters found on this machine.
     *
     * Separate from [fillDetectedNodes] because finding them runs `node -v` on every
     * candidate, which is a process spawn each — fine on a background thread, an outright
     * freeze on the EDT, where the settings page used to do it while opening.
     */
    private fun loadDetectedNodes() {
        val detected = NodeRuntimeResolver.detectAll().map { it.toString() }

        onUi {
            val existing = (0 until nodeCombo.itemCount).mapNotNull { nodeCombo.getItemAt(it) }
            detected.filterNot { it in existing }.forEach { nodeCombo.addItem(it) }
        }
    }

    private fun selectedVersion(): String? =
        ClaudeAcpPageModel.versionOf(versionCombo.selectedItem as? String)

    /**
     * Whether the picker asks for a different build than the one recorded.
     *
     * Recorded, not resolved: the settings dialog calls [isModified] constantly — on focus
     * changes and keystrokes — and producing a fresh status resolves the interpreter, which
     * spawns a process.
     */
    private fun versionChanged(): Boolean {
        if (!settings.state.manageAgent) return false
        val desired = selectedVersion() ?: return false
        return desired != settings.state.installedVersion
    }

    private fun syncManaged() {
        val managed = settings.state.manageAgent
        managedControls.forEach { it.isEnabled = managed }
        toggleButton.text = if (managed) "Remove Agent" else "Add Agent"
    }

    /**
     * Does not touch the busy state: its callers already hold one through [inBackground], and
     * a second `beginBusy` here was never matched by a second `endBusy` — which left the page
     * spinning on "Loading adapter versions" forever while the result quietly arrived below.
     */
    private fun reloadVersions(refresh: Boolean, report: Boolean = false) {
        val installed = installer.installedVersions()
        val fetched = manager.availableVersions(refresh)
        val published = fetched.getOrNull().orEmpty()
        // Taken here rather than on the EDT: producing it resolves the interpreter, which
        // spawns `node -v`.
        val status = manager.status()
        val active = status.installedVersion

        val rows = ClaudeAcpPageModel.versionRows(published, installed, active)

        // Walking a couple of node_modules trees is thousands of stat calls, so it stays on
        // this background thread rather than riding along with the label update on the EDT.
        val summary = ClaudeAcpPageModel.describeDisk(installed.size, manager.diskUsage())

        onUi {
            versionCombo.removeAllItems()
            rows.forEach { versionCombo.addItem(it) }
            rows.firstOrNull { ClaudeAcpPageModel.versionOf(it) == active }
                ?.let { versionCombo.selectedItem = it }
            diskLabel.text = summary
            lastStatus = status

            when {
                // Not fatal: what is already downloaded still runs, and the list falls back
                // to it. Saying so beats a combo that is silently short.
                fetched.isFailure ->
                    showError("Could not reach the registry. Showing downloaded versions only.")

                report -> showInfo(
                    ClaudeAcpPageModel.describeFreshness(published.firstOrNull(), active),
                )
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
        // The result is deliberately ignored: joining an existing busy state should still
        // update the label to name the newest operation, and every other effect here is
        // idempotent.
        busy.begin()
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
        if (!busy.end()) return

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

    /**
     * Paints the status line from the last snapshot taken off the EDT.
     *
     * It never asks for a fresh one, because producing one resolves the Node interpreter,
     * which runs `node -v` with a five-second timeout — a process spawn, on the EDT, every
     * time this page repainted its own label. The background paths that already do slow work
     * hand their snapshot here instead.
     *
     * A label is also as wide as its text wants to be, and a path to a node binary has no
     * space to wrap at, so the path is abbreviated and kept in full in the tooltip.
     */
    private fun refreshStatus(status: ClaudeAcpManager.Status? = lastStatus) {
        if (!settings.state.manageAgent) {
            statusLabel.text = "No agent installed"
            statusLabel.toolTipText = null
            return
        }

        if (status == null) {
            statusLabel.text = "Checking..."
            statusLabel.toolTipText = null
            return
        }

        lastStatus = status
        statusLabel.text = ClaudeAcpPageModel.describeStatus(
            status.installedVersion,
            status.nodePath,
            System.getProperty("user.home").orEmpty(),
        )
        statusLabel.toolTipText = status.nodePath
    }

    /**
     * Runs [body] off the EDT, pairing the busy state around it.
     *
     * Pairing lives here rather than at each call site because it stopped being balanced the
     * moment the busy state became a counter: six `beginBusy` calls were scattered across
     * button handlers against two `endBusy` calls, and every action left the page spinning.
     * `finally` also covers a body that throws, which nothing did before.
     */
    private fun inBackground(title: String, busy: String? = null, body: () -> Unit) {
        if (busy != null) beginBusy(busy)

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(null, title, false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        body()
                    } finally {
                        if (busy != null) onUi { endBusy() }
                    }
                }
            },
        )
    }

    private companion object {
        const val DEFAULT_INTERVAL_HOURS = 24

        // Owned by the model, which also reads them back off the controls.
        const val AUTOMATIC_NODE = ClaudeAcpPageModel.AUTOMATIC_NODE
        const val LOADING_ITEM = ClaudeAcpPageModel.LOADING_ITEM

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
