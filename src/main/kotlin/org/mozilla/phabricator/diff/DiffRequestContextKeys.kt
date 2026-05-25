package org.mozilla.phabricator.diff

import com.intellij.openapi.util.Key

/**
 * User-data keys carried on every `SimpleDiffRequest` that [ChangesetDiffOpener] produces. The
 * [PhabricatorDiffExtension] reads them to decide whether a given diff viewer is Phabricator-
 * managed (and which revision / diff / changeset it represents). Replaces the VSCode plugin's
 * `phab://` URI-scheme approach for anchoring inline comments — same information, carried by
 * structured user data instead of a URL.
 */
object DiffRequestContextKeys {
    val REVISION_PHID: Key<String> = Key.create("phabricator.revisionPHID")
    val REVISION_MONOGRAM: Key<String> = Key.create("phabricator.revisionMonogram")
    val DIFF_PHID: Key<String> = Key.create("phabricator.diffPHID")
    val DIFF_ID: Key<Int> = Key.create("phabricator.diffId")
    val CHANGESET_PATH: Key<String> = Key.create("phabricator.changesetPath")
}
