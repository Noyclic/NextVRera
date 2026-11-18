package com.noyclic.questphonexr.tracking

/**
 * Execution backends are selected independently for each LiteRT model.
 * A probe must represent a real model/delegate attempt, not just library presence.
 */
enum class LiteRtExecutionBackend(val wireId: String) {
    QUALCOMM_QNN_HTP("qnn_htp"),
    GPU("gpu"),
    CPU_XNNPACK("cpu_xnnpack")
}

data class LiteRtBackendProbe(
    val backend: LiteRtExecutionBackend,
    val available: Boolean,
    val detail: String = ""
)

data class LiteRtBackendSelection(
    val backend: LiteRtExecutionBackend?,
    /** Ordered attempts, including failed backends, for later diagnostic export. */
    val attempts: List<LiteRtBackendProbe>
)

/**
 * QNN HTP -> GPU -> CPU fallback policy. Call once per model because the palm detector and
 * landmark model may support different operations and therefore different accelerators.
 */
object LiteRtBackendPolicy {
    val preferenceOrder: List<LiteRtExecutionBackend> = listOf(
        LiteRtExecutionBackend.QUALCOMM_QNN_HTP,
        LiteRtExecutionBackend.GPU,
        LiteRtExecutionBackend.CPU_XNNPACK
    )

    fun select(probes: List<LiteRtBackendProbe>): LiteRtBackendSelection {
        require(probes.map { it.backend }.distinct().size == probes.size) {
            "Each LiteRT backend may be probed only once per model initialization."
        }
        val byBackend = probes.associateBy { it.backend }
        val ordered = preferenceOrder.mapNotNull(byBackend::get)
        val selected = ordered.firstOrNull { it.available }?.backend
        return LiteRtBackendSelection(backend = selected, attempts = ordered)
    }
}
