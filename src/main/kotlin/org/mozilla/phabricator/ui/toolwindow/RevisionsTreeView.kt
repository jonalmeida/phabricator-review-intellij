package org.mozilla.phabricator.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.phabricator.conduit.model.Changeset
import org.mozilla.phabricator.diff.ChangesetDiffOpener
import org.mozilla.phabricator.service.PhabSessionService
import org.mozilla.phabricator.service.RevisionModel
import org.mozilla.phabricator.service.RevisionsManager
import org.mozilla.phabricator.service.RevisionsManager.CategoryKey

/**
 * Tree presenting the four revision categories. Children are loaded lazily the first time a
 * category is expanded; subsequent expansions reuse the cache in [RevisionsManager] until a refresh
 * clears it.
 */
class RevisionsTreeView(private val project: Project, parentDisposable: Disposable) {
    private val root = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(root)
    private val tree =
        Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = RevisionsTreeCellRenderer()
        }
    private val categoryNodes = mutableMapOf<CategoryKey, DefaultMutableTreeNode>()

    val component: JComponent = ScrollPaneFactory.createScrollPane(tree)

    init {
        rebuildCategories()
        installSpeedSearch()
        tree.addTreeExpansionListener(
            object : TreeExpansionListener {
                override fun treeExpanded(event: TreeExpansionEvent) {
                    val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    when (val payload = node.userObject) {
                        is RevisionsTreeNode.Category ->
                            if (!payload.loaded) loadCategory(node, payload.key)
                        is RevisionsTreeNode.Revision ->
                            if (!payload.filesLoaded) loadRevisionFiles(node, payload.model)
                        else -> Unit
                    }
                }

                override fun treeCollapsed(event: TreeExpansionEvent) {}
            }
        )

        object : DoubleClickListener() {
                override fun onDoubleClick(event: MouseEvent): Boolean {
                    val node =
                        tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return false
                    val payload = node.userObject as? RevisionsTreeNode.FileChange ?: return false
                    ChangesetDiffOpener.open(project, payload.revision, payload.changeset)
                    return true
                }
            }
            .installOn(tree)

        // Listen for refreshes (e.g. via Refresh action / polling tick) and
        // reload categories the user has already expanded.
        ApplicationManager.getApplication()
            .messageBus
            .connect(parentDisposable)
            .subscribe(
                RevisionsManager.REFRESH_TOPIC,
                RevisionsManager.RefreshListener { category ->
                    ApplicationManager.getApplication().invokeLater { onRefresh(category) }
                },
            )
    }

    fun refreshAll() {
        rebuildCategories()
    }

    /**
     * Native IntelliJ speed-search: typing characters in the tree narrows the visible nodes to
     * those whose searchable text matches, the same UX as the Project view tree. The returned
     * string is computed per-row from the node payload:
     * - Category rows match on their human label ("My Active", "Needs My Review", ...).
     * - Revision rows match on `Dxxxxx`, the title, and the status value, so typing either an id, a
     *   substring of the title, or e.g. "accepted" filters the list.
     * - File-change rows match on the file path.
     * - Loading / Empty / Error placeholders are unsearchable (empty string) so they do not
     *   distract while the user is typing.
     */
    private fun installSpeedSearch() {
        TreeSpeedSearch.installOn(tree, /* canExpand= */ true) { treePath ->
            val node = treePath.lastPathComponent as? DefaultMutableTreeNode ?: return@installOn ""
            when (val payload = node.userObject) {
                is RevisionsTreeNode.Category -> payload.label
                is RevisionsTreeNode.Revision -> {
                    val m = payload.model
                    "${m.monogram} ${m.title} ${m.statusValue}"
                }
                is RevisionsTreeNode.FileChange -> payload.path
                else -> ""
            }
        }
    }

    private fun rebuildCategories() {
        root.removeAllChildren()
        categoryNodes.clear()
        for (key in CategoryKey.entries) {
            val node =
                DefaultMutableTreeNode(RevisionsTreeNode.Category(key), true).apply {
                    add(loadingNode())
                }
            categoryNodes[key] = node
            root.add(node)
        }
        treeModel.reload(root)
    }

    private fun onRefresh(category: CategoryKey?) {
        if (category == null) {
            for ((key, node) in categoryNodes) {
                val payload = node.userObject as RevisionsTreeNode.Category
                if (
                    payload.loaded &&
                        tree.isExpanded(treeModel.getPathToRoot(node).let { TreePathOf(it) })
                ) {
                    loadCategory(node, key)
                } else if (payload.loaded) {
                    // Was loaded but not currently expanded -> invalidate so it reloads on next
                    // expand.
                    node.userObject = RevisionsTreeNode.Category(key, loaded = false)
                    node.removeAllChildren()
                    node.add(loadingNode())
                    treeModel.reload(node)
                }
            }
        } else {
            val node = categoryNodes[category] ?: return
            val payload = node.userObject as RevisionsTreeNode.Category
            if (payload.loaded) {
                loadCategory(node, category)
            }
        }
    }

    private fun loadCategory(node: DefaultMutableTreeNode, key: CategoryKey) {
        node.removeAllChildren()
        node.add(loadingNode())
        treeModel.reload(node)

        val scope = PhabSessionService.getInstance().coroutineScope
        scope.launch {
            val revisions = runCatching {
                withContext(Dispatchers.IO) {
                    RevisionsManager.getInstance(project).getRevisionsForCategory(key)
                }
            }
            ApplicationManager.getApplication().invokeLater { renderCategory(node, key, revisions) }
        }
    }

    private fun renderCategory(
        node: DefaultMutableTreeNode,
        key: CategoryKey,
        revisionsResult: Result<List<RevisionModel>>,
    ) {
        node.removeAllChildren()
        revisionsResult.fold(
            onSuccess = { revisions ->
                if (revisions.isEmpty()) {
                    node.add(emptyNode())
                } else {
                    revisions.forEach { rev ->
                        // Preseed each revision with a Loading child so the
                        // disclosure triangle appears immediately.
                        val revisionNode =
                            DefaultMutableTreeNode(RevisionsTreeNode.Revision(rev), true)
                        revisionNode.add(loadingNode())
                        node.add(revisionNode)
                    }
                }
                node.userObject = RevisionsTreeNode.Category(key, loaded = true)
            },
            onFailure = { err ->
                LOG.warn("Loading category $key failed", err)
                node.add(errorNode(err.message ?: err.javaClass.simpleName))
                node.userObject = RevisionsTreeNode.Category(key, loaded = false)
            },
        )
        treeModel.reload(node)
    }

    private fun loadRevisionFiles(node: DefaultMutableTreeNode, model: RevisionModel) {
        node.removeAllChildren()
        node.add(loadingNode())
        treeModel.reload(node)

        val scope = PhabSessionService.getInstance().coroutineScope
        scope.launch {
            val changesets = runCatching { withContext(Dispatchers.IO) { model.getChangesets() } }
            ApplicationManager.getApplication().invokeLater { renderFiles(node, model, changesets) }
        }
    }

    private fun renderFiles(
        node: DefaultMutableTreeNode,
        model: RevisionModel,
        changesetsResult: Result<List<Changeset>>,
    ) {
        node.removeAllChildren()
        changesetsResult.fold(
            onSuccess = { changesets ->
                if (changesets.isEmpty()) {
                    node.add(emptyNode())
                } else {
                    changesets.forEach { cs ->
                        node.add(
                            DefaultMutableTreeNode(RevisionsTreeNode.FileChange(model, cs), false)
                        )
                    }
                }
                node.userObject = RevisionsTreeNode.Revision(model, filesLoaded = true)
            },
            onFailure = { err ->
                LOG.warn("Loading changesets for ${model.monogram} failed", err)
                node.add(errorNode(err.message ?: err.javaClass.simpleName))
                node.userObject = RevisionsTreeNode.Revision(model, filesLoaded = false)
            },
        )
        treeModel.reload(node)
    }

    companion object {
        private val LOG = logger<RevisionsTreeView>()
    }
}

private typealias TreePathOf = javax.swing.tree.TreePath
