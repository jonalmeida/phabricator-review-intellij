package org.mozilla.phabricator.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.SideBorder
import com.intellij.ui.components.JBLabel
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import org.mozilla.phabricator.diff.ChangesetDiffOpener

/**
 * Activity timeline section of the revision overview tab.
 *
 * Layout follows the IntelliJ Platform UX guidelines: each entry is a two-column row with a 24-px
 * (HiDPI-scaled) icon column on the left and the entry's header + optional HTML body on the right.
 * Entries are separated by a 1-px theme-aware divider so the rhythm reads as a clean activity log
 * rather than a wall of text.
 *
 * Three entry shapes:
 * - [TimelineEntry.Comment]: top-level reply -- icon = speech bubble; body is the rendered Remarkup
 *   HTML returned by `remarkup.process`.
 * - [TimelineEntry.StateChange]: review-state mutation (accept, abandon, reviewers.add, ...). Icon
 *   is verb-specific (e.g. green check for accepted, warning for rejected, cancel for abandon) so
 *   the user can scan the timeline at a glance.
 * - [TimelineEntry.InlineRollup]: "N inline comments on src/foo.kt" -- rendered as a hyperlink that
 *   opens the diff viewer for that file (when the matching changeset is on the active diff). Plain
 *   label when the file is no longer in the active diff (e.g. an inline left on a superseded diff).
 *
 * Timestamps use [DateFormatUtil.formatPrettyDateTime] so recent events read as "Today, 10:30",
 * yesterday as "Yesterday, 11:45", anything older as a short absolute date. The full timestamp
 * remains visible as a tooltip for precision.
 */
internal object OverviewActivityTimeline {

    private val ABSOLUTE_TIMESTAMP_FORMAT =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /** Width of the icon column to the left of each entry. Scaled at runtime for HiDPI. */
    private const val ICON_COLUMN_WIDTH = 24

    /** Vertical padding inside each entry; also doubles as the gap between rows. */
    private const val ENTRY_PADDING = 8

    fun build(project: Project, data: OverviewData): JPanel {
        val column =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }

        if (data.timeline.isEmpty()) {
            column.add(emptyState())
            return column
        }

        data.timeline.forEachIndexed { index, entry ->
            val row = renderEntry(project, data, entry)
            row.alignmentX = Component.LEFT_ALIGNMENT
            // Every row except the last carries a subtle bottom divider for visual rhythm.
            val padding = JBUI.Borders.empty(ENTRY_PADDING, 0)
            row.border =
                if (index < data.timeline.size - 1) {
                    JBUI.Borders.compound(SideBorder(separatorColor(), SideBorder.BOTTOM), padding)
                } else {
                    padding
                }
            column.add(row)
        }
        return column
    }

    private fun emptyState(): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(12, 0)
            add(JBLabel(AllIcons.General.Information))
            add(JBLabel("No activity yet.").apply { foreground = UIUtil.getInactiveTextColor() })
        }

    private fun renderEntry(project: Project, data: OverviewData, entry: TimelineEntry): JPanel =
        when (entry) {
            is TimelineEntry.Comment -> renderComment(entry)
            is TimelineEntry.StateChange -> renderStateChange(entry)
            is TimelineEntry.InlineRollup -> renderInlineRollup(project, data, entry)
        }

    private fun renderComment(entry: TimelineEntry.Comment): JPanel {
        val header = personHeader(entry.authorDisplayName, entry.dateCreated, verb = null)
        val body = OverviewHtml.newPane().apply { text = OverviewHtml.wrap(entry.renderedHtml) }
        return rowFrame(icon = AllIcons.General.BalloonInformation, header = header, body = body)
    }

    private fun renderStateChange(entry: TimelineEntry.StateChange): JPanel {
        val header = personHeader(entry.authorDisplayName, entry.dateCreated, verb = entry.text)
        return rowFrame(icon = iconForVerb(entry.text), header = header, body = null)
    }

    private fun renderInlineRollup(
        project: Project,
        data: OverviewData,
        entry: TimelineEntry.InlineRollup,
    ): JPanel {
        val noun = if (entry.count == 1) "inline comment" else "inline comments"
        val labelText = "${entry.count} $noun on ${entry.path}"
        val tooltipText = formatAbsoluteTimestamp(entry.dateCreated)

        // Resolve to a clickable hyperlink iff the path still matches an entry in the active diff
        // (an inline left on a superseded diff has no matching OverviewFile -- show as label).
        val matchingFile = data.files.firstOrNull { it.path == entry.path }
        val header: JComponent =
            if (matchingFile != null) {
                HyperlinkLabel(labelText).apply {
                    toolTipText = tooltipText
                    addHyperlinkListener {
                        ChangesetDiffOpener.open(project, data.model, matchingFile.changeset)
                    }
                }
            } else {
                JBLabel(labelText).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    toolTipText = tooltipText
                }
            }
        return rowFrame(icon = AllIcons.General.BalloonInformation, header = header, body = null)
    }

    /**
     * Two-column row: icon on the left, header + optional body stacked vertically on the right. The
     * icon column is fixed-width so every row's content aligns vertically -- the user can scan the
     * timeline by reading the left edge for "what kind of event".
     */
    private fun rowFrame(icon: Icon, header: JComponent, body: JComponent?): JPanel {
        val iconLabel =
            JBLabel(icon).apply {
                verticalAlignment = SwingConstants.TOP
                horizontalAlignment = SwingConstants.LEFT
                preferredSize = Dimension(JBUI.scale(ICON_COLUMN_WIDTH), preferredSize.height)
                // Nudge down so the icon sits on the header text's baseline rather than its top.
                border = JBUI.Borders.emptyTop(2)
            }
        val content =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
        header.alignmentX = Component.LEFT_ALIGNMENT
        content.add(header)
        if (body != null) {
            body.alignmentX = Component.LEFT_ALIGNMENT
            content.add(body)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(iconLabel, BorderLayout.WEST)
            add(content, BorderLayout.CENTER)
        }
    }

    /**
     * Header line: bold author, optional verb in subdued color, then a "·" separator and a
     * pretty-formatted timestamp. The full absolute timestamp is always available as a tooltip so
     * the pretty form ("Today, 10:30") doesn't sacrifice precision.
     */
    private fun personHeader(author: String, dateCreated: Long, verb: String?): JComponent {
        val row =
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                toolTipText = formatAbsoluteTimestamp(dateCreated)
            }
        row.add(JBLabel(author).apply { font = font.deriveFont(java.awt.Font.BOLD) })
        if (verb != null) {
            row.add(JBLabel(" $verb").apply { foreground = UIUtil.getInactiveTextColor() })
        }
        row.add(
            JBLabel(" · ${formatPrettyTimestamp(dateCreated)}").apply {
                foreground = UIUtil.getInactiveTextColor()
            }
        )
        return row
    }

    /**
     * Map a verb phrase (from [TransactionVerbs]) to an [AllIcons] entry that gives the user a
     * quick visual cue for the category of event. Stays inside the icon set
     * [org.mozilla.phabricator.ui.toolwindow.RevisionStatusIcons] already uses so the look is
     * consistent across the plugin.
     */
    private fun iconForVerb(verb: String): Icon =
        when {
            verb.startsWith("accepted") -> AllIcons.RunConfigurations.TestPassed
            verb.startsWith("requested changes") -> AllIcons.General.BalloonWarning
            verb.startsWith("abandoned") -> AllIcons.Actions.Cancel
            verb.startsWith("resigned") -> AllIcons.Actions.Cancel
            verb.startsWith("uploaded a new diff") -> AllIcons.General.Modified
            verb.startsWith("planned changes") -> AllIcons.General.Modified
            verb.startsWith("requested another review") -> AllIcons.General.BalloonInformation
            verb.startsWith("reopened") -> AllIcons.General.BalloonInformation
            verb.startsWith("closed") -> AllIcons.RunConfigurations.TestPassed
            verb.startsWith("took over") -> AllIcons.General.Note
            verb.startsWith("status") -> AllIcons.General.Modified
            verb.contains("reviewer") -> AllIcons.General.Note
            verb.contains("project") -> AllIcons.General.Note
            verb.contains("subscrib") -> AllIcons.General.Note
            verb.startsWith("renamed") -> AllIcons.General.Note
            verb.startsWith("edited") -> AllIcons.General.Note
            verb.startsWith("linked") -> AllIcons.General.Note
            verb.startsWith("toggled draft") -> AllIcons.General.Note
            else -> AllIcons.General.Note
        }

    /**
     * Theme-aware divider between entries. Falls back to the platform's default border color when
     * the named-color key isn't registered (e.g. on heavily-customised LAFs).
     */
    private fun separatorColor(): java.awt.Color =
        JBColor.namedColor("Component.borderColor", JBColor.border())

    private fun formatPrettyTimestamp(epochSeconds: Long): String =
        DateFormatUtil.formatPrettyDateTime(Date(epochSeconds * 1000))

    private fun formatAbsoluteTimestamp(epochSeconds: Long): String =
        ABSOLUTE_TIMESTAMP_FORMAT.format(Date(epochSeconds * 1000))
}
