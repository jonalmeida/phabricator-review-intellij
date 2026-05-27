package org.mozilla.phabricator.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.simple.SimpleOnesideDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.phabricator.common.EdtDispatcher
import org.mozilla.phabricator.service.InlineCommentController
import org.mozilla.phabricator.service.InlineThread
import org.mozilla.phabricator.service.PhabSessionService
import org.mozilla.phabricator.service.RevisionsManager

/**
 * Attaches inline-comment gutter icons to the IntelliJ diff viewer when the request was opened by
 * [ChangesetDiffOpener]. Reads Phabricator context from [DiffRequestContextKeys], loads threads via
 * [InlineCommentController] in the background, then attaches a per-thread
 * [InlineCommentGutterRenderer] on the appropriate side's editor.
 *
 * Subscribes to [RevisionsManager.COMMENTS_TOPIC] for the duration of the viewer so writes (commit
 * 5 onwards) re-render in place.
 */
class PhabricatorDiffExtension : DiffExtension() {

    override fun onViewerCreated(
        viewer: FrameDiffTool.DiffViewer,
        context: DiffContext,
        request: DiffRequest,
    ) {
        val revisionPHID = request.getUserData(DiffRequestContextKeys.REVISION_PHID) ?: return
        val diffPHID = request.getUserData(DiffRequestContextKeys.DIFF_PHID) ?: return
        val changesetPath = request.getUserData(DiffRequestContextKeys.CHANGESET_PATH) ?: return
        val project = context.project ?: return

        val (beforeEditor, afterEditor) = editorsFor(viewer) ?: return

        // FrameDiffTool.DiffViewer extends Disposable; hang our Disposable + coroutine lifecycle
        // off the viewer so closing the diff tab cancels in-flight Conduit calls and unregisters
        // the MessageBus subscriber below.
        val viewerDisposable = Disposer.newDisposable("PhabricatorDiffExtension viewer")
        Disposer.register(viewer, viewerDisposable)
        val parentJob = SupervisorJob()
        val viewerScope = CoroutineScope(parentJob)
        Disposer.register(viewerDisposable, Disposable { parentJob.cancel() })

        viewerScope.launch {
            val threadsResult = runCatching {
                loadThreads(project, revisionPHID, diffPHID, changesetPath)
            }
            withContext(EdtDispatcher) {
                threadsResult
                    .onSuccess { loaded ->
                        if (loaded != null) {
                            renderThreads(
                                project = project,
                                threads = loaded.first,
                                controller = loaded.second,
                                beforeEditor = beforeEditor,
                                afterEditor = afterEditor,
                                viewerScope = viewerScope,
                                request = request,
                            )
                        }
                    }
                    .onFailure { LOG.warn("Loading inline threads failed", it) }
            }
        }

        // Live refresh on comment writes (commits 5 / 6 will publish to this topic).
        ApplicationManager.getApplication()
            .messageBus
            .connect(viewerDisposable)
            .subscribe(
                RevisionsManager.COMMENTS_TOPIC,
                RevisionsManager.CommentsListener { changedPHID ->
                    if (changedPHID != revisionPHID) return@CommentsListener
                    viewerScope.launch {
                        val refreshed = runCatching {
                            loadThreads(project, revisionPHID, diffPHID, changesetPath)
                        }
                        withContext(EdtDispatcher) {
                            refreshed.onSuccess { loaded ->
                                if (loaded != null) {
                                    clearGutters(beforeEditor)
                                    clearGutters(afterEditor)
                                    renderThreads(
                                        project = project,
                                        threads = loaded.first,
                                        controller = loaded.second,
                                        beforeEditor = beforeEditor,
                                        afterEditor = afterEditor,
                                        viewerScope = viewerScope,
                                        request = request,
                                    )
                                }
                            }
                        }
                    }
                },
            )
    }

    private suspend fun loadThreads(
        project: Project,
        revisionPHID: String,
        diffPHID: String,
        changesetPath: String,
    ): Pair<List<InlineThread>, InlineCommentController>? {
        val model =
            RevisionsManager.getInstance(project).getCachedRevisionByPhid(revisionPHID)
                ?: return null
        val client = PhabSessionService.getInstance().session?.client ?: return null
        val controller = InlineCommentController(model, client)
        val threads = controller.threadsFor(changesetPath, diffPHID)
        // Pre-resolve every comment author so the popup's UserResolver lookup hits a warm cache
        // and renders display names rather than raw PHIDs. The resolver is shared with the
        // tool-window tree, so this also benefits any subsequent activity-timeline render.
        val resolver = RevisionsManager.getInstance(project).getUserResolver()
        val authorPhids = threads.flatMap { t -> t.comments.map { it.authorPHID } }.toSet()
        if (authorPhids.isNotEmpty()) resolver?.resolveMany(authorPhids)
        return threads to controller
    }

    private fun renderThreads(
        project: Project,
        threads: List<InlineThread>,
        controller: InlineCommentController,
        beforeEditor: EditorEx?,
        afterEditor: EditorEx?,
        viewerScope: CoroutineScope,
        request: DiffRequest,
    ) {
        for (thread in threads) {
            val target = if (thread.isNewFile) afterEditor else beforeEditor
            target?.let { editor ->
                attachGutterFor(editor, thread, project, controller, viewerScope, request)
            }
        }
    }

    private fun attachGutterFor(
        editor: EditorEx,
        thread: InlineThread,
        project: Project,
        controller: InlineCommentController,
        viewerScope: CoroutineScope,
        request: DiffRequest,
    ) {
        val lineIndex = (thread.line - 1).coerceAtLeast(0)
        val maxLine = editor.document.lineCount - 1
        if (maxLine < 0 || lineIndex > maxLine) return
        val highlighter =
            editor.markupModel.addRangeHighlighter(
                editor.document.getLineStartOffset(lineIndex),
                editor.document.getLineEndOffset(lineIndex),
                HighlighterLayer.LAST + 1,
                null,
                HighlighterTargetArea.EXACT_RANGE,
            )
        highlighter.gutterIconRenderer =
            InlineCommentGutterRenderer(thread) { threadAtClick ->
                val diffId = request.getUserData(DiffRequestContextKeys.DIFF_ID)
                InlineThreadPopup.show(
                    project = project,
                    thread = threadAtClick,
                    anchor = editor,
                    // Use the project's shared resolver (pre-populated by loadThreads with every
                    // comment-author PHID on this revision) so the popup header shows real
                    // display names instead of raw PHID-USER-* identifiers.
                    userResolver = RevisionsManager.getInstance(project).getUserResolver(),
                    scope = viewerScope,
                    onReply = { body ->
                        if (diffId == null) {
                            error(
                                "Diff id not yet resolved for this revision; try again in a moment."
                            )
                        } else {
                            controller.postReply(threadAtClick, body, diffId)
                        }
                    },
                )
            }
        highlighter.putUserData(PHAB_GUTTER, true)
    }

    private fun clearGutters(editor: EditorEx?) {
        if (editor == null) return
        editor.markupModel.allHighlighters
            .filter { it.getUserData(PHAB_GUTTER) == true }
            .forEach { editor.markupModel.removeHighlighter(it) }
    }

    /**
     * Extract the (before, after) editor pair for the supported viewer shapes. Phase-2 covers the
     * two-side text viewer (what `SimpleDiffRequest` with two text contents produces by default);
     * one-side / unified / threeside viewers degrade to "no gutters" rather than crash.
     */
    private fun editorsFor(viewer: FrameDiffTool.DiffViewer): Pair<EditorEx?, EditorEx?>? =
        when (viewer) {
            is TwosideTextDiffViewer -> viewer.editor1 to viewer.editor2
            is SimpleOnesideDiffViewer -> viewer.editor to null
            else -> null
        }

    companion object {
        private val LOG = logger<PhabricatorDiffExtension>()
        private val PHAB_GUTTER =
            com.intellij.openapi.util.Key.create<Boolean>("phabricator.inlineGutter")
    }
}
