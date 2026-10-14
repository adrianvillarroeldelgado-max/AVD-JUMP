package com.salto240

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Detección automática de despegue y aterrizaje.
 *
 * Se analiza una franja horizontal de la imagen justo encima del suelo, a la altura de las suelas.
 * Con los pies apoyados, la franja muestra el calzado; en el aire, solo el fondo.
 * 1. Se toma como referencia "en el aire" el cuadro más distinto del final (atleta de pie).
 * 2. Para cada cuadro se calcula qué fracción de la franja difiere de esa referencia (ocupación).
 *    No depende de la posición del pie, así que funciona aunque aterrice un poco desplazado.
 * 3. Ocupación alta = contacto; se filtran parpadeos de 1–2 cuadros y se buscan las fases de vuelo.
 * Despegue = primer cuadro sin contacto; aterrizaje = primer cuadro con contacto (misma definición que a mano).
 */
object AutoDetect {

    data class Result(
        val takeoff: Int? = null,
        val landing: Int? = null,
        val contact: Int? = null,          // Drop Jump: primer contacto tras caer del cajón
        val events: List<Int> = emptyList(), // Saltos repetidos: despegue, aterrizaje, …
        val error: String? = null,
        val preContact: Int? = null         // cuadros de apoyo continuo antes del (primer) despegue
    )

    /** Resta la media para que los cambios de luz (parpadeo LED a 240 fps) no cuenten como pie. */
    private fun centered(a: FloatArray): FloatArray {
        var m = 0.0
        for (v in a) m += v
        val mean = (m / a.size).toFloat()
        return FloatArray(a.size) { a[it] - mean }
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }

    /** Ocupación de cada cuadro respecto a la referencia sin pies. */
    fun occupancy(strips: List<FloatArray>, refIndex: Int? = null): Pair<FloatArray, String?> {
        val n = strips.size
        val c = strips.map { centered(it) }
        val m = c[0].size
        val tail = max(1, minOf(5, n / 10))
        val end = FloatArray(m) { i -> (n - tail until n).map { c[it][i] }.average().toFloat() }
        val ref = refIndex ?: (0 until n).maxByOrNull { dist(c[it], end) } ?: 0
        val e = c[ref]
        val d = FloatArray(m) { abs(end[it] - e[it]) }.sortedArray()
        val p90 = d[((m - 1) * 0.9).roundToInt()]
        if (p90 < 12f) return FloatArray(n) to "No se distinguen los pies del fondo en la franja elegida. Toca justo donde la suela toca el suelo y mejora la luz o el contraste."
        val tau = max(10f, 0.35f * p90)
        val occ = FloatArray(n) { t ->
            var k = 0
            val s = c[t]
            for (i in 0 until m) if (abs(s[i] - e[i]) > tau) k++
            k.toFloat() / m
        }
        return occ to null
    }

    private data class Run(val start: Int, val end: Int, val contact: Boolean) {
        val len get() = end - start + 1
    }

    private fun runs(flags: BooleanArray): MutableList<Run> {
        val out = mutableListOf<Run>()
        var s = 0
        for (i in 1..flags.size) {
            if (i == flags.size || flags[i] != flags[s]) {
                out.add(Run(s, i - 1, flags[s])); s = i
            }
        }
        return out
    }

    /** Elimina tramos interiores más cortos que [minLen] (ruido) invirtiendo su estado. */
    private fun smooth(flags: BooleanArray, minLen: Int) {
        var changed = true
        while (changed) {
            changed = false
            for (r in runs(flags)) {
                if (r.len < minLen && r.start > 0 && r.end < flags.size - 1) {
                    for (i in r.start..r.end) flags[i] = !r.contact
                    changed = true
                    break
                }
            }
        }
    }

    fun detect(strips: List<FloatArray>, fps: Int, test: TestType, refIndex: Int? = null): Result {
        val n = strips.size
        if (n < 20) return Result(error = "El video es demasiado corto.")
        val (occ, err) = occupancy(strips, refIndex)
        if (err != null) return Result(error = err)

        val tail = max(1, minOf(5, n / 10))
        val stand = (n - tail until n).map { occ[it] }.average().toFloat()
        if (stand < 0.03f) return Result(error = "Al final del video no se ven los pies en la franja. Termina la grabación con el atleta de pie y toca a la altura de las suelas.")
        val theta = 0.3f * stand
        val contact = BooleanArray(n) { occ[it] > theta }
        smooth(contact, 3)

        val minFlight = max(5, (0.08 * fps).roundToInt())
        val rs = runs(contact)
        val flights = rs.withIndex().filter { (i, r) ->
            !r.contact && r.start > 0 && r.end < n - 1 && r.len >= minFlight && i > 0
        }
        if (flights.isEmpty()) return Result(error = "No se encontró ninguna fase de vuelo. Revisa la franja del suelo o marca a mano.")

        return when (test) {
            TestType.RJ -> {
                val ev = mutableListOf<Int>()
                flights.forEach { (_, r) -> ev.add(r.start); ev.add(r.end + 1) }
                Result(events = ev, preContact = rs.getOrNull(flights.first().index - 1)?.len)
            }
            TestType.DJ -> {
                // Vuelo precedido por un contacto que a su vez empieza tras una fase sin contacto (la caída del cajón).
                val f = flights.firstOrNull { (i, _) -> i >= 2 && rs[i - 1].contact && rs[i - 1].start > 0 }
                    ?: flights.maxByOrNull { it.value.len }!!
                val prev = rs.getOrNull(f.index - 1)
                val c = if (prev != null && prev.contact && prev.start > 0) prev.start else null
                Result(takeoff = f.value.start, landing = f.value.end + 1, contact = c,
                    error = if (c == null) "No se detectó el contacto inicial tras la caída; márcalo a mano." else null)
            }
            else -> {
                val f = flights.maxByOrNull { it.value.len }!!
                Result(takeoff = f.value.start, landing = f.value.end + 1, preContact = rs.getOrNull(f.index - 1)?.len)
            }
        }
    }
}
