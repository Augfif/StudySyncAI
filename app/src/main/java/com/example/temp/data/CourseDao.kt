package com.example.temp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: Course): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<Course>): List<Long>

    @Query("DELETE FROM courses")
    suspend fun clearSchedule()

    @Query("SELECT * FROM courses ORDER BY dayOfWeek ASC, startPeriod ASC, endPeriod ASC")
    fun getAllCourses(): Flow<List<Course>>
}
