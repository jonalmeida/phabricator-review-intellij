package org.mozilla.phabricator.common

import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.MainCoroutineDispatcher

/**
 * Hand-rolled MainCoroutineDispatcher that hops onto the EDT via `Application.invokeLater`. Used to
 * land back on the EDT after a background Conduit call without pinning to a specific
 * kotlinx-coroutines-android / -swing API version (those vary across IntelliJ Platform versions).
 */
object EdtDispatcher : MainCoroutineDispatcher() {
    override val immediate: MainCoroutineDispatcher = this

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        ApplicationManager.getApplication().invokeLater(block)
    }
}
