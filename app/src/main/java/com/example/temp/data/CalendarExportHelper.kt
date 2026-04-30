package com.example.temp.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CalendarExportHelper(
    context: Context
) {
    private val appContext = context.applicationContext

    suspend fun exportCourses(
        courses: List<Course>,
        semesterStartMonday: LocalDate,
        timeConfigManager: TimeConfigManager
    ): Result<Int> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(hasCalendarPermissions(appContext)) {
                    "Calendar read/write permissions are required before exporting courses."
                }

                val calendarId = findWritableCalendarId()
                    ?: error("No writable system calendar was found.")
                val events = buildCourseEvents(courses, semesterStartMonday, timeConfigManager)

                events.chunked(MAX_EVENTS_PER_BATCH).sumOf { chunk ->
                    insertEventBatch(calendarId, chunk)
                    chunk.size
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findWritableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = ? AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(
            "1",
            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()
        )

        appContext.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            var fallbackCalendarId: Long? = null
            while (cursor.moveToNext()) {
                val calendarId = cursor.getLong(0)
                val isPrimary = cursor.getInt(1) == 1
                if (isPrimary) return calendarId
                if (fallbackCalendarId == null) fallbackCalendarId = calendarId
            }
            return fallbackCalendarId
        }

        return null
    }

    @SuppressLint("MissingPermission")
    private fun insertEventBatch(calendarId: Long, events: List<CourseCalendarEvent>) {
        if (events.isEmpty()) return

        val operations = ArrayList<ContentProviderOperation>(events.size * 2)
        events.forEach { event ->
            val eventInsertIndex = operations.size
            operations += ContentProviderOperation
                .newInsert(CalendarContract.Events.CONTENT_URI)
                .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                .withValue(CalendarContract.Events.TITLE, event.title)
                .withValue(CalendarContract.Events.EVENT_LOCATION, event.location)
                .withValue(CalendarContract.Events.DESCRIPTION, event.description)
                .withValue(CalendarContract.Events.DTSTART, event.startMillis)
                .withValue(CalendarContract.Events.DTEND, event.endMillis)
                .withValue(CalendarContract.Events.EVENT_TIMEZONE, event.timeZoneId)
                .build()

            operations += ContentProviderOperation
                .newInsert(CalendarContract.Reminders.CONTENT_URI)
                .withValueBackReference(CalendarContract.Reminders.EVENT_ID, eventInsertIndex)
                .withValue(CalendarContract.Reminders.MINUTES, DEFAULT_REMINDER_MINUTES)
                .withValue(
                    CalendarContract.Reminders.METHOD,
                    CalendarContract.Reminders.METHOD_ALERT
                )
                .build()
        }

        appContext.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
    }

    private fun buildCourseEvents(
        courses: List<Course>,
        semesterStartMonday: LocalDate,
        timeConfigManager: TimeConfigManager
    ): List<CourseCalendarEvent> {
        val zoneId = ZoneId.systemDefault()

        return courses.flatMap { course ->
            val startPeriodTime = timeConfigManager.getTimeForPeriod(course.startPeriod)
            val endPeriodTime = timeConfigManager.getTimeForPeriod(course.endPeriod)
            val weeks = parseWeeks(course.weekRange)

            if (
                startPeriodTime == null ||
                endPeriodTime == null ||
                course.dayOfWeek !in DAYS_OF_WEEK ||
                weeks.isEmpty()
            ) {
                emptyList()
            } else {
                weeks.map { week ->
                    val courseDate = semesterStartMonday
                        .plusWeeks((week - 1).toLong())
                        .plusDays((course.dayOfWeek - 1).toLong())
                    val startDateTime = LocalDateTime.of(
                        courseDate,
                        LocalTime.parse(startPeriodTime.startTime)
                    )
                    val endDateTime = LocalDateTime.of(
                        courseDate,
                        LocalTime.parse(endPeriodTime.endTime)
                    )

                    CourseCalendarEvent(
                        title = course.name,
                        location = course.location,
                        description = buildCourseDescription(course, week),
                        startMillis = startDateTime.atZone(zoneId).toInstant().toEpochMilli(),
                        endMillis = endDateTime.atZone(zoneId).toInstant().toEpochMilli(),
                        timeZoneId = zoneId.id
                    )
                }
            }
        }
    }

    private fun buildCourseDescription(course: Course, week: Int): String {
        return listOf(
            "教师：${course.teacher}",
            "节次：${course.startPeriod}-${course.endPeriod}",
            "周次：第${week}周"
        ).joinToString("\n")
    }

    private fun parseWeeks(weekRange: String): List<Int> {
        val normalized = weekRange
            .replace("周", "")
            .replace("第", "")
            .replace("，", ",")
            .replace("、", ",")
            .replace("；", ",")
            .replace(";", ",")

        return normalized
            .split(",")
            .flatMap { token ->
                val rangeMatch = WEEK_RANGE_REGEX.find(token)
                if (rangeMatch != null) {
                    val start = rangeMatch.groupValues[1].toInt()
                    val end = rangeMatch.groupValues[2].toInt()
                    if (start <= end) (start..end).toList() else (end..start).toList()
                } else {
                    WEEK_NUMBER_REGEX.findAll(token).map { it.value.toInt() }.toList()
                }
            }
            .filter { it > 0 }
            .distinct()
            .sorted()
    }

    private data class CourseCalendarEvent(
        val title: String,
        val location: String,
        val description: String,
        val startMillis: Long,
        val endMillis: Long,
        val timeZoneId: String
    )

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )

        private const val DEFAULT_REMINDER_MINUTES = 15
        private const val MAX_EVENTS_PER_BATCH = 100
        private val DAYS_OF_WEEK = 1..7
        private val WEEK_RANGE_REGEX = Regex("(\\d+)\\s*[-~到至]\\s*(\\d+)")
        private val WEEK_NUMBER_REGEX = Regex("\\d+")

        fun hasCalendarPermissions(context: Context): Boolean {
            return REQUIRED_PERMISSIONS.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
}
