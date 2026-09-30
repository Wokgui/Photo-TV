package fr.wokgui.phototv

object SlideshowOrder {
    fun newBag(size: Int, currentIndex: Int, random: kotlin.random.Random = kotlin.random.Random.Default): List<Int> {
        if (size <= 1) return emptyList()
        val current = currentIndex.coerceIn(0, size - 1)
        val bag = (0 until size).filter { it != current }.toMutableList()
        bag.shuffle(random)
        return bag
    }
}
