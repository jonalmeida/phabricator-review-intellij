package org.mozilla.phabricator.editor

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Activity timeline: top-level comments rendered as HTML blocks, state changes as a short "Author
 * <verb>" line, and per-file inline-comment rollups as "N inline comments on src/foo.kt". Sorted
 * chronologically oldest-first to read like a transcript.
 */
internal object OverviewActivityTimeline {

    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun build(data: OverviewData): JPanel {
        val column =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
        if (data.timeline.isEmpty()) {
            column.add(
                JBLabel("(no activity yet)").apply { foreground = UIUtil.getInactiveTextColor() }
            )
            return column
        }
        data.timeline.forEachIndexed { index, entry ->
            if (index > 0) {
                column.add(
                    JPanel().apply {
                        isOpaque = false
                        preferredSize = java.awt.Dimension(1, 6)
                        maximumSize = java.awt.Dimension(Int.MAX_VALUE, 6)
                    }
                )
            }
            column.add(renderEntry(entry))
        }
        return column
    }

    private fun renderEntry(entry: TimelineEntry): JPanel =
        when (entry) {
            is TimelineEntry.Comment -> renderComment(entry)
            is TimelineEntry.StateChange -> renderStateChange(entry)
            is TimelineEntry.InlineRollup -> renderInlineRollup(entry)
        }

    private fun renderComment(entry: TimelineEntry.Comment): JPanel {
        val header =
            JBLabel("${entry.authorDisplayName} · ${formatTimestamp(entry.dateCreated)}").apply {
                font = font.deriveFont(java.awt.Font.BOLD)
                border = JBUI.Borders.emptyBottom(4)
            }
        val body = OverviewHtml.newPane().apply { text = OverviewHtml.wrap(entry.renderedHtml) }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 0)
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    private fun renderStateChange(entry: TimelineEntry.StateChange): JPanel {
        val line =
            "${entry.authorDisplayName} ${entry.text} · ${formatTimestamp(entry.dateCreated)}"
        return JPanel().apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel(line).apply { foreground = UIUtil.getInactiveTextColor() })
        }
    }

    private fun renderInlineRollup(entry: TimelineEntry.InlineRollup): JPanel {
        val noun = if (entry.count == 1) "inline comment" else "inline comments"
        return JPanel().apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(
                JBLabel("${entry.count} $noun on ${entry.path} · open the diff to view").apply {
                    foreground = UIUtil.getInactiveTextColor()
                }
            )
        }
    }

    private fun formatTimestamp(epochSeconds: Long): String =
        TIMESTAMP_FORMAT.format(Date(epochSeconds * 1000))
}
