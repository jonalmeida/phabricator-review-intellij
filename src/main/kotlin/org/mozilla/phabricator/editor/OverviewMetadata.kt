package org.mozilla.phabricator.editor

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.mozilla.phabricator.service.RevisionsManager

/** Reviewers / projects / stack / summary / test plan sections rendered as a vertical column. */
internal object OverviewMetadata {

    fun build(project: Project, data: OverviewData, scope: CoroutineScope): JPanel {
        val column =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

        addSection(column, "Reviewers", reviewersPanel(data))
        addSection(column, "Projects", projectsPanel(data))
        if (data.stackParents.isNotEmpty() || data.stackChildren.isNotEmpty()) {
            addSection(column, "Stack", stackPanel(project, data))
        }
        val summaryEdit: (() -> Unit)? =
            if (data.isAuthor) {
                {
                    showEdit(project, scope, "Summary", data.model.summaryRaw) {
                        data.model.editSummary(it)
                    }
                }
            } else null
        val testPlanEdit: (() -> Unit)? =
            if (data.isAuthor) {
                {
                    showEdit(project, scope, "Test Plan", data.model.testPlanRaw) {
                        data.model.editTestPlan(it)
                    }
                }
            } else null
        addSection(column, "Summary", htmlPanel(data.summaryHtml), summaryEdit)
        addSection(column, "Test Plan", htmlPanel(data.testPlanHtml), testPlanEdit)

        return column
    }

    private fun addSection(
        column: JPanel,
        label: String,
        body: JPanel,
        onEdit: (() -> Unit)? = null,
    ) {
        val titleRow =
            JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(8, 0, 4, 0)
                alignmentX = Component.LEFT_ALIGNMENT
                add(JBLabel(label).apply { font = font.deriveFont(java.awt.Font.BOLD) })
                onEdit?.let { add(OverviewHeader.editPencil("Edit $label", it)) }
            }
        column.add(titleRow)
        body.alignmentX = Component.LEFT_ALIGNMENT
        column.add(body)
    }

    private fun showEdit(
        project: Project,
        scope: CoroutineScope,
        fieldLabel: String,
        currentValue: String,
        save: suspend (String) -> Unit,
    ) {
        val dialog =
            OverviewMetadataEditDialog(
                project = project,
                fieldLabel = fieldLabel,
                currentValue = currentValue,
                multiline = true,
            )
        if (!dialog.showAndGet()) return
        if (!dialog.isModified) return
        val newValue = dialog.newValue ?: return
        scope.launch { runCatching { save(newValue) } }
    }

    private fun reviewersPanel(data: OverviewData): JPanel {
        val rows =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
        if (data.reviewers.isEmpty()) {
            rows.add(
                JBLabel("(no reviewers)").apply {
                    foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
                }
            )
            return rows
        }
        data.reviewers.forEach { reviewer ->
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
            val name = "${if (reviewer.isProject) "#" else ""}${reviewer.displayName}"
            row.add(JBLabel(name))
            val tag = StringBuilder(reviewerStatusLabel(reviewer.status))
            if (reviewer.isBlocking) tag.append(" · blocking")
            row.add(
                JBLabel(tag.toString()).apply {
                    foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
                }
            )
            row.alignmentX = Component.LEFT_ALIGNMENT
            rows.add(row)
        }
        return rows
    }

    private fun projectsPanel(data: OverviewData): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        if (data.projects.isEmpty()) {
            row.add(
                JBLabel("(none)").apply {
                    foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
                }
            )
        } else {
            data.projects.forEach { row.add(JBLabel("#${it.displayName}")) }
        }
        return row
    }

    private fun stackPanel(project: Project, data: OverviewData): JPanel {
        val column =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
        if (data.stackParents.isNotEmpty()) {
            column.add(JBLabel("Parents:"))
            data.stackParents.forEach { ref -> column.add(stackEntry(project, ref)) }
            column.add(Box.createVerticalStrut(4))
        }
        if (data.stackChildren.isNotEmpty()) {
            column.add(JBLabel("Children:"))
            data.stackChildren.forEach { ref -> column.add(stackEntry(project, ref)) }
        }
        return column
    }

    private fun stackEntry(project: Project, ref: StackRef): HyperlinkLabel {
        // We only have the target PHID; the display label uses the resolved name lookup if cached.
        val resolver = RevisionsManager.getInstance(project).getUserResolver()
        val display = resolver?.displayName(ref.phid)?.takeIf { it != ref.phid } ?: ref.phid
        return HyperlinkLabel(display).apply {
            addHyperlinkListener {
                val cached = RevisionsManager.getInstance(project).getCachedRevisionByPhid(ref.phid)
                if (cached != null) {
                    RevisionOverviewOpener.open(project, cached)
                } else {
                    // Open a tab anchored on the PHID alone. Title falls back to the PHID until the
                    // tab's own loader resolves the real monogram + title.
                    val placeholderFile =
                        RevisionOverviewVirtualFile(
                            revisionPHID = ref.phid,
                            monogram = "D?",
                            title = ref.phid,
                        )
                    FileEditorManager.getInstance(project)
                        .openFile(placeholderFile, /* focusEditor= */ true)
                }
            }
        }
    }

    private fun htmlPanel(html: String): JPanel {
        val pane = OverviewHtml.newPane().apply { text = OverviewHtml.wrap(html) }
        val wrapper =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
        wrapper.add(pane)
        return wrapper
    }

    private fun reviewerStatusLabel(status: String): String =
        when (status) {
            "accepted" -> "accepted"
            "accepted-prior" -> "accepted (older diff)"
            "rejected" -> "requested changes"
            "blocking" -> "blocking"
            "resigned" -> "resigned"
            "added" -> "pending"
            else -> status.ifEmpty { "pending" }
        }
}
