package org.mozilla.phabricator.diff

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SideBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.HyperlinkEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.mozilla.phabricator.service.InlineComment
import org.mozilla.phabricator.service.InlineThread
import org.mozilla.phabricator.service.UserResolver

/**
 * Inline-comment thread popup shown by the diff viewer's gutter icons. Same visual language as
 * [org.mozilla.phabricator.editor.OverviewActivityTimeline]: every comment is a two-column row
 * (24-px icon column on the left, header + HTML body on the right), comments are separated by a
 * theme-aware 1-px divider, and timestamps render via [DateFormatUtil.formatPrettyDateTime] with
 * the absolute timestamp preserved as a tooltip. The reply composer sits below a labelled separator
 * at the bottom of the popup.
 *
 * Reply callbacks run on the caller-provided [CoroutineScope] (the diff extension's viewer-scoped
 * scope); a balloon notification surfaces success or failure even after the popup closes.
 *
 * The Done toggle was removed in Phase-2 bug 4 (Mozilla Phabricator's `differential.revision.edit`
 * rejects `inline.done`/`inline.undone` transactions). The thread's current Done state is still
 * visible in the popup title until the right Phorge-side mechanism is identified.
 */
object InlineThreadPopup {

    /** HTML-pixel wrap width for the JEditorPane body. Matches the popup's nominal column width. */
    private const val TEXT_COLUMN_WIDTH = 480

    /** Icon-column width, in *unscaled* CSS-like pixels (we apply [JBUI.scale] at runtime). */
    private const val ICON_COLUMN_WIDTH = 24

    private val ABSOLUTE_TIMESTAMP_FORMAT =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun show(
        project: Project,
        thread: InlineThread,
        anchor: Editor,
        userResolver: UserResolver?,
        scope: CoroutineScope,
        onReply: suspend (body: String) -> Unit,
    ) {
        val popupRef = arrayOfNulls<JBPopup>(1)
        val content = buildContent(thread, userResolver, scope, onReply) { popupRef[0] }
        val popup =
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, content.preferredFocus)
                .setTitle(popupTitle(thread))
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .setFocusable(true)
                // Component preferred sizes drive the final dimensions; JBPopup grows vertically
                // until the content fits.
                .setMinSize(Dimension(JBUI.scale(TEXT_COLUMN_WIDTH + 60), JBUI.scale(220)))
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
        popupAccessor: () -> JBPopup?,
    ): PopupRoot {
        val comments = buildCommentList(thread, userResolver)
        val commentsScroll =
            ScrollPaneFactory.createScrollPane(comments).apply {
                border = BorderFactory.createEmptyBorder()
                // Width pins the scroll viewport to the icon column + body width; height caps the
                // tall threads while letting short ones shrink naturally.
                preferredSize =
                    Dimension(
                        JBUI.scale(TEXT_COLUMN_WIDTH + 56),
                        comments.preferredSize.height.coerceAtMost(JBUI.scale(360)),
                    )
                alignmentX = Component.LEFT_ALIGNMENT
            }

        val replyComposer = buildReplyComposer(scope, onReply, popupAccessor)

        val root =
            PopupRoot(preferredFocus = replyComposer.replyArea).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = true
                background = UIUtil.getPanelBackground()
                add(commentsScroll)
                add(replyComposer.component)
            }
        return root
    }

    /** Vertical stack of one row per comment, separated by 1-px theme-aware dividers. */
    private fun buildCommentList(thread: InlineThread, userResolver: UserResolver?): JPanel {
        val column =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(8)
                alignmentX = Component.LEFT_ALIGNMENT
            }
        thread.comments.forEachIndexed { index, comment ->
            val row = buildCommentRow(comment, userResolver)
            row.alignmentX = Component.LEFT_ALIGNMENT
            val padding = JBUI.Borders.empty(8, 0)
            row.border =
                if (index < thread.comments.size - 1) {
                    JBUI.Borders.compound(SideBorder(separatorColor(), SideBorder.BOTTOM), padding)
                } else {
                    padding
                }
            column.add(row)
        }
        return column
    }

    /**
     * Two-column row: icon on the left (24-px scaled), header + HTML body on the right. Same layout
     * the activity timeline uses so the two surfaces feel visually consistent.
     */
    private fun buildCommentRow(comment: InlineComment, userResolver: UserResolver?): JPanel {
        val icon = AllIcons.General.BalloonInformation
        val iconLabel =
            JBLabel(icon).apply {
                verticalAlignment = SwingConstants.TOP
                horizontalAlignment = SwingConstants.LEFT
                preferredSize = Dimension(JBUI.scale(ICON_COLUMN_WIDTH), preferredSize.height)
                border = JBUI.Borders.emptyTop(2) // nudge onto the header text baseline
            }

        val header = buildCommentHeader(comment, userResolver)
        val body =
            JEditorPane().apply {
                contentType = "text/html"
                isEditable = false
                isOpaque = false
                border = BorderFactory.createEmptyBorder()
                text = wrapWithThemeCss(comment.renderedHtml.ifEmpty { "<i>(empty)</i>" })
                addHyperlinkListener { e ->
                    if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                        e.url?.toExternalForm()?.let { com.intellij.ide.BrowserUtil.browse(it) }
                    }
                }
            }

        val content =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
        header.alignmentX = Component.LEFT_ALIGNMENT
        body.alignmentX = Component.LEFT_ALIGNMENT
        content.add(header)
        content.add(body)

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(iconLabel, BorderLayout.WEST)
            add(content, BorderLayout.CENTER)
        }
    }

    /**
     * Header line: bold author + dimmed " · pretty-timestamp". The full absolute timestamp is the
     * tooltip so the pretty form ("Today, 10:30") doesn't sacrifice precision.
     */
    private fun buildCommentHeader(comment: InlineComment, userResolver: UserResolver?): JPanel {
        val authorName = userResolver?.displayName(comment.authorPHID) ?: comment.authorPHID
        val absoluteTimestamp = formatAbsoluteTimestamp(comment.dateCreated)
        val prettyTimestamp = formatPrettyTimestamp(comment.dateCreated)
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            toolTipText = absoluteTimestamp
            border = JBUI.Borders.emptyBottom(4)
            add(JBLabel(authorName).apply { font = font.deriveFont(java.awt.Font.BOLD) })
            add(JBLabel(" · $prettyTimestamp").apply { foreground = UIUtil.getInactiveTextColor() })
        }
    }

    /**
     * Reply composer: labelled hint, text area in a scroll pane, Reply button right-aligned. The
     * whole block sits below a top border so it visually separates from the comment list.
     */
    private fun buildReplyComposer(
        scope: CoroutineScope,
        onReply: suspend (body: String) -> Unit,
        popupAccessor: () -> JBPopup?,
    ): ReplyComposer {
        val replyArea =
            JBTextArea(5, 40).apply {
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(4)
            }
        val replyScroll =
            ScrollPaneFactory.createScrollPane(replyArea).apply {
                preferredSize = Dimension(JBUI.scale(TEXT_COLUMN_WIDTH + 24), JBUI.scale(96))
                alignmentX = Component.LEFT_ALIGNMENT
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

        val component =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                // Top border separates the composer from the comment list using the same
                // theme-aware colour the inter-comment dividers use.
                border =
                    JBUI.Borders.compound(
                        SideBorder(separatorColor(), SideBorder.TOP),
                        JBUI.Borders.empty(8),
                    )
                add(
                    JBLabel("Reply (saved as a draft until you publish):").apply {
                        foreground = UIUtil.getInactiveTextColor()
                        border = JBUI.Borders.emptyBottom(4)
                    },
                    BorderLayout.NORTH,
                )
                add(replyScroll, BorderLayout.CENTER)
                add(
                    JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                        isOpaque = false
                        border = JBUI.Borders.emptyTop(6)
                        add(replyButton)
                    },
                    BorderLayout.SOUTH,
                )
            }
        return ReplyComposer(component = component, replyArea = replyArea)
    }

    /** Theme-aware divider between comments (and below the comment list). */
    private fun separatorColor(): Color =
        JBColor.namedColor("Component.borderColor", JBColor.border())

    private fun wrapWithThemeCss(content: String): String {
        val fg = cssColor(UIUtil.getLabelForeground())
        return "<html><body width='$TEXT_COLUMN_WIDTH' style='font-family:sans-serif;color:$fg;margin:0;padding:0;'>$content</body></html>"
    }

    private fun cssColor(c: Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    private fun formatPrettyTimestamp(epochSeconds: Long): String =
        DateFormatUtil.formatPrettyDateTime(Date(epochSeconds * 1000))

    private fun formatAbsoluteTimestamp(epochSeconds: Long): String =
        ABSOLUTE_TIMESTAMP_FORMAT.format(Date(epochSeconds * 1000))

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

/**
 * Two-output payload for [InlineThreadPopup.buildReplyComposer]: the component + its focus target.
 */
private data class ReplyComposer(val component: JComponent, val replyArea: JComponent)
