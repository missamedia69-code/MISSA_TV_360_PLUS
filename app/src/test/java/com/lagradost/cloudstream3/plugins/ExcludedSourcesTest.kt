package com.lagradost.cloudstream3.plugins

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcludedSourcesTest {
    @Test fun `All for U and common internal aliases are excluded`() {
        assertTrue(ExcludedSources.contains("All for U"))
        assertTrue(ExcludedSources.contains("AllForUProvider"))
        assertTrue(ExcludedSources.contains("ALL FOR YOU"))
        assertFalse(ExcludedSources.contains("All for Us"))
        assertFalse(ExcludedSources.contains("MediaTri"))
    }
}
