package org.mozilla.phabricator.actions

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.launch
import org.mozilla.phabricator.conduit.ConduitError
import org.mozilla.phabricator.diff.DiffRequestContextKeys
import org.mozilla.phabricator.service.InlineCommentController
import org.mozilla.phabricator.service.PhabSessionService
import org.mozilla.phabricator.service.RevisionsManager

/**
 * Toolbar action on the IntelliJ diff viewer (`Diff.ViewerToolbar` group). Fires the empty-string
 * `comment` transaction that Phabricator uses to promote any pending draft inlines of the current
 * user on the revision to published.
 *
 * Phabricator's transaction.search does not surface drafts, so we cannot detect "has drafts" from
 * the server side — the action is enabled whenever the open diff is Phabricator-managed and the
 * user is signed in. Clicking with no drafts is a no-op on the server side (no published comment
 * appears) so the safety floor is fine.
 */
class PublishDraftsAction :
    AnAction(
        "Publish Phabricator Drafts",
        "Promote your pending draft inline comments on this revision to published.",
        AllIcons.Actions.Upload,
    ) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val request = e.getData(DiffDataKeys.DIFF_REQUEST)
        val hasContext = request?.getUserData(DiffRequestContextKeys.REVISION_PHID) != null
        e.presentation.isEnabledAndVisible =
            hasContext && PhabSessionService.getInstance().isSignedIn
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val request = e.getData(DiffDataKeys.DIFF_REQUEST) ?: return
        val revisionPHID = request.getUserData(DiffRequestContextKeys.REVISION_PHID) ?: return
        val model =
            RevisionsManager.getInstance(project).getCachedRevisionByPhid(revisionPHID) ?: return
        val client = PhabSessionService.getInstance().session?.client ?: return
        val monogram =
            request.getUserData(DiffRequestContextKeys.REVISION_MONOGRAM) ?: model.monogram

        val controller = InlineCommentController(model, client)
        PhabSessionService.getInstance().coroutineScope.launch {
            try {
                controller.publishDrafts()
                ApplicationManager.getApplication().invokeLater {
                    notify(
                        "Published any pending drafts on $monogram",
                        NotificationType.INFORMATION,
                    )
                }
            } catch (ex: ConduitError) {
                LOG.warn("publishDrafts failed: ${ex.code} ${ex.info}")
                ApplicationManager.getApplication().invokeLater {
                    notify("Publish failed: ${ex.code}", NotificationType.ERROR)
                }
            } catch (ex: Exception) {
                LOG.warn("publishDrafts failed", ex)
                ApplicationManager.getApplication().invokeLater {
                    notify(
                        "Publish failed: ${ex.message ?: ex.javaClass.simpleName}",
                        NotificationType.ERROR,
                    )
                }
            }
        }
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Mozilla Phabricator")
            .createNotification(message, type)
            .notify(null)
    }

    companion object {
        private val LOG = logger<PublishDraftsAction>()
    }
}
