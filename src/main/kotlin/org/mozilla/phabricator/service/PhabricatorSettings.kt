package org.mozilla.phabricator.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level settings, persisted to `<config>/options/MozillaPhabricator.xml`.
 *
 * Mirrors the VSCode plugin's `phabricator.*` configuration block in package.json. Only Phase-1
 * fields are present; Phase 2/3 will add landoBaseUrl, searchfoxRepo, etc. as the features that use
 * them land.
 */
@State(name = "MozillaPhabricator", storages = [Storage("MozillaPhabricator.xml")])
@Service(Service.Level.APP)
class PhabricatorSettings : PersistentStateComponent<PhabricatorSettings.State> {

    data class State(
        var baseUrl: String = DEFAULT_BASE_URL,
        var refreshIntervalSeconds: Int = DEFAULT_REFRESH_INTERVAL_SECONDS,
    )

    private var state: State = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    var baseUrl: String
        get() = state.baseUrl
        set(value) {
            state.baseUrl = sanitizeBaseUrl(value)
        }

    var refreshIntervalSeconds: Int
        get() = state.refreshIntervalSeconds
        set(value) {
            state.refreshIntervalSeconds = value.coerceAtLeast(MIN_REFRESH_INTERVAL_SECONDS)
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://phabricator.services.mozilla.com/api/"
        const val DEFAULT_REFRESH_INTERVAL_SECONDS = 900
        const val MIN_REFRESH_INTERVAL_SECONDS = 30

        fun getInstance(): PhabricatorSettings =
            ApplicationManager.getApplication().getService(PhabricatorSettings::class.java)

        /**
         * Trim, normalise trailing slash, and require the `/api/` suffix that Conduit accepts.
         * Returns the value unchanged if it cannot be coerced — the underlying ConduitTransport
         * will reject it loudly at use time.
         */
        fun sanitizeBaseUrl(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return DEFAULT_BASE_URL
            val withSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
            return if (withSlash.endsWith("/api/")) withSlash else "${withSlash}api/"
        }
    }
}
