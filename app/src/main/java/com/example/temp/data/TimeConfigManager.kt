package com.example.temp.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class TimeConfigManager private constructor(
    context: Context
) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val gson = Gson()
    private val periodTimeListType = object : TypeToken<List<PeriodTime>>() {}.type

    init {
        ensureDefaultScheduleSaved()
    }

    fun getPeriodTimes(): List<PeriodTime> {
        val json = preferences.getString(KEY_PERIOD_TIMES, null)
        return json?.let {
            gson.fromJson<List<PeriodTime>>(it, periodTimeListType)
        } ?: DEFAULT_PERIOD_TIMES
    }

    fun updatePeriodTime(period: Int, startTime: String, endTime: String): List<PeriodTime> {
        return updatePeriodTime(
            PeriodTime(
                period = period,
                startTime = startTime,
                endTime = endTime
            )
        )
    }

    fun updatePeriodTime(periodTime: PeriodTime): List<PeriodTime> {
        require(periodTime.period in DEFAULT_PERIOD_RANGE) {
            "Period must be between $FIRST_PERIOD and $LAST_PERIOD."
        }

        val updatedTimes = getPeriodTimes()
            .map { current ->
                if (current.period == periodTime.period) periodTime else current
            }
            .sortedBy { it.period }

        savePeriodTimes(updatedTimes)
        return updatedTimes
    }

    fun getTimeForPeriod(period: Int): PeriodTime? {
        return getPeriodTimes().firstOrNull { it.period == period }
    }

    private fun ensureDefaultScheduleSaved() {
        if (!preferences.contains(KEY_PERIOD_TIMES)) {
            savePeriodTimes(DEFAULT_PERIOD_TIMES)
        }
    }

    private fun savePeriodTimes(periodTimes: List<PeriodTime>) {
        preferences.edit()
            .putString(KEY_PERIOD_TIMES, gson.toJson(periodTimes))
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "time_config"
        private const val KEY_PERIOD_TIMES = "period_times"
        private const val FIRST_PERIOD = 1
        private const val LAST_PERIOD = 12
        private val DEFAULT_PERIOD_RANGE = FIRST_PERIOD..LAST_PERIOD

        val DEFAULT_PERIOD_TIMES = listOf(
            PeriodTime(period = 1, startTime = "08:00", endTime = "08:45"),
            PeriodTime(period = 2, startTime = "08:55", endTime = "09:40"),
            PeriodTime(period = 3, startTime = "10:00", endTime = "10:45"),
            PeriodTime(period = 4, startTime = "10:55", endTime = "11:40"),
            PeriodTime(period = 5, startTime = "14:00", endTime = "14:45"),
            PeriodTime(period = 6, startTime = "14:55", endTime = "15:40"),
            PeriodTime(period = 7, startTime = "16:00", endTime = "16:45"),
            PeriodTime(period = 8, startTime = "16:55", endTime = "17:40"),
            PeriodTime(period = 9, startTime = "19:00", endTime = "19:45"),
            PeriodTime(period = 10, startTime = "19:55", endTime = "20:40"),
            PeriodTime(period = 11, startTime = "20:50", endTime = "21:35"),
            PeriodTime(period = 12, startTime = "21:45", endTime = "22:30")
        )

        @Volatile
        private var instance: TimeConfigManager? = null

        fun getInstance(context: Context): TimeConfigManager {
            return instance ?: synchronized(this) {
                instance ?: TimeConfigManager(context).also { instance = it }
            }
        }
    }
}
