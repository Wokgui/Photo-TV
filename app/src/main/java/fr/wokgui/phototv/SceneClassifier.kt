package fr.wokgui.phototv

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

object SceneClassifier {
    const val PEOPLE = "Personnes"
    const val ANIMALS = "Animaux"
    const val FOOD = "Nourriture"
    const val PORTRAIT = "Portrait"
    const val NIGHT = "Nuit"
    const val SEA_SKY = "Mer / ciel"
    const val NATURE = "Nature / paysage"
    const val WARM = "Intérieur / chaleureux"
    const val URBAN = "Ville / architecture"
    const val GENERAL = "Général"

    fun fromLabels(labels: Collection<String>): String? {
        if (labels.isEmpty()) return null
        val text = labels.joinToString(" ").lowercase()
        fun has(vararg words: String): Boolean = words.any(text::contains)
        return when {
            has("person", "people", "face", "family", "child", "selfie", "portrait") -> PEOPLE
            has("animal", "dog", "cat", "bird", "horse", "pet", "wildlife", "mammal") -> ANIMALS
            has("food", "dish", "meal", "dessert", "fruit", "cuisine", "breakfast", "lunch", "dinner") -> FOOD
            has("sea", "ocean", "beach", "sky", "cloud", "coast") -> SEA_SKY
            has("city", "building", "architecture", "street", "skyscraper", "bridge") -> URBAN
            has("mountain", "landscape", "forest", "nature", "plant", "lake", "river", "garden") -> NATURE
            has("night", "darkness") -> NIGHT
            has("room", "interior", "home", "furniture") -> WARM
            else -> null
        }
    }

    fun classify(bitmap: Bitmap, width: Int = bitmap.width, height: Int = bitmap.height): String {
        if (bitmap.width <= 0 || bitmap.height <= 0) return GENERAL
        val portraitShape = height > width * 1.18f
        val cols = 8
        val rows = 6
        var luma = 0.0
        var saturation = 0.0
        var blueDominant = 0
        var greenDominant = 0
        var warmDominant = 0
        var samples = 0

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val x = ((col + .5f) * bitmap.width / cols).toInt().coerceIn(0, bitmap.width - 1)
                val y = ((row + .5f) * bitmap.height / rows).toInt().coerceIn(0, bitmap.height - 1)
                val color = bitmap.getPixel(x, y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                val hi = max(r, max(g, b))
                val lo = min(r, min(g, b))
                luma += .2126 * r + .7152 * g + .0722 * b
                saturation += hi - lo
                if (b > r * 1.12 && b > g * 1.05) blueDominant++
                if (g > r * 1.08 && g > b * 1.04) greenDominant++
                if (r > b * 1.18 && r >= g * .96) warmDominant++
                samples++
            }
        }

        val avgLuma = luma / samples.coerceAtLeast(1)
        val avgSat = saturation / samples.coerceAtLeast(1)
        val blueRatio = blueDominant.toDouble() / samples.coerceAtLeast(1)
        val greenRatio = greenDominant.toDouble() / samples.coerceAtLeast(1)
        val warmRatio = warmDominant.toDouble() / samples.coerceAtLeast(1)

        return when {
            avgLuma < 52.0 -> NIGHT
            portraitShape && avgLuma in 65.0..225.0 -> PORTRAIT
            blueRatio >= .34 && avgLuma >= 85.0 -> SEA_SKY
            greenRatio >= .28 && avgSat >= 25.0 -> NATURE
            warmRatio >= .32 && avgLuma in 65.0..190.0 -> WARM
            avgSat < 38.0 && avgLuma in 65.0..205.0 -> URBAN
            else -> GENERAL
        }
    }
}
