package fr.wokgui.phototv

object ProfilePolicy {
    const val PERSONAL = 0
    const val FAMILY = 1
    const val GUEST = 2

    val labels = listOf("Principal", "Famille", "Invités")

    fun normalize(index: Int): Int = index.coerceIn(PERSONAL, GUEST)
    fun label(index: Int): String = labels[normalize(index)]
    fun isGuest(index: Int): Boolean = normalize(index) == GUEST
}
