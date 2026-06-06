package org.mozilla.phabricator.editor

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Top-of-panel composer + action row.
 *
 * Layout: a persistent reply text area (6 rows soft-wrapped) above a button row of "Comment /
 * Accept / Request Changes / Abandon". The text area is shared across every action -- the body the
 * user types is sent as the comment payload for whichever button they click.
 *
 * Button enablement matches the VSCode plugin: Accept + Request Changes only when the viewer is a
 * reviewer and not the author; Abandon only when the viewer is the author; Comment is always
 * available when signed in.
 *
 * Destructive Abandon fires a confirm dialog before posting.
 */
internal object OverviewActionsToolbar {

    fun build(project: Project, data: OverviewData, scope: CoroutineScope): JPanel {
        val column =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }

        val composer =
            JBTextArea(6, 60).apply {
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(4)
            }
        val composerScroll =
            ScrollPaneFactory.createScrollPane(composer).apply {
                preferredSize = Dimension(720, 128)
                maximumSize = Dimension(Int.MAX_VALUE, 128)
                alignmentX = Component.LEFT_ALIGNMENT
            }

        val buttonRow =
            JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }

        val commentButton =
            button("Comment") {
                val body = composer.text.trim()
                if (body.isEmpty()) {
                    info("Comment body is empty")
                    return@button
                }
                run(scope, composer, listOf(it)) { data.model.comment(body) }
                    .also { _ -> notify("Comment posted on ${data.model.monogram}") }
            }
        val acceptButton =
            button("Accept") {
                    val body = composer.text.trim().ifEmpty { null }
                    run(scope, composer, listOf(it)) { data.model.accept(body) }
                        .also { _ -> notify("${data.model.monogram} accepted") }
                }
                .apply { isEnabled = data.isReviewer && !data.isAuthor }
        val requestChangesButton =
            button("Request Changes") {
                    val body = composer.text.trim()
                    if (body.isEmpty()) {
                        info("Request Changes requires a comment")
                        return@button
                    }
                    run(scope, composer, listOf(it)) { data.model.requestChanges(body) }
                        .also { _ -> notify("Requested changes on ${data.model.monogram}") }
                }
                .apply { isEnabled = data.isReviewer && !data.isAuthor }
        val abandonButton =
            button("Abandon") {
                    val confirmed =
                        Messages.showYesNoDialog(
                            project,
                            "Abandon ${data.model.monogram}? You can reclaim it later from the web UI.",
                            "Abandon Revision",
                            Messages.getWarningIcon(),
                        ) == Messages.YES
                    if (!confirmed) return@button
                    val body = composer.text.trim().ifEmpty { null }
                    run(scope, composer, listOf(it)) { data.model.abandon(body) }
                        .also { _ -> notify("${data.model.monogram} abandoned") }
                }
                .apply {
                    isEnabled = data.isAuthor
                    isVisible = data.isAuthor
                }

        // ----- Phase 4: the six remaining top-level actions.
        // Visibility lifted from vscode/src/phabricator/revisionOverview.ts:1314-1340 for
        // commandeer/resign; from Phabricator transaction semantics for the inferred four.
        // We use isVisible rather than just isEnabled so the toolbar doesn't show greyed-out
        // buttons the viewer can never act on -- matches the VSCode webview which only renders
        // buttons that match the current state.
        val status = data.model.statusValue
        val commandeerButton =
            button("Commandeer") {
                    val confirmed =
                        Messages.showYesNoDialog(
                            project,
                            "Take over ${data.model.monogram} from its author? " +
                                "You will become the new author; the previous author moves to reviewer.",
                            "Commandeer Revision",
                            Messages.getWarningIcon(),
                        ) == Messages.YES
                    if (!confirmed) return@button
                    val body = composer.text.trim().ifEmpty { null }
                    run(scope, composer, listOf(it)) { data.model.commandeer(body) }
                        .also { _ -> notify("${data.model.monogram} commandeered") }
                }
                .apply { isVisible = !data.isAuthor }

        val resignButton =
            button("Resign") {
                    val confirmed =
                        Messages.showYesNoDialog(
                            project,
                            "Resign from reviewing ${data.model.monogram}?",
                            "Resign As Reviewer",
                            Messages.getQuestionIcon(),
                        ) == Messages.YES
                    if (!confirmed) return@button
                    val body = composer.text.trim().ifEmpty { null }
                    run(scope, composer, listOf(it)) { data.model.resign(body) }
                        .also { _ -> notify("Resigned from ${data.model.monogram}") }
                }
                .apply { isVisible = !data.isAuthor && data.isReviewer }

        val reclaimButton =
            button("Reclaim") {
                    val confirmed =
                        Messages.showYesNoDialog(
                            project,
                            "Reclaim ${data.model.monogram}? It will move back to needs-review.",
                            "Reclaim Revision",
                            Messages.getQuestionIcon(),
                        ) == Messages.YES
                    if (!confirmed) return@button
                    val body = composer.text.trim().ifEmpty { null }
                    run(scope, composer, listOf(it)) { data.model.reclaim(body) }
                        .also { _ -> notify("${data.model.monogram} reclaimed") }
                }
                .apply { isVisible = data.isAuthor && status == "abandoned" }

        val reopenButton =
            button("Reopen") {
                    val confirmed =
                        Messages.showYesNoDialog(
                            project,
                            "Reopen ${data.model.monogram}?",
                            "Reopen Revision",
                            Messages.getQuestionIcon(),
                        ) == Messages.YES
                    if (!confirmed) return@button
                    val body = composer.text.trim().ifEmpty { null }
                    run(scope, composer, listOf(it)) { data.model.reopen(body) }
                        .also { _ -> notify("${data.model.monogram} reopened") }
                }
                .apply { isVisible = status == "published" }

        val planChangesButton =
            button("Plan Changes") {
                    // Optional-body composer path (same shape as Accept) -- no confirm dialog,
                    // the action is non-destructive and easily reversible via Request Review.
                    val body = composer.text.trim().ifEmpty { null }
                    run(scope, composer, listOf(it)) { data.model.planChanges(body) }
                        .also { _ -> notify("${data.model.monogram} moved to changes planned") }
                }
                .apply { isVisible = data.isAuthor && status == "needs-review" }

        val requestReviewButton =
            button("Request Review") {
                    val body = composer.text.trim().ifEmpty { null }
                    run(scope, composer, listOf(it)) { data.model.requestReview(body) }
                        .also { _ -> notify("Re-requested review on ${data.model.monogram}") }
                }
                .apply {
                    isVisible =
                        data.isAuthor &&
                            status in setOf("needs-revision", "changes-planned", "draft")
                }

        buttonRow.add(commentButton)
        buttonRow.add(acceptButton)
        buttonRow.add(requestChangesButton)
        buttonRow.add(planChangesButton)
        buttonRow.add(requestReviewButton)
        buttonRow.add(commandeerButton)
        buttonRow.add(resignButton)
        buttonRow.add(reclaimButton)
        buttonRow.add(reopenButton)
        buttonRow.add(abandonButton)

        column.add(JBLabel("Reply (used as the comment body for the action you choose):"))
        column.add(composerScroll)
        column.add(buttonRow.apply { border = JBUI.Borders.emptyTop(4) })

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 0, 12, 0)
            add(column, BorderLayout.CENTER)
        }
    }

    private fun button(label: String, onClick: (JButton) -> Unit): JButton =
        JButton(label).apply { addActionListener { onClick(this) } }

    /**
     * Runs a suspend block on [scope], temporarily disabling the supplied buttons + the composer
     * for visual feedback, re-enabling them on completion. On success the composer is cleared; on
     * failure the body is preserved so the user can retry without re-typing.
     */
    private fun run(
        scope: CoroutineScope,
        composer: JBTextArea,
        toDisable: List<JButton>,
        block: suspend () -> Unit,
    ) {
        toDisable.forEach { it.isEnabled = false }
        composer.isEditable = false
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    ApplicationManager.getApplication().invokeLater {
                        composer.text = ""
                        composer.isEditable = true
                        toDisable.forEach { it.isEnabled = true }
                    }
                }
                .onFailure { err ->
                    ApplicationManager.getApplication().invokeLater {
                        composer.isEditable = true
                        toDisable.forEach { it.isEnabled = true }
                        error("Action failed: ${err.message ?: err.javaClass.simpleName}")
                    }
                }
        }
    }

    private fun info(message: String) = balloon(message, NotificationType.INFORMATION)

    private fun error(message: String) = balloon(message, NotificationType.ERROR)

    private fun notify(message: String) = balloon(message, NotificationType.INFORMATION)

    private fun balloon(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Mozilla Phabricator")
            .createNotification(message, type)
            .notify(null)
    }
}
