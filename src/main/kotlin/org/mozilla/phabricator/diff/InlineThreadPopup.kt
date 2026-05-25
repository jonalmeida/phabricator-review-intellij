package org.mozilla.phabricator.diff

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.HyperlinkEvent
import org.mozilla.phabricator.service.InlineComment
import org.mozilla.phabricator.service.InlineThread
import org.mozilla.phabricator.service.UserResolver

/**
 * Read-only thread popup: one comment block per [InlineComment] in the thread, each rendering the
 * pre-computed [InlineComment.renderedHtml] in a `JEditorPane` (HTML content type) so Phabricator's
 * Remarkup output displays with its intended formatting.
 *
 * Phase 2 commit 5 will add the reply text area + Done checkbox below the comment list.
 */
object InlineThreadPopup {

    fun show(project: Project, thread: InlineThread, anchor: Editor, userResolver: UserResolver?) {
        val content = buildContent(thread, userResolver)
        val popup: JBPopup =
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, content)
                .setTitle(popupTitle(thread))
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .setFocusable(true)
                .setMinSize(Dimension(360, 160))
                .createPopup()
        // Anchor near the line that fired the click, on the caret if available.
        popup.showInBestPositionFor(anchor)
    }

    private fun popupTitle(thread: InlineThread): String {
        val sideTag = if (thread.isNewFile) "tip" else "base"
        val done = if (thread.isDone) " — done" else ""
        return "${thread.path}:${thread.line} ($sideTag)$done"
    }

    private fun buildContent(thread: InlineThread, userResolver: UserResolver?): JComponent {
        val list = JPanel()
        list.layout = BoxLayout(list, BoxLayout.Y_AXIS)
        list.border = JBUI.Borders.empty(8)
        list.background = UIUtil.getEditorPaneBackground()

        thread.comments.forEachIndexed { index, comment ->
            if (index > 0) list.add(Box.createVerticalStrut(8))
            list.add(buildCommentBlock(comment, userResolver))
        }

        return ScrollPaneFactory.createScrollPane(list).apply {
            border = BorderFactory.createEmptyBorder()
            preferredSize = Dimension(520, 280)
        }
    }

    private fun buildCommentBlock(comment: InlineComment, userResolver: UserResolver?): JComponent {
        val authorName = userResolver?.displayName(comment.authorPHID) ?: comment.authorPHID
        val timestamp =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(comment.dateCreated * 1000))
        val header =
            JLabel("$authorName • $timestamp", SwingConstants.LEFT).apply {
                font = font.deriveFont(font.style or java.awt.Font.BOLD)
                border = JBUI.Borders.emptyBottom(4)
            }
        val body =
            JEditorPane().apply {
                contentType = "text/html"
                isEditable = false
                background = UIUtil.getEditorPaneBackground()
                border = BorderFactory.createEmptyBorder()
                text = comment.renderedHtml.ifEmpty { "<i>(empty)</i>" }
                addHyperlinkListener { e ->
                    if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                        e.url?.toExternalForm()?.let { com.intellij.ide.BrowserUtil.browse(it) }
                    }
                }
            }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }
}
