package com.salto240

import java.time.LocalDate
import kotlin.math.sqrt

/** Cálculos de carga sin Android (se prueban en la computadora). Cargas como (día "yyyy-MM-dd", UA). */
object LoadCalc {
    data class Calc(val acute: Double, val chronic: Double, val acwr: Double?, val week: Int,
                    val monotony: Double?, val strain: Double?, val days: Int,
                    /** null sin datos suficientes; 0 verde, 1 amarillo, 2 rojo. */
                    val zone: Int?)

    /** Carga diaria desde [from] hasta [to], ambos incluidos; días sin sesión = 0. */
    fun daily(loads: List<Pair<String, Double>>, from: LocalDate, to: LocalDate): List<Pair<LocalDate, Double>> {
        val byDay = loads.groupBy { it.first }.mapValues { (_, l) -> l.sumOf { it.second } }
        val out = ArrayList<Pair<LocalDate, Double>>()
        var d = from
        while (!d.isAfter(to)) { out.add(d to (byDay[d.toString()] ?: 0.0)); d = d.plusDays(1) }
        return out
    }

    /** Medias móviles exponenciales aguda (7 d) y crónica (28 d), día a día (Williams et al. 2017). */
    fun ewma(loads: List<Double>): List<Pair<Double, Double>> {
        val la = 2.0 / (7 + 1); val lc = 2.0 / (28 + 1)
        var a = 0.0; var c = 0.0
        return loads.map { x -> a = la * x + (1 - la) * a; c = lc * x + (1 - lc) * c; a to c }
    }

    fun zone(acwr: Double?): Int? = when {
        acwr == null -> null
        acwr > 1.5 -> 2
        acwr > 1.3 || acwr < 0.8 -> 1
        else -> 0
    }

    fun status(loads: List<Pair<String, Double>>, day: LocalDate): Calc? {
        if (loads.isEmpty()) return null
        val first = loads.minOf { LocalDate.parse(it.first) }
        if (first.isAfter(day)) return null
        val d = daily(loads, first, day)
        val (a, c) = ewma(d.map { it.second }).last()
        val acwr = if (d.size >= 21 && c > 0) a / c else null
        val last7 = d.takeLast(7).map { it.second }
        val week = last7.sum().toInt()
        val mean = last7.average()
        val sd = sqrt(last7.sumOf { (it - mean) * (it - mean) } / (last7.size - 1).coerceAtLeast(1))
        val mono = if (d.size >= 7 && sd > 0) mean / sd else null
        return Calc(a, c, acwr, week, mono, mono?.let { week * it }, d.size, zone(acwr))
    }
}
