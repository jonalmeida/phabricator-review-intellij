package com.mozilla.phabricator.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class RevisionsTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val payload = node.userObject) {
            is RevisionsTreeNode.Category -> {
                icon = AllIcons.Nodes.Folder
                append(payload.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                val count = node.childCount.takeIf { payload.loaded && it > 0 } ?: 0
                if (count > 0) {
                    append("  $count", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }

            is RevisionsTreeNode.Revision -> {
                val m = payload.model
                icon = RevisionStatusIcons.forStatus(m.statusValue)
                append(m.monogram, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  ")
                append(m.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                toolTipText = "${m.monogram} — ${m.title} (${m.statusValue})"
            }

            RevisionsTreeNode.Loading -> {
                icon = AllIcons.Process.Step_1
                append("Loading…", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }

            RevisionsTreeNode.Empty -> {
                icon = AllIcons.General.InspectionsEye
                append("No revisions", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }

            is RevisionsTreeNode.Error -> {
                icon = AllIcons.General.BalloonError
                append(payload.message, SimpleTextAttributes.ERROR_ATTRIBUTES)
            }

            else -> append(payload?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }
}
