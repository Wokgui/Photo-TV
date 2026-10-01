package fr.wokgui.phototv

import java.util.Calendar

object MemoriesPolicy {
    fun isSameDayPreviousYear(takenAt: Long, now: Calendar = Calendar.getInstance()): Boolean {
        if (takenAt <= 0L) return false
        val date = Calendar.getInstance().apply { timeInMillis = takenAt }
        return date.get(Calendar.YEAR) < now.get(Calendar.YEAR) &&
            date.get(Calendar.MONTH) == now.get(Calendar.MONTH) &&
            date.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH)
    }

    fun isSameMonth(takenAt: Long, now: Calendar = Calendar.getInstance()): Boolean {
        if (takenAt <= 0L) return false
        val date = Calendar.getInstance().apply { timeInMillis = takenAt }
        return date.get(Calendar.MONTH) == now.get(Calendar.MONTH)
    }

    fun bestScore(
        favorite: Boolean,
        width: Int,
        height: Int
    ): Long {
        val favoriteBoost = if (favorite) 10_000_000_000_000L else 0L
        return favoriteBoost +
            width.toLong().coerceAtLeast(0L) * height.toLong().coerceAtLeast(0L)
    }
}
