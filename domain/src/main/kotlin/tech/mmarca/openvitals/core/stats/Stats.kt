package tech.mmarca.openvitals.core.stats

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The empty-list contract for aggregates. [averageOrNull] means "unknown";
 * [averageOrZero] means "the true total was zero". Never NaN, which slips
 * past every guard and renders literally.
 */
fun Iterable<Double>.averageOrNull(): Double? {
    var sum = 0.0
    var count = 0
    for (value in this) {
        sum += value
        count++
    }
    return if (count == 0) null else sum / count
}

@JvmName("averageOrNullOfLong")
fun Iterable<Long>.averageOrNull(): Double? {
    var sum = 0.0
    var count = 0
    for (value in this) {
        sum += value
        count++
    }
    return if (count == 0) null else sum / count
}

@JvmName("averageOrNullOfInt")
fun Iterable<Int>.averageOrNull(): Double? {
    var sum = 0.0
    var count = 0
    for (value in this) {
        sum += value
        count++
    }
    return if (count == 0) null else sum / count
}

fun Iterable<Double>.averageOrZero(): Double = averageOrNull() ?: 0.0

@JvmName("averageOrZeroOfLong")
fun Iterable<Long>.averageOrZero(): Double = averageOrNull() ?: 0.0

/** The middle value; for an even count the mean of the two middle values. Null when empty. */
fun Iterable<Double>.medianOrNull(): Double? {
    val sorted = sorted()
    if (sorted.isEmpty()) return null
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}

@JvmName("medianOrNullOfInt")
fun Iterable<Int>.medianOrNull(): Double? = map(Int::toDouble).medianOrNull()

/**
 * The middle value; for an even count the lower of the two middle values, so
 * the result is always one of the values (a whole bpm, for example). Null when empty.
 */
fun Iterable<Long>.lowerMedianOrNull(): Long? {
    val sorted = sorted()
    if (sorted.isEmpty()) return null
    return sorted[sorted.lastIndex / 2]
}

/** The circular mean of minutes of day, so 23:30 and 00:30 average to 00:00. Null when empty. */
fun circularMeanMinutes(minutesOfDay: List<Int>): Int? {
    if (minutesOfDay.isEmpty()) return null
    val sinSum = minutesOfDay.sumOf { sin(it * 2.0 * PI / MinutesPerDay) }
    val cosSum = minutesOfDay.sumOf { cos(it * 2.0 * PI / MinutesPerDay) }
    val meanAngle = atan2(sinSum / minutesOfDay.size, cosSum / minutesOfDay.size)
    val minutes = (meanAngle * MinutesPerDay / (2.0 * PI)).roundToInt()
    return ((minutes % MinutesPerDay) + MinutesPerDay) % MinutesPerDay
}

private const val MinutesPerDay = 24 * 60
