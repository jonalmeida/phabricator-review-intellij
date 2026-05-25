package org.mozilla.phabricator.editor

import com.intellij.ide.BrowserUtil
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

/**
 * Shared HTML-rendering helper used by the overview's summary / test plan blocks. Wraps the
 * server-rendered Remarkup with theme-aware CSS so the panel honours the active IDE theme on both
 * dark and light backgrounds, mirroring the trick from
 * [org.mozilla.phabricator.diff.InlineThreadPopup].
 */
internal object OverviewHtml {

    /** Column width (CSS pixels) the HTML wraps at -- matches the inline-thread popup. */
    const val COLUMN_WIDTH = 720

    fun newPane(): JEditorPane =
        JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            isOpaque = false
            addHyperlinkListener { event ->
                if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    event.url?.toExternalForm()?.let { BrowserUtil.browse(it) }
                }
            }
        }

    fun wrap(html: String): String {
        val fg = cssColor(UIUtil.getLabelForeground())
        val body = html.ifEmpty { "<i>(empty)</i>" }
        return "<html><body width='$COLUMN_WIDTH' style='font-family:sans-serif;color:$fg;margin:0;padding:0;'>$body</body></html>"
    }

    private fun cssColor(c: Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)
}
