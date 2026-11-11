package tech.mmarca.openvitals.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.domain.model.NutritionNutrient

class CoffeeRecipeDrinkCatalogTest {

    private val presets = CoffeeRecipeDrinkCatalog.beveragePresets().associateBy { it.name }

    @Test
    fun `serving volumes are the Nespresso recipe sizes`() {
        assertEquals(75.0, presets.getValue("Espresso Macchiato").volumeMilliliters, 0.0)
        assertEquals(55.0, presets.getValue("Cortado").volumeMilliliters, 0.0)
        assertEquals(150.0, presets.getValue("Flat White").volumeMilliliters, 0.0)
        assertEquals(260.0, presets.getValue("Latte Macchiato").volumeMilliliters, 0.0)
    }

    @Test
    fun `a 40 ml shot carries the USDA espresso caffeine`() {
        presets.values.forEach { drink ->
            assertEquals(0.0848, drink.nutrientValues.getValue(NutritionNutrient.CAFFEINE), 1e-9)
        }
    }

    @Test
    fun `flat white energy is its espresso plus its milk`() {
        val kcal = presets.getValue("Flat White").nutrientValues.getValue(NutritionNutrient.ENERGY)

        assertEquals(0.4 * 9.0 + 1.1 * 61.0, kcal, 1e-9)
    }

    @Test
    fun `more milk means more energy and calcium`() {
        val cortado = presets.getValue("Cortado").nutrientValues
        val latte = presets.getValue("Latte Macchiato").nutrientValues

        assertTrue(latte.getValue(NutritionNutrient.ENERGY) > cortado.getValue(NutritionNutrient.ENERGY))
        assertTrue(latte.getValue(NutritionNutrient.CALCIUM) > cortado.getValue(NutritionNutrient.CALCIUM))
    }

    @Test
    fun `ids are unique and carry the catalog prefix`() {
        val ids = CoffeeRecipeDrinkCatalog.beveragePresets().map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("coffeerecipe-") })
    }
}
