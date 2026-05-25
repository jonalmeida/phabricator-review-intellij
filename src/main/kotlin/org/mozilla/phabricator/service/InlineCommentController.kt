package org.mozilla.phabricator.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import org.mozilla.phabricator.conduit.ConduitClient
import org.mozilla.phabricator.conduit.model.InlineCommentFields
import org.mozilla.phabricator.conduit.model.Transaction

/**
 * Builds inline-comment threads for a revision and exposes write paths (reply / new thread /
 * markDone / publishDrafts). Ports the data model from
 * `phabricator-review-vscode/src/view/revisionCommentController.ts` — VSCode-specific
 * `CommentController` plumbing is left for the UI layer in [org.mozilla.phabricator.diff].
 *
 * Each controller instance is bound to a single [RevisionModel]; create-per-diff-viewer so the
 * lifecycle matches the editor.
 */
class InlineCommentController(private val model: RevisionModel, private val client: ConduitClient) {
    /**
     * Inline-comment threads anchored on [changesetPath] at the given [diffPHID]. Walks the
     * revision's transaction timeline, filters to inline transactions whose anchor matches the
     * requested file + diff, groups them into threads (root + chronological replies), and
     * batch-renders bodies via `remarkup.process`.
     */
    suspend fun threadsFor(changesetPath: String, diffPHID: String): List<InlineThread> {
        val transactions = model.getTransactions()
        val anchored =
            transactions.mapNotNull { tx -> tx.anchorOnChangeset(changesetPath, diffPHID) }
        if (anchored.isEmpty()) return emptyList()

        // One body per transaction (Phabricator emits one comment per inline tx; edits create new
        // versions in tx.comments but for Phase 2 we render the first / current entry).
        val bodies = anchored.map { it.body() }
        val rendered =
            runCatching { client.processRemarkup(bodies) }
                .getOrElse {
                    LOG.warn("processRemarkup failed; falling back to raw bodies", it)
                    bodies
                }
        val renderedByIndex = bodies.indices.associateWith { rendered.getOrNull(it) ?: bodies[it] }

        val comments =
            anchored.mapIndexed { i, item ->
                item to
                    InlineComment(
                        transactionPHID = item.tx.phid,
                        authorPHID = item.tx.authorPHID,
                        dateCreated = item.tx.dateCreated,
                        rawBody = bodies[i],
                        renderedHtml = renderedByIndex[i] ?: bodies[i],
                    )
            }

        return groupReplies(comments)
    }

    /**
     * Post a reply to an existing thread. The leaf-most comment of the thread is used as the
     * `replyToCommentPHID` target (matches `revisionCommentController.ts:findReplyTarget`).
     *
     * @return The draft comment's PHID.
     */
    suspend fun postReply(thread: InlineThread, body: String, diffId: Int): String {
        val newPhid =
            client.createInline(
                diffId = diffId,
                path = thread.path,
                line = thread.line,
                isNewFile = thread.isNewFile,
                content = body,
                length = thread.length,
                replyToCommentPHID = thread.replyTargetPHID,
            )
        publishCommentsChanged()
        return newPhid
    }

    /** Post a brand-new thread on a line. Returns the new draft's PHID. */
    suspend fun postNewThread(
        diffId: Int,
        path: String,
        line: Int,
        isNewFile: Boolean,
        body: String,
        length: Int = 1,
    ): String {
        val phid =
            client.createInline(
                diffId = diffId,
                path = path,
                line = line,
                isNewFile = isNewFile,
                content = body,
                length = length,
            )
        publishCommentsChanged()
        return phid
    }

    /** Toggle a thread's Done state. */
    suspend fun markDone(thread: InlineThread, done: Boolean) {
        val phids = thread.comments.map { it.transactionPHID }
        client.markInlineDone(revisionPHID = model.phid, commentPHIDs = phids, done = done)
        publishCommentsChanged()
    }

    /**
     * Publish the user's pending draft inlines on this revision. Phabricator promotes drafts on any
     * top-level transaction; we use the empty-string `comment` form as the lightest no-op trigger.
     */
    suspend fun publishDrafts() {
        client.publishDrafts(model.phid)
        publishCommentsChanged()
    }

    private fun publishCommentsChanged() {
        // Invalidate the local cache so the next getTransactions() reflects writes we just made.
        model.invalidateTransactions()
        // ApplicationManager is null in pure-JVM unit tests; UI integration tests + production
        // always have a real platform. Defensive null-check rather than wiring an injectable
        // publisher (kept simple while the bus surface is small).
        val app = ApplicationManager.getApplication() ?: return
        app.messageBus.syncPublisher(RevisionsManager.COMMENTS_TOPIC).commentsChanged(model.phid)
    }

    companion object {
        private val LOG = logger<InlineCommentController>()
    }
}

// -------------------------------------------------------- data model

data class InlineComment(
    /** Transaction PHID — what [createInline.replyToCommentPHID] and `inline.done` expect. */
    val transactionPHID: String,
    val authorPHID: String,
    val dateCreated: Long,
    val rawBody: String,
    val renderedHtml: String,
)

/**
 * One inline-comment thread (root + chronological replies) anchored on a specific line of a
 * changeset. [comments] is sorted by `dateCreated` ascending; replyTargetPHID points at the
 * leaf-most so new replies chain off it.
 */
data class InlineThread(
    val rootPHID: String,
    val path: String,
    val line: Int,
    val length: Int,
    val isNewFile: Boolean,
    val isDone: Boolean,
    val comments: List<InlineComment>,
) {
    val replyTargetPHID: String
        get() = comments.last().transactionPHID

    /** The diff side this thread lives on. */
    val side: Side
        get() = if (isNewFile) Side.AFTER else Side.BEFORE

    enum class Side {
        BEFORE,
        AFTER,
    }
}

// -------------------------------------------------------- internal helpers

internal data class AnchoredInline(val tx: Transaction, val anchor: InlineCommentFields)

private fun Transaction.anchorOnChangeset(path: String, diffPHID: String): AnchoredInline? {
    val anchor = InlineCommentFields.from(fields) ?: return null
    if (anchor.path != path || anchor.diffPHID != diffPHID) return null
    return AnchoredInline(this, anchor)
}

private fun AnchoredInline.body(): String = tx.comments.firstOrNull()?.content?.raw ?: ""

/**
 * Walk the [InlineCommentFields.replyToCommentPHID] chain to find each comment's root and group
 * replies under it. Mirrors `revisionCommentController.ts:groupReplies` (lines 349–371).
 *
 * Returns one [InlineThread] per root, comments sorted ascending by `dateCreated`. A thread is
 * marked [InlineThread.isDone] iff its root carries `isDone=true` — matches Phabricator's
 * thread-level Done state semantics.
 */
internal fun groupReplies(anchored: List<Pair<AnchoredInline, InlineComment>>): List<InlineThread> {
    val byTransactionPhid = anchored.associateBy { it.first.tx.phid }
    val heads = mutableListOf<Pair<AnchoredInline, InlineComment>>()
    val replies = mutableMapOf<String, MutableList<Pair<AnchoredInline, InlineComment>>>()

    for (item in anchored) {
        val parent = item.first.anchor.replyToCommentPHID
        if (parent != null) {
            // Walk up to the root: chained replies share the head's bucket so we render a flat
            // thread rather than nested sub-threads.
            var head: Pair<AnchoredInline, InlineComment>? = byTransactionPhid[parent]
            while (head?.first?.anchor?.replyToCommentPHID != null) {
                head = byTransactionPhid[head.first.anchor.replyToCommentPHID!!]
            }
            val headPhid = head?.first?.tx?.phid ?: parent
            replies.getOrPut(headPhid) { mutableListOf() } += item
        } else {
            heads += item
        }
    }

    return heads.map { (head, headComment) ->
        val tail = (replies[head.tx.phid] ?: emptyList()).sortedBy { it.first.tx.dateCreated }
        val allComments = listOf(headComment) + tail.map { it.second }
        InlineThread(
            rootPHID = head.tx.phid,
            path = head.anchor.path,
            line = head.anchor.line,
            length = head.anchor.length,
            isNewFile = head.anchor.isNewFile,
            isDone = head.anchor.isDone,
            comments = allComments,
        )
    }
}
