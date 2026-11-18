package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtDspTwoHandRoiPolicyTest {

    @Test
    fun createsTwoNonOverlappingSeedRegionsAcrossTheFrame() {
        val crops = LiteRtDspTwoHandRoiPolicy.createSeedCrops(640, 480)

        assertEquals(2, crops.size)
        assertEquals(640, crops[0].sourceWidthPx)
        assertEquals(480, crops[0].sourceHeightPx)
        assertTrue(crops[0].leftPx + crops[0].sizePx <= crops[1].leftPx)
        assertTrue(crops[0].leftPx + crops[0].sizePx / 2 < 320)
        assertTrue(crops[1].leftPx + crops[1].sizePx / 2 > 320)
    }

    @Test
    fun rejectsTinyFramesInsteadOfCreatingInvalidCrops() {
        assertTrue(LiteRtDspTwoHandRoiPolicy.createSeedCrops(100, 480).isEmpty())
        assertTrue(LiteRtDspTwoHandRoiPolicy.createSeedCrops(640, 48).isEmpty())
    }

    @Test
    fun acceptsAHandLikeTwentyOnePointLayoutInsideItsSeedRoi() {
        val crop = LiteRtDspTwoHandRoiPolicy.createSeedCrops(640, 480).first()
        val points = MutableList(21) { HandPoint(0.25f, 0.58f, 0f) }
        points[4] = HandPoint(0.12f, 0.48f, 0f)
        points[8] = HandPoint(0.20f, 0.32f, 0f)
        points[12] = HandPoint(0.25f, 0.27f, 0f)
        points[16] = HandPoint(0.30f, 0.32f, 0f)
        points[20] = HandPoint(0.36f, 0.40f, 0f)

        assertTrue(LiteRtDspTwoHandRoiPolicy.hasPlausibleHand(points, crop))
    }

    @Test
    fun rejectsCollapsedLandmarksFromAnEmptyOrUnusableRoi() {
        val crop = LiteRtDspTwoHandRoiPolicy.createSeedCrops(640, 480).first()
        val points = List(21) { HandPoint(0.25f, 0.5f, 0f) }

        assertFalse(LiteRtDspTwoHandRoiPolicy.hasPlausibleHand(points, crop))
    }

    @Test
    fun suppressesTwoRoisThatReturnTheSamePhysicalWrist() {
        val first = MutableList(21) { HandPoint(0.25f, 0.5f, 0f) }
        val same = first.toList()
        val second = first.map { HandPoint(it.x + 0.2f, it.y, it.z) }

        assertFalse(LiteRtDspTwoHandRoiPolicy.areDistinctHands(first, same))
        assertTrue(LiteRtDspTwoHandRoiPolicy.areDistinctHands(first, second))
    }
}
