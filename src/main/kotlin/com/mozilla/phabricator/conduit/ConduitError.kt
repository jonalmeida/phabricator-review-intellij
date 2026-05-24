package com.mozilla.phabricator.conduit

class ConduitError(
    val code: String?,
    val info: String?,
    val method: String,
    val httpStatus: Int? = null,
) : RuntimeException(buildMessage(method, code, info))

private fun buildMessage(method: String, code: String?, info: String?): String {
    val codePart = code ?: "error"
    return if (info != null) "$method: $codePart — $info" else "$method: $codePart"
}
