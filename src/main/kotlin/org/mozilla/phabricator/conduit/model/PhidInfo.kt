package org.mozilla.phabricator.conduit.model

/**
 * Generic PHID resolution payload returned by Phabricator's `phid.query` endpoint. Used by
 * [org.mozilla.phabricator.service.UserResolver] for PHID types not covered by `user.search` or
 * `project.search` -- in practice this is mostly applications
 * (`PHID-APPS-PhabricatorHarbormasterApplication` etc.) that appear as transaction authors when a
 * Phabricator bot acts on a revision.
 */
data class PhidInfo(
    val phid: String,
    /** Short display name, e.g. "Harbormaster". */
    val name: String,
    /** Long display name; often identical to [name] for applications. */
    val fullName: String,
    /** Human-readable PHID type (e.g. "Application", "User", "Project"). */
    val typeName: String,
    /** Canonical URL for the object, when the server exposes one. */
    val uri: String?,
)
