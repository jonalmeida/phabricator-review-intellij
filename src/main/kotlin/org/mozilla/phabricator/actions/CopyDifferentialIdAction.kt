package org.mozilla.phabricator.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

/**
 * Copies the selected revision's differential identifier (the `Dxxxxx` monogram) to the system
 * clipboard. Available from the revisions tree's right-click context menu.
 */
class CopyDifferentialIdAction :
    AnAction("Copy Differential ID", "Copy the Dxxxxx monogram to the clipboard.", null) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.getData(PhabricatorDataKeys.SELECTED_REVISION_MODEL) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val model = e.getData(PhabricatorDataKeys.SELECTED_REVISION_MODEL) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(model.monogram))
    }
}
