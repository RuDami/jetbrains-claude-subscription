package dev.rudami.claudeacp

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Picks which downloaded adapters to delete.
 *
 * The version in use is not in the list at all, rather than listed and disabled: it can
 * never be a valid choice — deleting it leaves the launcher pointing at nothing — and a row
 * you are not allowed to touch is only there to be clicked at.
 */
class AdapterCleanupDialog(
    private val active: String?,
    private val removable: List<VersionEntry>,
    private val busy: Set<String> = emptySet(),
) : DialogWrapper(true) {

    data class VersionEntry(val version: String, val bytes: Long)

    private val list = CheckBoxList<String>()

    private val includeBusy = JBCheckBox("Delete versions an open chat is still using")

    init {
        title = "Delete Downloaded Adapters"
        setOKButtonText("Delete")
        includeBusy.addActionListener { rebuild() }
        rebuild()
        init()
    }

    /** True when the user opted into deleting versions a running chat holds. */
    val forced: Boolean get() = includeBusy.isSelected

    /**
     * Rebuilds the list for the current choice of whether busy versions may go.
     *
     * They are hidden rather than shown-and-disabled until asked for, because deleting one
     * breaks the chat running on it — but hiding them with no way back left no answer to
     * "how do I reclaim that space", which is worse.
     */
    private fun rebuild() {
        val previously = selected.toSet()
        list.clear()

        removable.filter { forced || it.version !in busy }
            .forEach { list.addItem(it.version, describe(it), it.version in previously) }
    }

    /** Versions the user ticked. */
    val selected: List<String>
        get() = (0 until list.model.size).mapNotNull { index ->
            list.getItemAt(index)?.takeIf { list.isItemSelected(index) }
        }

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(JBLabel(describeKept()))
        }

        row {
            cell(JBScrollPane(list))
                .align(AlignX.FILL)
                .align(AlignY.FILL)
                .applyToComponent { preferredSize = Dimension(360, 180) }
        }.resizableRow()

        row {
            button("Select All") { setAllSelected(true) }
            button("Select None") { setAllSelected(false) }
        }

        if (busy.any { it != active }) {
            row { cell(includeBusy) }
            row {
                comment(
                    "Deleting one of those stops the chat running on it from starting " +
                        "anything new. Closing that chat frees the version without this.",
                    COMMENT_WRAP,
                )
            }
        }
    }

    override fun doValidate(): ValidationInfo? = when {
        removable.isEmpty() -> ValidationInfo("The only adapter on disk is the one in use.")
        selected.isEmpty() -> ValidationInfo("Tick at least one version to delete.")
        else -> null
    }

    /** Names what is being spared, so an absent row is explained rather than just missing. */
    private fun describeKept(): String {
        val stillRunning = busy.filter { it != active }
        val parts = buildList {
            active?.let { add("Keeping $it, which the agent is running.") }
            if (stillRunning.isNotEmpty() && !forced) {
                add("Also keeping " + stillRunning.joinToString() + ", still used by an open chat.")
            }
            if (isEmpty()) add("No adapter is currently active.")
        }
        return parts.joinToString(" ")
    }

    private fun setAllSelected(selected: Boolean) {
        (0 until list.model.size).forEach { list.setItemSelected(list.getItemAt(it), selected) }
        list.repaint()
    }

    private fun describe(entry: VersionEntry): String {
        val size = entry.bytes / MEGABYTE
        val suffix = if (entry.version in busy) "  —  in use by an open chat" else ""
        return entry.version + "  —  " + size + " MB" + suffix
    }

    private companion object {
        const val MEGABYTE = 1024L * 1024L
        const val COMMENT_WRAP = 60
    }
}
