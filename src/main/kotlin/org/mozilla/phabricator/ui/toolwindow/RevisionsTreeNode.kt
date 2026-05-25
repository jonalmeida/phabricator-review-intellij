package org.mozilla.phabricator.ui.toolwindow

import org.mozilla.phabricator.service.RevisionModel
import org.mozilla.phabricator.service.RevisionsManager.CategoryKey
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Tree-node payloads. The model itself is the standard Swing [DefaultMutableTreeNode], so the type
 * system distinguishes node kinds by `userObject` rather than via a SimpleNode hierarchy. This
 * keeps the model simple and lets the renderer match on payload type.
 */
sealed interface RevisionsTreeNode {
    data class Category(val key: CategoryKey, val loaded: Boolean = false) : RevisionsTreeNode {
        val label: String
            get() = key.label
    }

    data class Revision(val model: RevisionModel, val filesLoaded: Boolean = false) :
        RevisionsTreeNode

    data class FileChange(
        val revision: RevisionModel,
        val changeset: org.mozilla.phabricator.conduit.model.Changeset,
    ) : RevisionsTreeNode {
        val path: String
            get() = changeset.currentPath.ifEmpty { changeset.oldPath.orEmpty() }
    }

    object Loading : RevisionsTreeNode

    data class Error(val message: String) : RevisionsTreeNode

    object Empty : RevisionsTreeNode
}

/** Convenience to create the placeholder a category shows before loading. */
fun loadingNode(): DefaultMutableTreeNode = DefaultMutableTreeNode(RevisionsTreeNode.Loading, false)

fun emptyNode(): DefaultMutableTreeNode = DefaultMutableTreeNode(RevisionsTreeNode.Empty, false)

fun errorNode(message: String): DefaultMutableTreeNode =
    DefaultMutableTreeNode(RevisionsTreeNode.Error(message), false)
