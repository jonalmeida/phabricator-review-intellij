package org.mozilla.phabricator.service

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.wm.IdeFrame

/**
 * Tracks IDE focus so background polling can pause when the user is away, mirroring the VSCode
 * plugin's `vscode.window.onDidChangeWindowState` behavior. Registered via `<applicationListener>`
 * in plugin.xml.
 */
class PhabricatorAppActivationListener : ApplicationActivationListener {

    override fun applicationActivated(ideFrame: IdeFrame) {
        isAppActive = true
    }

    override fun applicationDeactivated(ideFrame: IdeFrame) {
        isAppActive = false
    }

    companion object {
        @Volatile
        var isAppActive: Boolean = true
            internal set
    }
}
