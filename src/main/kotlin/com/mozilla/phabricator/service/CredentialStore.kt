package com.mozilla.phabricator.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service

/**
 * Secure storage for the Conduit API token. Backed by IntelliJ's [PasswordSafe], which is the
 * IDE-equivalent of VSCode's SecretStorage and uses OS keychains where available.
 */
@Service(Service.Level.APP)
class CredentialStore {

    fun saveToken(token: String) {
        require(token.isNotBlank()) { "Conduit token must not be blank" }
        PasswordSafe.instance.set(ATTRIBUTES, Credentials(USER_KEY, token))
    }

    fun readToken(): String? {
        val credentials = PasswordSafe.instance.get(ATTRIBUTES) ?: return null
        val password = credentials.getPasswordAsString()?.takeIf { it.isNotEmpty() }
        return password
    }

    fun clearToken() {
        PasswordSafe.instance.set(ATTRIBUTES, null)
    }

    companion object {
        private const val USER_KEY = "default"

        // Service+key namespace tracks the VSCode plugin's
        // `mozilla.phabricator.conduitToken` so an existing user's mental
        // model carries over.
        private val ATTRIBUTES =
            CredentialAttributes(
                serviceName = generateServiceName("Mozilla Phabricator", "conduitToken"),
                userName = USER_KEY,
            )

        fun getInstance(): CredentialStore =
            ApplicationManager.getApplication().getService(CredentialStore::class.java)
    }
}
