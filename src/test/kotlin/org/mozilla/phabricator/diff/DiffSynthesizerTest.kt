package org.mozilla.phabricator.diff

import org.mozilla.phabricator.conduit.model.Changeset
import org.mozilla.phabricator.conduit.model.ChangesetFileType
import org.mozilla.phabricator.conduit.model.ChangesetHunk
import org.mozilla.phabricator.conduit.model.ChangesetType
import org.mozilla.phabricator.diff.DiffSynthesizer.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ports the corpus-synthesis cases that the VSCode plugin tests via src/common/diffHunk.ts
 * behavior. The wire contract for hunk.corpus is:
 * - lines start with ' ' (context) / '+' (added) / '-' (removed) / '\\'
 * - the corpus often ends with a trailing newline -> empty last split
 *
 * Mozilla's Phabricator emits effectively unlimited context, so concatenating a changeset's hunks
 * reconstructs the entire file.
 */
class DiffSynthesizerTest {

    @Test
    fun `synthesizeSideFromCorpus drops added lines for the before side`() {
        val corpus = " line1\n+line2-added\n line3\n"
        val before = DiffSynthesizer.synthesizeSideFromCorpus(corpus, Side.BEFORE)
        assertEquals("line1\nline3\n", before)
    }

    @Test
    fun `synthesizeSideFromCorpus drops removed lines for the after side`() {
        val corpus = " line1\n-line2-removed\n line3\n"
        val after = DiffSynthesizer.synthesizeSideFromCorpus(corpus, Side.AFTER)
        assertEquals("line1\nline3\n", after)
    }

    @Test
    fun `synthesizeSideFromCorpus handles add-only corpus`() {
        val corpus = "+a\n+b\n+c\n"
        assertEquals("", DiffSynthesizer.synthesizeSideFromCorpus(corpus, Side.BEFORE))
        assertEquals("a\nb\nc\n", DiffSynthesizer.synthesizeSideFromCorpus(corpus, Side.AFTER))
    }

    @Test
    fun `synthesizeSideFromCorpus handles delete-only corpus`() {
        val corpus = "-a\n-b\n-c\n"
        assertEquals("a\nb\nc\n", DiffSynthesizer.synthesizeSideFromCorpus(corpus, Side.BEFORE))
        assertEquals("", DiffSynthesizer.synthesizeSideFromCorpus(corpus, Side.AFTER))
    }

    @Test
    fun `synthesizeSideFromCorpus handles a mixed corpus end-to-end`() {
        val corpus =
            """
             a
            -b
            +B
             c
            -d
            +D
            +e
             f
        """
                .trimIndent() + "\n"
        val before = DiffSynthesizer.synthesizeSideFromCorpus(corpus, Side.BEFORE)
        val after = DiffSynthesizer.synthesizeSideFromCorpus(corpus, Side.AFTER)
        assertEquals("a\nb\nc\nd\nf\n", before)
        assertEquals("a\nB\nc\nD\ne\nf\n", after)
    }

    @Test
    fun `synthesizeSideFromCorpus skips no-newline markers`() {
        val corpus = " a\n+b\n\\ No newline at end of file\n"
        val out = DiffSynthesizer.synthesizeSideFromCorpus(corpus, Side.AFTER)
        assertEquals("a\nb\n", out)
    }

    @Test
    fun `synthesizeSideFromCorpus drops a trailing empty element from split newline`() {
        // No trailing newline.
        val corpus = " a\n+b"
        val out = DiffSynthesizer.synthesizeSideFromCorpus(corpus, Side.AFTER)
        assertEquals("a\nb\n", out)
    }

    @Test
    fun `synthesizeSideFromCorpus returns empty for empty corpus`() {
        assertEquals("", DiffSynthesizer.synthesizeSideFromCorpus("", Side.BEFORE))
        assertEquals("", DiffSynthesizer.synthesizeSideFromCorpus("", Side.AFTER))
    }

    @Test
    fun `synthesizeSide returns empty before for ADD changesets`() {
        val cs =
            changeset(type = ChangesetType.ADD, hunks = listOf(ChangesetHunk(corpus = "+a\n+b\n")))
        assertEquals("", DiffSynthesizer.synthesizeSide(cs, Side.BEFORE))
        assertEquals("a\nb\n", DiffSynthesizer.synthesizeSide(cs, Side.AFTER))
    }

    @Test
    fun `synthesizeSide returns empty after for DELETE changesets`() {
        val cs =
            changeset(
                type = ChangesetType.DELETE,
                hunks = listOf(ChangesetHunk(corpus = "-a\n-b\n")),
            )
        assertEquals("a\nb\n", DiffSynthesizer.synthesizeSide(cs, Side.BEFORE))
        assertEquals("", DiffSynthesizer.synthesizeSide(cs, Side.AFTER))
    }

    @Test
    fun `synthesizeSide concatenates corpus across multiple hunks`() {
        val cs =
            changeset(
                type = ChangesetType.CHANGE,
                hunks =
                    listOf(ChangesetHunk(corpus = " a\n-b\n"), ChangesetHunk(corpus = "+B\n c\n")),
            )
        val before = DiffSynthesizer.synthesizeSide(cs, Side.BEFORE)
        val after = DiffSynthesizer.synthesizeSide(cs, Side.AFTER)
        // Hunks contain only their slices, so concatenation reproduces the
        // VSCode plugin's behavior when corpora are partial. Context line
        // ordering across hunks is preserved.
        assertTrue(before.contains("a") && before.contains("b"))
        assertTrue(after.contains("B") && after.contains("c"))
    }

    private fun changeset(
        type: ChangesetType = ChangesetType.CHANGE,
        hunks: List<ChangesetHunk> = emptyList(),
        currentPath: String = "src/foo.kt",
    ) =
        Changeset(
            id = 1,
            oldPath = if (type == ChangesetType.ADD) null else currentPath,
            currentPath = if (type == ChangesetType.DELETE) "" else currentPath,
            awayPaths = emptyList(),
            type = type,
            fileType = ChangesetFileType.TEXT,
            oldFileType = ChangesetFileType.TEXT,
            addLines = 0,
            delLines = 0,
            metadata = emptyMap(),
            hunks = hunks,
        )
}
