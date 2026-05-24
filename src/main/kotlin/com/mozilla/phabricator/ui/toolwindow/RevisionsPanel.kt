package com.mozilla.phabricator.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.mozilla.phabricator.service.PhabSession
import com.mozilla.phabricator.service.PhabSessionService
import com.mozilla.phabricator.service.SessionListener
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Root component for the Phabricator tool window. Toggles between a signed-out prompt and a
 * signed-in placeholder; the actual revisions tree fills the signed-in body in commit 7.
 */
class RevisionsPanel(private val project: Project, private val parentDisposable: Disposable) :
    JPanel(BorderLayout()) {

    private val statusLabel = JLabel("", SwingConstants.CENTER)
    private val signInButton =
        JButton("Sign In").apply { addActionListener { invokeAction("Phabricator.SignIn") } }
    private val signOutButton =
        JButton("Sign Out").apply { addActionListener { invokeAction("Phabricator.SignOut") } }
    private val refreshButton =
        JButton("Refresh").apply { addActionListener { invokeAction("Phabricator.Refresh") } }
    private val body = JPanel(BorderLayout())
    private var treeView: RevisionsTreeView? = null

    init {
        border = JBUI.Borders.empty(8)
        add(buildToolbar(), BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)

        ApplicationManager.getApplication()
            .messageBus
            .connect(parentDisposable)
            .subscribe(
                SessionListener.TOPIC,
                object : SessionListener {
                    override fun signedIn(session: PhabSession) =
                        ApplicationManager.getApplication().invokeLater { render(session) }

                    override fun signedOut() =
                        ApplicationManager.getApplication().invokeLater { render(null) }
                },
            )

        render(PhabSessionService.getInstance().session)
    }

    private fun buildToolbar(): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(statusLabel)
            add(signInButton)
            add(signOutButton)
            add(refreshButton)
        }

    private fun render(session: PhabSession?) {
        body.removeAll()
        if (session == null) {
            statusLabel.text = "Not signed in"
            signInButton.isVisible = true
            signOutButton.isVisible = false
            refreshButton.isVisible = false
            treeView = null
            body.add(
                JLabel("Sign in to view Phabricator revisions.", SwingConstants.CENTER),
                BorderLayout.CENTER,
            )
        } else {
            statusLabel.text = "Signed in as ${session.userName}"
            signInButton.isVisible = false
            signOutButton.isVisible = true
            refreshButton.isVisible = true
            val view =
                treeView ?: RevisionsTreeView(project, parentDisposable).also { treeView = it }
            body.add(view.component, BorderLayout.CENTER)
        }
        body.revalidate()
        body.repaint()
    }

    private fun invokeAction(actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        val event =
            AnActionEvent.createEvent(
                action,
                DataContext.EMPTY_CONTEXT,
                null,
                "Phabricator.ToolWindow",
                ActionUiKind.NONE,
                null,
            )
        ActionUtil.invokeAction(action, event, null)
    }
}
