package org.mozilla.phabricator.editor

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.mozilla.phabricator.ui.toolwindow.RevisionStatusIcons

/**
 * Header row: status icon · monogram · title (with pencil if author) · author · optional bug link.
 */
internal object OverviewHeader {

    fun build(project: Project, data: OverviewData, scope: CoroutineScope): JPanel {
        val titleRow =
            JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(
                    JBLabel(RevisionStatusIcons.forStatus(data.model.statusValue)).apply {
                        verticalAlignment = SwingConstants.CENTER
                    }
                )
                add(
                    JBLabel(data.model.monogram).apply {
                        font = font.deriveFont(font.style or java.awt.Font.BOLD, font.size + 4f)
                    }
                )
                add(JBLabel(data.model.title).apply { font = font.deriveFont(font.size + 2f) })
                if (data.isAuthor) {
                    add(editPencil("Edit title") { showEditTitle(project, data, scope) })
                }
            }
        titleRow.alignmentX = Component.LEFT_ALIGNMENT

        val subtitleParts =
            mutableListOf<String>().apply {
                add(data.model.statusName)
                if (data.authorDisplayName.isNotEmpty()) add("by ${data.authorDisplayName}")
            }
        val subtitle =
            JBLabel(subtitleParts.joinToString(" · ")).apply {
                foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
                border = JBUI.Borders.empty(2, 0, 0, 0)
                alignmentX = Component.LEFT_ALIGNMENT
            }

        val container =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(titleRow)
                add(subtitle)
                data.model.bugzillaBugId?.let { bugId ->
                    add(Box.createVerticalStrut(4))
                    add(bugzillaLink(bugId).apply { alignmentX = Component.LEFT_ALIGNMENT })
                }
            }
        return container
    }

    private fun showEditTitle(project: Project, data: OverviewData, scope: CoroutineScope) {
        val dialog =
            OverviewMetadataEditDialog(
                project = project,
                fieldLabel = "Title",
                currentValue = data.model.title,
                multiline = false,
            )
        if (!dialog.showAndGet()) return
        if (!dialog.isModified) return
        val newValue = dialog.newValue ?: return
        scope.launch { runCatching { data.model.editTitle(newValue) } }
    }

    private fun bugzillaLink(bugId: String): HyperlinkLabel {
        val url = "https://bugzilla.mozilla.org/show_bug.cgi?id=$bugId"
        return HyperlinkLabel("Bug $bugId").apply {
            setHyperlinkTarget(url)
            addHyperlinkListener { BrowserUtil.browse(url) }
        }
    }

    internal fun editPencil(tooltip: String, onClick: () -> Unit): JBLabel =
        JBLabel(AllIcons.Actions.Edit).apply {
            toolTipText = tooltip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        onClick()
                    }
                }
            )
        }
}
