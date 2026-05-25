package org.mozilla.phabricator.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.Icon
import org.mozilla.phabricator.service.InlineThread

/**
 * Gutter icon that surfaces an inline comment thread on a single line of the diff editor. Clicking
 * the icon hands off to [onClick] (the diff extension wires this to [InlineThreadPopup.show]).
 *
 * Equality is based on the thread's root PHID so IntelliJ can dedupe renderers when the markup is
 * rebuilt (e.g. after a comment refresh).
 */
class InlineCommentGutterRenderer(
    private val thread: InlineThread,
    private val onClick: (InlineThread) -> Unit,
) : GutterIconRenderer() {

    override fun getIcon(): Icon =
        if (thread.isDone) AllIcons.RunConfigurations.TestPassed
        else AllIcons.General.BalloonInformation

    override fun getTooltipText(): String {
        val n = thread.comments.size
        val plural = if (n == 1) "comment" else "comments"
        val doneTag = if (thread.isDone) " (done)" else ""
        return "$n $plural$doneTag"
    }

    override fun getClickAction(): AnAction =
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                onClick(thread)
            }
        }

    override fun isNavigateAction(): Boolean = true

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun equals(other: Any?): Boolean =
        other is InlineCommentGutterRenderer && other.thread.rootPHID == thread.rootPHID

    override fun hashCode(): Int = thread.rootPHID.hashCode()
}
