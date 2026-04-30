package com.example.temp.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class ScheduleRepository private constructor(
    private val courseDao: CourseDao
) {
    val allCourses: Flow<List<Course>> = courseDao.getAllCourses()

    suspend fun insertCourse(course: Course): Long {
        return courseDao.insertCourse(course)
    }

    suspend fun insertCourses(courses: List<Course>): List<Long> {
        return courseDao.insertCourses(courses)
    }

    suspend fun clearSchedule() {
        courseDao.clearSchedule()
    }

    companion object {
        @Volatile
        private var instance: ScheduleRepository? = null

        fun getInstance(context: Context): ScheduleRepository {
            return instance ?: synchronized(this) {
                instance ?: ScheduleRepository(
                    AppDatabase.getInstance(context).courseDao()
                ).also { instance = it }
            }
        }
    }
}
