package org.mozilla.phabricator.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * FileEditor that hosts the [RevisionOverviewPanel] inside an IntelliJ editor tab.
 *
 * Phase 3 commit 2: read-only, no state to persist, no navigation hooks; commits 3-5 grow the panel
 * without changing this wrapper.
 */
class RevisionOverviewFileEditor(
    private val project: Project,
    private val file: RevisionOverviewVirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val panel = RevisionOverviewPanel(project, file, parentDisposable = this)

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel

    override fun getName(): String = "Phabricator Revision"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) {
        // Read-only panel in Phase 3 -- no scroll position / selection to persist yet.
    }

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun dispose() {
        // Commits 3+ will attach a CoroutineScope here and cancel it on dispose.
    }
}
