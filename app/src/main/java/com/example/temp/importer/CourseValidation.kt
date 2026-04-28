package com.example.temp.importer

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken

private const val TAG = "CourseValidation"

fun validateCourses(jsonString: String): List<Course> {
    val courses: List<Course> = try {
        val type = object : TypeToken<List<Course>>() {}.type
        Gson().fromJson<List<Course>>(jsonString, type) ?: emptyList()
    } catch (e: JsonSyntaxException) {
        Log.e(TAG, "Invalid JSON format: ${e.message}")
        return emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse courses JSON: ${e.message}")
        return emptyList()
    }

    return courses.mapIndexedNotNull { index, course ->
        val issues = mutableListOf<String>()

        if (course.name.isBlank()) issues += "name is blank"
        if (course.position.isBlank()) issues += "position is blank"
        if (course.teacher.isBlank()) issues += "teacher is blank"
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
