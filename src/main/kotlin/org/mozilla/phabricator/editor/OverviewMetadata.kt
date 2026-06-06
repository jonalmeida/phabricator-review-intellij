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

        addSection(column, "Reviewers", reviewersPanel(project, data, scope))
        addSection(column, "Projects", projectsPanel(project, data, scope))
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

    private fun reviewersPanel(
        project: Project,
        data: OverviewData,
        scope: CoroutineScope,
    ): JPanel {
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
        } else {
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
                // X icon -- visible when the viewer can remove this reviewer (author can remove
                // anyone; a reviewer can remove themselves to fast-path a Resign).
                val canRemove = data.isAuthor || reviewer.phid == data.viewerPHID
                if (canRemove) {
                    row.add(
                        OverviewHeader.editPencil(
                            "Remove ${reviewer.displayName}",
                            com.intellij.icons.AllIcons.Actions.Close,
                        ) {
                            val confirmed =
                                com.intellij.openapi.ui.Messages.showYesNoDialog(
                                    project,
                                    "Remove ${reviewer.displayName} as a reviewer?",
                                    "Remove Reviewer",
                                    com.intellij.openapi.ui.Messages.getQuestionIcon(),
                                ) == com.intellij.openapi.ui.Messages.YES
                            if (!confirmed) return@editPencil
                            scope.launch {
                                runCatching { data.model.removeReviewers(listOf(reviewer.phid)) }
                            }
                        }
                    )
                }
                row.alignmentX = Component.LEFT_ALIGNMENT
                rows.add(row)
            }
        }
        // Author always sees a "+ Add Reviewer" affordance below the chip rows.
        if (data.isAuthor) {
            val client = RevisionsManager.getInstance(project).session?.client
            val addRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
            val addLabel =
                JBLabel(com.intellij.icons.AllIcons.General.Add).apply {
                    text = "Add reviewer"
                    iconTextGap = 4
                    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                }
            addLabel.addMouseListener(
                object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        val c = client ?: return
                        ReviewerPicker.show(addLabel, c, scope) { user ->
                            scope.launch {
                                runCatching { data.model.addReviewers(listOf(user.phid)) }
                            }
                        }
                    }
                }
            )
            addRow.add(addLabel)
            addRow.alignmentX = Component.LEFT_ALIGNMENT
            rows.add(addRow)
        }
        return rows
    }

    private fun projectsPanel(project: Project, data: OverviewData, scope: CoroutineScope): JPanel {
        val rows =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
        val chipRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        if (data.projects.isEmpty()) {
            chipRow.add(
                JBLabel("(none)").apply {
                    foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
                }
            )
        } else {
            data.projects.forEach { proj ->
                val chip = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { isOpaque = false }
                chip.add(JBLabel("#${proj.displayName}"))
                if (data.isAuthor) {
                    chip.add(
                        OverviewHeader.editPencil(
                            "Remove #${proj.displayName}",
                            com.intellij.icons.AllIcons.Actions.Close,
                        ) {
                            val confirmed =
                                com.intellij.openapi.ui.Messages.showYesNoDialog(
                                    project,
                                    "Remove #${proj.displayName} from this revision?",
                                    "Remove Project",
                                    com.intellij.openapi.ui.Messages.getQuestionIcon(),
                                ) == com.intellij.openapi.ui.Messages.YES
                            if (!confirmed) return@editPencil
                            scope.launch {
                                runCatching { data.model.removeProjects(listOf(proj.phid)) }
                            }
                        }
                    )
                }
                chipRow.add(chip)
            }
        }
        chipRow.alignmentX = Component.LEFT_ALIGNMENT
        rows.add(chipRow)
        if (data.isAuthor) {
            val client = RevisionsManager.getInstance(project).session?.client
            val addRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
            val addLabel =
                JBLabel(com.intellij.icons.AllIcons.General.Add).apply {
                    text = "Add project"
                    iconTextGap = 4
                    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                }
            addLabel.addMouseListener(
                object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        val c = client ?: return
                        ProjectPicker.show(addLabel, c, scope) { p ->
                            scope.launch { runCatching { data.model.addProjects(listOf(p.phid)) } }
                        }
                    }
                }
            )
            addRow.add(addLabel)
            addRow.alignmentX = Component.LEFT_ALIGNMENT
            rows.add(addRow)
        }
        return rows
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
