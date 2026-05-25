package org.mozilla.phabricator.conduit

import org.mozilla.phabricator.conduit.model.Changeset
import org.mozilla.phabricator.conduit.model.ChangesetFileType
import org.mozilla.phabricator.conduit.model.ChangesetHunk
import org.mozilla.phabricator.conduit.model.ChangesetType
import org.mozilla.phabricator.conduit.model.ConduitSearchResult
import org.mozilla.phabricator.conduit.model.Diff
import org.mozilla.phabricator.conduit.model.Project
import org.mozilla.phabricator.conduit.model.QueriedDiff
import org.mozilla.phabricator.conduit.model.Revision
import org.mozilla.phabricator.conduit.model.User
import org.mozilla.phabricator.conduit.model.WhoAmI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Phase-1 read-only client over [ConduitTransport]. Ports the subset of
 * `phabricator-review-vscode/src/client/client.js` needed for browsing revisions and rendering
 * diffs. Write endpoints (comment, accept, createInline, createRevision, …) land in Phase 2/3.
 */
class ConduitClient(val transport: ConduitTransport) {

    constructor(
        token: String,
        baseUrl: String = ConduitTransport.DEFAULT_BASE_URL,
        httpTransport: HttpTransport = JdkHttpTransport(),
        userAgent: String = ConduitTransport.DEFAULT_USER_AGENT,
    ) : this(ConduitTransport(token, baseUrl, httpTransport, userAgent))

    val baseUrl: String
        get() = transport.baseUrl

    /** Low-level escape hatch — exposed so [org.mozilla.phabricator.service] tests can stub. */
    suspend fun call(method: String, args: JsonObject = JsonObject(emptyMap())): JsonElement =
        transport.call(method, args)

    suspend fun whoami(): WhoAmI =
        JSON.decodeFromJsonElement(WhoAmI.serializer(), call("user.whoami"))

    // ------------------------------------------------------------- revisions

    /**
     * Search differential revisions. Constraints/attachments are passed straight through — see
     * Phabricator docs for the full set. The result is a [Flow] that auto-pages.
     */
    fun searchRevisions(
        constraints: RevisionConstraints = RevisionConstraints(),
        attachments: RevisionAttachments = RevisionAttachments(),
        order: String? = null,
        limit: Int? = null,
    ): Flow<Revision> = paginate { after ->
        val args = buildJsonObject {
            put("constraints", constraints.toJson())
            put("attachments", attachments.toJson())
            order?.let { put("order", it) }
            limit?.let { put("limit", it) }
            after?.let { put("after", it) }
        }
        JSON.decodeFromJsonElement(
            ConduitSearchResult.serializer(Revision.serializer()),
            call("differential.revision.search", args),
        )
    }

    suspend fun getRevision(
        id: Int,
        attachments: RevisionAttachments = RevisionAttachments(),
    ): Revision? {
        val flow =
            searchRevisions(
                constraints = RevisionConstraints(ids = listOf(id)),
                attachments = attachments,
                limit = 1,
            )
        return flow.toList().firstOrNull()
    }

    suspend fun getRevisionByPhid(
        phid: String,
        attachments: RevisionAttachments = RevisionAttachments(),
    ): Revision? {
        val flow =
            searchRevisions(
                constraints = RevisionConstraints(phids = listOf(phid)),
                attachments = attachments,
                limit = 1,
            )
        return flow.toList().firstOrNull()
    }

    /**
     * Returns the de-duplicated list of revision PHIDs the user is subscribed to. Mirrors
     * [client.js#querySubscribedRevisionPHIDs].
     */
    suspend fun querySubscribedRevisionPHIDs(
        userPHID: String,
        status: String = "status-open",
        limit: Int? = null,
    ): List<String> {
        val args = buildJsonObject {
            putJsonArray("subscribers") { add(userPHID) }
            put("status", status)
            put("order", "order-modified")
            limit?.let { put("limit", it) }
        }
        val result = call("differential.query", args)
        val arr = (result as? JsonArray) ?: return emptyList()
        val seen = LinkedHashSet<String>()
        for (item in arr) {
            val phid = (item as? JsonObject)?.get("phid") as? JsonPrimitive ?: continue
            if (phid.isString && phid.content.isNotEmpty()) {
                seen += phid.content
            }
        }
        return seen.toList()
    }

    // ----------------------------------------------------------------- diffs

    fun searchDiffs(
        ids: List<Int>? = null,
        phids: List<String>? = null,
        revisionPHIDs: List<String>? = null,
    ): Flow<Diff> = paginate { after ->
        val args = buildJsonObject {
            putJsonObject("constraints") {
                ids?.let { putJsonArray("ids") { it.forEach { id -> add(id) } } }
                phids?.let { putJsonArray("phids") { it.forEach { p -> add(p) } } }
                revisionPHIDs?.let { putJsonArray("revisionPHIDs") { it.forEach { p -> add(p) } } }
            }
            after?.let { put("after", it) }
        }
        JSON.decodeFromJsonElement(
            ConduitSearchResult.serializer(Diff.serializer()),
            call("differential.diff.search", args),
        )
    }

    /**
     * Fetch full changesets for one or more diff ids. Mirrors [client.js#queryDiffs]. The Conduit
     * endpoint returns a map keyed by stringified diff id; we normalize to a Kotlin `Map<Int,
     * QueriedDiff>`.
     */
    suspend fun queryDiffs(diffIds: List<Int>): Map<Int, QueriedDiff> {
        if (diffIds.isEmpty()) return emptyMap()
        val args = buildJsonObject { putJsonArray("ids") { diffIds.forEach { add(it) } } }
        val result = call("differential.querydiffs", args) as? JsonObject ?: return emptyMap()
        val out = mutableMapOf<Int, QueriedDiff>()
        for ((_, raw) in result) {
            val obj = raw as? JsonObject ?: continue
            val id = obj.intOrZero("id")
            out[id] =
                QueriedDiff(
                    id = id,
                    phid = obj.optString("phid"),
                    revisionPHID = obj.optString("revisionPHID"),
                    repositoryPHID = obj.optString("repositoryPHID"),
                    sourceControlBaseRevision = obj.optString("sourceControlBaseRevision"),
                    dateCreated = obj.optLong("dateCreated"),
                    dateModified = obj.optLong("dateModified"),
                    changes =
                        (obj["changes"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonObject)?.toChangeset() }
                            .orEmpty(),
                )
        }
        return out
    }

    // ------------------------------------------------------------- remarkup

    /**
     * Render Remarkup source to HTML via Phabricator's `remarkup.process`. Returns an empty list if
     * [contents] is empty (no Conduit call made).
     */
    suspend fun processRemarkup(
        contents: List<String>,
        context: String = "differential",
    ): List<String> {
        if (contents.isEmpty()) return emptyList()
        val args = buildJsonObject {
            put("context", context)
            putJsonArray("contents") { contents.forEach { add(it) } }
        }
        val result = call("remarkup.process", args) as? JsonArray ?: return emptyList()
        return result.map { entry ->
            when (entry) {
                is JsonObject -> (entry["content"] as? JsonPrimitive)?.content ?: ""
                is JsonPrimitive -> entry.content
                else -> ""
            }
        }
    }

    // -------------------------------------------------- user / project resolution

    suspend fun resolveUsers(phids: List<String>): Map<String, User> {
        if (phids.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, User>()
        searchUsersByConstraint(
                buildJsonObject { putJsonArray("phids") { phids.toSet().forEach { add(it) } } }
            )
            .toList()
            .forEach { out[it.phid] = it }
        return out
    }

    suspend fun resolveProjects(phids: List<String>): Map<String, Project> {
        if (phids.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, Project>()
        searchProjectsByConstraint(
                buildJsonObject { putJsonArray("phids") { phids.toSet().forEach { add(it) } } }
            )
            .toList()
            .forEach { out[it.phid] = it }
        return out
    }

    /** Project memberships for a user — used to expand "Needs My Review". */
    suspend fun listProjectsForMember(userPHID: String): List<Project> =
        searchProjectsByConstraint(buildJsonObject { putJsonArray("members") { add(userPHID) } })
            .toList()

    private fun searchUsersByConstraint(constraints: JsonObject): Flow<User> = paginate { after ->
        val args = buildJsonObject {
            put("constraints", constraints)
            after?.let { put("after", it) }
        }
        JSON.decodeFromJsonElement(
            ConduitSearchResult.serializer(User.serializer()),
            call("user.search", args),
        )
    }

    private fun searchProjectsByConstraint(constraints: JsonObject): Flow<Project> =
        paginate { after ->
            val args = buildJsonObject {
                put("constraints", constraints)
                after?.let { put("after", it) }
            }
            JSON.decodeFromJsonElement(
                ConduitSearchResult.serializer(Project.serializer()),
                call("project.search", args),
            )
        }

    companion object {
        internal val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
    }
}

// -------------------------------------------------------- constraints helpers

data class RevisionConstraints(
    val ids: List<Int>? = null,
    val phids: List<String>? = null,
    val responsiblePHIDs: List<String>? = null,
    val authorPHIDs: List<String>? = null,
    val reviewerPHIDs: List<String>? = null,
    val subscribers: List<String>? = null,
    val statuses: List<String>? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        ids?.let { putJsonArray("ids") { it.forEach { v -> add(v) } } }
        phids?.let { putJsonArray("phids") { it.forEach { v -> add(v) } } }
        responsiblePHIDs?.let { putJsonArray("responsiblePHIDs") { it.forEach { v -> add(v) } } }
        authorPHIDs?.let { putJsonArray("authorPHIDs") { it.forEach { v -> add(v) } } }
        reviewerPHIDs?.let { putJsonArray("reviewerPHIDs") { it.forEach { v -> add(v) } } }
        subscribers?.let { putJsonArray("subscribers") { it.forEach { v -> add(v) } } }
        statuses?.let { putJsonArray("statuses") { it.forEach { v -> add(v) } } }
    }
}

data class RevisionAttachments(
    val reviewers: Boolean = false,
    val reviewersExtra: Boolean = false,
    val subscribers: Boolean = false,
    val projects: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        if (reviewers) put("reviewers", true)
        if (reviewersExtra) put("reviewers_extra", true)
        if (subscribers) put("subscribers", true)
        if (projects) put("projects", true)
    }
}

// -------------------------------------------------------- internal helpers

private fun JsonObject.optString(key: String): String? {
    val v = this[key] ?: return null
    if (v is JsonNull) return null
    val p = v as? JsonPrimitive ?: return null
    return p.content.takeIf { it.isNotEmpty() }
}

private fun JsonObject.intOrZero(key: String): Int {
    val v = (this[key] as? JsonPrimitive)?.content ?: return 0
    return v.toIntOrNull() ?: 0
}

private fun JsonObject.optLong(key: String): Long? {
    val v = (this[key] as? JsonPrimitive)?.content ?: return null
    return v.toLongOrNull()
}

private fun JsonObject.toChangeset(): Changeset {
    val id = intOrZero("id")
    val oldPath = optString("oldPath")
    val currentPath = optString("currentPath") ?: ""
    val type = ChangesetType.fromValue(intOrZero("type").coerceAtLeast(1))
    val fileType = ChangesetFileType.fromValue(intOrZero("fileType").coerceAtLeast(1))
    val oldFileType = ChangesetFileType.fromValue(intOrZero("oldFileType").coerceAtLeast(1))
    val addLines = intOrZero("addLines")
    val delLines = intOrZero("delLines")
    val awayPaths =
        (this["awayPaths"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
    val metadata =
        (this["metadata"] as? JsonObject)
            ?.mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() }
            .orEmpty()
    val hunks =
        (this["hunks"] as? JsonArray)
            ?.mapNotNull { entry -> (entry as? JsonObject)?.toHunk() }
            .orEmpty()
    return Changeset(
        id = id,
        oldPath = oldPath,
        currentPath = currentPath,
        awayPaths = awayPaths,
        type = type,
        fileType = fileType,
        oldFileType = oldFileType,
        addLines = addLines,
        delLines = delLines,
        metadata = metadata,
        hunks = hunks,
    )
}

private fun JsonObject.toHunk(): ChangesetHunk =
    ChangesetHunk(
        oldOffset = intOrZero("oldOffset"),
        oldLength = intOrZero("oldLength"),
        newOffset = intOrZero("newOffset"),
        newLength = intOrZero("newLength"),
        corpus = optString("corpus") ?: "",
    )
