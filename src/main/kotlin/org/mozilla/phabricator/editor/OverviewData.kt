package org.mozilla.phabricator.editor

import org.mozilla.phabricator.conduit.model.Changeset
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
    val files: List<OverviewFile>,
    val timeline: List<TimelineEntry>,
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

/**
 * One changeset row in the Files section: path / status label / inline-comment count. The full
 * [Changeset] is carried so the double-click handler can re-use [ChangesetDiffOpener] without
 * re-resolving anything.
 */
data class OverviewFile(
    val path: String,
    val statusLabel: String,
    val inlineCount: Int,
    val changeset: Changeset,
)

/**
 * One entry in the activity timeline. Comments (top-level only — inline comments collapse into
 * file-anchored rollups) render the rendered HTML body; state-change entries render a short verb
 * line like "Alice accepted the revision".
 */
sealed interface TimelineEntry {
    val dateCreated: Long
    val authorPHID: String

    data class Comment(
        override val authorPHID: String,
        override val dateCreated: Long,
        val authorDisplayName: String,
        val renderedHtml: String,
    ) : TimelineEntry

    data class StateChange(
        override val authorPHID: String,
        override val dateCreated: Long,
        val authorDisplayName: String,
        val text: String,
    ) : TimelineEntry

    /**
     * Rollup line "N inline comments on src/foo.kt" so the timeline stays uncluttered. Phase-2's
     * gutter icons already surface the actual threads on the diff view; this entry is just a
     * pointer.
     */
    data class InlineRollup(
        override val authorPHID: String,
        override val dateCreated: Long,
        val path: String,
        val count: Int,
    ) : TimelineEntry
}
