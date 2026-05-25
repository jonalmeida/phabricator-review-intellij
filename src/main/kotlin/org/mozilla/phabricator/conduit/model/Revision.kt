package org.mozilla.phabricator.conduit.model

import kotlinx.serialization.Serializable

@Serializable
data class Revision(
    val id: Int,
    val phid: String,
    val fields: RevisionFields = RevisionFields(),
    /**
     * Populated when the request was made with reviewers/subscribers/projects attachments enabled
     * (see [org.mozilla.phabricator.conduit.RevisionAttachments]). Null otherwise. The wire shape
     * is `attachments: { reviewers: { reviewers: [...] }, subscribers: { ... }, projects: { ... }
     * }`, which we model as nested optionals so a request that asked for only one attachment still
     * decodes cleanly.
     */
    val attachments: RevisionAttachmentsBlock? = null,
) {
    val monogram: String
        get() = "D$id"
}

@Serializable
data class RevisionFields(
    val title: String = "",
    val uri: String = "",
    val authorPHID: String = "",
    val status: RevisionStatus = RevisionStatus(),
    val repositoryPHID: String? = null,
    val diffPHID: String = "",
    val summary: String = "",
    val testPlan: String = "",
    val isDraft: Boolean = false,
    val holdAsDraft: Boolean = false,
    val dateCreated: Long = 0,
    val dateModified: Long = 0,
    /**
     * Mozilla-Phabricator-specific extension: `bugzilla.bug-id` carries the linked Bugzilla bug
     * number as a string. Phabricator stores it as `{ "bug-id": "12345" }` under a `bugzilla`
     * sub-object on the fields block.
     */
    val bugzilla: BugzillaRef? = null,
)

@Serializable
data class RevisionStatus(
    val value: String = "",
    val name: String = "",
    val closed: Boolean = false,
)

@Serializable
data class BugzillaRef(
    /** Stringified bug id, e.g. "1234567"; null when no bug is linked. */
    @kotlinx.serialization.SerialName("bug-id") val bugId: String? = null
)

/**
 * The `attachments` block returned by `differential.revision.search` when the request opts in. Each
 * attachment is independent; callers only see the fields they asked for.
 */
@Serializable
data class RevisionAttachmentsBlock(
    val reviewers: ReviewersAttachment? = null,
    val subscribers: SubscribersAttachment? = null,
    val projects: ProjectsAttachment? = null,
)

@Serializable data class ReviewersAttachment(val reviewers: List<Reviewer> = emptyList())

@Serializable
data class Reviewer(
    val reviewerPHID: String,
    /**
     * One of: `added`, `accepted`, `rejected`, `blocking`, `resigned`, `accepted-prior`. `added`
     * means "assigned but hasn't reviewed"; `accepted-prior` means "accepted an earlier diff" (the
     * revision has since been updated, so the acceptance is stale).
     */
    val status: String = "",
    val isBlocking: Boolean = false,
    val actorPHID: String? = null,
)

@Serializable
data class SubscribersAttachment(
    val subscriberPHIDs: List<String> = emptyList(),
    val subscriberCount: Int = 0,
    val viewerIsSubscribed: Boolean = false,
)

@Serializable data class ProjectsAttachment(val projectPHIDs: List<String> = emptyList())

/**
 * One entry from `edge.search`. Used by Phase 3 to discover a revision's stack (parents and
 * children) via the `revision.parent` / `revision.child` edge types.
 */
data class Edge(val source: String, val target: String, val type: String)
