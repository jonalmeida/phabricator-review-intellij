package org.mozilla.phabricator.editor

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import org.mozilla.phabricator.service.PhabricatorOpenViewsRegistry
import org.mozilla.phabricator.service.RevisionModel

/**
 * Opens (or focuses) the IDE editor tab that hosts a revision's overview panel. Equality on
 * [RevisionOverviewVirtualFile] (keyed on revisionPHID) means FileEditorManager reuses the existing
 * tab if the revision is already open.
 */
object RevisionOverviewOpener {

    fun open(project: Project, revision: RevisionModel) {
        val file =
            RevisionOverviewVirtualFile(
                revisionPHID = revision.phid,
                monogram = revision.monogram,
                title = revision.title.ifEmpty { revision.monogram },
            )
        PhabricatorOpenViewsRegistry.getInstance(project).register(file)
        FileEditorManager.getInstance(project).openFile(file, /* focusEditor= */ true)
    }
}
