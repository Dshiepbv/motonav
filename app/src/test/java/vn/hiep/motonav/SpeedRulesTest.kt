package vn.hiep.motonav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedRulesTest {
    @Test
    fun explicitRoadLimitWinsOverConflictingOfflinePoint() {
        val selected = SpeedRules.preferRecordedLimit(LimitResult(60, false), 40)

        assertEquals(60, selected?.kmh)
        assertFalse(selected!!.estimated)
    }

    @Test
    fun offlinePointReplacesOnlyAnEstimatedRoadLimit() {
        val selected = SpeedRules.preferRecordedLimit(LimitResult(50, true), 60)

        assertEquals(60, selected?.kmh)
        assertFalse(selected!!.estimated)
    }

    @Test
    fun estimatedRoadLimitRemainsWhenOfflineDataIsUnavailable() {
        val selected = SpeedRules.preferRecordedLimit(LimitResult(50, true), null)

        assertEquals(50, selected?.kmh)
        assertTrue(selected!!.estimated)
    }

    @Test
    fun noRoadOrOfflineLimitRemainsUnknown() {
        assertNull(SpeedRules.preferRecordedLimit(null, null))
    }
}
