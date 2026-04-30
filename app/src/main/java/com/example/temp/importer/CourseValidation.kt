package com.example.temp.importer

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

private const val TAG = "CourseValidation"
private const val UNKNOWN_POSITION = "未知地点"
private const val UNKNOWN_TEACHER = "未知教师"

fun validateCourses(jsonString: String): List<Course> {
    val root = try {
        JsonParser.parseString(jsonString)
    } catch (e: JsonSyntaxException) {
        Log.e(TAG, "Invalid JSON format: ${e.message}")
        return emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse courses JSON: ${e.message}")
        return emptyList()
    }

    val courses = normalizeCourses(root)
    if (courses.isEmpty()) {
        Log.e(TAG, "No course candidates found. Expected a JSON array or a compatible timetable object.")
        return emptyList()
    }

    return courses.mapIndexedNotNull { index, course ->
        val issues = mutableListOf<String>()

        if (course.name.isBlank()) issues += "name is blank"
        if (course.day !in 1..7) issues += "day out of range: ${course.day}"

        if (course.weeks.isEmpty()) {
            issues += "weeks is empty"
        } else if (course.weeks.any { it <= 0 }) {
            issues += "weeks contains non-positive value: ${course.weeks}"
        }

        if (course.sections.isEmpty()) {
            issues += "sections is empty"
        } else if (course.sections.any { it <= 0 }) {
            issues += "sections contains non-positive value: ${course.sections}"
        }

        if (issues.isNotEmpty()) {
            Log.e(TAG, "Drop invalid course at index=$index, reasons=${issues.joinToString("; ")}")
            null
        } else {
            course
        }
    }
}

private fun normalizeCourses(root: JsonElement): List<Course> {
    if (root.isJsonArray) {
        return parseCourseArray(root.asJsonArray)
    }

    if (!root.isJsonObject) {
        Log.e(TAG, "Root JSON is neither array nor object.")
        return emptyList()
    }

    val obj = root.asJsonObject
    obj.arrayOrNull("courses")?.let { return parseCourseArray(it) }

    obj.arrayOrNull("timetable")?.let { timetable ->
        return timetable.flatMapIndexed { dayIndex, dayElement ->
            val dayObject = dayElement.asObjectOrNull()
            if (dayObject == null) {
                Log.e(TAG, "Drop invalid timetable item at index=$dayIndex: not an object")
                emptyList()
            } else {
                val day = parseDay(dayObject.get("day")) ?: 0
                val courses = dayObject.arrayOrNull("courses")
                if (courses == null) {
                    Log.e(TAG, "Drop timetable day at index=$dayIndex: courses is missing")
                    emptyList()
                } else {
                    parseCourseArray(courses, fallbackDay = day)
                }
            }
        }
    }

    Log.e(TAG, "Unsupported JSON object shape. keys=${obj.keySet().joinToString()}")
    return emptyList()
}

private fun parseCourseArray(array: JsonArray, fallbackDay: Int? = null): List<Course> {
    return array.mapIndexedNotNull { index, element ->
        val obj = element.asObjectOrNull()
        if (obj == null) {
            Log.e(TAG, "Drop invalid course candidate at index=$index: not an object")
            null
        } else {
            parseCourseObject(obj, fallbackDay)
        }
    }
}

private fun parseCourseObject(obj: JsonObject, fallbackDay: Int?): Course {
    val position = obj.firstString("position", "room", "classroom", "location")
    val teacher = obj.firstString("teacher", "teacherName")

    if (position.isBlank()) {
        Log.e(TAG, "Course '${obj.firstString("name", "courseName", "course_name")}' has no position, keep it as $UNKNOWN_POSITION")
    }
    if (teacher.isBlank()) {
        Log.e(TAG, "Course '${obj.firstString("name", "courseName", "course_name")}' has no teacher, keep it as $UNKNOWN_TEACHER")
    }

    return Course(
        name = obj.firstString("name", "courseName", "course_name").trim(),
        position = position.ifBlank { UNKNOWN_POSITION },
        teacher = teacher.ifBlank { UNKNOWN_TEACHER },
        weeks = parseIntList(obj.get("weeks")),
        day = parseDay(obj.get("day")) ?: fallbackDay ?: 0,
        sections = parseIntList(obj.get("sections")).ifEmpty {
            parseIntList(obj.get("time"))
        }
    )
}

private fun JsonObject.firstString(vararg names: String): String {
    for (name in names) {
        val element = get(name)
        if (element != null && !element.isJsonNull && element.isJsonPrimitive) {
            val value = element.asString.trim()
            if (value.isNotEmpty() && value != "null") return value
        }
    }
    return ""
}

private fun JsonObject.arrayOrNull(name: String): JsonArray? {
    val element = get(name)
    return if (element != null && element.isJsonArray) element.asJsonArray else null
}

private fun JsonElement.asObjectOrNull(): JsonObject? {
    return if (isJsonObject) asJsonObject else null
}

private fun parseDay(element: JsonElement?): Int? {
    if (element == null || element.isJsonNull) return null

    if (element.isJsonPrimitive) {
        val primitive = element.asJsonPrimitive
        if (primitive.isNumber) return primitive.asInt

        val text = primitive.asString.trim()
        text.toIntOrNull()?.let { return it }

        return when {
            text.contains("周一") || text.contains("星期一") || text.equals("Monday", true) -> 1
            text.contains("周二") || text.contains("星期二") || text.equals("Tuesday", true) -> 2
            text.contains("周三") || text.contains("星期三") || text.equals("Wednesday", true) -> 3
            text.contains("周四") || text.contains("星期四") || text.equals("Thursday", true) -> 4
            text.contains("周五") || text.contains("星期五") || text.equals("Friday", true) -> 5
            text.contains("周六") || text.contains("星期六") || text.equals("Saturday", true) -> 6
            text.contains("周日") || text.contains("周天") ||
                text.contains("星期日") || text.contains("星期天") ||
                text.equals("Sunday", true) -> 7
            else -> null
        }
    }

    return null
}

private fun parseIntList(element: JsonElement?): List<Int> {
    if (element == null || element.isJsonNull) return emptyList()

    if (element.isJsonArray) {
        return element.asJsonArray.flatMap { parseIntList(it) }.distinct()
    }

    if (!element.isJsonPrimitive) return emptyList()

    val primitive = element.asJsonPrimitive
    if (primitive.isNumber) return listOf(primitive.asInt)

    return parsePositiveIntsFromText(primitive.asString)
}

private fun parsePositiveIntsFromText(text: String): List<Int> {
    val range = Regex("(\\d+)\\s*[-~到至]\\s*(\\d+)").find(text)
    if (range != null) {
        val start = range.groupValues[1].toInt()
        val end = range.groupValues[2].toInt()
        return if (start <= end) (start..end).toList() else (end..start).toList()
    }

    return Regex("\\d+")
        .findAll(text)
        .map { it.value.toInt() }
        .toList()
}
