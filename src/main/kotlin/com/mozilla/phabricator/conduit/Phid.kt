package com.mozilla.phabricator.conduit

object PhidTypes {
    const val REVISION = "DREV"
    const val DIFF = "DIFF"
    const val USER = "USER"
    const val REPO = "REPO"
    const val PROJECT = "PROJ"
    const val BUILD_TARGET = "HMBT"
    const val BUILD_PLAN = "HMCP"
    const val XACT_DREV = "XACT"
    const val FILE = "FILE"
}

private val PHID_RE = Regex("""^PHID-([A-Z]+)-[A-Za-z0-9]+$""")

fun isPhid(value: Any?): Boolean = value is String && PHID_RE.matches(value)

fun phidType(phid: String): String? = PHID_RE.matchEntire(phid)?.groupValues?.get(1)
