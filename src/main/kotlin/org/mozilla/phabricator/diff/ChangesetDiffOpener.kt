package org.mozilla.phabricator.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.launch
import org.mozilla.phabricator.conduit.model.Changeset
import org.mozilla.phabricator.conduit.model.ChangesetFileType
import org.mozilla.phabricator.conduit.model.ChangesetType
import org.mozilla.phabricator.conduit.model.Diff
import org.mozilla.phabricator.service.PhabSessionService
import org.mozilla.phabricator.service.PhabricatorOpenViewsRegistry
import org.mozilla.phabricator.service.RevisionModel

/**
 * Opens IntelliJ's built-in diff viewer for a [Changeset]. Sides are synthesized from the hunk
 * corpus via [DiffSynthesizer]; file type is resolved from the path so syntax highlighting works
 * without touching the VirtualFileSystem.
 *
 * The diff opens as an editor tab (`ChainDiffVirtualFile` wrapping a `SimpleDiffRequestChain`)
 * rather than via `DiffManager.showDiff` so the resulting [com.intellij.openapi.vfs.VirtualFile] is
 * enumerable + closeable by [PhabricatorOpenViewsRegistry] on sign-out -- a user signing out would
 * otherwise leave inert review buttons on a diff they can no longer submit comments on.
 *
 * Each request carries Phabricator context as user data ([DiffRequestContextKeys]); the
 * [PhabricatorDiffExtension] reads it to attach inline-comment gutters.
 */
object ChangesetDiffOpener {

    fun open(project: Project, revision: RevisionModel, changeset: Changeset) {
        if (
            changeset.fileType == ChangesetFileType.BINARY ||
                changeset.fileType == ChangesetFileType.IMAGE
        ) {
            val binaryRequest =
                SimpleDiffRequest(
                    diffTitle(revision, changeset),
                    DiffContentFactory.getInstance().create("(binary file -- contents not shown)"),
                    DiffContentFactory.getInstance().create("(binary file -- contents not shown)"),
                    "Base",
                    "Tip",
                )
            attachPhabricatorContext(binaryRequest, revision, changeset)
            openAsEditorTab(project, revision, changeset, binaryRequest)
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
        attachPhabricatorContext(request, revision, changeset)
        openAsEditorTab(project, revision, changeset, request)
    }

    private fun openAsEditorTab(
        project: Project,
        revision: RevisionModel,
        changeset: Changeset,
        request: SimpleDiffRequest,
    ) {
        val chain = SimpleDiffRequestChain(request)
        val file = ChainDiffVirtualFile(chain, diffTitle(revision, changeset))
        ApplicationManager.getApplication().invokeLater {
            PhabricatorOpenViewsRegistry.getInstance(project).register(file)
            FileEditorManager.getInstance(project).openFile(file, /* focusEditor= */ true)
        }
    }

    /**
     * Stamp the [DiffRequestContextKeys] onto the request so the inline-comment extension can
     * recognise it. [DIFF_ID] is the active diff's numeric id (needed by `createInline`); we
     * resolve it lazily on a background coroutine because it requires a Conduit round-trip via
     * `searchDiffs(phids=...)`. Failure to resolve is fine: the extension treats a missing diff id
     * as "comments are read-only" rather than crashing.
     */
    private fun attachPhabricatorContext(
        request: SimpleDiffRequest,
        revision: RevisionModel,
        changeset: Changeset,
    ) {
        request.putUserData(DiffRequestContextKeys.REVISION_PHID, revision.phid)
        request.putUserData(DiffRequestContextKeys.REVISION_MONOGRAM, revision.monogram)
        request.putUserData(DiffRequestContextKeys.DIFF_PHID, revision.diffPHID)
        request.putUserData(
            DiffRequestContextKeys.CHANGESET_PATH,
            changeset.currentPath.ifEmpty { changeset.oldPath.orEmpty() },
        )
        // Resolve the diff id off the EDT; this is best-effort, the extension can degrade
        // gracefully if it's still null when it reads the key.
        PhabSessionService.getInstance().coroutineScope.launch {
            val diffId = runCatching { resolveDiffId(revision.diffPHID) }.getOrNull()
            if (diffId != null) {
                request.putUserData(DiffRequestContextKeys.DIFF_ID, diffId)
            }
        }
    }

    private suspend fun resolveDiffId(diffPHID: String): Int? {
        val client = PhabSessionService.getInstance().session?.client ?: return null
        val matches = mutableListOf<Diff>()
        client.searchDiffs(phids = listOf(diffPHID)).collect { matches += it }
        return matches.firstOrNull()?.id
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
