package io.bigmoeonedge.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutotunePlannerTest {
    @Test
    fun candidatesAreBoundedAndExcludeLossyKnobs() {
        val base = AppSettings(nExpertUsed = 2, dropColdPct = 75, routeAhead = 1, spec = AppSettings.SPEC_MTP)
        val candidates = AutotunePlanner.candidates(base)
        assertTrue(candidates.size <= AutotunePlanner.MAX_CANDIDATES)
        assertTrue(candidates.all { it.settings.nExpertUsed == 0 })
        assertTrue(candidates.all { it.settings.dropColdPct == 0 })
        assertTrue(candidates.all { it.settings.routeAhead == 0 })
        assertTrue(candidates.all { it.settings.spec == AppSettings.SPEC_OFF })
    }

    @Test
    fun scheduleBalancesEveryCandidateAcrossTwoRepetitions() {
        val schedule = AutotunePlanner.schedule(AppSettings(), 7)
        assertEquals(AutotunePlanner.candidates(AppSettings()).size * 2, schedule.size)
        assertEquals(schedule.map { it.candidate.id }.toSet().size, schedule.size / 2)
        assertTrue(schedule.take(schedule.size / 2).map { it.candidate.id }.toSet() ==
            schedule.drop(schedule.size / 2).map { it.candidate.id }.toSet())
    }

    @Test
    fun recommendationRequiresMatchingCompleteRepeatedTrials() {
        val candidate = AutotunePlanner.candidates(AppSettings()).first()
        val good = (1..2).map { AutotuneResult(candidate, it, it, null, true, it.toDouble(), null, null, "complete") }
        val bad = AutotuneResult(candidate, 3, 3, null, false, 100.0, null, null, "complete")
        assertNotNull(AutotunePlanner.recommend(good + bad))
        assertFalse(AutotunePlanner.recommend(good.map { it.copy(outputMatches = false) }) != null)
    }
}
