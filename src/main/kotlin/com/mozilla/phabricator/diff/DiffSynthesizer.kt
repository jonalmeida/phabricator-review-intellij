package com.mozilla.phabricator.diff

import com.mozilla.phabricator.conduit.model.Changeset
import com.mozilla.phabricator.conduit.model.ChangesetType

/**
 * Port of `src/common/diffHunk.ts#synthesizeSideFromCorpus`. Each line of the Phabricator hunk
 * corpus begins with one of: ' ' context (in both sides) '-' removed (only in 'before') '+' added
 * (only in 'after') '\\' "no newline at end of file" marker (skip)
 *
 * Mozilla's Phabricator emits effectively unlimited context, so concatenating each hunk's corpus
 * reconstructs the entire file for either side.
 */
object DiffSynthesizer {

    enum class Side {
        BEFORE,
        AFTER,
    }

    fun synthesizeSideFromCorpus(corpus: String, side: Side): String {
        val skipPrefix = if (side == Side.BEFORE) '+' else '-'
        val out = StringBuilder()
        var lineCount = 0

        val lines = corpus.split('\n')
        // The corpus often ends with a trailing newline -> empty last element.
        val effective =
            if (lines.isNotEmpty() && lines.last().isEmpty()) {
                lines.dropLast(1)
            } else {
                lines
            }

        for (line in effective) {
            if (line.isEmpty()) {
                // Defensive: empty mid-corpus lines have no prefix; treat as
                // context-empty-line (which both sides should keep).
                if (lineCount > 0) out.append('\n')
                lineCount++
                continue
            }
            when (line[0]) {
                '\\' -> continue // no-newline marker
                skipPrefix -> continue
                else -> {
                    if (lineCount > 0) out.append('\n')
                    out.append(line, 1, line.length)
                    lineCount++
                }
            }
        }
        if (lineCount > 0) out.append('\n')
        return out.toString()
    }

    /**
     * Synthesize a side for an entire [Changeset]. For Add/Delete changesets the absent side
     * returns an empty string, matching the VSCode plugin's treatment for diff display.
     */
    fun synthesizeSide(changeset: Changeset, side: Side): String {
        // For added files there's no 'before'; for deleted files there's no 'after'.
        if (side == Side.BEFORE && changeset.type == ChangesetType.ADD) return ""
        if (side == Side.AFTER && changeset.type == ChangesetType.DELETE) return ""

        val combined = buildString {
            for (hunk in changeset.hunks) {
                append(hunk.corpus)
                if (!hunk.corpus.endsWith('\n')) append('\n')
            }
        }
        return synthesizeSideFromCorpus(combined, side)
    }
}
