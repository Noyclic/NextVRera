package com.noyclic.questphonexr.sandbox.vio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class VioGeometryTest {
    @Test fun vectorArithmeticAndNormalizationAreStable() {
        val a = Vec3(3.0, 0.0, 4.0)
        assertEquals(5.0, a.length(), 1e-9)
        assertEquals(Vec3(0.6, 0.0, 0.8), a.normalized())
        assertEquals(Vec3.ZERO, Vec3.ZERO.normalized())
        assertEquals(Vec3(0.0, 0.0, 1.0), Vec3.UNIT_X.cross(Vec3.UNIT_Y))
    }

    @Test fun quaternionAxisAngleRotatesVector() {
        val rotation = Quaternion.fromAxisAngle(Vec3.UNIT_Z, PI / 2.0)
        val rotated = rotation.rotate(Vec3.UNIT_X)
        assertEquals(0.0, rotated.x, 1e-8)
        assertEquals(1.0, rotated.y, 1e-8)
        assertEquals(0.0, rotated.z, 1e-8)
    }

    @Test fun quaternionAngularVelocityUsesRadiansAndSeconds() {
        val rotation = Quaternion.fromAngularVelocity(Vec3(0.0, 0.0, PI), 0.5)
        assertEquals(PI / 2.0, rotation.angularDistance(Quaternion.IDENTITY), 1e-8)
    }

    @Test fun slerpChoosesShortestArcAndNormalizes() {
        val start = Quaternion.IDENTITY
        val end = Quaternion.fromAxisAngle(Vec3.UNIT_Y, PI)
        val middle = start.slerp(end, 0.5)
        assertEquals(1.0, middle.normSquared(), 1e-9)
        assertEquals(PI / 2.0, middle.angularDistance(start), 1e-8)
    }

    @Test fun androidRotationVectorAcceptsThreeAndFourComponents() {
        val three = Quaternion.fromAndroidRotationVector(floatArrayOf(0f, 0f, 0.70710677f))
        val four = Quaternion.fromAndroidRotationVector(floatArrayOf(0f, 0f, 0.70710677f, 0.70710677f))
        assertNotNull(three)
        assertNotNull(four)
        assertEquals(1.0, four!!.normSquared(), 1e-6)
        assertNull(Quaternion.fromAndroidRotationVector(floatArrayOf(Float.NaN, 0f, 0f)))
        assertNull(Quaternion.fromAndroidRotationVector(floatArrayOf(0f, 0f)))
    }

    @Test fun poseSanitizationNeverPublishesNan() {
        val pose = VioPose(
            10L, Quaternion(0.0, 0.0, 0.0, 0.0),
            Vec3(Double.NaN, 1.0, 2.0), TranslationScale.METRIC,
            Double.NaN, true, "test"
        ).sanitized()
        assertEquals(Quaternion.IDENTITY, pose.orientation)
        assertEquals(Vec3.ZERO, pose.position)
        assertEquals(0.0, pose.trackingQuality, 0.0)
    }
}
