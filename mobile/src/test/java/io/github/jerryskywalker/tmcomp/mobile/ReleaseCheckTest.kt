package io.github.jerryskywalker.tmcomp.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseCheckTest {
    @Test
    fun comparesStableTagsNumerically() {
        assertEquals(1, compareReleaseVersions("v0.10.0", "0.9.4"))
        assertEquals(1, compareReleaseVersions("v1.0.0", "0.99.99"))
        assertEquals(-1, compareReleaseVersions("v0.4.1", "0.4.2"))
        assertEquals(0, compareReleaseVersions("v0.4.1", "0.4.1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPrereleaseTags() {
        compareReleaseVersions("v0.4.2-rc.1", "0.4.1")
    }
}
