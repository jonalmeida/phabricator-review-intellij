package org.mozilla.phabricator.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import org.mozilla.phabricator.conduit.model.Changeset
import org.mozilla.phabricator.conduit.model.ChangesetFileType
import org.mozilla.phabricator.conduit.model.ChangesetType
import org.mozilla.phabricator.service.RevisionModel

/**
 * Opens IntelliJ's built-in diff viewer for a [Changeset]. Phase 1: read-only. Sides are
 * synthesized from the hunk corpus via [DiffSynthesizer]; file type is resolved from the path so
 * syntax highlighting works without touching the VirtualFileSystem.
 *
 * Phase 2 will swap [DiffManager.showDiff] for a DiffRequestPanel + custom DiffExtension so inline
 * comment gutters can attach.
 */
object ChangesetDiffOpener {

    fun open(project: Project, revision: RevisionModel, changeset: Changeset) {
        if (
            changeset.fileType == ChangesetFileType.BINARY ||
                changeset.fileType == ChangesetFileType.IMAGE
        ) {
            ApplicationManager.getApplication().invokeLater {
                DiffManager.getInstance()
                    .showDiff(
                        project,
                        SimpleDiffRequest(
                            diffTitle(revision, changeset),
                            DiffContentFactory.getInstance()
                                .create("(binary file -- contents not shown)"),
                            DiffContentFactory.getInstance()
                                .create("(binary file -- contents not shown)"),
                            "Base",
                            "Tip",
                        ),
                    )
            }
            return
        }

        val before = DiffSynthesizer.synthesizeSide(changeset, DiffSynthesizer.Side.BEFORE)
        val after = DiffSynthesizer.synthesizeSide(changeset, DiffSynthesizer.Side.AFTER)
        val fileType = resolveFileType(changeset)

        val request =
            SimpleDiffRequest(
                diffTitle(revision, changeset),
                DiffContentFactory.getInstance().create(project, before, fileType),
                DiffContentFactory.getInstance().create(project, after, fileType),
                sideTitle(revision, changeset, before = true),
                sideTitle(revision, changeset, before = false),
            )
        ApplicationManager.getApplication().invokeLater {
            DiffManager.getInstance().showDiff(project, request)
        }
    }

    private fun diffTitle(revision: RevisionModel, changeset: Changeset): String =
        "${revision.monogram} — ${changeset.currentPath.ifEmpty { changeset.oldPath ?: "(unknown path)" }}"

    private fun sideTitle(revision: RevisionModel, changeset: Changeset, before: Boolean): String =
        when {
            before && changeset.type == ChangesetType.ADD -> "(added in ${revision.monogram})"
            !before && changeset.type == ChangesetType.DELETE -> "(removed in ${revision.monogram})"
            before -> changeset.oldPath ?: changeset.currentPath
            else -> changeset.currentPath
        }

    private fun resolveFileType(changeset: Changeset): FileType {
        val name = changeset.currentPath.ifEmpty { changeset.oldPath.orEmpty() }
        return FileTypeManager.getInstance().getFileTypeByFileName(name)
    }
}
