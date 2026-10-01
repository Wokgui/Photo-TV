package fr.wokgui.phototv

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneClassifierTest {
    @Test fun mapsPeopleLabels() {
        assertEquals(SceneClassifier.PEOPLE, SceneClassifier.fromLabels(listOf("Person", "Smile")))
    }

    @Test fun mapsAnimalLabels() {
        assertEquals(SceneClassifier.ANIMALS, SceneClassifier.fromLabels(listOf("Dog", "Pet")))
    }

    @Test fun mapsFoodLabels() {
        assertEquals(SceneClassifier.FOOD, SceneClassifier.fromLabels(listOf("Food", "Dessert")))
    }

    @Test fun mapsUrbanAndNatureLabels() {
        assertEquals(SceneClassifier.URBAN, SceneClassifier.fromLabels(listOf("Building", "Street")))
        assertEquals(SceneClassifier.NATURE, SceneClassifier.fromLabels(listOf("Mountain", "Landscape")))
    }
}
