package org.mozilla.phabricator.editor

import com.intellij.openapi.project.Project
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.mozilla.phabricator.conduit.ConduitClient
import org.mozilla.phabricator.conduit.RevisionAttachments
import org.mozilla.phabricator.conduit.RevisionConstraints
import org.mozilla.phabricator.service.PhabSessionService
import org.mozilla.phabricator.service.RevisionModel
import org.mozilla.phabricator.service.RevisionsManager

/**
 * Builds an [OverviewData] snapshot for a single revision. Runs three Conduit calls in parallel
 * (whoami + searchRevisions-with-attachments + searchEdges) plus one batched `remarkup.process` for
 * summary + test plan rendering.
 *
 * Returns null when the active session is missing -- the panel reuses the loading placeholder.
 */
object OverviewLoader {

    private val STACK_EDGE_TYPES = listOf("revision.parent", "revision.child")

    suspend fun load(project: Project, revisionPHID: String): OverviewData? = coroutineScope {
        val client = PhabSessionService.getInstance().session?.client ?: return@coroutineScope null

        val cachedModel =
            RevisionsManager.getInstance(project).getCachedRevisionByPhid(revisionPHID)
        val resolver = RevisionsManager.getInstance(project).getUserResolver()

        // Parallel: whoami / revision (with attachments) / stack edges.
        val whoAmIDeferred = async { client.whoami() }
        val revisionDeferred = async {
            val refreshed =
                client.getRevisionByPhid(
                    phid = revisionPHID,
                    attachments =
                        RevisionAttachments(reviewers = true, subscribers = true, projects = true),
                )
            if (refreshed != null && cachedModel != null) {
                cachedModel.update(refreshed)
                cachedModel
            } else if (refreshed != null) {
                RevisionModel(refreshed, client)
            } else cachedModel
        }
        val edgesDeferred = async {
            client.searchEdges(sourcePHIDs = listOf(revisionPHID), types = STACK_EDGE_TYPES)
        }
        val (who, model, edges) =
            Triple(whoAmIDeferred.await(), revisionDeferred.await(), edgesDeferred.await())
        if (model == null) return@coroutineScope null

        // Resolve display names. The set covers: revision author + every reviewer + every project
        // tag + every stack-edge target.
        val phidsToResolve = buildSet {
            add(model.authorPHID)
            model.reviewers.forEach { add(it.reviewerPHID) }
            addAll(model.projectPHIDs)
            edges.forEach { add(it.target) }
        }
        resolver?.resolveMany(phidsToResolve)

        // Render Remarkup once for summary + testPlan; the activity timeline (commit 4) batches
        // comment bodies into a separate processRemarkup call.
        val rendered =
            renderMarkup(client, model.revision.fields.summary, model.revision.fields.testPlan)

        val resolvedReviewers =
            model.reviewers.map { r ->
                val displayName = resolver?.displayName(r.reviewerPHID) ?: r.reviewerPHID
                val isProject = resolver?.isProject(r.reviewerPHID) ?: false
                ResolvedReviewer(
                    phid = r.reviewerPHID,
                    displayName = displayName,
                    isProject = isProject,
                    status = r.status,
                    isBlocking = r.isBlocking,
                )
            }

        val resolvedProjects =
            model.projectPHIDs.map { phid ->
                ResolvedProject(phid = phid, displayName = resolver?.displayName(phid) ?: phid)
            }

        val parents =
            edges
                .filter { it.type == "revision.parent" }
                .map { StackRef(phid = it.target, edge = it) }
        val children =
            edges
                .filter { it.type == "revision.child" }
                .map { StackRef(phid = it.target, edge = it) }

        OverviewData(
            model = model,
            viewerPHID = who.phid,
            authorDisplayName = resolver?.displayName(model.authorPHID) ?: model.authorPHID,
            reviewers = resolvedReviewers,
            projects = resolvedProjects,
            stackParents = parents,
            stackChildren = children,
            summaryHtml = rendered.first,
            testPlanHtml = rendered.second,
        )
    }

    private suspend fun renderMarkup(
        client: ConduitClient,
        summary: String,
        testPlan: String,
    ): Pair<String, String> {
        val bodies = listOf(summary, testPlan)
        val rendered = runCatching { client.processRemarkup(bodies) }.getOrElse { bodies }
        val summaryHtml = rendered.getOrElse(0) { summary }
        val testPlanHtml = rendered.getOrElse(1) { testPlan }
        return summaryHtml to testPlanHtml
    }

    // The constraints-by-PHID shape is identical to Phase 1's getRevisionByPhid path, kept inline
    // here for the parallel call structure.
    @Suppress("unused")
    private fun searchByPhid(phid: String): RevisionConstraints =
        RevisionConstraints(phids = listOf(phid))
}
