package com.mozilla.phabricator.conduit.model

import kotlinx.serialization.Serializable

@Serializable
data class Project(val id: Int = 0, val phid: String, val fields: ProjectFields = ProjectFields())

@Serializable data class ProjectFields(val name: String = "", val slug: String? = null)
