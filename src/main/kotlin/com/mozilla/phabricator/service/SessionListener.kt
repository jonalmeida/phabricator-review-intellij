package com.mozilla.phabricator.service

import com.intellij.util.messages.Topic

/**
 * MessageBus topic published whenever the user signs in or out. Equivalent to the VSCode plugin's
 * `credentials.onDidChangeSession` event.
 */
interface SessionListener {
    fun signedIn(session: PhabSession) {}

    fun signedOut() {}

    companion object {
        @JvmField
        @Topic.AppLevel
        val TOPIC: Topic<SessionListener> =
            Topic.create("Mozilla Phabricator session", SessionListener::class.java)
    }
}
