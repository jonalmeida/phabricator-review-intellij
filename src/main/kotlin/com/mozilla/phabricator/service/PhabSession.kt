package com.mozilla.phabricator.service

import com.mozilla.phabricator.conduit.ConduitClient

/**
 * Authenticated session: client wired to the user's token + identity returned by whoami(). Held by
 * [PhabSessionService] when signed in.
 */
data class PhabSession(val client: ConduitClient, val userPHID: String, val userName: String)
