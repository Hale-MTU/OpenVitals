# Manual Entry Of Metrics

> **Status:** Current implemented behavior.
> **Audience:** Users and contributors.
> **Implementation:** `features/manualentry` and child packages for activity, hydration, food, nutrition, body, vitals, and mindfulness.
> **Navigation:** `Screen.ManualEntry` and manual entry routes; widgets in `ManualEntryWidgetId`.
> **Related:** [Feature map](feature-map.md), [Permissions](../app/permissions.md), [Beverage logging and caffeine](beverage-logging-and-caffeine.md), [Food logging](food-logging.md).

Manual entry flows let the user write explicit records to Health Connect. OpenVitals does not keep a separate health database for these records.

## Weight

Weight is a core manual metric flow. The user enters a value and saves it to Health Connect. OpenVitals-created weight entries can later be edited or deleted when the app has the required write permission. Weight data also feeds body detail screens, BMI, body composition context, and statistics.

## Supported Manual Entries

OpenVitals supports explicit logging for:

- Beverages/hydration, including drink/container choices, custom amounts, caffeine-aware presets, and selected nutrition defaults.
- Foods from a catalog the user builds: each food has a name, an amount, a category and nutrients; a logged portion scales the nutrients and is saved as a Health Connect nutrition record.
- Nutrition totals typed in directly: energy, protein, total fat and total carbohydrate on one form, plus any other nutrient Health Connect stores (fiber, sugar, fats, vitamins, minerals) added from a chooser. One save writes one Health Connect nutrition record; blank fields are left out, and the date and time can be set back to log earlier days. Caffeine is not offered, because it is logged as a drink. Amounts do not change with the unit system, matching the nutrition screens: energy in kcal, the macros in grams, and vitamins and minerals in the mg or µg a nutrition label uses. These records are not drinks, so the beverage screens skip them.
- Activity sessions, optionally with routes, distance, elevation, calories, repetitions, title, and notes.
- Mindfulness sessions through a timer or manual duration.
- Body measurements such as weight, height, and body fat.
- Vitals such as blood pressure, SpO2, respiratory rate, and body temperature.

## Permission Handling

Write permissions can be granted during onboarding or requested when an entry flow needs them. The dashboard remains read-only even if write permissions have already been granted.

## External Records

Records created by other apps stay read-only in OpenVitals. OpenVitals checks ownership before allowing edits or deletes of records it created.

Editing an activity follows the same line. The edit form is filled with the steps, distance, elevation and calories that OpenVitals itself stored for that session, never with the total across apps or a calorie estimate. A save replaces only the totals the user changed. A total the user left alone keeps its stored value, including one the form does not show for that activity type, such as the steps of a run imported from a watch. The stored heart rate, power, cadence and speed series are kept too.

## Calorie Estimate For A Workout

A recorded or imported workout that measured no calories gets an estimate in the form, which the user can change before saving. It is never made without a weight in the body profile: the form then says that the weight is missing.

- **From the heart rate**, when the session has one and the body profile has sex and birth year: Keytel et al., "Prediction of energy expenditure from heart rate monitoring during submaximal exercise", J Sports Sci 23(3):289–297 (2005), doi:10.1080/02640410470001730089, the model without VO2max. The coefficients were read in Keytel's PhD thesis (University of Cape Town, 2004), chapter 4. It is used only while the mean heart rate is between 57 % and 90 % of the maximum (the profile's, or the age-predicted one), the range the equation was derived on; that limit is this project's rule.
- **From the activity**, otherwise: the MET value of the 2024 Adult Compendium of Physical Activities (Herrmann et al., J Sport Health Sci 13(1):6–12, 2024, doi:10.1016/j.jshs.2023.10.010). `domain/insights/CompendiumMet.kt` keeps the Compendium code beside each Health Connect exercise type; a type with no matching activity gets no estimate.
- **The resting share** is the body profile's basal metabolic rate (Mifflin-St Jeor) for the session's length, or 1 MET when the profile cannot give one. Active calories are the gross estimate minus that share.
- **From the distance on foot**, for a walk or run without a usable heart rate: ACSM's metabolic equations for level walking and running (ACSM's Guidelines for Exercise Testing and Prescription, 10th ed., 2018, Table 6.3), whose horizontal parts are 0.1 and 0.2 ml O2 per kg per metre, about 0.49 and 0.98 kcal per kg per km at the table's 4.9 kcal per litre. Margaria et al. 1963 (doi:10.1152/jappl.1963.18.2.367) found the same order for running, independent of speed. Only at the pace the table names: walking at 50 to 100 m/min, running above 134 m/min; otherwise the Compendium MET is used. Ascent is not counted.
