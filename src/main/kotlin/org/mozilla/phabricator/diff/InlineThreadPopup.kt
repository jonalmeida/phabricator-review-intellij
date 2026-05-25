package org.mozilla.phabricator.diff

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.HyperlinkEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.mozilla.phabricator.service.InlineComment
import org.mozilla.phabricator.service.InlineThread
import org.mozilla.phabricator.service.UserResolver

private const val TEXT_COLUMN_WIDTH = 480

/**
 * Inline-comment thread popup with a reply composer + Done toggle.
 *
 * Layout (top to bottom):
 * 1. Scroll pane containing one [JEditorPane] per comment (renders the precomputed Remarkup HTML).
 * 2. A Done [JBCheckBox] bound to the thread's `isDone` state.
 * 3. A reply [JBTextArea] (5 rows, soft-wrapped) + a Reply button.
 *
 * Reply / Done callbacks run on the caller-provided [CoroutineScope] (the diff extension's
 * viewer-scoped scope), and a "Mozilla Phabricator" balloon surfaces success and error states so
 * users get immediate feedback even when the popup has already closed.
 */
object InlineThreadPopup {

    fun show(
        project: Project,
        thread: InlineThread,
        anchor: Editor,
        userResolver: UserResolver?,
        scope: CoroutineScope,
        onReply: suspend (body: String) -> Unit,
        onMarkDone: suspend (done: Boolean) -> Unit,
    ) {
        val popupRef = arrayOfNulls<JBPopup>(1)
        val content = buildContent(thread, userResolver, scope, onReply, onMarkDone) { popupRef[0] }
        val popup =
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, content.preferredFocus)
                .setTitle(popupTitle(thread))
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .setFocusable(true)
                // Sized via component preferred sizes (set on the comments scroll and reply
                // text area below); JBPopup grows vertically until the content fits.
                .setMinSize(Dimension(TEXT_COLUMN_WIDTH + 40, 200))
                .createPopup()
        popupRef[0] = popup
        popup.showInBestPositionFor(anchor)
    }

    private fun popupTitle(thread: InlineThread): String {
        val sideTag = if (thread.isNewFile) "tip" else "base"
        val done = if (thread.isDone) " — done" else ""
        return "${thread.path}:${thread.line} ($sideTag)$done"
    }

    private fun buildContent(
        thread: InlineThread,
        userResolver: UserResolver?,
        scope: CoroutineScope,
        onReply: suspend (body: String) -> Unit,
        onMarkDone: suspend (done: Boolean) -> Unit,
        popupAccessor: () -> JBPopup?,
    ): PopupRoot {
        val comments = JPanel()
        comments.layout = BoxLayout(comments, BoxLayout.Y_AXIS)
        comments.border = JBUI.Borders.empty(8)
        comments.background = UIUtil.getEditorPaneBackground()
        thread.comments.forEachIndexed { index, comment ->
            if (index > 0) comments.add(Box.createVerticalStrut(8))
            comments.add(buildCommentBlock(comment, userResolver))
        }
        // Cap the comment list height so very long threads scroll, but let it shrink to fit
        // short ones; the JEditorPane wraps at TEXT_COLUMN_WIDTH so the width is fixed.
        val commentsScroll =
            ScrollPaneFactory.createScrollPane(comments).apply {
                border = BorderFactory.createEmptyBorder()
                preferredSize =
                    Dimension(
                        TEXT_COLUMN_WIDTH + 24,
                        comments.preferredSize.height.coerceAtMost(360),
                    )
            }

        val doneCheckbox =
            JBCheckBox("Done", thread.isDone).apply {
                addActionListener { evt ->
                    val newState = isSelected
                    isEnabled = false
                    scope.launch {
                        runCatching { onMarkDone(newState) }
                            .onSuccess {
                                ApplicationManager.getApplication().invokeLater { isEnabled = true }
                            }
                            .onFailure { err ->
                                ApplicationManager.getApplication().invokeLater {
                                    isSelected = !newState
                                    isEnabled = true
                                }
                                notifyError(
                                    "Mark done failed: ${err.message ?: err.javaClass.simpleName}"
                                )
                            }
                    }
                }
            }

        val replyArea =
            JBTextArea(5, 40).apply {
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(4)
            }
        val replyScroll =
            ScrollPaneFactory.createScrollPane(replyArea).apply {
                preferredSize = Dimension(TEXT_COLUMN_WIDTH + 24, 96)
            }

        val replyButton =
            JButton("Reply").apply {
                addActionListener {
                    val body = replyArea.text.trim()
                    if (body.isEmpty()) {
                        notifyInfo("Reply body is empty")
                        return@addActionListener
                    }
                    isEnabled = false
                    replyArea.isEditable = false
                    scope.launch {
                        runCatching { onReply(body) }
                            .onSuccess {
                                ApplicationManager.getApplication().invokeLater {
                                    notifyInfo("Draft saved")
                                    popupAccessor()?.cancel()
                                }
                            }
                            .onFailure { err ->
                                ApplicationManager.getApplication().invokeLater {
                                    isEnabled = true
                                    replyArea.isEditable = true
                                    notifyError(
                                        "Reply failed: ${err.message ?: err.javaClass.simpleName}"
                                    )
                                }
                            }
                    }
                }
            }

        val replyRow =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8, 8, 8)
                add(JBLabel("Reply (will be saved as a draft):"), BorderLayout.NORTH)
                add(replyScroll, BorderLayout.CENTER)
                add(
                    JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                        isOpaque = false
                        add(replyButton)
                    },
                    BorderLayout.SOUTH,
                )
            }

        val doneRow =
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8, 0, 8)
                add(doneCheckbox)
            }

        val root =
            PopupRoot(preferredFocus = replyArea).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(commentsScroll)
                add(doneRow)
                add(replyRow)
            }
        return root
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
                isOpaque = false
                border = BorderFactory.createEmptyBorder()
                // The `width=` attribute on the <body> tag forces JEditorPane's HTMLEditorKit
                // to wrap at that pixel width; combined with isOpaque=false + theme-aware
                // foreground in the inline style, the popup honours the active IDE theme.
                text = wrapWithThemeCss(comment.renderedHtml.ifEmpty { "<i>(empty)</i>" })
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

    private fun wrapWithThemeCss(content: String): String {
        val fg = cssColor(UIUtil.getLabelForeground())
        return "<html><body width='$TEXT_COLUMN_WIDTH' style='font-family:sans-serif;color:$fg;margin:0;padding:0;'>$content</body></html>"
    }

    private fun cssColor(c: Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    private fun notifyInfo(message: String) = notify(message, NotificationType.INFORMATION)

    private fun notifyError(message: String) = notify(message, NotificationType.ERROR)

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Mozilla Phabricator")
            .createNotification(message, type)
            .notify(null)
    }
}

/** Root component for the popup; exposes the field that should receive focus on open. */
private class PopupRoot(val preferredFocus: JComponent) : JPanel()
