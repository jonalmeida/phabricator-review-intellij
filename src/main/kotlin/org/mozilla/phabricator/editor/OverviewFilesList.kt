package org.mozilla.phabricator.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JPanel
import org.mozilla.phabricator.diff.ChangesetDiffOpener

/**
 * Files section: one row per changeset. Each row is path + status badge + inline-comment count.
 * Clicking a row opens the same diff viewer the tool-window tree opens.
 */
internal object OverviewFilesList {

    fun build(project: Project, data: OverviewData): JPanel {
        val column =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
        if (data.files.isEmpty()) {
            column.add(
                JBLabel("(no files)").apply {
                    foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
                }
            )
            return column
        }
        data.files.forEach { file -> column.add(buildRow(project, data, file)) }
        return column
    }

    private fun buildRow(project: Project, data: OverviewData, file: OverviewFile): JPanel {
        val row =
            JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(2, 0)
            }
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.path)
        row.add(
            JBLabel(fileType.icon ?: AllIcons.FileTypes.Any_type).apply {
                horizontalAlignment = javax.swing.SwingConstants.LEFT
            }
        )
        if (file.statusLabel.isNotEmpty()) {
            row.add(
                JBLabel(file.statusLabel).apply {
                    foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
                }
            )
        }
        row.add(JBLabel(file.path))
        if (file.inlineCount > 0) {
            val noun = if (file.inlineCount == 1) "comment" else "comments"
            row.add(
                JBLabel("${file.inlineCount} $noun").apply {
                    foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
                }
            )
        }
        row.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 || e.clickCount == 1 && e.button == MouseEvent.BUTTON1) {
                        ChangesetDiffOpener.open(project, data.model, file.changeset)
                    }
                }
            }
        )
        return row
    }
}
