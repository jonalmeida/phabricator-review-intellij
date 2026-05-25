package org.mozilla.phabricator.ui.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import org.mozilla.phabricator.service.PhabricatorSettings
import javax.swing.JComponent

/**
 * Settings UI shown at Settings → Tools → Mozilla Phabricator. Maps directly to the VSCode plugin's
 * `phabricator.*` configuration entries.
 */
class PhabricatorConfigurable : Configurable {

    private val settings = PhabricatorSettings.getInstance()

    // Working-copy buffer, written back to settings.state on apply()
    private var baseUrl: String = settings.baseUrl
    private var refreshIntervalSeconds: Int = settings.refreshIntervalSeconds

    override fun getDisplayName(): String = "Mozilla Phabricator"

    override fun createComponent(): JComponent {
        // Re-sync working copy when the panel is (re)opened.
        baseUrl = settings.baseUrl
        refreshIntervalSeconds = settings.refreshIntervalSeconds

        return panel {
            row("Conduit base URL:") {
                textField()
                    .columns(40)
                    .bindText({ baseUrl }, { baseUrl = it })
                    .comment(
                        "Must end with <code>/api/</code>. " +
                            "Default: <code>${PhabricatorSettings.DEFAULT_BASE_URL}</code>"
                    )
            }
            row("Refresh interval (seconds):") {
                intTextField(
                        range =
                            IntRange(PhabricatorSettings.MIN_REFRESH_INTERVAL_SECONDS, 24 * 60 * 60)
                    )
                    .columns(6)
                    .bindIntText({ refreshIntervalSeconds }, { refreshIntervalSeconds = it })
                    .comment("How often to poll for revision updates while the IDE is focused.")
            }
        }
    }

    override fun isModified(): Boolean =
        baseUrl != settings.baseUrl || refreshIntervalSeconds != settings.refreshIntervalSeconds

    override fun apply() {
        settings.baseUrl = baseUrl
        settings.refreshIntervalSeconds = refreshIntervalSeconds
        // Re-read sanitized values so the form reflects what got persisted.
        baseUrl = settings.baseUrl
        refreshIntervalSeconds = settings.refreshIntervalSeconds
    }

    override fun reset() {
        baseUrl = settings.baseUrl
        refreshIntervalSeconds = settings.refreshIntervalSeconds
    }
}
