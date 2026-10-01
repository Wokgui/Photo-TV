package fr.wokgui.phototv

object NightSchedule {
    fun isActive(
        enabled: Boolean,
        startHour: Int,
        endHour: Int,
        currentHour: Int
    ): Boolean {
        if (!enabled) return false

        val start = startHour.coerceIn(0, 23)
        val end = endHour.coerceIn(0, 23)
        val hour = currentHour.coerceIn(0, 23)

        return when {
            start == end -> true
            start < end -> hour in start until end
            else -> hour >= start || hour < end
        }
    }
}
