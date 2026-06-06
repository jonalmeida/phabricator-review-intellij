package org.mozilla.phabricator.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.phabricator.common.EdtDispatcher
import org.mozilla.phabricator.service.RevisionsManager

/**
 * Root component shown inside the `RevisionOverviewFileEditor` tab. Loads its data via
 * [OverviewLoader] on a per-instance CoroutineScope and renders header + metadata sections in a
 * scrolling vertical column.
 *
 * Live refresh: subscribes to [RevisionsManager.COMMENTS_TOPIC] for the same revisionPHID so a
 * write elsewhere (e.g. submitting an inline comment in the diff popup, or a poll tick noticing a
 * new top-level comment) triggers a reload in place.
 */
class RevisionOverviewPanel(
    private val project: Project,
    private val file: RevisionOverviewVirtualFile,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()) {

    private val parentJob = SupervisorJob()
    private val scope = CoroutineScope(parentJob)
    private val column =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

    init {
        border = JBUI.Borders.empty(12)
        background = UIUtil.getEditorPaneBackground()

        // Bootstrap UI: header skeleton + loading placeholder.
        renderLoading()

        add(ScrollPaneFactory.createScrollPane(column).apply { border = JBUI.Borders.empty() })

        // Tie scope to parent (FileEditor) disposable so closing the tab cancels everything.
        com.intellij.openapi.util.Disposer.register(parentDisposable) { scope.cancel() }

        // Subscribe to comment refresh signals.
        ApplicationManager.getApplication()
            .messageBus
            .connect(parentDisposable)
            .subscribe(
                RevisionsManager.COMMENTS_TOPIC,
                RevisionsManager.CommentsListener { changedPHID ->
                    if (changedPHID == file.revisionPHID) reload()
                },
            )

        reload()
    }

    private fun reload() {
        scope.launch {
            val data = runCatching { OverviewLoader.load(project, file.revisionPHID) }
            withContext(EdtDispatcher) {
                data
                    .onSuccess { it?.let { renderLoaded(it) } ?: renderError("Not signed in.") }
                    .onFailure {
                        LOG.warn("Overview load failed", it)
                        renderError(it.message ?: it.javaClass.simpleName)
                    }
            }
        }
    }

    private fun renderLoading() {
        column.removeAll()
        column.add(skeletonHeader())
        column.add(
            JBLabel("Loading revision details…").apply {
                foreground = UIUtil.getInactiveTextColor()
                border = JBUI.Borders.emptyTop(8)
                alignmentX = Component.LEFT_ALIGNMENT
            }
        )
        column.revalidate()
        column.repaint()
    }

    private fun renderError(message: String) {
        column.removeAll()
        column.add(skeletonHeader())
        column.add(
            JBLabel("Could not load revision: $message").apply {
                foreground = UIUtil.getErrorForeground()
                border = JBUI.Borders.emptyTop(8)
                alignmentX = Component.LEFT_ALIGNMENT
            }
        )
        column.revalidate()
        column.repaint()
    }

    private fun renderLoaded(data: OverviewData) {
        column.removeAll()
        column.add(
            OverviewHeader.build(project, data, scope).also {
                it.alignmentX = Component.LEFT_ALIGNMENT
            }
        )
        column.add(
            OverviewActionsToolbar.build(project, data, scope).also {
                it.alignmentX = Component.LEFT_ALIGNMENT
            }
        )
        column.add(
            OverviewMetadata.build(project, data, scope).also {
                it.alignmentX = Component.LEFT_ALIGNMENT
            }
        )
        column.add(sectionLabel("Files").also { it.alignmentX = Component.LEFT_ALIGNMENT })
        column.add(
            OverviewFilesList.build(project, data).also { it.alignmentX = Component.LEFT_ALIGNMENT }
        )
        column.add(sectionLabel("Activity").also { it.alignmentX = Component.LEFT_ALIGNMENT })
        column.add(
            OverviewActivityTimeline.build(project, data).also {
                it.alignmentX = Component.LEFT_ALIGNMENT
            }
        )
        column.revalidate()
        column.repaint()
    }

    private fun sectionLabel(text: String): JBLabel =
        JBLabel(text).apply {
            font = font.deriveFont(java.awt.Font.BOLD)
            border = JBUI.Borders.empty(12, 0, 4, 0)
        }

    private fun skeletonHeader(): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(
                JBLabel("${file.monogram}   ${file.title}").apply {
                    font = font.deriveFont(font.style or java.awt.Font.BOLD, font.size + 4f)
                },
                BorderLayout.WEST,
            )
        }

    companion object {
        private val LOG = logger<RevisionOverviewPanel>()
    }
}
