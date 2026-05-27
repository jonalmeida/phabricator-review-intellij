package org.mozilla.phabricator.ui.toolwindow

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
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
import org.mozilla.phabricator.actions.PhabricatorDataKeys
import org.mozilla.phabricator.conduit.model.Changeset
import org.mozilla.phabricator.diff.ChangesetDiffOpener
import org.mozilla.phabricator.editor.RevisionOverviewOpener
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
        installContextMenu()
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
                    return when (val payload = node.userObject) {
                        is RevisionsTreeNode.FileChange -> {
                            ChangesetDiffOpener.open(project, payload.revision, payload.changeset)
                            true
                        }
                        is RevisionsTreeNode.Revision -> {
                            RevisionOverviewOpener.open(project, payload.model)
                            true
                        }
                        else -> false
                    }
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
     * Wires the right-click context menu on revision rows. The menu is the action group
     * `Phabricator.RevisionContextMenu` declared in plugin.xml; the tree publishes
     * [PhabricatorDataKeys.SELECTED_REVISION_MODEL] (and
     * [com.intellij.openapi.actionSystem.PlatformDataKeys.PROJECT]) via a [DataProvider] so the
     * action `update()` / `actionPerformed()` callbacks can read the currently-selected revision
     * without depending on a UI component.
     *
     * A click on a row pre-selects it so right-clicking an unselected row still acts on what the
     * user clicked, matching the platform's Project View tree behaviour.
     */
    private fun installContextMenu() {
        DataManager.registerDataProvider(tree, DataProvider { dataId -> resolveData(dataId) })
        PopupHandler.installPopupMenu(tree, REVISION_CONTEXT_GROUP_ID, REVISION_CONTEXT_PLACE)
    }

    private fun resolveData(dataId: String): Any? =
        when (dataId) {
            PhabricatorDataKeys.SELECTED_REVISION_MODEL.name -> selectedRevisionModel()
            PlatformDataKeys.PROJECT.name -> project
            else -> null
        }

    private fun selectedRevisionModel(): RevisionModel? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return when (val payload = node.userObject) {
            is RevisionsTreeNode.Revision -> payload.model
            is RevisionsTreeNode.FileChange -> payload.revision
            else -> null
        }
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
        private const val REVISION_CONTEXT_GROUP_ID = "Phabricator.RevisionContextMenu"
        private const val REVISION_CONTEXT_PLACE = "PhabricatorRevisionTreePopup"
    }
}

private typealias TreePathOf = javax.swing.tree.TreePath
