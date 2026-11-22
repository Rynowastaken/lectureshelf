package com.ryno.classrecorder.core

import org.json.JSONArray
import org.json.JSONObject

object AppDefaults {
    const val FILENAME_TEMPLATE = "{date}_{time}_{class}_{room}"
    const val DATE_FORMAT = "yyyy-MM-dd"
    const val SCHEDULE_URL = ""
    const val MCP_PORT = 8765
}

data class CourseSlot(
    val name: String,
    val teacher: String = "",
    val room: String = ""
)

data class TimeSlotRow(
    val slot: String,
    val time: String,
    val days: List<CourseSlot?>
)

data class UnifiedSchedule(
    val schoolName: String,
    val sourceUrl: String,
    val slots: List<TimeSlotRow>
)

data class CurrentClassMatch(
    val kind: String,
    val slot: String,
    val time: String,
    val weekdayIndex: Int,
    val course: CourseSlot
)

data class RecordingRecord(
    val id: String,
    val filePath: String,
    val courseName: String,
    val teacher: String,
    val room: String,
    val slot: String,
    val startedAt: Long,
    val durationMs: Long,
    val status: String,
    val notes: String,
    val tags: List<String>
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("filePath", filePath)
        put("courseName", courseName)
        put("teacher", teacher)
        put("room", room)
        put("slot", slot)
        put("startedAt", startedAt)
        put("durationMs", durationMs)
        put("status", status)
        put("notes", notes)
        put("tags", JSONArray(tags))
    }
}

data class McpStatus(
    val running: Boolean,
    val port: Int,
    val recentCalls: List<String>
)

object JsonModels {
    fun parseSchedule(obj: JSONObject?): UnifiedSchedule? {
        if (obj == null) return null
        val slotsArr = obj.optJSONArray("slots") ?: return null
        val slots = ArrayList<TimeSlotRow>(slotsArr.length())
        for (i in 0 until slotsArr.length()) {
            val row = slotsArr.optJSONObject(i) ?: continue
            val daysArr = row.optJSONArray("days") ?: JSONArray()
            val days = ArrayList<CourseSlot?>(daysArr.length())
            for (d in 0 until daysArr.length()) {
                val c = daysArr.optJSONObject(d)
                days.add(
                    c?.let {
                        CourseSlot(
                            name = it.optString("name"),
                            teacher = it.optString("teacher"),
                            room = it.optString("room")
                        )
                    }
                )
            }
            slots.add(
                TimeSlotRow(
                    slot = row.optString("slot"),
                    time = row.optString("time"),
                    days = days
                )
            )
        }
        return UnifiedSchedule(
            schoolName = obj.optString("schoolName", "NUTC"),
            sourceUrl = obj.optString("sourceUrl"),
            slots = slots
        )
    }

    fun parseCurrentClass(obj: JSONObject?): CurrentClassMatch? {
        if (obj == null) return null
        val c = obj.optJSONObject("course") ?: return null
        return CurrentClassMatch(
            kind = obj.optString("kind", "active"),
            slot = obj.optString("slot"),
            time = obj.optString("time"),
            weekdayIndex = obj.optInt("weekdayIndex"),
            course = CourseSlot(
                name = c.optString("name"),
                teacher = c.optString("teacher"),
                room = c.optString("room")
            )
        )
    }

    fun parseRecording(obj: JSONObject): RecordingRecord {
        val tagsArr = obj.optJSONArray("tags") ?: JSONArray()
        val tags = ArrayList<String>(tagsArr.length())
        for (i in 0 until tagsArr.length()) {
            val tag = tagsArr.optString(i)
            if (tag.isNotEmpty()) tags.add(tag)
        }
        return RecordingRecord(
            id = obj.optString("id"),
            filePath = obj.optString("filePath"),
            courseName = obj.optString("courseName", "Uncategorized"),
            teacher = obj.optString("teacher"),
            room = obj.optString("room"),
            slot = obj.optString("slot"),
            startedAt = obj.optLong("startedAt"),
            durationMs = obj.optLong("durationMs"),
            status = obj.optString("status"),
            notes = obj.optString("notes"),
            tags = tags
        )
    }

    fun parseRecordings(arr: JSONArray?): List<RecordingRecord> {
        if (arr == null) return emptyList()
        val out = ArrayList<RecordingRecord>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(parseRecording(it)) }
        }
        return out
    }

    fun parseMcpStatus(obj: JSONObject?): McpStatus {
        if (obj == null) return McpStatus(false, AppDefaults.MCP_PORT, emptyList())
        val callsArr = obj.optJSONArray("recentCalls") ?: JSONArray()
        val calls = ArrayList<String>(callsArr.length())
        for (i in 0 until callsArr.length()) {
            callsArr.optString(i)?.let { calls.add(it) }
        }
        return McpStatus(
            running = obj.optBoolean("running", false),
            port = obj.optInt("port", AppDefaults.MCP_PORT),
            recentCalls = calls
        )
    }
}
