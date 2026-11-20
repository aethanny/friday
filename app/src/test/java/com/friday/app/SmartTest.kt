package com.friday.app

import com.friday.app.data.db.SnoozeLogEntity
import com.friday.app.domain.Habits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class SmartTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun log(ageDays: Int, h: Int, m: Int) = SnoozeLogEntity(taskId = 1, at = now - ageDays * day, targetMin = h * 60 + m)
    private val t17 = LocalTime.of(17, 0)

    @Test fun learnsConsistentSnoozeTime() =
        assertEquals(LocalTime.of(19, 0), Habits.learnedMove(listOf(log(1, 19, 5), log(2, 18, 50), log(3, 19, 10)), t17, now))

    @Test fun ignoresScatteredSnoozes() =
        assertNull(Habits.learnedMove(listOf(log(1, 19, 0), log(2, 21, 30), log(3, 15, 0)), t17, now))

    @Test fun needsThree() = assertNull(Habits.learnedMove(listOf(log(1, 19, 0), log(2, 19, 0)), t17, now))

    @Test fun ignoresOldEntries() =
        assertNull(Habits.learnedMove(listOf(log(40, 19, 0), log(41, 19, 0), log(42, 19, 0)), t17, now))

    @Test fun ignoresIfAlreadyNearCurrent() =
        assertNull(Habits.learnedMove(listOf(log(1, 17, 10), log(2, 17, 5), log(3, 17, 15)), t17, now))
}
