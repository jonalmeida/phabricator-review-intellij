package com.mozilla.phabricator.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.mozilla.phabricator.service.PhabSessionService
import kotlinx.coroutines.launch

class PhabricatorToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RevisionsPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)

        // Lazily try to restore the session when the tool window first opens.
        // No-op if already signed in.
        val service = PhabSessionService.getInstance()
        service.coroutineScope.launch { service.restore() }
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
