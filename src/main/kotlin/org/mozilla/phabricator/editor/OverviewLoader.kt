package org.mozilla.phabricator.editor

import com.intellij.openapi.project.Project
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.mozilla.phabricator.conduit.RevisionAttachments
import org.mozilla.phabricator.conduit.model.Changeset
import org.mozilla.phabricator.conduit.model.ChangesetType
import org.mozilla.phabricator.conduit.model.InlineCommentFields
import org.mozilla.phabricator.conduit.model.Transaction
import org.mozilla.phabricator.service.PhabSessionService
import org.mozilla.phabricator.service.RevisionModel
import org.mozilla.phabricator.service.RevisionsManager
import org.mozilla.phabricator.service.UserResolver

/**
 * Builds an [OverviewData] snapshot for a single revision. Runs all the slow network calls in
 * parallel via `coroutineScope { async } ... await()` -- whoami, the revision-with-attachments
 * search, the stack edges, the changesets, and the transaction timeline. Then batches
 * `remarkup.process` over the summary + test plan + every top-level comment body in a single
 * round-trip.
 *
 * Returns null when the session is missing -- the panel keeps its loading placeholder rather than
 * showing an error.
 */
object OverviewLoader {

    private val STACK_EDGE_TYPES = listOf("revision.parent", "revision.child")

    suspend fun load(project: Project, revisionPHID: String): OverviewData? = coroutineScope {
        val client = PhabSessionService.getInstance().session?.client ?: return@coroutineScope null

        val manager = RevisionsManager.getInstance(project)
        val cachedModel = manager.getCachedRevisionByPhid(revisionPHID)
        val resolver = manager.getUserResolver()

        // Parallel: whoami / revision (with attachments) / stack edges.
        val whoAmIDeferred = async { client.whoami() }
        val revisionDeferred = async {
            val refreshed =
                client.getRevisionByPhid(
                    phid = revisionPHID,
                    attachments =
                        RevisionAttachments(reviewers = true, subscribers = true, projects = true),
                )
            when {
                refreshed != null && cachedModel != null -> {
                    cachedModel.update(refreshed)
                    cachedModel
                }
                refreshed != null -> RevisionModel(refreshed, client)
                else -> cachedModel
            }
        }
        val edgesDeferred = async {
            client.searchEdges(sourcePHIDs = listOf(revisionPHID), types = STACK_EDGE_TYPES)
        }

        val who = whoAmIDeferred.await()
        val model = revisionDeferred.await() ?: return@coroutineScope null
        val edges = edgesDeferred.await()

        // Files + timeline (Phase-3 commit 4). Each side falls back to an empty section on
        // failure -- a transient network error on getTransactions should not abort the entire
        // overview.
        val changesetsDeferred = async {
            runCatching { model.getChangesets() }.getOrElse { emptyList() }
        }
        val transactionsDeferred = async {
            runCatching { model.getTransactions() }.getOrElse { emptyList() }
        }
        val changesets = changesetsDeferred.await()
        val transactions = transactionsDeferred.await()

        // Resolve display names for every PHID we will display: author + reviewers + projects +
        // stack targets + every transaction author.
        val phidsToResolve = buildSet {
            add(model.authorPHID)
            model.reviewers.forEach { add(it.reviewerPHID) }
            addAll(model.projectPHIDs)
            edges.forEach { add(it.target) }
            transactions.forEach { add(it.authorPHID) }
        }
        resolver?.resolveMany(phidsToResolve)

        // Batched Remarkup: summary + testPlan + every top-level comment body in one call. The
        // result is positional, so we line indices up: 0 = summary, 1 = testPlan, then one entry
        // per top-level comment transaction in order.
        val commentTransactions =
            transactions.filter { it.type == "comment" && it.comments.isNotEmpty() }
        val commentBodies = commentTransactions.map { it.comments.first().content.raw }
        val markupBodies =
            listOf(model.revision.fields.summary, model.revision.fields.testPlan) + commentBodies
        val rendered =
            runCatching { client.processRemarkup(markupBodies) }.getOrElse { markupBodies }
        val summaryHtml = rendered.getOrElse(0) { model.revision.fields.summary }
        val testPlanHtml = rendered.getOrElse(1) { model.revision.fields.testPlan }
        val renderedComments =
            commentTransactions.mapIndexed { i, tx ->
                tx to rendered.getOrElse(2 + i) { tx.comments.first().content.raw }
            }

        val resolvedReviewers =
            model.reviewers.map { r ->
                ResolvedReviewer(
                    phid = r.reviewerPHID,
                    displayName = resolver?.displayName(r.reviewerPHID) ?: r.reviewerPHID,
                    isProject = resolver?.isProject(r.reviewerPHID) ?: false,
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

        val inlineCountByPath = countInlinesByPath(transactions, model.diffPHID)
        val files = buildFiles(changesets, inlineCountByPath)
        val timeline = buildTimeline(transactions, resolver, renderedComments)

        OverviewData(
            model = model,
            viewerPHID = who.phid,
            authorDisplayName = resolver?.displayName(model.authorPHID) ?: model.authorPHID,
            reviewers = resolvedReviewers,
            projects = resolvedProjects,
            stackParents = parents,
            stackChildren = children,
            summaryHtml = summaryHtml,
            testPlanHtml = testPlanHtml,
            files = files,
            timeline = timeline,
        )
    }

    private fun countInlinesByPath(
        transactions: List<Transaction>,
        diffPHID: String,
    ): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (tx in transactions) {
            if (tx.type != "inline") continue
            val anchor = InlineCommentFields.from(tx.fields) ?: continue
            if (anchor.diffPHID != diffPHID) continue
            counts.merge(anchor.path, 1, Int::plus)
        }
        return counts
    }

    private fun buildFiles(
        changesets: List<Changeset>,
        inlineCountByPath: Map<String, Int>,
    ): List<OverviewFile> =
        changesets.map { cs ->
            val path = cs.currentPath.ifEmpty { cs.oldPath.orEmpty() }
            OverviewFile(
                path = path,
                statusLabel = changeStatusLabelFor(cs.type),
                inlineCount = inlineCountByPath[path] ?: 0,
                changeset = cs,
            )
        }

    private fun buildTimeline(
        transactions: List<Transaction>,
        resolver: UserResolver?,
        renderedComments: List<Pair<Transaction, String>>,
    ): List<TimelineEntry> {
        val htmlByPhid = renderedComments.associate { (tx, html) -> tx.phid to html }
        // Roll inline transactions up by file so the user sees one "N inline comments on
        // src/foo.kt" entry per file rather than a flood of individual rows. Phase-2's diff
        // gutters surface the real threads when the user opens the diff.
        val inlineByPath = linkedMapOf<String, MutableList<Transaction>>()
        val out = mutableListOf<TimelineEntry>()
        for (tx in transactions) {
            when {
                tx.type == "comment" && tx.comments.isNotEmpty() -> {
                    out +=
                        TimelineEntry.Comment(
                            authorPHID = tx.authorPHID,
                            dateCreated = tx.dateCreated,
                            authorDisplayName =
                                resolver?.displayName(tx.authorPHID) ?: tx.authorPHID,
                            renderedHtml =
                                htmlByPhid[tx.phid]
                                    ?: tx.comments.firstOrNull()?.content?.raw.orEmpty(),
                        )
                }
                tx.type == "inline" -> {
                    val anchor = InlineCommentFields.from(tx.fields) ?: continue
                    inlineByPath.getOrPut(anchor.path) { mutableListOf() } += tx
                }
                else -> {
                    val verb = TransactionVerbs.verbFor(tx) ?: continue
                    out +=
                        TimelineEntry.StateChange(
                            authorPHID = tx.authorPHID,
                            dateCreated = tx.dateCreated,
                            authorDisplayName =
                                resolver?.displayName(tx.authorPHID) ?: tx.authorPHID,
                            text = verb,
                        )
                }
            }
        }
        for ((path, txs) in inlineByPath) {
            val latest = txs.maxByOrNull { it.dateCreated } ?: continue
            out +=
                TimelineEntry.InlineRollup(
                    authorPHID = latest.authorPHID,
                    dateCreated = latest.dateCreated,
                    path = path,
                    count = txs.size,
                )
        }
        return out.sortedBy { it.dateCreated }
    }

    /**
     * Mirrors the changesetStatusLabel mapping used in
     * [org.mozilla.phabricator.ui.toolwindow.RevisionsTreeCellRenderer]. The renderer keeps its
     * copy private; rather than expose a public helper we duplicate the five-case mapping here.
     */
    private fun changeStatusLabelFor(type: ChangesetType): String =
        when (type) {
            ChangesetType.ADD -> "A"
            ChangesetType.CHANGE -> "M"
            ChangesetType.DELETE -> "D"
            ChangesetType.MOVE_AWAY,
            ChangesetType.MOVE_HERE -> "R"
            ChangesetType.COPY_AWAY,
            ChangesetType.COPY_HERE,
            ChangesetType.MULTICOPY -> "C"
        }
}
