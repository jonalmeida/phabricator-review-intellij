package com.mozilla.phabricator.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.mozilla.phabricator.conduit.ConduitClient
import com.mozilla.phabricator.conduit.ConduitError
import kotlinx.coroutines.CoroutineScope

/**
 * Holds the authenticated [PhabSession] (if any) and exposes sign-in / sign-out flows. Equivalent
 * to the VSCode plugin's `CredentialStore` façade in src/auth/credentialStore.ts — the
 * secret-storage half here is delegated to [CredentialStore].
 */
@Service(Service.Level.APP)
class PhabSessionService(val coroutineScope: CoroutineScope) {

    @Volatile private var current: PhabSession? = null

    val session: PhabSession?
        get() = current

    val isSignedIn: Boolean
        get() = current != null

    /**
     * Sign in with a Conduit token. Verifies the token by calling `user.whoami`; on success the
     * token is persisted to [CredentialStore] and the session is published on the MessageBus.
     *
     * @throws ConduitError if the token is rejected.
     */
    suspend fun signIn(token: String) {
        val client =
            ConduitClient(token = token, baseUrl = PhabricatorSettings.getInstance().baseUrl)
        val who = client.whoami()
        CredentialStore.getInstance().saveToken(token)
        val newSession = PhabSession(client, who.phid, who.userName)
        current = newSession
        publish { signedIn(newSession) }
    }

    fun signOut() {
        CredentialStore.getInstance().clearToken()
        current = null
        publish { signedOut() }
    }

    /**
     * On IDE startup, if a token is stored, re-establish the session in the background. Failures
     * are logged but not surfaced — the user will see the signed-out UI and can sign in again.
     * Returns the new session, or null if no token was stored / it was rejected.
     */
    suspend fun restore(): PhabSession? {
        if (current != null) return current
        val token = CredentialStore.getInstance().readToken() ?: return null
        return try {
            signIn(token)
            current
        } catch (e: ConduitError) {
            LOG.warn("Stored Conduit token was rejected (${e.code}); signing out.")
            CredentialStore.getInstance().clearToken()
            null
        }
    }

    private fun publish(block: SessionListener.() -> Unit) {
        ApplicationManager.getApplication().messageBus.syncPublisher(SessionListener.TOPIC).block()
    }

    companion object {
        private val LOG = logger<PhabSessionService>()

        fun getInstance(): PhabSessionService =
            ApplicationManager.getApplication().getService(PhabSessionService::class.java)
    }
}
