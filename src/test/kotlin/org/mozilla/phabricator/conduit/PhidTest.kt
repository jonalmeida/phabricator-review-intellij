package org.mozilla.phabricator.conduit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhidTest {

    @Test
    fun `isPhid recognises canonical PHID strings`() {
        assertTrue(isPhid("PHID-USER-abcdef0123"))
        assertTrue(isPhid("PHID-DREV-xyz"))
        assertTrue(isPhid("PHID-DIFF-A0B1C2"))
    }

    @Test
    fun `isPhid rejects malformed and non-string values`() {
        assertFalse(isPhid(""))
        assertFalse(isPhid("not-a-phid"))
        assertFalse(isPhid("PHID--abc")) // empty type segment
        assertFalse(isPhid("PHID-lowercase-abc"))
        assertFalse(isPhid(null))
        assertFalse(isPhid(42))
    }

    @Test
    fun `phidType extracts the type segment`() {
        assertEquals(PhidTypes.USER, phidType("PHID-USER-abc"))
        assertEquals(PhidTypes.REVISION, phidType("PHID-DREV-xyz"))
        assertNull(phidType("not-a-phid"))
    }
}
