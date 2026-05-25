package org.mozilla.phabricator.conduit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Activity entry on a Phabricator object (revision). Mirrors `types.js` Transaction shape.
 *
 * `fields` shape varies per transaction `type`; we keep it as a raw [JsonObject] and decode
 * per-type via helpers (e.g. [InlineCommentFields.from]).
 */
@Serializable
data class Transaction(
    val id: String = "",
    val phid: String = "",
    val type: String = "",
    val authorPHID: String = "",
    val objectPHID: String = "",
    val dateCreated: Long = 0,
    val dateModified: Long = 0,
    val groupID: String? = null,
    val fields: JsonObject = JsonObject(emptyMap()),
    val comments: List<TransactionComment> = emptyList(),
)

@Serializable
data class TransactionComment(
    val phid: String = "",
    val version: Int = 0,
    val authorPHID: String = "",
    val dateCreated: Long = 0,
    val dateModified: Long = 0,
    val removed: Boolean = false,
    val content: TransactionCommentContent = TransactionCommentContent(),
)

@Serializable data class TransactionCommentContent(val raw: String = "")

/**
 * Anchor fields carried by inline-comment transactions. Mirrors `types.js` InlineCommentFields and
 * the extraction logic in `revisionCommentController.ts:isInlineByAnchor`.
 *
 * Server versions vary: some send `diffPHID` directly, others nest it under `diff.phid`. [from]
 * accepts both.
 */
data class InlineCommentFields(
    val diffPHID: String,
    val path: String,
    val line: Int,
    val length: Int,
    val isNewFile: Boolean,
    val replyToCommentPHID: String?,
    val isDone: Boolean,
) {
    companion object {
        fun from(fields: JsonObject): InlineCommentFields? {
            val path = fields.stringOrNull("path") ?: return null
            val diffPHID = inlineDiffPHID(fields) ?: return null
            val line = fields.intOrNull("line") ?: return null
            val length = fields.intOrNull("length") ?: 1
            val isNewFile = fields.boolOrNull("isNewFile") ?: false
            val replyTo = fields.stringOrNull("replyToCommentPHID")
            val isDone = fields.boolOrNull("isDone") ?: false
            return InlineCommentFields(
                diffPHID = diffPHID,
                path = path,
                line = line,
                length = length,
                isNewFile = isNewFile,
                replyToCommentPHID = replyTo,
                isDone = isDone,
            )
        }

        private fun inlineDiffPHID(fields: JsonObject): String? {
            fields.stringOrNull("diffPHID")?.let {
                return it
            }
            val diff = fields["diff"] as? JsonObject ?: return null
            return diff.stringOrNull("phid")
        }
    }
}

/**
 * Result of `differential.revision.edit`. Phabricator returns `{ object, transactions }`; `object`
 * is either a PHID string or a richer object depending on instance. We keep both fields opaque
 * (callers in Phase 2/3 only care about success).
 */
@Serializable
data class EditResult(
    @SerialName("object") val objectRef: JsonElement = JsonNull,
    val transactions: List<JsonElement> = emptyList(),
)

private fun JsonObject.stringOrNull(key: String): String? {
    val v = this[key] ?: return null
    if (v is JsonNull) return null
    val prim = v as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return prim.content.takeIf { it.isNotEmpty() }
}

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
