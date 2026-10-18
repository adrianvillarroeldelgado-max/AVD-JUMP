package com.salto240

import android.content.Context
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Intentos de una prueba en un mismo día: mejor, media, variación entre intentos (CV) y avisos.
 *
 * - Saltos simples: altura. Drop Jump y saltos repetidos: RSI (si lo hay), que es lo que se evalúa.
 * - Se agrupan por prueba y carga (en el perfil F-V cada carga es una prueba distinta).
 * - Intento atípico: se aleja más de un 10 % de la mediana de los demás (con 3 o más intentos).
 * - CV > 5 % indica intentos poco consistentes: conviene revisar la técnica o hacer otro intento.
 */
object Attempts {

    data class Summary(
        val test: TestType,
        val loadKg: Double,
        val jumps: List<Jump>,
        val usesRsi: Boolean,
        val best: Double,
        val mean: Double,
        val cv: Double?,
        val atypical: Set<Long>
    ) {
        val n get() = jumps.size
        fun fmt(v: Double) = if (usesRsi) String.format(Locale.getDefault(), "RSI %.2f", v) else String.format(Locale.getDefault(), "%.1f cm", v)

        /** "CMJ · 3 intentos · mejor 42,1 cm · media 41,5 cm · CV 1,4 %" */
        fun line(): String {
            val load = if (loadKg > 0.4) String.format(Locale.getDefault(), " +%.1f kg", loadKg) else ""
            val sb = StringBuilder("${test.short}$load · $n ${if (n == 1) "intento" else "intentos"} · mejor ${fmt(best)}")
            if (n > 1) sb.append(" · media ").append(fmt(mean))
            cv?.let { sb.append(String.format(Locale.getDefault(), " · CV %.1f %%", it)) }
            return sb.toString()
        }

        /** Advertencias del grupo de intentos. */
        fun warnings(): List<String> {
            val w = ArrayList<String>()
            if (atypical.isNotEmpty()) w.add(if (atypical.size == 1) "1 intento atípico: revísalo o repítelo." else "${atypical.size} intentos atípicos: revísalos.")
            cv?.takeIf { it > 5.0 }?.let { w.add(String.format(Locale.getDefault(), "Variación alta entre intentos (CV %.1f %%, deseable < 5 %%): repasa la técnica.", it)) }
            val flagged = jumps.count { it.flags.isNotEmpty() }
            if (flagged > 0) w.add("$flagged ${if (flagged == 1) "intento con aviso" else "intentos con aviso"} de técnica.")
            return w
        }
    }

    private fun value(j: Jump, rsi: Boolean): Double? = if (rsi) j.rsi else j.heightCm

    /** Resúmenes de un conjunto de saltos (normalmente un día de un atleta), uno por prueba y carga. */
    fun summarize(js: List<Jump>): List<Summary> =
        js.groupBy { it.test to Math.round((it.loadKg ?: 0.0) * 2) / 2.0 }
            .mapNotNull { (k, list) -> summary(k.first, k.second, list.sortedBy { it.date }) }
            .sortedBy { it.test.ordinal }

    fun summary(test: TestType, load: Double, list: List<Jump>): Summary? {
        val rsi = (test.isDrop || test.isRepeated) && list.any { it.rsi != null }
        val vals = list.mapNotNull { j -> value(j, rsi)?.let { j to it } }
        if (vals.isEmpty()) return null
        val xs = vals.map { it.second }
        val mean = xs.average()
        val cv = if (xs.size >= 2 && mean > 0) {
            val sd = sqrt(xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1))
            sd / mean * 100
        } else null
        val atypical = HashSet<Long>()
        if (vals.size >= 3) for ((j, v) in vals) {
            val others = vals.filter { it.first.id != j.id }.map { it.second }.sorted()
            val med = if (others.size % 2 == 1) others[others.size / 2] else (others[others.size / 2 - 1] + others[others.size / 2]) / 2
            if (med > 0 && abs(v - med) / med > 0.10) atypical.add(j.id)
        }
        return Summary(test, load, list, rsi, xs.max(), mean, cv, atypical)
    }

    /** Texto para mostrar al guardar un intento: posición, mejor, media, CV y avisos. */
    fun afterSave(ctx: Context, saved: Jump): String? {
        val today = Store.ofAthlete(ctx, saved.athlete).filter { it.day == saved.day && it.test == saved.test &&
            Math.round((it.loadKg ?: 0.0) * 2) == Math.round((saved.loadKg ?: 0.0) * 2) }
        val s = summary(saved.test, saved.loadKg ?: 0.0, today) ?: return null
        val sb = StringBuilder("Intento ${s.n} de hoy · mejor ${s.fmt(s.best)}")
        if (s.n > 1) sb.append(" · media ").append(s.fmt(s.mean))
        s.cv?.let { sb.append(String.format(Locale.getDefault(), " · CV %.1f %%", it)) }
        if (saved.id in s.atypical) sb.append("\n⚠ Este intento se aleja más de un 10 % de los demás.")
        saved.flags.forEach { sb.append("\n⚠ ").append(it) }
        if (s.n < 3 && !saved.test.isRepeated) sb.append("\nRecomendado: 3 intentos válidos.")
        return sb.toString()
    }
}
