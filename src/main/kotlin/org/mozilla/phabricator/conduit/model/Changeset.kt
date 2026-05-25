package org.mozilla.phabricator.conduit.model

import kotlinx.serialization.Serializable

/**
 * Phabricator file-change types from `differential.querydiffs`. 1=add, 2=change, 3=delete,
 * 4=moveAway, 5=copyAway, 6=moveHere, 7=copyHere, 8=multicopy.
 */
@Suppress("MagicNumber")
enum class ChangesetType(val value: Int) {
    ADD(1),
    CHANGE(2),
    DELETE(3),
    MOVE_AWAY(4),
    COPY_AWAY(5),
    MOVE_HERE(6),
    COPY_HERE(7),
    MULTICOPY(8);

    companion object {
        fun fromValue(value: Int): ChangesetType =
            entries.firstOrNull { it.value == value } ?: CHANGE
    }
}

/** 1=text, 2=image, 3=binary, 4=directory, 5=symlink, 6=deleted, 7=normal. */
@Suppress("MagicNumber")
enum class ChangesetFileType(val value: Int) {
    TEXT(1),
    IMAGE(2),
    BINARY(3),
    DIRECTORY(4),
    SYMLINK(5),
    DELETED(6),
    NORMAL(7);

    companion object {
        fun fromValue(value: Int): ChangesetFileType =
            entries.firstOrNull { it.value == value } ?: TEXT
    }
}

@Serializable
data class ChangesetHunk(
    val oldOffset: Int = 0,
    val oldLength: Int = 0,
    val newOffset: Int = 0,
    val newLength: Int = 0,
    val corpus: String = "",
)

data class Changeset(
    val id: Int,
    val oldPath: String?,
    val currentPath: String,
    val awayPaths: List<String>,
    val type: ChangesetType,
    val fileType: ChangesetFileType,
    val oldFileType: ChangesetFileType,
    val addLines: Int,
    val delLines: Int,
    val metadata: Map<String, String>,
    val hunks: List<ChangesetHunk>,
)

data class QueriedDiff(
    val id: Int,
    val phid: String?,
    val revisionPHID: String?,
    val repositoryPHID: String?,
    val sourceControlBaseRevision: String?,
    val dateCreated: Long?,
    val dateModified: Long?,
    val changes: List<Changeset>,
)
