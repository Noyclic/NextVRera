package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiteRtBackendPolicyTest {

    @Test
    fun prefersQnnWhenARealHtpProbeSucceeds() {
        val selected = LiteRtBackendPolicy.select(
            listOf(
                LiteRtBackendProbe(LiteRtExecutionBackend.CPU_XNNPACK, true),
                LiteRtBackendProbe(LiteRtExecutionBackend.GPU, true),
                LiteRtBackendProbe(LiteRtExecutionBackend.QUALCOMM_QNN_HTP, true)
            )
        )

        assertEquals(LiteRtExecutionBackend.QUALCOMM_QNN_HTP, selected.backend)
        assertEquals(
            listOf(
                LiteRtExecutionBackend.QUALCOMM_QNN_HTP,
                LiteRtExecutionBackend.GPU,
                LiteRtExecutionBackend.CPU_XNNPACK
            ),
            selected.attempts.map { it.backend }
        )
    }

    @Test
    fun fallsBackToGpuWhenQnnCannotBeUsed() {
        val selected = LiteRtBackendPolicy.select(
            listOf(
                LiteRtBackendProbe(
                    LiteRtExecutionBackend.QUALCOMM_QNN_HTP,
                    available = false,
                    detail = "HTP delegate initialization failed"
                ),
                LiteRtBackendProbe(LiteRtExecutionBackend.GPU, available = true),
                LiteRtBackendProbe(LiteRtExecutionBackend.CPU_XNNPACK, available = true)
            )
        )

        assertEquals(LiteRtExecutionBackend.GPU, selected.backend)
    }

    @Test
    fun fallsBackToCpuWhenQnnAndGpuAreUnavailable() {
        val selected = LiteRtBackendPolicy.select(
            listOf(
                LiteRtBackendProbe(LiteRtExecutionBackend.QUALCOMM_QNN_HTP, false),
                LiteRtBackendProbe(LiteRtExecutionBackend.GPU, false),
                LiteRtBackendProbe(LiteRtExecutionBackend.CPU_XNNPACK, true)
            )
        )

        assertEquals(LiteRtExecutionBackend.CPU_XNNPACK, selected.backend)
    }

    @Test
    fun reportsNoBackendWhenEveryProbeFails() {
        val selected = LiteRtBackendPolicy.select(
            listOf(
                LiteRtBackendProbe(LiteRtExecutionBackend.QUALCOMM_QNN_HTP, false),
                LiteRtBackendProbe(LiteRtExecutionBackend.GPU, false),
                LiteRtBackendProbe(LiteRtExecutionBackend.CPU_XNNPACK, false)
            )
        )

        assertNull(selected.backend)
    }
}
