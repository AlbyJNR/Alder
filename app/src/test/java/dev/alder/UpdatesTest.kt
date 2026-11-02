package dev.alder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun comparesVersionsByNumber() {
        assertTrue(newerVersion("1.1", "1.0"))
        assertTrue(newerVersion("1.10", "1.9"))
        assertTrue(newerVersion("2", "1.9.9"))
        assertFalse(newerVersion("1.0", "1.0"))
        assertFalse(newerVersion("1.0", "1.0.1"))
        assertFalse(newerVersion("1.0.0", "1.0"))
    }

    @Test fun quietCheckIsDueSixHoursAfterTheLast() {
        val h = 3600_000L
        assertTrue(quietDue(0, 10 * h))
        assertTrue(quietDue(1 * h, 8 * h))
        assertFalse(quietDue(1 * h, 6 * h))
        assertFalse(quietDue(5 * h, 5 * h + 1))
    }
}
