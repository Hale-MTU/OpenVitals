package tech.mmarca.openvitals.domain.insights

import tech.mmarca.openvitals.domain.model.BeverageCategory
import tech.mmarca.openvitals.domain.model.CustomHydrationDrink
import tech.mmarca.openvitals.domain.model.NutritionNutrient

/**
 * OpenVitals' own catalog of generic espresso-and-milk drinks. Each drink is a
 * recipe of one espresso shot and milk, and its caffeine and nutrients are the
 * sum of those two parts. That keeps every figure traceable to one source:
 *
 * - Recipe volumes: Nespresso Professional, "Momento Milk Recipe Book" (espresso
 *   macchiato 40 + 35 ml, cortado 40 + 15 ml, flat white 40 + 20 + 90 ml, latte
 *   macchiato 40 + 100 + 120 ml). The book's milk and milk foam are both counted
 *   as milk, which slightly overstates the milk, since foam holds air.
 * - Espresso: USDA FoodData Central SR Legacy 171891, "Beverages, coffee,
 *   brewed, espresso, restaurant-prepared", per 100 g: 9 kcal, 1.67 g
 *   carbohydrate, 0.18 g fat, 0.12 g protein, 212 mg caffeine.
 * - Milk: USDA FoodData Central SR Legacy 172217, "Milk, whole, 3.25% milkfat",
 *   per 100 g: 61 kcal, 3.15 g protein, 3.25 g fat, 4.8 g carbohydrate (all
 *   lactose), 43 mg sodium, 132 mg potassium, 113 mg calcium.
 *
 * A millilitre counts as a gram for both parts. Semi-skimmed or plant milk, or a
 * double shot, give other figures; log those as a custom drink.
 */
object CoffeeRecipeDrinkCatalog : HydrationDrinkCatalog {
    private const val BeveragePresetIdPrefix = "coffeerecipe-"

    data class Recipe(
        val id: String,
        val name: String,
        val espressoMilliliters: Double,
        val milkMilliliters: Double,
    ) {
        val servingMilliliters: Double get() = espressoMilliliters + milkMilliliters
    }

    val recipes: List<Recipe> = listOf(
        Recipe(id = "espresso-macchiato", name = "Espresso Macchiato", espressoMilliliters = 40.0, milkMilliliters = 35.0),
        Recipe(id = "cortado", name = "Cortado", espressoMilliliters = 40.0, milkMilliliters = 15.0),
        Recipe(id = "flat-white", name = "Flat White", espressoMilliliters = 40.0, milkMilliliters = 110.0),
        Recipe(id = "latte-macchiato", name = "Latte Macchiato", espressoMilliliters = 40.0, milkMilliliters = 220.0),
    )

    override fun beveragePresets(): List<CustomHydrationDrink> =
        recipes.map { recipe ->
            CustomHydrationDrink(
                id = "$BeveragePresetIdPrefix${recipe.id}",
                name = recipe.name,
                volumeMilliliters = recipe.servingMilliliters,
                nutrientValues = nutrientValuesFor(recipe),
                category = BeverageCategory.COFFEE,
                isPreloaded = true,
            )
        }

    fun nutrientValuesFor(recipe: Recipe): Map<NutritionNutrient, Double> {
        val espresso = recipe.espressoMilliliters / GramsPerHundred
        val milk = recipe.milkMilliliters / GramsPerHundred
        return buildMap {
            put(NutritionNutrient.ENERGY, espresso * 9.0 + milk * 61.0)
            put(NutritionNutrient.TOTAL_CARBOHYDRATE, espresso * 1.67 + milk * 4.8)
            put(NutritionNutrient.SUGAR, milk * 4.8)
            put(NutritionNutrient.TOTAL_FAT, espresso * 0.18 + milk * 3.25)
            put(NutritionNutrient.PROTEIN, espresso * 0.12 + milk * 3.15)
            put(NutritionNutrient.SODIUM, milk * 43.0 / MilligramsPerGram)
            put(NutritionNutrient.POTASSIUM, milk * 132.0 / MilligramsPerGram)
            put(NutritionNutrient.CALCIUM, milk * 113.0 / MilligramsPerGram)
            put(NutritionNutrient.CAFFEINE, espresso * 212.0 / MilligramsPerGram)
        }
    }

    private const val GramsPerHundred = 100.0
    private const val MilligramsPerGram = 1000.0
}
