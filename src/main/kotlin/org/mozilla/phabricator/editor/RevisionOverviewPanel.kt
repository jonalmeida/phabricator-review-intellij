package org.mozilla.phabricator.editor

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Root component shown inside the `RevisionOverviewFileEditor` tab.
 *
 * Phase 3 commit 2 (this one) lands the scaffold: just a header showing the monogram + title with a
 * "(loading…)" placeholder body. Commits 3-5 fill in:
 * - Header: status icon, author, optional bug link (commit 3).
 * - Metadata: reviewers, projects, stack, summary, test plan (commit 3).
 * - Files list + activity timeline (commit 4).
 * - Action toolbar + comment composer (commit 5).
 */
class RevisionOverviewPanel(
    @Suppress("UnusedPrivateMember") private val project: Project,
    private val file: RevisionOverviewVirtualFile,
) : JPanel(BorderLayout()) {

    init {
        border = JBUI.Borders.empty(12)
        background = UIUtil.getEditorPaneBackground()

        val header =
            JBLabel("${file.monogram}   ${file.title}").apply {
                font = font.deriveFont(font.style or java.awt.Font.BOLD, font.size + 4f)
            }
        add(header, BorderLayout.NORTH)

        val body =
            JBLabel("(loading…)").apply {
                foreground = UIUtil.getInactiveTextColor()
                border = JBUI.Borders.emptyTop(8)
            }
        add(body, BorderLayout.CENTER)
    }
}
