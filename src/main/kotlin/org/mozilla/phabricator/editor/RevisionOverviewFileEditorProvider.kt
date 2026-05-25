package org.mozilla.phabricator.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Routes [RevisionOverviewVirtualFile] tokens to a [RevisionOverviewFileEditor] tab.
 *
 * `policy = HIDE_DEFAULT_EDITOR` so the platform's text-editor provider does not also try to open
 * the virtual file (which would show an empty text editor alongside ours).
 */
class RevisionOverviewFileEditorProvider : FileEditorProvider {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file is RevisionOverviewVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        RevisionOverviewFileEditor(project, file as RevisionOverviewVirtualFile)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        const val EDITOR_TYPE_ID = "phabricator-revision-overview"
    }
}
