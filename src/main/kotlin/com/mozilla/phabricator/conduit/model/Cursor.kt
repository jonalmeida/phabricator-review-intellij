package com.mozilla.phabricator.conduit.model

import kotlinx.serialization.Serializable

@Serializable
data class ConduitCursor(
    val after: String? = null,
    val before: String? = null,
    val limit: Int? = null,
    val order: String? = null,
)

@Serializable
data class ConduitSearchResult<T>(
    val data: List<T> = emptyList(),
    val cursor: ConduitCursor = ConduitCursor(),
)
