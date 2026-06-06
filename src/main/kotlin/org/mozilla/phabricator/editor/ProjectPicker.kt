package org.mozilla.phabricator.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mozilla.phabricator.conduit.ConduitClient
import org.mozilla.phabricator.conduit.model.Project

/**
 * Search-as-you-type popup for adding a project tag. Same structure as [ReviewerPicker] -- only the
 * endpoint (`project.search` via `searchProjectsByName`) and the row renderer differ. Refactor into
 * a shared generic if a third picker (Phase 5 testing-tag?) joins.
 */
internal object ProjectPicker {

    fun show(
        anchor: Component,
        client: ConduitClient,
        scope: CoroutineScope,
        onSelected: (Project) -> Unit,
    ) {
        val listModel = DefaultListModel<Project>()
        val list =
            JBList(listModel).apply {
                cellRenderer = projectRenderer()
                selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
                visibleRowCount = 6
            }
        val searchField =
            JBTextField().apply {
                preferredSize = Dimension(JBUI.scale(280), preferredSize.height)
                emptyText.text = "Search projects by name…"
            }
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(6)
                add(searchField)
                add(Box.createVerticalStrut(4))
                add(JScrollPane(list))
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
                                runCatching { client.searchProjectsByName(query) }
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

    private fun projectRenderer(): DefaultListCellRenderer =
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
                if (value is Project) {
                    label.text = "#${value.fields.name}"
                }
                return label
            }
        }
}
