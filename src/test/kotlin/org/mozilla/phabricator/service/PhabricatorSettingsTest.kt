package org.mozilla.phabricator.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [PhabricatorSettings] helpers. The persistence layer is provided by the
 * IntelliJ platform and is exercised by manual e2e (runIde) and the live e2e in Commit 5+.
 */
class PhabricatorSettingsTest {

    @Test
    fun `sanitizeBaseUrl appends slash and api segment when missing`() {
        assertEquals(
            "https://phab.example/api/",
            PhabricatorSettings.sanitizeBaseUrl("https://phab.example"),
        )
        assertEquals(
            "https://phab.example/api/",
            PhabricatorSettings.sanitizeBaseUrl("https://phab.example/"),
        )
    }

    @Test
    fun `sanitizeBaseUrl leaves already-valid URL untouched`() {
        assertEquals(
            "https://phab.example/api/",
            PhabricatorSettings.sanitizeBaseUrl("https://phab.example/api/"),
        )
    }

    @Test
    fun `sanitizeBaseUrl trims surrounding whitespace`() {
        assertEquals(
            "https://phab.example/api/",
            PhabricatorSettings.sanitizeBaseUrl("  https://phab.example/api/  "),
        )
    }

    @Test
    fun `sanitizeBaseUrl falls back to default on empty input`() {
        assertEquals(PhabricatorSettings.DEFAULT_BASE_URL, PhabricatorSettings.sanitizeBaseUrl(""))
        assertEquals(
            PhabricatorSettings.DEFAULT_BASE_URL,
            PhabricatorSettings.sanitizeBaseUrl("   "),
        )
    }
}
