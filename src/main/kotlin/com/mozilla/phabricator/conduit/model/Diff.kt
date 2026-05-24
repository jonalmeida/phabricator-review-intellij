package com.mozilla.phabricator.conduit.model

import kotlinx.serialization.Serializable

@Serializable data class Diff(val id: Int, val phid: String, val fields: DiffFields = DiffFields())

@Serializable
data class DiffFields(
    val revisionPHID: String? = null,
    val authorPHID: String = "",
    val repositoryPHID: String? = null,
    val dateCreated: Long = 0,
    val dateModified: Long = 0,
)
