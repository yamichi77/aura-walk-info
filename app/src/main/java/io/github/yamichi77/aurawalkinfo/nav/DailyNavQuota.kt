package io.github.yamichi77.aurawalkinfo.nav

import android.content.SharedPreferences
import java.time.LocalDate

internal class DailyNavQuota(
    private val preferences: SharedPreferences,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    fun tryAcquire(endpoint: NavEndpoint): Boolean = synchronized(lock) {
        val date = today().toEpochDay()
        val dateKey = "${endpoint.name}.day"
        val countKey = "${endpoint.name}.count"
        val count = if (preferences.getLong(dateKey, Long.MIN_VALUE) == date) {
            preferences.getInt(countKey, 0)
        } else {
            0
        }
        if (count >= endpoint.dailyLimit) return false
        val saved = preferences.edit().putLong(dateKey, date).putInt(countKey, count + 1).commit()
        if (!saved) throw NavFailure(NavError.OTHER)
        true
    }

    private companion object {
        val lock = Any()
    }
}
