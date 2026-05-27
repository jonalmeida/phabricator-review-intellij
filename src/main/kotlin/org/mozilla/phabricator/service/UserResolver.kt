package org.mozilla.phabricator.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mozilla.phabricator.conduit.ConduitClient
import org.mozilla.phabricator.conduit.PhidTypes
import org.mozilla.phabricator.conduit.model.PhidInfo
import org.mozilla.phabricator.conduit.model.Project
import org.mozilla.phabricator.conduit.model.User
import org.mozilla.phabricator.conduit.phidType

/**
 * Caches resolved PHIDs and exposes display-name helpers used by the tool-window tree, the diff
 * popup, and the overview panel. Port of `src/phabricator/userResolver.ts`; the 10 ms batching
 * window from the VSCode plugin is intentionally omitted -- Phase-1 callers resolve in one shot per
 * refresh, so explicit batching would be premature complexity.
 *
 * Three resolution paths:
 * - `PHID-USER-…` -> `user.search` (rich User DTO).
 * - `PHID-PROJ-…` -> `project.search` (rich Project DTO).
 * - Everything else -> `phid.query` (lightweight [PhidInfo]). This catches application PHIDs like
 *   `PHID-APPS-PhabricatorHarbormasterApplication` that appear as transaction authors when a
 *   Phabricator bot acts on a revision.
 */
class UserResolver(private val client: ConduitClient) {

    private val users = mutableMapOf<String, User>()
    private val projects = mutableMapOf<String, Project>()
    private val others = mutableMapOf<String, PhidInfo>()
    private val lock = Mutex()

    suspend fun resolveMany(phids: Collection<String>) {
        val fresh =
            lock.withLock {
                phids.filter { it !in users && it !in projects && it !in others }.toSet()
            }
        if (fresh.isEmpty()) return

        val userPhids = mutableListOf<String>()
        val projectPhids = mutableListOf<String>()
        val otherPhids = mutableListOf<String>()
        for (phid in fresh) {
            when (phidType(phid)) {
                PhidTypes.USER -> userPhids += phid
                PhidTypes.PROJECT -> projectPhids += phid
                else -> otherPhids += phid // applications, files, builds, ...
            }
        }

        val resolvedUsers =
            if (userPhids.isNotEmpty()) client.resolveUsers(userPhids) else emptyMap()
        val resolvedProjects =
            if (projectPhids.isNotEmpty()) client.resolveProjects(projectPhids) else emptyMap()
        // phid.query failure should not corrupt the rest of the resolve -- a missing
        // application name just means we fall back to the raw PHID for that one entry.
        val resolvedOthers =
            if (otherPhids.isNotEmpty()) {
                runCatching { client.queryPHIDs(otherPhids) }.getOrElse { emptyMap() }
            } else {
                emptyMap()
            }

        lock.withLock {
            users.putAll(resolvedUsers)
            projects.putAll(resolvedProjects)
            others.putAll(resolvedOthers)
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
        others[phid]?.let {
            // Prefer the short name ("Harbormaster") over the full name when both are present;
            // they are usually identical for applications but the short form is more compact.
            return it.name.ifEmpty { it.fullName }.ifEmpty { phid }
        }
        return phid
    }

    fun isProject(phid: String): Boolean = phid in projects

    fun getProject(phid: String): Project? = projects[phid]

    fun clear() {
        users.clear()
        projects.clear()
        others.clear()
    }
}
