package com.mozilla.phabricator.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.mozilla.phabricator.conduit.ConduitError
import com.mozilla.phabricator.service.PhabSessionService
import com.mozilla.phabricator.service.PhabricatorSettings
import com.mozilla.phabricator.ui.auth.SignInDialog
import kotlinx.coroutines.launch

class SignInAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !PhabSessionService.getInstance().isSignedIn
    }

    override fun actionPerformed(e: AnActionEvent) {
        val service = PhabSessionService.getInstance()
        if (service.isSignedIn) return

        val baseUrl = PhabricatorSettings.getInstance().baseUrl
        val dialog = SignInDialog(e.project, conduitTokenUrlFor(baseUrl))
        if (!dialog.showAndGet()) return
        val token = dialog.token

        service.coroutineScope.launch {
            try {
                service.signIn(token)
                ApplicationManager.getApplication().invokeLater {
                    notifyInfo("Signed in to Mozilla Phabricator")
                }
            } catch (ex: ConduitError) {
                LOG.warn("Sign-in failed: ${ex.code} ${ex.info}")
                ApplicationManager.getApplication().invokeLater {
                    notifyError("Phabricator rejected the token: ${ex.code}")
                }
            } catch (ex: Exception) {
                LOG.warn("Sign-in failed", ex)
                ApplicationManager.getApplication().invokeLater {
                    notifyError("Sign-in failed: ${ex.message ?: ex.javaClass.simpleName}")
                }
            }
        }
    }

    private fun notifyInfo(message: String) = notify(message, NotificationType.INFORMATION)

    private fun notifyError(message: String) = notify(message, NotificationType.ERROR)

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(message, type)
            .notify(null)
    }

    companion object {
        private val LOG = logger<SignInAction>()
        private const val NOTIFICATION_GROUP = "Mozilla Phabricator"

        /**
         * Build the human URL the user should visit to mint a token. The Conduit API URL ends with
         * `/api/`; the human-facing settings page is served from the same host's root, so we strip
         * the trailing `/api/`.
         */
        fun conduitTokenUrlFor(apiBaseUrl: String): String {
            val withoutApi = apiBaseUrl.removeSuffix("/").removeSuffix("/api")
            return "$withoutApi/conduit/login/"
        }
    }
}
