package org.mozilla.phabricator.editor

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mozilla.phabricator.conduit.model.Transaction

/**
 * Maps a [Transaction.type] to a short past-tense verb phrase for the activity timeline. Matches
 * the verb choices the VSCode plugin renders in the React webview's timeline so the two clients
 * agree on terminology when viewed side-by-side.
 */
internal object TransactionVerbs {

    fun verbFor(transaction: Transaction): String? =
        when (transaction.type) {
            "accept" -> "accepted the revision"
            "reject" -> "requested changes"
            "request-review" -> "requested another review"
            "plan-changes" -> "planned changes"
            "abandon" -> "abandoned the revision"
            "reclaim" -> "reclaimed the revision"
            "reopen" -> "reopened the revision"
            "close" -> "closed the revision"
            "commandeer" -> "took over the revision"
            "resign" -> "resigned as a reviewer"
            "update" -> "uploaded a new diff${diffSuffix(transaction.fields)}"
            "status" -> "status changed to ${statusSuffix(transaction.fields)}"
            "title" -> "renamed the revision"
            "summary" -> "edited the summary"
            "testPlan" -> "edited the test plan"
            "reviewers.add",
            "reviewers.set" -> "added reviewers"
            "reviewers.remove" -> "removed reviewers"
            "projects.add",
            "projects.set" -> "added projects"
            "projects.remove" -> "removed projects"
            "subscribers.add",
            "subscribers.set" -> "subscribed"
            "subscribers.remove" -> "unsubscribed"
            "bugzilla.bug-id" -> "linked a Bugzilla bug"
            "uplift.request" -> "requested an uplift"
            "draft" -> "toggled draft state"
            // comment / inline are surfaced by other entry types; suppress duplicates here.
            "comment",
            "inline",
            "inline.done",
            "inline.undone" -> null
            else -> transaction.type
        }

    private fun diffSuffix(fields: JsonObject): String {
        val newId =
            (fields["new"] as? JsonObject)?.let {
                (it["id"] as? JsonPrimitive)?.content ?: (it["phid"] as? JsonPrimitive)?.content
            }
        return if (!newId.isNullOrEmpty()) " ($newId)" else ""
    }

    private fun statusSuffix(fields: JsonObject): String {
        val to = (fields["new"] as? JsonPrimitive)?.content
        return to ?: "(unknown)"
    }
}
