package com.mozilla.phabricator.ui.toolwindow

import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * Maps Phabricator revision status values to IntelliJ icons. Best-effort: we use bundled AllIcons
 * so the plugin does not ship its own icon set in Phase 1.
 */
object RevisionStatusIcons {
    fun forStatus(status: String): Icon =
        when (status) {
            "accepted" -> AllIcons.RunConfigurations.TestPassed
            "needs-review" -> AllIcons.General.BalloonInformation
            "needs-revision" -> AllIcons.General.BalloonWarning
            "changes-planned" -> AllIcons.General.Modified
            "draft" -> AllIcons.General.Note
            "published" -> AllIcons.RunConfigurations.TestPassed
            "abandoned" -> AllIcons.Actions.Cancel
            else -> AllIcons.FileTypes.Any_type
        }
}
