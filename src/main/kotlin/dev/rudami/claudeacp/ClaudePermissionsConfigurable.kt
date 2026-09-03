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
 * Settings | Tools | Claude Code Permissions — the project's `.claude/settings.json`.
 *
 * This is not our file: Claude Code reads it whether or not this plugin exists, and the
 * adapter watches it, so edits reach a running agent immediately. The page is a front end
 * for two keys, and everything else in the file is left exactly as found.
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
    private val modeCombo = ComboBox(PERMISSION_MODES)
    private val allowArea = rulesArea()
    private val denyArea = rulesArea()
    private val askArea = rulesArea()
    private val modelsArea = rulesArea()
    private val pathLabel = JBLabel()

    /** Reloaded whenever the scope changes, so the fields always show the chosen file. */
    private var loaded: ClaudeSettingsFile.Permissions = ClaudeSettingsFile.Permissions()
    private var loadedModels: List<String> = emptyList()

    override fun getDisplayName(): String = "Claude Code Permissions"

    override fun createComponent(): JComponent {
        scopeCombo.addActionListener { loadFromDisk() }

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
                row {
                    label("One per line, for example Bash(npm run test:*) or Read(./.env).")
                }

                rulesRow("Always allow:", allowArea)
                rulesRow("Always ask:", askArea)
                rulesRow("Never allow:", denyArea)

                row {
                    label("Deny wins over allow.")
                }
            }

            group("Models") {
                rulesRow("Allowed models:", modelsArea)
                row {
                    label("Empty allows every model. Accepts opus, sonnet or a full model id.")
                }
            }
        }
    }

    private fun com.intellij.ui.dsl.builder.Panel.rulesRow(label: String, area: JBTextArea) {
        row(label) {
            cell(JBScrollPane(area))
                .align(AlignX.FILL)
                .applyToComponent { preferredSize = Dimension(320, 70) }
        }
    }

    // ---------------------------------------------------------------- Configurable

    override fun reset() {
        scopeCombo.selectedItem = ClaudeSettingsFile.Scope.SHARED
        loadFromDisk()
    }

    override fun isModified(): Boolean =
        currentPermissions() != loaded || currentModels() != loadedModels

    override fun apply() {
        val file = settingsFile()
        val written = file.write(currentPermissions(), currentModels())

        if (!written) {
            Messages.showErrorDialog(
                "The settings file could not be parsed, so nothing was written. Fix or delete " +
                    "it and try again.",
                "Claude Code Permissions",
            )
            return
        }

        loaded = currentPermissions()
        loadedModels = currentModels()
    }

    // ---------------------------------------------------------------- state

    private fun projectDir(): Path? = project.basePath?.let(Paths::get)

    private fun settingsFile(): ClaudeSettingsFile {
        val scope = scopeCombo.selectedItem as ClaudeSettingsFile.Scope
        val base = projectDir() ?: Paths.get(System.getProperty("user.home"))
        return ClaudeSettingsFile(scope.fileIn(base))
    }

    private fun loadFromDisk() {
        val file = settingsFile()
        pathLabel.text = shorten(file.path)
        pathLabel.toolTipText = file.path.toString()

        loaded = file.readPermissions()
        loadedModels = file.readAvailableModels()

        allowArea.text = loaded.allow.joinToString("\n")
        denyArea.text = loaded.deny.joinToString("\n")
        askArea.text = loaded.ask.joinToString("\n")
        modeCombo.selectedItem = loaded.defaultMode ?: PERMISSION_MODES.first()
        modelsArea.text = loadedModels.joinToString("\n")
    }

    private fun currentPermissions() = ClaudeSettingsFile.Permissions(
        allow = allowArea.lines(),
        deny = denyArea.lines(),
        ask = askArea.lines(),
        defaultMode = (modeCombo.selectedItem as? String)?.takeIf { it != PERMISSION_MODES.first() },
    )

    private fun currentModels(): List<String> = modelsArea.lines()

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

        /** "Inherit" is this page's way of writing no `defaultMode` at all. */
        val PERMISSION_MODES = arrayOf(
            "Inherit",
            "default",
            "plan",
            "acceptEdits",
            "bypassPermissions",
        )

        const val COMMENT_WRAP = 60
        const val MAX_PATH_CHARS = 45
    }
}
