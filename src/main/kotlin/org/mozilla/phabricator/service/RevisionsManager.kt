package org.mozilla.phabricator.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mozilla.phabricator.conduit.ConduitClient
import org.mozilla.phabricator.conduit.ConduitError
import org.mozilla.phabricator.conduit.RevisionAttachments
import org.mozilla.phabricator.conduit.RevisionConstraints
import org.mozilla.phabricator.conduit.collectList
import org.mozilla.phabricator.conduit.model.Revision

/**
 * Project-scoped manager for the four revision categories shown in the tool window. Ports
 * `src/phabricator/revisionsManager.ts`. Phase-1 surface only: the testing-tag directory, attention
 * count, and local-git matching are deferred until the features they support land.
 *
 * Lifecycle:
 * - Subscribes to [SessionListener.TOPIC] to clear caches on sign-out and rebuild membership on
 *   sign-in.
 * - Runs a polling loop on its own [CoroutineScope]; the loop honours an `isAppActive` flag toggled
 *   by [PhabricatorAppActivationListener] (in plugin.xml) so polling pauses when the IDE loses
 *   focus, matching VSCode's `onDidChangeWindowState` behavior.
 */
@Service(Service.Level.PROJECT)
class RevisionsManager(
    @Suppress("UnusedPrivateMember") private val project: Project,
    private val coroutineScope: CoroutineScope,
) {

    enum class CategoryKey(val label: String) {
        MINE("My Active"),
        REVIEWER("Needs My Review"),
        SUBSCRIBER("Subscribed"),
        CLOSED("Recently Closed"),
    }

    private val cache = mutableMapOf<CategoryKey, List<RevisionModel>>()
    private val byPhid = mutableMapOf<String, RevisionModel>()
    private val byId = mutableMapOf<Int, RevisionModel>()
    private val mutex = Mutex()

    @Volatile private var userResolver: UserResolver? = null

    @Volatile private var projectMembership: List<String> = emptyList()

    @Volatile private var pollJob: Job? = null

    init {
        ApplicationManager.getApplication()
            .messageBus
            .connect(coroutineScope.asDisposable())
            .subscribe(
                SessionListener.TOPIC,
                object : SessionListener {
                    override fun signedIn(session: PhabSession) {
                        coroutineScope.launch { onSignIn(session) }
                    }

                    override fun signedOut() {
                        coroutineScope.launch { onSignOut() }
                    }
                },
            )

        // If the session was restored before this service was constructed,
        // catch up.
        PhabSessionService.getInstance().session?.let { existing ->
            coroutineScope.launch { onSignIn(existing) }
        }
    }

    val session: PhabSession?
        get() = PhabSessionService.getInstance().session

    /** Forces the next [getRevisionsForCategory] call to re-fetch. */
    suspend fun refresh(category: CategoryKey? = null) {
        mutex.withLock { if (category == null) cache.clear() else cache.remove(category) }
        publishRefresh(category)
    }

    suspend fun getRevisionsForCategory(category: CategoryKey): List<RevisionModel> {
        mutex
            .withLock { cache[category] }
            ?.let {
                return it
            }

        val activeSession = session ?: return emptyList()
        val constraints = constraintsFor(category, activeSession.userPHID) ?: return emptyList()
        val limit = if (category == CategoryKey.CLOSED) CLOSED_LIMIT else DEFAULT_LIMIT

        val revisions =
            if (category == CategoryKey.SUBSCRIBER) {
                fetchSubscribed(activeSession, constraints, limit)
            } else {
                fetchRevisions(activeSession, constraints, limit)
            }

        val filteredSource =
            if (category == CategoryKey.REVIEWER) {
                revisions.filter { it.fields.authorPHID != activeSession.userPHID }
            } else {
                revisions
            }

        val models =
            mutex.withLock {
                val out =
                    filteredSource.map { revision ->
                        val existing = byPhid[revision.phid]
                        if (existing != null) {
                            existing.update(revision)
                            existing
                        } else {
                            val model = RevisionModel(revision, activeSession.client)
                            byPhid[revision.phid] = model
                            byId[revision.id] = model
                            model
                        }
                    }
                cache[category] = out
                out
            }
        return models
    }

    fun getCachedRevisionByPhid(phid: String): RevisionModel? = byPhid[phid]

    private suspend fun fetchRevisions(
        session: PhabSession,
        constraints: RevisionConstraints,
        limit: Int,
    ): List<Revision> =
        session.client
            .searchRevisions(
                constraints = constraints,
                attachments =
                    RevisionAttachments(reviewers = true, subscribers = true, projects = true),
                order = "updated",
                limit = limit,
            )
            .collectList(limit)

    private suspend fun fetchSubscribed(
        session: PhabSession,
        constraints: RevisionConstraints,
        limit: Int,
    ): List<Revision> {
        val primary = fetchRevisions(session, constraints, limit)
        if (primary.isNotEmpty()) return primary

        // Phabricator legacy fallback: Mozilla's instance only surfaces
        // subscribers via `differential.query`, not differential.revision.search.
        return try {
            val phids = session.client.querySubscribedRevisionPHIDs(session.userPHID, limit = limit)
            if (phids.isEmpty()) return emptyList()
            val orderIndex = phids.withIndex().associate { (i, phid) -> phid to i }
            fetchRevisions(session, RevisionConstraints(phids = phids), limit).sortedBy {
                orderIndex[it.phid] ?: 0
            }
        } catch (e: ConduitError) {
            LOG.warn("differential.query fallback failed for Subscribed: ${e.code}")
            primary
        }
    }

    private suspend fun onSignIn(session: PhabSession) {
        mutex.withLock {
            cache.clear()
            byPhid.clear()
            byId.clear()
        }
        userResolver = UserResolver(session.client)
        loadProjectMembership(session.client, session.userPHID)
        startPolling()
        publishRefresh(null)
    }

    private suspend fun onSignOut() {
        mutex.withLock {
            cache.clear()
            byPhid.clear()
            byId.clear()
        }
        userResolver = null
        projectMembership = emptyList()
        stopPolling()
        publishRefresh(null)
    }

    private suspend fun loadProjectMembership(client: ConduitClient, userPHID: String) {
        projectMembership =
            try {
                client.listProjectsForMember(userPHID).map { it.phid }
            } catch (e: ConduitError) {
                LOG.warn("listProjectsForMember failed: ${e.code}")
                emptyList()
            }
    }

    private fun constraintsFor(category: CategoryKey, userPHID: String): RevisionConstraints? =
        when (category) {
            CategoryKey.MINE ->
                RevisionConstraints(
                    authorPHIDs = listOf(userPHID),
                    statuses = ACTIVE_REVISION_STATUSES,
                )

            CategoryKey.REVIEWER ->
                RevisionConstraints(
                    reviewerPHIDs = listOf(userPHID) + projectMembership,
                    statuses = listOf("needs-review"),
                )

            CategoryKey.SUBSCRIBER ->
                RevisionConstraints(
                    subscribers = listOf(userPHID),
                    statuses = ACTIVE_REVISION_STATUSES,
                )

            CategoryKey.CLOSED ->
                RevisionConstraints(
                    authorPHIDs = listOf(userPHID),
                    statuses = listOf("published", "abandoned"),
                )
        }

    private fun startPolling() {
        stopPolling()
        val seconds = PhabricatorSettings.getInstance().refreshIntervalSeconds
        if (seconds <= 0) return
        pollJob =
            coroutineScope.launch {
                while (isActive) {
                    delay(seconds.seconds)
                    if (!PhabricatorAppActivationListener.isAppActive) continue
                    if (session == null) continue
                    runCatching { refresh() }
                        .onFailure { LOG.warn("Background refresh failed", it) }
                }
            }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun publishRefresh(category: CategoryKey?) {
        ApplicationManager.getApplication()
            .messageBus
            .syncPublisher(REFRESH_TOPIC)
            .revisionsChanged(category)
    }

    fun interface RefreshListener {
        fun revisionsChanged(category: CategoryKey?)
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        const val CLOSED_LIMIT = 25

        val ACTIVE_REVISION_STATUSES =
            listOf("needs-review", "accepted", "needs-revision", "changes-planned", "draft")

        @JvmField
        @Topic.AppLevel
        val REFRESH_TOPIC: Topic<RefreshListener> =
            Topic.create("Mozilla Phabricator revisions refresh", RefreshListener::class.java)

        private val LOG = logger<RevisionsManager>()

        fun getInstance(project: Project): RevisionsManager =
            project.getService(RevisionsManager::class.java)
    }
}

/**
 * Connect a [CoroutineScope] to a [com.intellij.openapi.Disposable] so subscribers wired to its
 * lifecycle are unregistered when the scope is cancelled.
 */
private fun CoroutineScope.asDisposable(): com.intellij.openapi.Disposable {
    val disposable = com.intellij.openapi.util.Disposer.newDisposable("RevisionsManager messages")
    coroutineContext[Job]?.invokeOnCompletion {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.util.Disposer.dispose(disposable)
        }
    }
    return disposable
}
