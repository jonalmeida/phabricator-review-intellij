package org.mozilla.phabricator.editor

import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.testFramework.LightVirtualFile

/**
 * Identity token for an open revision-overview editor tab. Carries only the data the
 * [RevisionOverviewFileEditorProvider] needs to recognise and route it. Equality is keyed on
 * [revisionPHID] so opening the same revision twice reuses the existing tab rather than stacking
 * duplicates.
 *
 * [presentableName] returns "Dxxxxx — Title" so the tab label matches what the user already sees in
 * the tool-window tree.
 */
class RevisionOverviewVirtualFile(
    val revisionPHID: String,
    val monogram: String,
    val title: String,
) :
    LightVirtualFile(
        /* name = */ "$monogram — $title",
        /* fileType = */ UnknownFileType.INSTANCE,
        /* text = */ "",
    ) {

    init {
        isWritable = false
    }

    override fun equals(other: Any?): Boolean =
        other is RevisionOverviewVirtualFile && other.revisionPHID == revisionPHID

    override fun hashCode(): Int = revisionPHID.hashCode()
}
