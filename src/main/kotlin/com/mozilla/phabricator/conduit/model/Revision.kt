package com.mozilla.phabricator.conduit.model

import kotlinx.serialization.Serializable

@Serializable
data class Revision(val id: Int, val phid: String, val fields: RevisionFields = RevisionFields()) {
    val monogram: String
        get() = "D$id"
}

@Serializable
data class RevisionFields(
    val title: String = "",
    val uri: String = "",
    val authorPHID: String = "",
    val status: RevisionStatus = RevisionStatus(),
    val repositoryPHID: String? = null,
    val diffPHID: String = "",
    val summary: String = "",
    val testPlan: String = "",
    val isDraft: Boolean = false,
    val holdAsDraft: Boolean = false,
    val dateCreated: Long = 0,
    val dateModified: Long = 0,
)

@Serializable
data class RevisionStatus(
    val value: String = "",
    val name: String = "",
    val closed: Boolean = false,
)
