package org.mozilla.phabricator.editor

import org.mozilla.phabricator.conduit.model.Edge
import org.mozilla.phabricator.service.RevisionModel

/**
 * Snapshot of everything the read-only overview panel renders. Built off-EDT by [OverviewLoader],
 * handed to the panel for a single EDT-side render. Keeping the data class immutable means we can
 * swap it atomically on refresh without partial-update flicker.
 */
data class OverviewData(
    val model: RevisionModel,
    val viewerPHID: String,
    val authorDisplayName: String,
    val reviewers: List<ResolvedReviewer>,
    val projects: List<ResolvedProject>,
    val stackParents: List<StackRef>,
    val stackChildren: List<StackRef>,
    val summaryHtml: String,
    val testPlanHtml: String,
) {
    val isAuthor: Boolean
        get() = model.isAuthor(viewerPHID)

    val isReviewer: Boolean
        get() = model.isReviewer(viewerPHID)
}

data class ResolvedReviewer(
    val phid: String,
    val displayName: String,
    val isProject: Boolean,
    val status: String,
    val isBlocking: Boolean,
)

data class ResolvedProject(val phid: String, val displayName: String)

/** A single parent/child reference rendered as a clickable hyperlink in the stack section. */
data class StackRef(val phid: String, val edge: Edge)
