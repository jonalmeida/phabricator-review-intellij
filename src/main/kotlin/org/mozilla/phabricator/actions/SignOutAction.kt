package org.mozilla.phabricator.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import org.mozilla.phabricator.service.PhabSessionService

class SignOutAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = PhabSessionService.getInstance().isSignedIn
    }

    override fun actionPerformed(e: AnActionEvent) {
        PhabSessionService.getInstance().signOut()
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Mozilla Phabricator")
            .createNotification("Signed out of Mozilla Phabricator", NotificationType.INFORMATION)
            .notify(null)
    }
}
