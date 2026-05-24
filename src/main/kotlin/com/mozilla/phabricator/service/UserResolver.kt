package com.mozilla.phabricator.service

import com.mozilla.phabricator.conduit.ConduitClient
import com.mozilla.phabricator.conduit.PhidTypes
import com.mozilla.phabricator.conduit.model.Project
import com.mozilla.phabricator.conduit.model.User
import com.mozilla.phabricator.conduit.phidType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caches resolved user/project PHIDs and exposes display-name helpers used by the tree view. Port
 * of `src/phabricator/userResolver.ts`; the 10 ms batching window from the VSCode plugin is
 * intentionally omitted -- Phase-1 callers resolve in one shot per refresh, so explicit batching
 * would be premature complexity. Add it back when Phase 2/3 surface call sites that fan out.
 */
class UserResolver(private val client: ConduitClient) {

    private val users = mutableMapOf<String, User>()
    private val projects = mutableMapOf<String, Project>()
    private val lock = Mutex()

    suspend fun resolveMany(phids: Collection<String>) {
        val fresh = lock.withLock { phids.filter { it !in users && it !in projects }.toSet() }
        if (fresh.isEmpty()) return

        val userPhids = mutableListOf<String>()
        val projectPhids = mutableListOf<String>()
        for (phid in fresh) {
            when (phidType(phid)) {
                PhidTypes.USER -> userPhids += phid
                PhidTypes.PROJECT -> projectPhids += phid
                else -> userPhids += phid // unknown type: try users first
            }
        }

        val resolvedUsers =
            if (userPhids.isNotEmpty()) client.resolveUsers(userPhids) else emptyMap()
        val resolvedProjects =
            if (projectPhids.isNotEmpty()) client.resolveProjects(projectPhids) else emptyMap()

        lock.withLock {
            users.putAll(resolvedUsers)
            projects.putAll(resolvedProjects)
        }
    }

    fun displayName(phid: String): String {
        users[phid]?.let {
            return it.fields.username
        }
        projects[phid]?.let {
            val raw = it.fields.slug ?: it.fields.name
            return raw.removePrefix("#")
        }
        return phid
    }

    fun isProject(phid: String): Boolean = phid in projects

    fun getProject(phid: String): Project? = projects[phid]

    fun clear() {
        users.clear()
        projects.clear()
    }
}
