package io.github.tanzir71.harkara.core.tools

import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.Madhab
import com.batoulapps.adhan.Qibla
import com.batoulapps.adhan.data.DateComponents
import java.time.Instant
import java.time.LocalDate

/** The five prayers plus sunrise, as instants. */
data class DayPrayers(
    val fajr: Instant,
    val sunrise: Instant,
    val dhuhr: Instant,
    val asr: Instant,
    val maghrib: Instant,
    val isha: Instant,
) {
    fun all(): List<Pair<String, Instant>> = listOf("fajr" to fajr, "sunrise" to sunrise, "dhuhr" to dhuhr, "asr" to asr, "maghrib" to maghrib, "isha" to isha)

    /** The next prayer (not sunrise) at or after [now], or null after Isha. */
    fun next(now: Instant): Pair<String, Instant>? = all().filter { it.first != "sunrise" }.firstOrNull { !it.second.isBefore(now) }
}

/**
 * Prayer times and Qibla, computed on the phone with Adhan (MIT). Defaults per the handover:
 * Karachi method (University of Islamic Sciences), Hanafi Asr.
 */
object PrayerTimesCalc {
    fun forDay(latitude: Double, longitude: Double, date: LocalDate, hanafi: Boolean = true): DayPrayers {
        val params = CalculationMethod.KARACHI.parameters.also { it.madhab = if (hanafi) Madhab.HANAFI else Madhab.SHAFI }
        val times = com.batoulapps.adhan.PrayerTimes(Coordinates(latitude, longitude), DateComponents(date.year, date.monthValue, date.dayOfMonth), params)
        return DayPrayers(
            times.fajr.toInstant(), times.sunrise.toInstant(), times.dhuhr.toInstant(),
            times.asr.toInstant(), times.maghrib.toInstant(), times.isha.toInstant(),
        )
    }

    /** Degrees clockwise from true north towards the Kaaba. */
    fun qibla(latitude: Double, longitude: Double): Double = Qibla(Coordinates(latitude, longitude)).direction
}

/** Morse code for the torch and the siren; SOS is `... --- ...`. */
object Morse {
    private val table = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.", 'H' to "....",
        'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.",
        'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..", '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
    )

    fun encode(text: String): String = text.uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .joinToString(" / ") { word -> word.mapNotNull { table[it] }.joinToString(" ") }

    /**
     * On/off durations in milliseconds (starting with "on"): dot = 1 unit, dash = 3, gap inside a letter
     * = 1, between letters = 3, between words = 7. [unitMs] 200 ms ≈ 6 words per minute, readable by eye.
     */
    fun timings(text: String, unitMs: Long = 200): List<Long> {
        val out = mutableListOf<Long>()
        val words = text.uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        words.forEachIndexed { wi, word ->
            val letters = word.mapNotNull { table[it] }
            letters.forEachIndexed { li, code ->
                code.forEachIndexed { si, symbol ->
                    out += if (symbol == '.') unitMs else 3 * unitMs
                    if (si < code.lastIndex) out += unitMs
                }
                if (li < letters.lastIndex) out += 3 * unitMs
            }
            if (wi < words.lastIndex) out += 7 * unitMs
        }
        return out
    }

    /** SOS repeated, with a word gap before repeating. */
    fun sos(unitMs: Long = 200): List<Long> = timings("SOS", unitMs) + (7 * unitMs)
}
