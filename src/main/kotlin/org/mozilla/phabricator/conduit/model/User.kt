package org.mozilla.phabricator.conduit.model

import kotlinx.serialization.Serializable

@Serializable
data class User(val id: Int = 0, val phid: String, val fields: UserFields = UserFields())

@Serializable
data class UserFields(
    val username: String = "",
    val realName: String = "",
    val roles: List<String> = emptyList(),
)
