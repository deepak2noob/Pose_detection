package com.example.posebenchmark

import org.junit.Assert.*
import org.junit.Test

class LandmarkDisplayFilterTest {
    @Test fun hysteresisDoesNotRequireWholeBody() {
        val arm = LandmarkDisplayFilter()
        val leg = LandmarkDisplayFilter()
        arm.update(0.2f, 0.3f, 0.59f)
        assertFalse(arm.visible)
        arm.update(0.2f, 0.3f, 0.60f)
        assertTrue(arm.visible)
        repeat(10) { arm.update(0.2f, 0.3f, 0.45f) }
        assertTrue(arm.visible)
        assertFalse(leg.visible)
    }

    @Test fun gracePeriodHoldsPositionAndRequiresConsecutiveLowFrames() {
        val filter = LandmarkDisplayFilter()
        filter.update(0.2f, 0.3f, 0.9f)
        repeat(2) { filter.update(0.8f, 0.9f, 0.1f) }
        assertTrue(filter.visible)
        assertEquals(0.2f, filter.x, 0f)
        filter.update(0.2f, 0.3f, 0.5f)
        repeat(2) { filter.update(0.8f, 0.9f, 0.1f) }
        assertTrue(filter.visible)
        filter.update(0.8f, 0.9f, 0.1f)
        assertFalse(filter.visible)
        filter.update(0.8f, 0.9f, 0.5f)
        assertFalse(filter.visible)
        filter.update(0.8f, 0.9f, 0.9f)
        assertEquals(0.8f, filter.x, 0f)
    }

    @Test fun smoothingReducesJitterAndResetSnapsToNewPerson() {
        val filter = LandmarkDisplayFilter()
        filter.update(0.2f, 0.3f, 0.9f)
        filter.update(0.4f, 0.5f, 0.9f)
        assertEquals(0.27f, filter.x, 0.0001f)
        assertEquals(0.37f, filter.y, 0.0001f)
        filter.reset()
        assertFalse(filter.visible)
        filter.update(0.8f, 0.9f, 0.9f)
        assertEquals(0.8f, filter.x, 0f)
    }

    @Test fun invalidAndOffFrameValuesHideImmediately() {
        val filter = LandmarkDisplayFilter()
        for ((x, y, visibility) in listOf(Triple(1.2f, 0.5f, 0.9f),
            Triple(Float.NaN, 0.5f, 0.9f), Triple(0.5f, Float.POSITIVE_INFINITY, 0.9f),
            Triple(0.5f, 0.5f, Float.NaN))) {
            filter.update(0.5f, 0.5f, 0.9f)
            filter.update(x, y, visibility)
            assertFalse(filter.visible)
        }
    }
}
