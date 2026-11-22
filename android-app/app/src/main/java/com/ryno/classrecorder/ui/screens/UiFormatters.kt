package com.ryno.classrecorder.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.ryno.classrecorder.core.AppDefaults
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object UiFormatters {
    private val SAMPLE_PREVIEW_DATE: LocalDate = LocalDate.of(2026, 11, 18)
    private val TIME_RANGE_SPLITTER = Regex("""\s*(?:~|～|–|—|-)\s*""")
    private val CLOCK_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val TIMESTAMP_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
    private val DAY_HEADER_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE · MMM d", Locale.US)

    /**
     * Formats duration milliseconds with tabular numerals (`mm:ss` or `h:mm:ss`).
     */
    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Normalizes timetable range strings (`08:10~09:00`, `13:25 - 14:15`) to an en-dash (`08:10–09:00`).
     */
    fun formatTimeRange(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val parts = trimmed.split(TIME_RANGE_SPLITTER).filter { it.isNotBlank() }
        return if (parts.size == 2) {
            "${parts[0]}–${parts[1]}"
        } else {
            trimmed
        }
    }

    /**
     * Accepts either Unix epoch seconds (used by RustCore SQLite rows) or epoch milliseconds.
     */
    fun normalizeEpochMillis(epoch: Long): Long {
        if (epoch <= 0L) return 0L
        return if (epoch < 100_000_000_000L) epoch * 1000L else epoch
    }

    fun toLocalDate(epoch: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
        val ms = normalizeEpochMillis(epoch)
        return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    }

    fun formatClockTime(epoch: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (epoch <= 0L) return "--:--"
        return runCatching {
            Instant.ofEpochMilli(normalizeEpochMillis(epoch)).atZone(zone).format(CLOCK_FMT)
        }.getOrDefault("--:--")
    }

    fun formatTimestamp(epoch: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (epoch <= 0L) return ""
        return runCatching {
            Instant.ofEpochMilli(normalizeEpochMillis(epoch)).atZone(zone).format(TIMESTAMP_FMT)
        }.getOrDefault(epoch.toString())
    }

    fun formatDayHeader(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DAY_HEADER_FMT)
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 KB"
        val mb = 1024L * 1024L
        return if (bytes >= mb) {
            String.format(Locale.US, "%.1f MB", bytes.toDouble() / mb.toDouble())
        } else {
            val kb = (bytes / 1024L).coerceAtLeast(1L)
            "$kb KB"
        }
    }

    /**
     * Sanitizes filesystem-unsafe characters identically to Rust's `sanitize_segment`.
     */
    fun sanitizePathSegment(raw: String, fallback: String = "Uncategorized"): String {
        val cleaned = buildString(raw.length) {
            for (c in raw.trim()) {
                when {
                    c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' ||
                        c == '"' || c == '<' || c == '>' || c == '|' || c == '\u0000' ||
                        c.isISOControl() -> append('_')
                    else -> append(c)
                }
            }
        }.trim('.').trim()
        return cleaned.ifEmpty { fallback }
    }

    /**
     * Disambiguates colliding archive entry paths (`_2`, `_3`, ...) so `ZipOutputStream`
     * never throws `ZipException: duplicate entry`.
     */
    fun buildUniqueZipEntryPath(
        courseName: String,
        fileName: String,
        usedEntries: MutableSet<String>
    ): String {
        val safeFolder = sanitizePathSegment(courseName, "Uncategorized")
        val safeFile = sanitizePathSegment(fileName, "recording.m4a")

        val dotIdx = safeFile.lastIndexOf('.')
        val stem = if (dotIdx > 0) safeFile.substring(0, dotIdx) else safeFile
        val ext = if (dotIdx > 0) safeFile.substring(dotIdx) else ""

        var candidate = "$safeFolder/$safeFile"
        var suffix = 2
        while (!usedEntries.add(candidate)) {
            candidate = "$safeFolder/${stem}_$suffix$ext"
            suffix++
        }
        return candidate
    }

    /**
     * Deterministically previews `.m4a` filenames against `2026-11-18`, supporting all
     * RustCore template placeholders (including `{lesson number}` and `{lession number}`).
     */
    fun previewFilename(
        template: String,
        dateFormat: String,
        courseName: String = "大學之道",
        room: String = "3309",
        teacher: String = "何昕家",
        slot: String = "3",
        date: LocalDate = SAMPLE_PREVIEW_DATE,
        timeText: String = "1015"
    ): String {
        val safeCourse = sanitizePathSegment(courseName, "Uncategorized")
        val safeRoom = sanitizePathSegment(room, "Room")
        val safeTeacher = sanitizePathSegment(teacher, "Instructor")
        val effectiveTemplate = template.trim().ifEmpty { AppDefaults.FILENAME_TEMPLATE }
        val formattedDate = formatPreviewDate(
            date,
            dateFormat.trim().ifEmpty { AppDefaults.DATE_FORMAT }
        )
        val rawStem = effectiveTemplate
            .replace("{class}", safeCourse)
            .replace("{course}", safeCourse)
            .replace("{date}", formattedDate)
            .replace("{lesson number}", "1")
            .replace("{lesson_number}", "1")
            .replace("{lession number}", "1")
            .replace("{lession_number}", "1")
            .replace("{lesson}", "1")
            .replace("{lession}", "1")
            .replace("{room}", safeRoom)
            .replace("{teacher}", safeTeacher)
            .replace("{time}", timeText)
            .replace("{slot}", slot)
        val stem = sanitizePathSegment(rawStem, "${formattedDate}_${timeText}_$safeCourse")
        return if (stem.endsWith(".m4a", ignoreCase = true)) stem else "$stem.m4a"
    }

    private fun formatPreviewDate(date: LocalDate, pattern: String): String =
        runCatching {
            DateTimeFormatter.ofPattern(pattern, Locale.US).format(date)
        }.getOrElse {
            DateTimeFormatter.ISO_LOCAL_DATE.format(date)
        }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
