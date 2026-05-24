package com.mozilla.phabricator.ui.auth

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPasswordField

/**
 * Dialog that captures the user's Conduit API token. Returns the entered token via [token] when
 * [showAndGet] is true.
 */
class SignInDialog(project: Project?, private val conduitTokenUrl: String) :
    DialogWrapper(project, true) {

    private val tokenField = JPasswordField(40)

    val token: String
        get() = String(tokenField.password).trim()

    init {
        title = "Sign in to Mozilla Phabricator"
        setOKButtonText("Sign In")
        init()
    }

    override fun getPreferredFocusedComponent(): JComponent = tokenField

    override fun createCenterPanel(): JComponent =
        panel {
                row {
                    cell(
                        HyperlinkLabel("Get a Conduit API token").apply {
                            addHyperlinkListener { BrowserUtil.browse(conduitTokenUrl) }
                        }
                    )
                }
                row("API token:") { cell(tokenField) }
                    .comment(
                        "Paste the api-... token from your Phabricator settings. " +
                            "Stored securely in the IDE password safe."
                    )
            }
            .apply { border = JBUI.Borders.empty(8, 8, 0, 8) }

    override fun doValidate(): ValidationInfo? {
        if (token.isEmpty()) {
            return ValidationInfo("Token is required", tokenField)
        }
        if (!token.startsWith("api-") && !token.startsWith("cli-")) {
            return ValidationInfo("Expected a token starting with api- or cli-", tokenField)
        }
        return null
    }
}
