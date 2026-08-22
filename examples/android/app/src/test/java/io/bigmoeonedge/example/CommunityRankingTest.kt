package io.bigmoeonedge.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityRankingTest {
    @Test
    fun parsesCommonModelScopeEnvelopeAndNormalizesRanking() {
        val ranking = CommunityRankingRepository.parse(
            "ModelScope",
            """{"Data":{"Trends":[{"ResourceDisplay":"org/a","ResourceDisplayCnName":"A","Downloads":1200,"Likes":7,"GmtModified":"2025-01-02"},{"ResourceDisplay":"org/b","ResourceDisplayCnName":"B","Downloads":"900","Likes":2}]}}""",
            42,
        )
        assertEquals(2, ranking.models.size)
        assertEquals("A", ranking.models[0].name)
        assertEquals(1200L, ranking.models[0].downloads)
        assertEquals(2, ranking.models[1].rank)
        assertEquals("https://modelscope.cn/models/org/a", ranking.models[0].url)
    }

    @Test
    fun formatsLargeCountsForCompactUi() {
        assertEquals("1.2K", formatCommunityCount(1_200))
        assertEquals("2.0M", formatCommunityCount(2_000_000))
        assertTrue(formatCommunityCount(null).isNotEmpty())
    }
}
