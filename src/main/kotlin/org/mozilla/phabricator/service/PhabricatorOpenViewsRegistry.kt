package org.mozilla.phabricator.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Project-scoped registry of "open Phabricator views" -- the revision-overview tabs and the
 * Phabricator-managed diff tabs ([org.mozilla.phabricator.diff.ChangesetDiffOpener] opens diffs as
 * [com.intellij.diff.editor.ChainDiffVirtualFile] entries so they can be enumerated and closed).
 *
 * On sign-out the [RevisionsManager] calls [closeAll] so users do not get stranded staring at
 * action toolbars + reply composers that no longer work.
 */
@Service(Service.Level.PROJECT)
class PhabricatorOpenViewsRegistry(private val project: Project) {

    private val files = CopyOnWriteArraySet<VirtualFile>()

    fun register(file: VirtualFile) {
        files += file
    }

    fun unregister(file: VirtualFile) {
        files -= file
    }

    /**
     * Close every Phabricator-managed editor tab in this project. Safe to call from any thread; the
     * FileEditorManager close call hops onto the EDT internally. The registry empties itself as the
     * tabs close (each [unregister] call is driven by the editor's own dispose hook).
     */
    fun closeAll() {
        val snapshot = files.toList()
        if (snapshot.isEmpty()) return
        ApplicationManager.getApplication().invokeLater {
            val fem = FileEditorManager.getInstance(project)
            snapshot.forEach { file -> if (file.isValid) fem.closeFile(file) }
            files.clear()
        }
    }

    companion object {
        fun getInstance(project: Project): PhabricatorOpenViewsRegistry =
            project.getService(PhabricatorOpenViewsRegistry::class.java)
    }
}
