package com.mozilla.phabricator.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.mozilla.phabricator.service.PhabSessionService
import com.mozilla.phabricator.service.RevisionsManager
import kotlinx.coroutines.launch

class RefreshRevisionsAction :
    AnAction(
        "Refresh Revisions",
        "Re-fetch revisions in all categories",
        AllIcons.Actions.Refresh,
    ) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = PhabSessionService.getInstance().isSignedIn && e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = RevisionsManager.getInstance(project)
        PhabSessionService.getInstance().coroutineScope.launch { manager.refresh() }
    }
}
