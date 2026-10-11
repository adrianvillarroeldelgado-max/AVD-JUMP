package com.salto240

import java.util.Locale

/** Indicador que se sigue a lo largo de las evaluaciones. */
enum class Metric(val label: String, val unit: String, val higherIsBetter: Boolean, val decimals: Int) {
    CMJ("CMJ", "cm", true, 1),
    CMJA("CMJ con brazos", "cm", true, 1),
    SJ("SJ sin carga", "cm", true, 1),
    RSI("RSI (Drop Jump)", "", true, 2),
    ASYM("Asimetría", "%", false, 1),
    RJ("Saltos repetidos (media)", "cm", true, 1),
    PMAX("Pmax perfil F-V", "W/kg", true, 1),
    FV("Perfil F-V (% del óptimo)", "%", true, 0);

    fun format(v: Double): String =
        String.format(Locale.getDefault(), "%.${decimals}f", v) + if (unit.isEmpty()) "" else " $unit"
}

/** Una evaluación = todos los saltos de un cliente en un mismo día. */
data class Evaluation(val day: String, val jumps: List<Jump>, val values: Map<Metric, Double>, val notes: List<String>)

object Evaluations {

    private fun fmt(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    /** Valor de cada indicador en un día. Para el perfil F-V toma el protocolo con más cargas ese día. */
    fun values(day: List<Jump>, athlete: Athlete): Map<Metric, Double> {
        val m = mutableMapOf<Metric, Double>()
        Analytics.best(day, TestType.CMJ)?.let { m[Metric.CMJ] = it }
        Analytics.best(day, TestType.CMJA)?.let { m[Metric.CMJA] = it }
        Analytics.bestSjUnloaded(day)?.let { m[Metric.SJ] = it }
        day.filter { it.test == TestType.DJ }.mapNotNull { it.rsi }.maxOrNull()?.let { m[Metric.RSI] = it }
        Analytics.asymmetry(day)?.let { m[Metric.ASYM] = it.third }
        day.filter { it.test == TestType.RJ }.maxByOrNull { it.date }?.let { m[Metric.RJ] = it.heightCm }
        val hpo = athlete.hpo
        if (hpo != null && day.isNotEmpty()) {
            val d = day.first().day
            val fv = Analytics.FV_TESTS.mapNotNull { t ->
                Analytics.fvProfile(Analytics.fvPoints(day, athlete, d, t), hpo)
            }.maxByOrNull { it.points.size }
            fv?.let { m[Metric.PMAX] = it.pmax; m[Metric.FV] = it.ratio }
        }
        return m
    }

    /** Evaluaciones del cliente, de la más reciente a la más antigua. */
    fun list(js: List<Jump>, athlete: Athlete): List<Evaluation> =
        js.filter { it.athlete == athlete.name }
            .groupBy { it.day }
            .toSortedMap(compareByDescending { it })
            .map { (d, list) ->
                val notes = list.map { it.notes }.filter { it.isNotBlank() && it != "manual" }.distinct()
                Evaluation(d, list.sortedBy { it.date }, values(list, athlete), notes)
            }

    /** Serie temporal de un indicador (orden cronológico). */
    fun trend(evals: List<Evaluation>, metric: Metric): List<Pair<String, Double>> =
        evals.mapNotNull { e -> e.values[metric]?.let { e.day to it } }.reversed()

    /** Cambio respecto a la evaluación anterior que tenga el mismo indicador. */
    fun delta(evals: List<Evaluation>, index: Int, metric: Metric): String? {
        val now = evals[index].values[metric] ?: return null
        val prev = evals.drop(index + 1).firstNotNullOfOrNull { it.values[metric] } ?: return null
        val diff = now - prev
        val arrow = when {
            Math.abs(diff) < 1e-9 -> "="
            (diff > 0) == metric.higherIsBetter -> "▲"
            else -> "▼"
        }
        val pct = if (prev != 0.0) fmt(" (%+.1f %%)", diff / prev * 100) else ""
        val unit = if (metric.unit.isEmpty() || metric.unit == "%") "" else " ${metric.unit}"
        val sign = String.format(Locale.getDefault(), "%+.${metric.decimals}f", diff)
        return if (metric.unit == "%") "$arrow $sign pts" else "$arrow $sign$unit$pct"
    }

    /** Fecha legible: 2026-10-11 → 11/10/2026 */
    fun pretty(day: String): String =
        if (day.length >= 10) "${day.substring(8, 10)}/${day.substring(5, 7)}/${day.substring(0, 4)}" else day

    fun summaryText(name: String, evals: List<Evaluation>): String {
        val sb = StringBuilder("Historial de evaluaciones — $name\n")
        evals.forEachIndexed { i, e ->
            sb.append("\n").append(pretty(e.day)).append(" · ").append(e.jumps.size).append(" saltos\n")
            for (m in Metric.values()) {
                val v = e.values[m] ?: continue
                sb.append("  ").append(m.label).append(": ").append(m.format(v))
                delta(evals, i, m)?.let { sb.append("  ").append(it) }
                sb.append('\n')
            }
        }
        return sb.toString()
    }
}
