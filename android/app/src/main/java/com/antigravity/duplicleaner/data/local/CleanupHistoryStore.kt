package com.antigravity.duplicleaner.data.local

import android.content.Context
import android.content.SharedPreferences
import com.antigravity.duplicleaner.util.FormatUtils
import org.json.JSONArray
import org.json.JSONObject

data class CleanupHistoryRecord(
    val timestampMs: Long,
    val cleanedFileCount: Int,
    val freedBytes: Long,
    val isTrashMove: Boolean,
    val sampleFileNames: List<String>
)

/**
 * 최근 중복 파일 정리 내역(최대 30건) 및 누적 확보 용량을
 * SharedPreferences("duplicleaner_history")에 JSON 형태로 영구 보존하는 저장소.
 */
class CleanupHistoryStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getHistoryRecords(): List<CleanupHistoryRecord> {
        val rawJson = prefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        return try {
            val array = JSONArray(rawJson)
            val result = ArrayList<CleanupHistoryRecord>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val namesArray = obj.optJSONArray("sampleFileNames")
                val names = mutableListOf<String>()
                if (namesArray != null) {
                    for (j in 0 until namesArray.length()) {
                        val name = namesArray.optString(j)
                        if (name.isNotBlank()) names.add(name)
                    }
                }
                result.add(
                    CleanupHistoryRecord(
                        timestampMs = obj.optLong("timestampMs", 0L),
                        cleanedFileCount = obj.optInt("cleanedFileCount", 0),
                        freedBytes = obj.optLong("freedBytes", 0L),
                        isTrashMove = obj.optBoolean("isTrashMove", true),
                        sampleFileNames = names
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadHistory(): List<CleanupHistoryRecord> = getHistoryRecords()

    fun addRecord(record: CleanupHistoryRecord): List<CleanupHistoryRecord> {
        val current = getHistoryRecords()
        val updated = (listOf(record) + current).take(MAX_HISTORY_RECORDS)
        saveRecords(updated)

        val previousTotalBytes = prefs.getLong(KEY_LIFETIME_FREED_BYTES, 0L)
        val previousTotalCount = prefs.getInt(KEY_LIFETIME_CLEANED_COUNT, 0)
        prefs.edit()
            .putLong(KEY_LIFETIME_FREED_BYTES, (previousTotalBytes + record.freedBytes).coerceAtLeast(0L))
            .putInt(KEY_LIFETIME_CLEANED_COUNT, (previousTotalCount + record.cleanedFileCount).coerceAtLeast(0))
            .apply()

        return updated
    }

    fun recordCleanup(
        cleanedFileCount: Int,
        freedBytes: Long,
        isTrashMove: Boolean,
        sampleFileNames: List<String> = emptyList(),
        timestampMs: Long = System.currentTimeMillis()
    ): List<CleanupHistoryRecord> {
        val record = CleanupHistoryRecord(
            timestampMs = timestampMs,
            cleanedFileCount = cleanedFileCount,
            freedBytes = freedBytes,
            isTrashMove = isTrashMove,
            sampleFileNames = sampleFileNames.take(5)
        )
        return addRecord(record)
    }

    fun getTotalLifetimeFreedBytes(): Long {
        val storedTotal = prefs.getLong(KEY_LIFETIME_FREED_BYTES, 0L)
        if (storedTotal > 0L) return storedTotal
        return getHistoryRecords().sumOf { it.freedBytes }
    }

    fun getTotalLifetimeCleanedCount(): Int {
        val storedCount = prefs.getInt(KEY_LIFETIME_CLEANED_COUNT, 0)
        if (storedCount > 0) return storedCount
        return getHistoryRecords().sumOf { it.cleanedFileCount }
    }

    fun buildHistorySummaryText(): String {
        val records = getHistoryRecords()
        if (records.isEmpty()) {
            return "아직 정리 기록이 없습니다."
        }
        val totalFreed = FormatUtils.formatBytes(getTotalLifetimeFreedBytes())
        val totalCount = getTotalLifetimeCleanedCount()
        val lines = mutableListOf(
            "누적 정리: 총 ${totalCount}개 파일 · $totalFreed 확보 (최근 ${records.size}건 보관)"
        )
        records.take(5).forEach { item ->
            val dateText = FormatUtils.formatDate(item.timestampMs / 1000L)
            val modeText = if (item.isTrashMove) "휴지통" else "영구삭제"
            val sampleText = if (item.sampleFileNames.isNotEmpty()) {
                " (${item.sampleFileNames.first()}${if (item.cleanedFileCount > 1) " 외 ${item.cleanedFileCount - 1}건" else ""})"
            } else {
                ""
            }
            lines.add(
                "• $dateText · ${item.cleanedFileCount}개 ($modeText, ${FormatUtils.formatBytes(item.freedBytes)})$sampleText"
            )
        }
        return lines.joinToString("\n")
    }

    fun clearHistory() {
        prefs.edit().clear().apply()
    }

    private fun saveRecords(records: List<CleanupHistoryRecord>) {
        val array = JSONArray()
        records.take(MAX_HISTORY_RECORDS).forEach { record ->
            val obj = JSONObject().apply {
                put("timestampMs", record.timestampMs)
                put("cleanedFileCount", record.cleanedFileCount)
                put("freedBytes", record.freedBytes)
                put("isTrashMove", record.isTrashMove)
                val namesArray = JSONArray()
                record.sampleFileNames.forEach { namesArray.put(it) }
                put("sampleFileNames", namesArray)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY_JSON, array.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "duplicleaner_history"
        const val MAX_HISTORY_RECORDS = 30
        private const val KEY_HISTORY_JSON = "history_records_json"
        private const val KEY_LIFETIME_FREED_BYTES = "lifetime_freed_bytes"
        private const val KEY_LIFETIME_CLEANED_COUNT = "lifetime_cleaned_count"
    }
}
