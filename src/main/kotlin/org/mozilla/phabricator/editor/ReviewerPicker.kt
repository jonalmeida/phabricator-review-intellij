package org.mozilla.phabricator.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mozilla.phabricator.conduit.ConduitClient
import org.mozilla.phabricator.conduit.model.User

/**
 * Search-as-you-type popup for picking a user to add as a reviewer. Renders a JBTextField on top of
 * a JBList; typing in the field kicks off a debounced [client.searchUsersByName] (250 ms) and the
 * result list repaints on the EDT.
 *
 * Wired as a JBPopupFactory popup so the picker behaves like the native IDE search popups
 * (focus-following, Esc-to-close, click-elsewhere-to-dismiss). Returns the selected user PHID via
 * [onSelected]; null is never invoked -- callers handle a cancelled popup with the popup's own
 * cancel callback (see [show]'s setCancelCallback).
 *
 * Mirrors the VSCode picker (`vscode.window.showQuickPick` from
 * `vscode/src/phabricator/revisionOverview.ts:368-389`).
 */
internal object ReviewerPicker {

    fun show(
        anchor: Component,
        client: ConduitClient,
        scope: CoroutineScope,
        onSelected: (User) -> Unit,
    ) {
        val listModel = DefaultListModel<User>()
        val list =
            JBList(listModel).apply {
                cellRenderer = userRenderer()
                selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
                visibleRowCount = 6
            }
        val searchField =
            JBTextField().apply {
                preferredSize = Dimension(JBUI.scale(280), preferredSize.height)
                emptyText.text = "Search users by name…"
            }
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(6)
                add(searchField)
                add(JBUI.Borders.emptyTop(4).let { javax.swing.Box.createVerticalStrut(4) })
                add(JBUI.size(0, 0).let { javax.swing.JScrollPane(list) })
            }

        var inFlight: Job? = null
        searchField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = scheduleSearch()

                override fun removeUpdate(e: DocumentEvent) = scheduleSearch()

                override fun changedUpdate(e: DocumentEvent) = scheduleSearch()

                private fun scheduleSearch() {
                    inFlight?.cancel()
                    val query = searchField.text
                    inFlight =
                        scope.launch {
                            delay(250)
                            val results =
                                runCatching { client.searchUsersByName(query) }
                                    .getOrElse { emptyList() }
                            ApplicationManager.getApplication().invokeLater {
                                listModel.clear()
                                results.forEach { listModel.addElement(it) }
                            }
                        }
                }
            }
        )

        val popup: JBPopup =
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, searchField)
                .setRequestFocus(true)
                .setMovable(true)
                .setResizable(true)
                .setCancelOnClickOutside(true)
                .setMinSize(Dimension(JBUI.scale(320), JBUI.scale(220)))
                .createPopup()

        list.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val selected = list.selectedValue ?: return@addListSelectionListener
            popup.cancel()
            onSelected(selected)
        }

        popup.showUnderneathOf(anchor)
    }

    private fun userRenderer(): DefaultListCellRenderer =
        object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val label =
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                        as JLabel
                if (value is User) {
                    val real = value.fields.realName
                    val user = value.fields.username
                    label.text =
                        if (real.isNotEmpty() && real != user) "$real (@$user)" else "@$user"
                }
                return label
            }
        }
}
