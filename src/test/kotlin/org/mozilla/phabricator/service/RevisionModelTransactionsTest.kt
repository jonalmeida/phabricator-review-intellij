package org.mozilla.phabricator.service

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mozilla.phabricator.conduit.ConduitClient
import org.mozilla.phabricator.conduit.ConduitTransport
import org.mozilla.phabricator.conduit.FakeHttpTransport
import org.mozilla.phabricator.conduit.model.Revision
import org.mozilla.phabricator.conduit.model.RevisionFields
import org.mozilla.phabricator.conduit.model.RevisionStatus

/**
 * Cache-invalidation parity with
 * `phabricator-review-vscode/src/phabricator/revisionModel.ts:update`:
 * - Changeset cache invalidates only when `diffPHID` actually changes (new diff uploaded).
 * - Transaction cache invalidates only when `dateModified` advances (any new activity).
 *
 * Drives a real [ConduitClient] over a [FakeHttpTransport] so the call count we assert against is
 * the same wire-level count a production run would make.
 */
class RevisionModelTransactionsTest {

    private val emptyTransactionPage =
        """{"result":{"data":[],"cursor":{"after":null}},"error_code":null,"error_info":null}"""

    private fun rev(
        diffPHID: String = "PHID-DIFF-1",
        dateModified: Long = 100,
        phid: String = "PHID-DREV-1",
        id: Int = 1,
    ) =
        Revision(
            id = id,
            phid = phid,
            fields =
                RevisionFields(
                    title = "T",
                    diffPHID = diffPHID,
                    dateModified = dateModified,
                    status = RevisionStatus(value = "needs-review"),
                ),
        )

    private fun setup(): Pair<FakeHttpTransport, ConduitClient> {
        val transport = FakeHttpTransport()
        val client = ConduitClient(ConduitTransport(token = "t", transport = transport))
        return transport to client
    }

    private fun transactionSearchCalls(transport: FakeHttpTransport): Int =
        transport.calls.count { it.url.endsWith("transaction.search") }

    @Test
    fun `update with same diff and dateModified keeps the transaction cache`() = runBlocking {
        val (transport, client) = setup()
        transport.enqueueJson(body = emptyTransactionPage)
        val model = RevisionModel(rev(), client)

        model.getTransactions()
        assertEquals(1, transactionSearchCalls(transport))

        model.update(rev())
        model.getTransactions()
        assertEquals(1, transactionSearchCalls(transport), "no-op update must not invalidate")
    }

    @Test
    fun `update advancing dateModified invalidates the transaction cache`() = runBlocking {
        val (transport, client) = setup()
        transport.enqueueJson(body = emptyTransactionPage)
        transport.enqueueJson(body = emptyTransactionPage)
        val model = RevisionModel(rev(dateModified = 100), client)

        model.getTransactions()
        assertEquals(1, transactionSearchCalls(transport))

        model.update(rev(dateModified = 200))
        model.getTransactions()
        assertEquals(2, transactionSearchCalls(transport))
    }

    @Test
    fun `update with new diffPHID alone keeps the transaction cache`() = runBlocking {
        val (transport, client) = setup()
        transport.enqueueJson(body = emptyTransactionPage)
        val model = RevisionModel(rev(diffPHID = "PHID-DIFF-1"), client)

        model.getTransactions()
        assertEquals(1, transactionSearchCalls(transport))

        // New diff but same dateModified: changeset cache invalidates (not tested here),
        // transaction cache stays warm.
        model.update(rev(diffPHID = "PHID-DIFF-2", dateModified = 100))
        model.getTransactions()
        assertEquals(
            1,
            transactionSearchCalls(transport),
            "transaction cache must not invalidate just because the diff changed",
        )
    }

    @Test
    fun `invalidateTransactions forces the next getTransactions to refetch`() = runBlocking {
        val (transport, client) = setup()
        transport.enqueueJson(body = emptyTransactionPage)
        transport.enqueueJson(body = emptyTransactionPage)
        val model = RevisionModel(rev(), client)

        model.getTransactions()
        model.invalidateTransactions()
        model.getTransactions()

        assertEquals(2, transactionSearchCalls(transport))
    }
}
