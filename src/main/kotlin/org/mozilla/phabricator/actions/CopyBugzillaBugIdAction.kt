package org.mozilla.phabricator.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

/**
 * Copies the selected revision's linked Bugzilla bug as "Bug <id>" to the system clipboard. Hidden
 * when the revision has no `bugzilla.bug-id` extension field set (i.e. no Mozilla bug link).
 * Available from the revisions tree's right-click context menu.
 *
 * Output format matches Mozilla's commit-message convention -- a user pasting the result into a
 * Bugzilla comment, commit message, or chat lands the expected "Bug 1234567" anchor that ties back
 * to the bug.
 */
class CopyBugzillaBugIdAction :
    AnAction("Copy Bug ID", "Copy 'Bug <id>' for the linked Bugzilla bug to the clipboard.", null) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val model = e.getData(PhabricatorDataKeys.SELECTED_REVISION_MODEL)
        e.presentation.isEnabledAndVisible = model?.bugzillaBugId != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val bugId = e.getData(PhabricatorDataKeys.SELECTED_REVISION_MODEL)?.bugzillaBugId ?: return
        CopyPasteManager.getInstance().setContents(StringSelection("Bug $bugId"))
    }
}
