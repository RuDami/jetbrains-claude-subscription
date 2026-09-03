package dev.rudami.claudeacp

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.Dimension
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JComponent

/**
 * Settings | Tools | Claude Code ACP Bridge | Permissions — the project's
 * `.claude/settings.json`.
 *
 * This is not our file: Claude Code reads it whether or not this plugin exists, and the
 * adapter watches it, so edits reach a running agent immediately. The page is a front end
 * for one key, and everything else in the file is left exactly as found.
 *
 * Deliberately not offering `availableModels`. The chat's own model picker already covers
 * choosing a model, that key is documented as an administrator's control, and there is no
 * way from here to enumerate the models an account can actually use — a field nobody can
 * fill in correctly is worse than no field.
 *
 * Rules are Claude Code's own syntax, e.g. `Bash(npm run test:*)` or `Read(./.env)`.
 * Deny wins over allow.
 *
 * Note for editors of this file: Kotlin nests block comments, so a glob containing a slash
 * followed by a star opens one inside a KDoc and swallows the rest of the file.
 */
class ClaudePermissionsConfigurable(private val project: Project) : Configurable {

    private val scopeCombo = ComboBox(ClaudeSettingsFile.Scope.entries.toTypedArray()).apply {
        renderer = SimpleScopeRenderer()
    }
    private val modeCombo = ComboBox(PERMISSION_MODES).apply { toolTipText = MODE_HELP }
    private val allowArea = rulesArea()
    private val denyArea = rulesArea()
    private val askArea = rulesArea()
    private val pathLabel = JBLabel()
    private val rulesNotice = JBLabel().apply { isVisible = false }

    /** Reloaded whenever the scope changes, so the fields always show the chosen file. */
    private var loaded: ClaudeSettingsFile.Permissions = ClaudeSettingsFile.Permissions()

    /** Which file the fields currently belong to; see [onScopeChanged]. */
    private var previousScope: ClaudeSettingsFile.Scope = ClaudeSettingsFile.Scope.SHARED

    override fun getDisplayName(): String = "Claude Code Permissions"

    override fun createComponent(): JComponent {
        scopeCombo.addActionListener { onScopeChanged() }
        modeCombo.addActionListener { syncRulesEnabled() }

        return panel {
            row("Applies to:") {
                cell(scopeCombo)
                    .align(AlignX.FILL)
                    .comment(
                        "Shared settings are committed with the project. Personal settings " +
                            "stay on this machine and win over shared ones.",
                        COMMENT_WRAP,
                    )
            }

            row("File:") { cell(pathLabel).align(AlignX.FILL) }

            row("Default mode:") {
                cell(modeCombo)
                    .align(AlignX.FILL)
                    .comment(
                        "How the agent behaves when a rule does not decide. Claude Code " +
                            "ignores an escalating mode coming from shared settings, so set " +
                            "those under Only me.",
                        COMMENT_WRAP,
                    )
            }

            group("Rules") {
                row { cell(rulesNotice).align(AlignX.FILL) }

                rulesRow("Always allow:", allowArea, "Runs without asking.")
                rulesRow("Always ask:", askArea, "Prompts even when another rule allows it.")
                rulesRow("Never allow:", denyArea, "Refused outright. Deny wins over allow.")

                row {
                    comment(
                        "One rule per line: a tool name, optionally narrowed in brackets. " +
                            "Bash(npm run test:*) allows those commands only; Read(./.env) " +
                            "covers that path. A bare tool name such as Edit covers every use " +
                            "of it. The chat writes here too: choosing Always Allow on a " +
                            "prompt adds a rule to this file, and this page keeps those.",
                        COMMENT_WRAP,
                    )
                }
            }
        }
    }

    private fun com.intellij.ui.dsl.builder.Panel.rulesRow(
        label: String,
        area: JBTextArea,
        description: String,
    ) {
        row(label) {
            cell(JBScrollPane(area))
                .align(AlignX.FILL)
                .applyToComponent { preferredSize = Dimension(320, 70) }
                .comment(description, COMMENT_WRAP)
        }
    }

    // ---------------------------------------------------------------- Configurable

    override fun reset() {
        previousScope = ClaudeSettingsFile.Scope.SHARED
        scopeCombo.selectedItem = previousScope
        loadFromDisk()
    }

    override fun isModified(): Boolean = currentPermissions() != loaded

    override fun apply() {
        if (writeTo(settingsFile())) {
            // Re-read rather than trusting the edit: the merge may have kept rules that
            // arrived from the chat, and the fields should show what the file now holds.
            loadFromDisk()
        }
    }

    /** @return false when the file could not be parsed, in which case nothing was written. */
    private fun writeTo(file: ClaudeSettingsFile): Boolean {
        // `loaded` is the baseline, so rules the chat approved while this page was open are
        // merged rather than overwritten.
        if (file.write(loaded, currentPermissions())) return true

        Messages.showErrorDialog(
            "The settings file could not be parsed, so nothing was written. Fix or delete it " +
                "and try again.",
            "Claude Code Permissions",
        )
        return false
    }

    // ---------------------------------------------------------------- state

    private fun baseDirectory(): Path =
        project.basePath?.let(Paths::get) ?: Paths.get(System.getProperty("user.home"))

    private fun settingsFile(): ClaudeSettingsFile {
        val scope = scopeCombo.selectedItem as ClaudeSettingsFile.Scope
        return ClaudeSettingsFile(scope.fileIn(baseDirectory()))
    }

    /**
     * Switching scope loads a different file, which would silently discard whatever was
     * typed for the previous one — and because the baseline reloads with it, the dialog
     * would then think nothing had changed and close without saving. The user is asked
     * instead, and can send the edits to the file they were made for.
     */
    private fun onScopeChanged() {
        if (currentPermissions() != loaded) {
            val answer = Messages.showYesNoCancelDialog(
                "Save the changes to " + previousScope.label.lowercase() + " before switching?",
                "Claude Code Permissions",
                Messages.getQuestionIcon(),
            )
            when (answer) {
                Messages.CANCEL -> {
                    scopeCombo.selectedItem = previousScope
                    return
                }

                Messages.YES -> writeTo(ClaudeSettingsFile(previousScope.fileIn(baseDirectory())))
            }
        }

        previousScope = scopeCombo.selectedItem as ClaudeSettingsFile.Scope
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val file = settingsFile()
        pathLabel.text = shorten(file.path)
        pathLabel.toolTipText = file.path.toString()

        loaded = file.readPermissions()

        allowArea.text = loaded.allow.joinToString("\n")
        denyArea.text = loaded.deny.joinToString("\n")
        askArea.text = loaded.ask.joinToString("\n")
        modeCombo.selectedItem = loaded.defaultMode ?: PERMISSION_MODES.first()
        syncRulesEnabled()
    }

    private fun currentPermissions() = ClaudeSettingsFile.Permissions(
        allow = allowArea.lines(),
        deny = denyArea.lines(),
        ask = askArea.lines(),
        defaultMode = (modeCombo.selectedItem as? String)?.takeIf { it != PERMISSION_MODES.first() },
    )

    /**
     * Greys the rule editors out under the modes that make them moot, and says which.
     *
     * `bypassPermissions` skips permission checks altogether and `plan` runs no tools at all,
     * so rules decide nothing under either. `auto` is *not* one of them, tempting as it looks:
     * the classifier only handles prompts that reach it, and a deny rule still short-circuits
     * before that — the SDK lists the auto-mode classifier and deny rules side by side as
     * separate sources of an auto-denial.
     */
    private fun syncRulesEnabled() {
        val mode = modeCombo.selectedItem as? String
        val notice = when (mode) {
            "bypassPermissions" -> "This mode skips permission checks, so no rule applies."
            "plan" -> "This mode runs no tools, so no rule applies."
            else -> null
        }

        listOf(allowArea, denyArea, askArea).forEach { it.isEnabled = notice == null }
        rulesNotice.text = notice.orEmpty()
        rulesNotice.isVisible = notice != null
    }

    private fun JBTextArea.lines(): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** A full path would set a floor under the panel's width; the tooltip keeps it. */
    private fun shorten(path: Path): String {
        val text = path.toString()
        val home = System.getProperty("user.home")
        val shortened = if (text.startsWith(home)) "~" + text.removePrefix(home) else text
        return if (shortened.length <= MAX_PATH_CHARS) shortened
        else "..." + shortened.takeLast(MAX_PATH_CHARS)
    }

    private class SimpleScopeRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            selected: Boolean,
            focused: Boolean,
        ): java.awt.Component = super.getListCellRendererComponent(
            list,
            (value as? ClaudeSettingsFile.Scope)?.label ?: value,
            index,
            selected,
            focused,
        )
    }

    private companion object {
        fun rulesArea() = JBTextArea().apply {
            lineWrap = false
            rows = 4
        }

        /**
         * Every mode the SDK's settings schema accepts, plus "Inherit", which is this page's
         * way of writing no `defaultMode` at all.
         *
         * The first cut listed four and silently dropped `auto` and `dontAsk` — a settings
         * page that offers a subset of the valid values makes the missing ones look invalid.
         */
        val PERMISSION_MODES = arrayOf(
            "Inherit",
            "default",
            "plan",
            "acceptEdits",
            "auto",
            "dontAsk",
            "bypassPermissions",
        )

        /** Short enough to read in a tooltip; the full story is Claude Code's own docs. */
        const val MODE_HELP = """<html>
            default - prompts before anything dangerous<br>
            plan - plans only, runs no tools<br>
            acceptEdits - file edits go through without asking<br>
            auto - a model classifier decides each prompt<br>
            dontAsk - never prompts, denies whatever is not pre-approved<br>
            bypassPermissions - skips permission checks entirely
            </html>"""

        const val COMMENT_WRAP = 60
        const val MAX_PATH_CHARS = 45
    }
}
