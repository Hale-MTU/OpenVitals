package tech.mmarca.openvitals.features.manualentry.activity

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.openvitals.domain.preferences.UnitSystem

class ActivityLoadInputTest {

    @Test fun `metric text is the form's text both ways`() {
        assertEquals("12,5", loadInputToKgText("12,5", UnitSystem.METRIC))
        assertEquals("12.5", kgTextToLoadInput("12.5", UnitSystem.METRIC))
    }

    @Test fun `pounds are kept as kilograms and come back as typed`() {
        for (pounds in listOf("135", "45", "2.5", "225", "317.5", "1")) {
            val kgText = loadInputToKgText(pounds, UnitSystem.IMPERIAL)
            assertEquals(pounds, kgTextToLoadInput(kgText, UnitSystem.IMPERIAL))
        }
        assertEquals("61.235", loadInputToKgText("135", UnitSystem.IMPERIAL))
        assertEquals("61.235", loadInputToKgText(" 135,0 ", UnitSystem.IMPERIAL))
    }

    @Test fun `what is not a positive number passes through for the form to reject`() {
        assertEquals("abc", loadInputToKgText("abc", UnitSystem.IMPERIAL))
        assertEquals("0", loadInputToKgText("0", UnitSystem.IMPERIAL))
        assertEquals("", loadInputToKgText("", UnitSystem.IMPERIAL))
        assertEquals("", kgTextToLoadInput("", UnitSystem.IMPERIAL))
        assertEquals("abc", kgTextToLoadInput("abc", UnitSystem.IMPERIAL))
    }

    @Test fun `a load over the limit in pounds is over the limit in kilograms`() {
        assertEquals(null, loadInputToKgText("2300", UnitSystem.IMPERIAL).toWeightKgOrNull())
        assertEquals(997.903, loadInputToKgText("2200", UnitSystem.IMPERIAL).toWeightKgOrNull()!!, 0.0005)
    }
}
