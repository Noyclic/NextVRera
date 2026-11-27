package com.noyclic.questphonexr.runtime

/**
 * Bounded, process-independent trace journal codec. JSON Lines keeps append
 * operations small and avoids a second database in the native runtime path.
 */
internal data class EndXrRuntimeTraceEntry(
    val sequence: Long,
    val elapsedRealtimeNanos: Long,
    val monotonicNanos: Long,
    val callerPackage: String,
    val pid: Int,
    val instanceId: String,
    val sessionId: String?,
    val stage: String,
    val operation: String,
    val result: String,
    val resultCode: Int,
    val durationNanos: Long,
    val frameIndex: Long,
    val threadId: Long,
    val details: String
) {
    fun toJson(): String = buildString {
        append('{')
        append("\"sequence\":").append(sequence).append(',')
        append("\"elapsed_realtime_ns\":").append(elapsedRealtimeNanos).append(',')
        append("\"monotonic_ns\":").append(monotonicNanos).append(',')
        append("\"caller_package\":").append(jsonString(callerPackage)).append(',')
        append("\"pid\":").append(pid).append(',')
        append("\"instance_id\":").append(jsonString(instanceId)).append(',')
        append("\"session_id\":").append(sessionId?.let { jsonString(it) } ?: "null").append(',')
        append("\"stage\":").append(jsonString(stage)).append(',')
        append("\"operation\":").append(jsonString(operation)).append(',')
        append("\"result\":").append(jsonString(result)).append(',')
        append("\"result_code\":").append(resultCode).append(',')
        append("\"duration_ns\":").append(durationNanos).append(',')
        append("\"frame_index\":").append(frameIndex).append(',')
        append("\"thread_id\":").append(threadId).append(',')
        append("\"details\":").append(jsonString(details))
        append('}')
    }

    companion object {
        private fun jsonString(value: String): String = buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else append(char)
                }
            }
            append('"')
        }
    }
}

internal object EndXrRuntimeTraceJournal {
    const val MAX_EVENTS = 192

    fun append(jsonLines: String?, entry: EndXrRuntimeTraceEntry): String {
        val existing = jsonLines.orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return (existing + entry.toJson()).takeLast(MAX_EVENTS).joinToString("\n")
    }

    fun count(jsonLines: String?): Int =
        jsonLines.orEmpty().lineSequence().count { it.isNotBlank() }

    /** Keeps only entries attributed to the selected Android package. */
    fun filterByCallerPackage(jsonLines: String?, packageName: String): String {
        if (packageName.isBlank()) return ""
        val marker = "\"caller_package\":\"$packageName\""
        return jsonLines.orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(marker) }
            .joinToString("\n")
    }

    fun asJsonArray(jsonLines: String?): String =
        jsonLines.orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(prefix = "[", postfix = "]", separator = ",")

    fun formatText(
        callerPackage: String?,
        pid: Int,
        traceCount: Int,
        firstFailureStage: String?,
        lastSuccessfulStage: String?,
        jsonLines: String?
    ): String = buildString {
        append("ENDXR LOW-LEVEL RUNTIME TRACE\n")
        append("caller_package=").append(callerPackage ?: "unknown").append('\n')
        append("runtime_pid=").append(pid).append('\n')
        append("events_retained=").append(traceCount).append('/').append(MAX_EVENTS).append('\n')
        append("first_failure_stage=").append(firstFailureStage ?: "NONE_RECORDED").append('\n')
        append("last_successful_stage=").append(lastSuccessfulStage ?: "NONE_RECORDED").append('\n')
        append("clock=Android elapsedRealtimeNanos / native steady_clock monotonic\n")
        append("---- EVENTS (JSONL) ----\n")
        append(jsonLines.orEmpty())
    }
}
