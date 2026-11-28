package com.noyclic.xrclientprobe

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores a compact history of diagnostic verdicts, not raw reports or user data.
 * This lets a device-side rerun reveal regressions without asking the user to
 * export and compare two long text files manually.
 */
internal object ExternalOpenXrAssessmentHistory {
    const val MAX_SNAPSHOTS = 8

    enum class ChangeKind { IMPROVED, REGRESSED, CHANGED, UNCHANGED }

    data class Snapshot(
        val timestampMillis: Long,
        val selectedRuntimePackage: String?,
        val highestVerifiedStage: String?,
        val firstFailureKey: String?,
        val stageStatuses: Map<String, String>
    )

    data class StageChange(
        val stageId: String,
        val previousStatus: String,
        val currentStatus: String,
        val kind: ChangeKind
    )

    data class Comparison(
        val previous: Snapshot,
        val current: Snapshot,
        val changes: List<StageChange>
    ) {
        val improvements: List<StageChange> get() = changes.filter { it.kind == ChangeKind.IMPROVED }
        val regressions: List<StageChange> get() = changes.filter { it.kind == ChangeKind.REGRESSED }
        val otherChanges: List<StageChange> get() = changes.filter { it.kind == ChangeKind.CHANGED }
        val unchangedCount: Int get() = changes.count { it.kind == ChangeKind.UNCHANGED }

        fun toDisplayText(): String = buildString {
            appendLine("PREVIOUS RUN COMPARISON")
            appendLine("previous_timestamp_ms=${previous.timestampMillis}")
            appendLine("current_timestamp_ms=${current.timestampMillis}")
            appendLine("previous_runtime=${previous.selectedRuntimePackage ?: "UNKNOWN"}")
            appendLine("current_runtime=${current.selectedRuntimePackage ?: "UNKNOWN"}")
            appendLine("improvements=${improvements.size}")
            appendLine("regressions=${regressions.size}")
            appendLine("other_changes=${otherChanges.size}")
            appendLine("unchanged_stages=$unchangedCount")
            if (regressions.isNotEmpty()) {
                appendLine()
                appendLine("REGRESSIONS")
                regressions.forEach {
                    appendLine("- ${it.stageId}: ${it.previousStatus} -> ${it.currentStatus}")
                }
            }
            if (improvements.isNotEmpty()) {
                appendLine()
                appendLine("IMPROVEMENTS")
                improvements.forEach {
                    appendLine("- ${it.stageId}: ${it.previousStatus} -> ${it.currentStatus}")
                }
            }
            if (otherChanges.isNotEmpty()) {
                appendLine()
                appendLine("OTHER STATUS CHANGES")
                otherChanges.forEach {
                    appendLine("- ${it.stageId}: ${it.previousStatus} -> ${it.currentStatus}")
                }
            }
            if (previous.firstFailureKey != current.firstFailureKey) {
                appendLine()
                appendLine("first_failure_changed=${previous.firstFailureKey ?: "NONE"} -> ${current.firstFailureKey ?: "NONE"}")
            } else {
                appendLine("first_failure_unchanged=${current.firstFailureKey ?: "NONE"}")
            }
            if (regressions.isEmpty() && improvements.isEmpty() && otherChanges.isEmpty()) {
                appendLine("result=NO_STAGE_STATUS_CHANGE")
            } else if (regressions.isEmpty()) {
                appendLine("result=NO_VERIFIED_STAGE_REGRESSION")
            } else {
                appendLine("result=VERIFIED_STAGE_REGRESSION_DETECTED")
            }
            append("Comparison is between two local diagnostic runs; it is not a performance benchmark.")
        }
    }

    fun snapshot(
        assessment: ExternalOpenXrCompatibilityAssessment.Assessment,
        timestampMillis: Long
    ): Snapshot = Snapshot(
        timestampMillis = timestampMillis.coerceAtLeast(0L),
        selectedRuntimePackage = assessment.selectedRuntimePackage,
        highestVerifiedStage = assessment.highestVerifiedStage?.name,
        firstFailureKey = assessment.firstFailure?.key,
        stageStatuses = assessment.stages.associate { it.id.name to it.status.name }
    )

    fun compare(previous: Snapshot, current: Snapshot): Comparison {
        val changes = ExternalOpenXrCompatibilityAssessment.StageId.values().mapNotNull { id ->
            val key = id.name
            val before = previous.stageStatuses[key] ?: return@mapNotNull null
            val after = current.stageStatuses[key] ?: return@mapNotNull null
            val kind = when {
                before == after -> ChangeKind.UNCHANGED
                before == ExternalOpenXrCompatibilityAssessment.Status.VERIFIED.name &&
                    after != ExternalOpenXrCompatibilityAssessment.Status.VERIFIED.name ->
                    ChangeKind.REGRESSED
                after == ExternalOpenXrCompatibilityAssessment.Status.VERIFIED.name &&
                    before != ExternalOpenXrCompatibilityAssessment.Status.VERIFIED.name ->
                    ChangeKind.IMPROVED
                else -> ChangeKind.CHANGED
            }
            StageChange(key, before, after, kind)
        }
        return Comparison(previous, current, changes)
    }

    internal fun encode(snapshots: List<Snapshot>): String {
        val array = JSONArray()
        snapshots.takeLast(MAX_SNAPSHOTS).forEach { snapshot ->
            val statuses = JSONObject()
            snapshot.stageStatuses.toSortedMap().forEach { (key, value) ->
                statuses.put(key, value)
            }
            array.put(JSONObject()
                .put("timestamp_ms", snapshot.timestampMillis)
                .put("selected_runtime_package", snapshot.selectedRuntimePackage)
                .put("highest_verified_stage", snapshot.highestVerifiedStage)
                .put("first_failure_key", snapshot.firstFailureKey)
                .put("stage_statuses", statuses))
        }
        return array.toString()
    }

    internal fun decode(raw: String?): List<Snapshot> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val statusesObject = item.optJSONObject("stage_statuses") ?: JSONObject()
                    val statuses = linkedMapOf<String, String>()
                    val keys = statusesObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = statusesObject.optString(key, "")
                        if (key.isNotBlank() && value.isNotBlank()) statuses[key] = value
                    }
                    add(
                        Snapshot(
                            timestampMillis = item.optLong("timestamp_ms", 0L).coerceAtLeast(0L),
                            selectedRuntimePackage = item.optNullableString("selected_runtime_package"),
                            highestVerifiedStage = item.optNullableString("highest_verified_stage"),
                            firstFailureKey = item.optNullableString("first_failure_key"),
                            stageStatuses = statuses
                        )
                    )
                }
            }.takeLast(MAX_SNAPSHOTS)
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    class Store(context: Context) {
        private val preferences: SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

        @Synchronized
        fun history(): List<Snapshot> = decode(preferences.getString(KEY_HISTORY, null))

        @Synchronized
        fun latest(): Snapshot? = history().lastOrNull()

        @Synchronized
        fun append(snapshot: Snapshot): Boolean {
            val updated = (history() + snapshot).takeLast(MAX_SNAPSHOTS)
            return preferences.edit().putString(KEY_HISTORY, encode(updated)).commit()
        }

        @Synchronized
        fun clear(): Boolean = preferences.edit().remove(KEY_HISTORY).commit()
    }

    private const val PREFERENCES = "external_openxr_assessment_history"
    private const val KEY_HISTORY = "snapshots_json"
}
