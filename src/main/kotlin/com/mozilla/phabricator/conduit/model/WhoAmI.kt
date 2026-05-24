package com.mozilla.phabricator.conduit.model

import kotlinx.serialization.Serializable

@Serializable
data class WhoAmI(
    val phid: String,
    val userName: String,
    val realName: String = "",
    val primaryEmail: String = "",
    val roles: List<String> = emptyList(),
)
