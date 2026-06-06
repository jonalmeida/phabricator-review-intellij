package org.mozilla.phabricator.conduit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
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
import org.mozilla.phabricator.conduit.model.Changeset
import org.mozilla.phabricator.conduit.model.ChangesetFileType
import org.mozilla.phabricator.conduit.model.ChangesetHunk
import org.mozilla.phabricator.conduit.model.ChangesetType
import org.mozilla.phabricator.conduit.model.ConduitSearchResult
import org.mozilla.phabricator.conduit.model.Diff
import org.mozilla.phabricator.conduit.model.Edge
import org.mozilla.phabricator.conduit.model.EditResult
import org.mozilla.phabricator.conduit.model.PhidInfo
import org.mozilla.phabricator.conduit.model.Project
import org.mozilla.phabricator.conduit.model.QueriedDiff
import org.mozilla.phabricator.conduit.model.Revision
import org.mozilla.phabricator.conduit.model.Transaction
import org.mozilla.phabricator.conduit.model.User
import org.mozilla.phabricator.conduit.model.WhoAmI

/**
 * Client over [ConduitTransport]. Ports the subset of
 * `phabricator-review-vscode/src/client/client.js` needed by Phases 1-3:
 * - Phase 1: revision / diff / changeset browsing (whoami, searchRevisions, getRevision*,
 *   querySubscribedRevisionPHIDs, searchDiffs, queryDiffs, processRemarkup, resolveUsers /
 *   resolveProjects / listProjectsForMember).
 * - Phase 2: inline-comment read/write (searchTransactions, createInline, deleteInline,
 *   markInlineDone, publishDrafts, editRevision).
 * - Phase 3: top-level review actions (comment, accept, requestChanges, abandon) and stack
 *   discovery via searchEdges.
 *
 * createRevision / updateRevision (submit-from-commit flow) land in Phase 4.
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

    // ------------------------------------------------------- transactions / inlines

    /**
     * Paginated `transaction.search` for an object (revision PHID or monogram). Mirrors
     * [client.js#searchTransactions]. Inline comments arrive as transactions whose `fields` carry
     * `path`/`line`/`diffPHID` — see `InlineCommentFields.from` for the anchor extractor.
     */
    fun searchTransactions(objectIdentifier: String): Flow<Transaction> = paginate { after ->
        val args = buildJsonObject {
            put("objectIdentifier", objectIdentifier)
            after?.let { put("after", it) }
        }
        JSON.decodeFromJsonElement(
            ConduitSearchResult.serializer(Transaction.serializer()),
            call("transaction.search", args),
        )
    }

    /**
     * Create a draft inline comment via the legacy `differential.createinline` endpoint.
     * Phabricator stores it as a draft visible only to the author; it gets promoted to a published
     * inline the next time the same user fires a revision-level transaction (comment/accept/reject)
     * via `differential.revision.edit` — see [publishDrafts].
     *
     * Phabricator's `lineLength` is "additional lines after the first", so a 1-line comment uses
     * `lineLength=0`, a 3-line comment uses `lineLength=2`. We translate [length] (number of lines,
     * default 1) to that form to match `client.js#createInline`.
     *
     * **Threading caveat:** the [replyToCommentPHID] parameter is *accepted on the Kotlin API* for
     * forwards compatibility but is **not** sent over the wire. Mozilla's Phabricator instance
     * rejects this parameter with `API Method "differential.createinline" does not define these
     * parameters: 'replyToCommentPHID'` (the upstream JS plugin sends it too but apparently has not
     * been live-tested against this server). Until the right Phorge-side mechanism for threading is
     * identified, replies post as new top-level inlines on the same line.
     *
     * @return The PHID of the newly created draft comment, or the empty string if the server
     *   response is unexpected.
     */
    suspend fun createInline(
        diffId: Int,
        path: String,
        line: Int,
        isNewFile: Boolean,
        content: String,
        length: Int = 1,
        @Suppress("UNUSED_PARAMETER") replyToCommentPHID: String? = null,
    ): String {
        val lineLength = maxOf(0, length - 1)
        val args = buildJsonObject {
            put("diffID", diffId)
            put("filePath", path)
            put("lineNumber", line)
            put("lineLength", lineLength)
            put("isNewFile", isNewFile)
            put("content", content)
            // replyToCommentPHID intentionally omitted; see kdoc.
        }
        val result = call("differential.createinline", args) as? JsonObject ?: return ""
        return result.optString("phid") ?: result.optString("id") ?: ""
    }

    /** Delete a draft inline by PHID. Only works on drafts owned by the authenticated user. */
    suspend fun deleteInline(phid: String) {
        val args = buildJsonObject { put("phid", phid) }
        call("differential.deleteinline", args)
    }

    /**
     * Toggle the `isDone` state on one or more inline comments via an `inline.done` /
     * `inline.undone` transaction on `differential.revision.edit`. Mirrors
     * [client.js#markInlineDone].
     */
    suspend fun markInlineDone(
        revisionPHID: String,
        commentPHIDs: List<String>,
        done: Boolean = true,
    ): EditResult =
        editRevision(
            objectIdentifier = revisionPHID,
            transactions =
                listOf(
                    buildJsonObject {
                        put("type", if (done) "inline.done" else "inline.undone")
                        putJsonArray("value") { commentPHIDs.forEach { add(it) } }
                    }
                ),
        )

    /**
     * Publish the current user's pending draft inlines on a revision. Phabricator promotes drafts
     * to published comments whenever the author fires any revision-level transaction; we use an
     * empty-string `comment` transaction (the lightest no-op) to trigger that promotion without
     * adding a top-level comment of our own.
     */
    suspend fun publishDrafts(revisionPHID: String): EditResult =
        editRevision(
            objectIdentifier = revisionPHID,
            transactions =
                listOf(
                    buildJsonObject {
                        put("type", "comment")
                        put("value", "")
                    }
                ),
        )

    // ---------------------------------------------------- top-level review actions

    /**
     * Post a top-level (non-inline) comment on a revision. Equivalent of [client.js#comment]: a
     * single `comment` transaction with the body as value.
     */
    suspend fun comment(revisionPHID: String, body: String): EditResult =
        editRevision(
            objectIdentifier = revisionPHID,
            transactions =
                listOf(
                    buildJsonObject {
                        put("type", "comment")
                        put("value", body)
                    }
                ),
        )

    /**
     * Accept the revision. If [body] is non-null, also posts a comment in the same edit so the
     * approval carries reviewer text. Mirrors [client.js#accept] — transaction shape is
     * `[{type:"accept", value:true}, {type:"comment", value:body}]` (the comment entry is omitted
     * when body is null or empty).
     */
    suspend fun accept(revisionPHID: String, body: String? = null): EditResult =
        editRevision(
            objectIdentifier = revisionPHID,
            transactions = actionTransactions(action = "accept", body = body),
        )

    /**
     * Request changes on a revision. Same shape as [accept] but with `type:"reject"`. Mirrors
     * [client.js#requestChanges].
     */
    suspend fun requestChanges(revisionPHID: String, body: String? = null): EditResult =
        editRevision(
            objectIdentifier = revisionPHID,
            transactions = actionTransactions(action = "reject", body = body),
        )

    /**
     * Abandon your own revision. Optional body posts as a follow-up comment. Mirrors
     * [client.js#abandon].
     */
    suspend fun abandon(revisionPHID: String, body: String? = null): EditResult =
        editRevision(
            objectIdentifier = revisionPHID,
            transactions = actionTransactions(action = "abandon", body = body),
        )

    /**
     * Take ownership of someone else's revision. Mirrors [client.js#commandeer] — single
     * `commandeer` transaction with `value: true`. The viewer becomes the new author; the previous
     * author moves to reviewer.
     */
    suspend fun commandeer(revisionPHID: String, body: String? = null): EditResult =
        editRevision(
            objectIdentifier = revisionPHID,
            transactions = actionTransactions(action = "commandeer", body = body),
        )

    /**
     * Remove yourself from the reviewer list. Mirrors [client.js#resign] — single `resign`
     * transaction with `value: true`. Phabricator allows non-authors to resign even when they're
     * blocking reviewers.
     */
    suspend fun resign(revisionPHID: String, body: String? = null): EditResult =
        editRevision(
            objectIdentifier = revisionPHID,
            transactions = actionTransactions(action = "resign", body = body),
        )

    /**
     * Reclaim an abandoned revision the viewer authored. Re-opens it into the active workflow.
     *
     * **Not in VSCode source.** The transaction type `reclaim` is documented only in
     * `vscode/src/phabricator/txLabels.ts:21-35`. The wire shape `{type: "reclaim", value: true}`
     * is inferred from Phabricator's transaction-naming convention and pinned by
     * `Phase4ActionsTest`.
     */
    suspend fun reclaim(revisionPHID: String, body: String? = null): EditResult =
        editRevision(
            objectIdentifier = revisionPHID,
            transactions = actionTransactions(action = "reclaim", body = body),
        )

    /**
     * Reopen a closed (published) revision. Same caveat as [reclaim]: inferred from txLabels.ts and
     * pinned by `Phase4ActionsTest`.
     */
    suspend fun reopen(revisionPHID: String, body: String? = null): EditResult =
        editRevision(
            objectIdentifier = revisionPHID,
            transactions = actionTransactions(action = "reopen", body = body),
        )

    /**
     * Move a revision the viewer authored into the "changes planned" state. Author-only action,
     * used to signal "I know this needs work, hold off reviewing". Wire shape inferred from
     * txLabels.ts and pinned by `Phase4ActionsTest`.
     */
    suspend fun planChanges(revisionPHID: String, body: String? = null): EditResult =
        editRevision(
            objectIdentifier = revisionPHID,
            transactions = actionTransactions(action = "plan-changes", body = body),
        )

    /**
     * Re-request review on a revision the viewer authored (after planning changes, addressing
     * requested changes, or moving out of draft). Wire shape inferred from txLabels.ts and pinned
     * by `Phase4ActionsTest`.
     */
    suspend fun requestReview(revisionPHID: String, body: String? = null): EditResult =
        editRevision(
            objectIdentifier = revisionPHID,
            transactions = actionTransactions(action = "request-review", body = body),
        )

    private fun actionTransactions(action: String, body: String?): List<JsonObject> {
        val head = buildJsonObject {
            put("type", action)
            put("value", true)
        }
        if (body.isNullOrEmpty()) return listOf(head)
        return listOf(
            head,
            buildJsonObject {
                put("type", "comment")
                put("value", body)
            },
        )
    }

    // -------------------------------------------------------------- edges (stack)

    /**
     * Walk the revision dependency graph for stack navigation. Mirrors [client.js#searchEdges]:
     * accepts `sourcePHIDs` + `types` and returns a flat list of [Edge] entries. Non-paginated to
     * match the JS client (Mozilla's instance pages the underlying endpoint but the JS plugin never
     * bothers; if we ever need pagination, [Pagination.paginate] is the swap-in).
     */
    suspend fun searchEdges(sourcePHIDs: List<String>, types: List<String>): List<Edge> {
        if (sourcePHIDs.isEmpty() || types.isEmpty()) return emptyList()
        val args = buildJsonObject {
            putJsonArray("sourcePHIDs") { sourcePHIDs.forEach { add(it) } }
            putJsonArray("types") { types.forEach { add(it) } }
        }
        val result = call("edge.search", args) as? JsonObject ?: return emptyList()
        val data = result["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val src = obj.optString("sourcePHID") ?: return@mapNotNull null
            val tgt = obj.optString("destinationPHID") ?: return@mapNotNull null
            val type = obj.optString("edgeType") ?: return@mapNotNull null
            Edge(source = src, target = tgt, type = type)
        }
    }

    // -------------------------------------------------------------- edit primitive

    /** Low-level edit endpoint. Mirrors [client.js#editRevision]. */
    suspend fun editRevision(objectIdentifier: String, transactions: List<JsonObject>): EditResult {
        val args = buildJsonObject {
            put("objectIdentifier", objectIdentifier)
            putJsonArray("transactions") { transactions.forEach { add(it) } }
        }
        return JSON.decodeFromJsonElement(
            EditResult.serializer(),
            call("differential.revision.edit", args),
        )
    }

    // ------------------------------------------------------------- phid.query

    /**
     * Generic PHID lookup. Returns a map keyed by PHID with display metadata for every PHID the
     * server recognises. Useful for resolving PHID types `user.search` / `project.search` do not
     * cover -- notably application PHIDs like `PHID-APPS-PhabricatorHarbormasterApplication` which
     * can appear as transaction authors when a Phabricator bot acts on a revision.
     */
    suspend fun queryPHIDs(phids: List<String>): Map<String, PhidInfo> {
        if (phids.isEmpty()) return emptyMap()
        val args = buildJsonObject { putJsonArray("phids") { phids.forEach { add(it) } } }
        val result = call("phid.query", args) as? JsonObject ?: return emptyMap()
        val out = mutableMapOf<String, PhidInfo>()
        for ((phid, entry) in result) {
            val obj = entry as? JsonObject ?: continue
            out[phid] =
                PhidInfo(
                    phid = phid,
                    name = obj.optString("name") ?: "",
                    fullName = obj.optString("fullName") ?: "",
                    typeName = obj.optString("typeName") ?: "",
                    uri = obj.optString("uri"),
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

    /**
     * Prefix-search active users by display name / username, capped at [limit] results. Wire shape
     * mirrors [client.js#searchUsers] -- `user.search` with constraints `{nameLike: query,
     * isDisabled: false}`. Returns an empty list for a blank query so a debounced UI picker can
     * issue the call eagerly without worrying about firing zero-length queries.
     */
    suspend fun searchUsersByName(query: String, limit: Int = 8): List<User> {
        if (query.isBlank()) return emptyList()
        val constraints = buildJsonObject {
            put("nameLike", query)
            put("isDisabled", false)
        }
        return searchUsersByConstraint(constraints).take(limit).toList()
    }

    /**
     * Prefix-search projects by name, capped at [limit] results. Mozilla's Phabricator uses `name`
     * as a prefix-match constraint on `project.search`. Empty query short-circuits like
     * [searchUsersByName].
     */
    suspend fun searchProjectsByName(query: String, limit: Int = 8): List<Project> {
        if (query.isBlank()) return emptyList()
        val constraints = buildJsonObject { put("name", query) }
        return searchProjectsByConstraint(constraints).take(limit).toList()
    }

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
