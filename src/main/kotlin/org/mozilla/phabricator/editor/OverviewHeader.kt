package org.mozilla.phabricator.editor

import com.intellij.ide.BrowserUtil
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingConstants
import org.mozilla.phabricator.ui.toolwindow.RevisionStatusIcons

/** Header row: status icon · monogram · title · author · optional bug link. */
internal object OverviewHeader {

    fun build(data: OverviewData): JPanel {
        val titleRow =
            JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(
                    JBLabel(RevisionStatusIcons.forStatus(data.model.statusValue)).apply {
                        verticalAlignment = SwingConstants.CENTER
                    }
                )
                add(
                    JBLabel(data.model.monogram).apply {
                        font = font.deriveFont(font.style or java.awt.Font.BOLD, font.size + 4f)
                    }
                )
                add(JBLabel(data.model.title).apply { font = font.deriveFont(font.size + 2f) })
            }
        titleRow.alignmentX = Component.LEFT_ALIGNMENT

        val subtitleParts =
            mutableListOf<String>().apply {
                add(data.model.statusName)
                if (data.authorDisplayName.isNotEmpty()) add("by ${data.authorDisplayName}")
            }
        val subtitle =
            JBLabel(subtitleParts.joinToString(" · ")).apply {
                foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
                border = JBUI.Borders.empty(2, 0, 0, 0)
                alignmentX = Component.LEFT_ALIGNMENT
            }

        val container =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(titleRow)
                add(subtitle)
                data.model.bugzillaBugId?.let { bugId ->
                    add(Box.createVerticalStrut(4))
                    add(bugzillaLink(bugId).apply { alignmentX = Component.LEFT_ALIGNMENT })
                }
            }
        return container
    }

    private fun bugzillaLink(bugId: String): HyperlinkLabel {
        val url = "https://bugzilla.mozilla.org/show_bug.cgi?id=$bugId"
        return HyperlinkLabel("Bug $bugId").apply {
            setHyperlinkTarget(url)
            addHyperlinkListener { BrowserUtil.browse(url) }
        }
    }
}
