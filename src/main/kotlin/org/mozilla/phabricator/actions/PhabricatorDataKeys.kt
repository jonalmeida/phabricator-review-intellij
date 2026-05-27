package org.mozilla.phabricator.actions

import com.intellij.openapi.actionSystem.DataKey
import org.mozilla.phabricator.service.RevisionModel

/**
 * DataKeys the Phabricator plugin publishes on its custom UI surfaces so actions can read context
 * without leaking the UI component into every callsite. The tool-window tree's DataProvider exposes
 * [SELECTED_REVISION_MODEL] when the active selection is a revision row.
 */
object PhabricatorDataKeys {
    val SELECTED_REVISION_MODEL: DataKey<RevisionModel> =
        DataKey.create("phabricator.selectedRevisionModel")
}
