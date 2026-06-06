package org.mozilla.phabricator.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Modal dialog for editing a single metadata field (title / summary / test plan).
 * - [multiline] = false renders a single-line [JBTextField] -- used for the title.
 * - [multiline] = true renders a multi-line [JBTextArea] wrapped in a scroll pane -- used for
 *   summary and test plan (which are Remarkup blocks of arbitrary length).
 *
 * Returns the trimmed new value on OK, null on Cancel. Empty / whitespace-only values are rejected
 * by [doValidate] so the dialog can't be OK'd into an empty title; for the multi-line variant,
 * empty is allowed (users can wipe a summary or test plan if they really want to).
 *
 * Mirrors the modal-composer pattern Phase 3's Accept-with-comment uses, but as a dedicated dialog
 * (DialogWrapper) instead of an inline composer because the surface area to edit -- especially the
 * summary -- benefits from its own focused window.
 */
internal class OverviewMetadataEditDialog(
    project: Project,
    private val fieldLabel: String,
    private val currentValue: String,
    private val multiline: Boolean,
) : DialogWrapper(project, true) {

    private val textField: JBTextField? = if (multiline) null else JBTextField(currentValue, 60)
    private val textArea: JBTextArea? =
        if (multiline)
            JBTextArea(currentValue, 12, 70).apply {
                lineWrap = true
                wrapStyleWord = true
            }
        else null

    init {
        title = "Edit $fieldLabel"
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(java.awt.BorderLayout())
        panel.border = JBUI.Borders.empty(8)
        if (multiline) {
            val scroll =
                ScrollPaneFactory.createScrollPane(textArea!!).apply {
                    preferredSize = Dimension(640, 320)
                }
            panel.add(scroll, java.awt.BorderLayout.CENTER)
        } else {
            panel.preferredSize = Dimension(560, 56)
            panel.add(textField!!, java.awt.BorderLayout.CENTER)
        }
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent? = textField ?: textArea

    override fun doValidate(): ValidationInfo? {
        if (multiline) return null
        val value = textField!!.text.trim()
        return if (value.isEmpty()) ValidationInfo("$fieldLabel cannot be empty", textField)
        else null
    }

    /** The new value entered by the user. Defined only after OK; returns null otherwise. */
    val newValue: String?
        get() = if (isOK) (textField?.text?.trim() ?: textArea!!.text) else null

    /** True if the user clicked OK *and* changed the value (Save on no-op is a soft cancel). */
    val isModified: Boolean
        get() = isOK && (newValue ?: "") != currentValue
}
