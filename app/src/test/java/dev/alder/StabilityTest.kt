package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules that keep CAS steady: the row band holding its row, memory given back, a failing site left alone. */
class StabilityTest {
    @Test fun bandJumpsOnlyWhenItsRowMoved() {
        assertNull(heldScroll(null, 0)) // no row to hold
        assertNull(heldScroll(424, 424)) // where snapTo sent it: a smooth scroll on its way is left alone
        assertEquals(636, heldScroll(424 + 212, 424)) // a row arrived above: follow the row down
        assertEquals(212, heldScroll(212, 424)) // a row above went away: follow it up
        assertEquals(0, heldScroll(-3, 424))
    }

    @Test fun memoryIsGivenBackByHowHardAndroidAsks() {
        assertNull(trimShares(5)) // TRIM_MEMORY_RUNNING_MODERATE: nothing yet
        assertEquals(0.5f to 0.5f, trimShares(10))
        assertEquals(0.5f to 0.5f, trimShares(15))
        assertEquals(0.5f to 0f, trimShares(20)) // off the screen: no full-screen art is needed
        assertEquals(0f to 0f, trimShares(40))
        assertEquals(0f to 0f, trimShares(80))
    }

    @Test fun onlyARefusingOrBrokenSiteRests() {
        assertTrue(restsAfter(403))
        assertTrue(restsAfter(429))
        assertTrue(restsAfter(503))
        assertFalse(restsAfter(200))
        assertFalse(restsAfter(404)) // one page missing is not the site failing
        assertTrue("api.graphql.imdb.com" in GUEST_HOSTS)
        assertFalse("api.strem.io" in GUEST_HOSTS) // Stremio is never rested: Retry must reach it
    }
}
