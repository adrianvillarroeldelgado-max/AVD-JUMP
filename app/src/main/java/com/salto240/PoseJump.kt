package com.salto240

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Medición con IA (como My Jump Lab): un modelo de pose encuentra el cuerpo del atleta en cada cuadro
 * de la cámara y la app sigue sus pies, sin línea del suelo ni cámara lenta.
 *
 * - Suelo: el nivel estable más bajo del pie más bajo. Al caminar siempre hay un pie apoyado, así que no hay "vuelo".
 * - Escala: distancia nariz–suela en reposo ≈ 91 % de la estatura de la ficha. Solo fija umbrales en cm:
 *   la altura sale del tiempo de vuelo, así que un error de escala casi no influye.
 * - Despegue y aterrizaje: se ajusta una parábola de caída libre (g conocida) a los cuadros cercanos al
 *   despegue y al aterrizaje, y se buscan sus cruces con el suelo. Así se miden milisegundos aunque la
 *   cámara vaya a 30 fps, y encoger las piernas a mitad del vuelo casi no influye.
 * - Altura = g·t²/8, igual que con plataforma de contacto y con el modo de 240 fps.
 *
 * Simulación (pose con ruido de 1,5 px, 3 px/cm): a 30 fps sesgo +0,05 cm y desviación 0,25 cm;
 * a 60 fps +0,03 ± 0,17 cm. Drop Jump: contacto ±4 ms. Sin saltos falsos al caminar o trotar.
 */
class PoseJumpDetector(private val test: TestType, statureCm: Double?, private val boxCm: Double? = null) {

    /** Un cuadro: tiempo (s) y coordenadas verticales en píxeles (y crece hacia abajo). null = no se ve. */
    data class Sample(val t: Double, val foot: Double?, val nose: Double?)

    data class Rep(
        val flightS: Double,
        val heightCm: Double,
        val contactS: Double?,     // DJ: contacto tras la caída; RJ: contacto previo (desde el 2.º salto)
        val takeoffT: Double,
        val landingT: Double
    )

    sealed class Event {
        data class Jump(val rep: Rep) : Event()
        data class Series(val reps: List<Rep>) : Event()
    }

    enum class State { NO_BODY, CALIBRATING, READY, AIR }

    private val stature = (statureCm ?: 170.0).coerceIn(100.0, 230.0)
    private val buf = ArrayList<Sample>()
    private var pxPerCm: Double? = null
    private var floor: Double? = null
    private var sigma = 1.0
    private var reportedUntil = Double.NEGATIVE_INFINITY

    var state = State.NO_BODY
        private set

    /** Nivel del suelo en píxeles (para dibujarlo), si ya se conoce. */
    val floorPx: Double? get() = floor
    val scale: Double? get() = pxPerCm

    private val settleS = if (test.isRepeated) 1.2 else 0.4
    private val minQuietS = 0.3
    private val g = Calc.G

    fun reset() {
        buf.clear(); pxPerCm = null; floor = null; sigma = 1.0
        reportedUntil = Double.NEGATIVE_INFINITY
        state = State.NO_BODY
    }

    fun add(s: Sample): Event? {
        if (buf.isNotEmpty() && s.t <= buf.last().t) return null
        buf.add(s)
        while (buf.size > 2 && buf.last().t - buf.first().t > 20.0) buf.removeAt(0)

        calibrate()
        val fl = floor
        val k = pxPerCm
        if (s.foot == null) { state = State.NO_BODY; return null }
        if (fl == null || k == null) { state = State.CALIBRATING; return null }
        state = if (s.foot < fl - thrPx(k)) State.AIR else State.READY
        return detect(fl, k)
    }

    private fun thrPx(k: Double) = maxOf(2.5 * k, 4.0 * sigma, 3.0)

    private fun median(a: MutableList<Double>): Double { a.sort(); return a[a.size / 2] }

    /** Ruido de la pose, suelo y escala a partir de los momentos en que el atleta está quieto. */
    private fun calibrate() {
        val n = buf.size
        if (n < 8) return
        // Ruido: mediana de la diferencia entre cuadros seguidos (robusta a los saltos).
        val diffs = ArrayList<Double>()
        var prev: Double? = null
        for (s in buf) {
            val f = s.foot ?: continue
            prev?.let { diffs.add(abs(f - it)) }
            prev = f
        }
        if (diffs.isNotEmpty()) sigma = max(0.5, median(diffs) / 0.954)

        val cands = ArrayList<Pair<Double, Double>>()   // (nivel del pie, tolerancia)
        val lengths = ArrayList<Double>()
        val win = ArrayList<Double>()
        val noses = ArrayList<Double>()
        var i = 0
        while (i < n) {
            val t0 = buf[i].t
            win.clear(); noses.clear()
            var j = i
            while (j < n && buf[j].t - t0 <= 0.3) {
                buf[j].foot?.let { win.add(it) }
                buf[j].nose?.let { noses.add(it) }
                j++
            }
            if (j >= n && buf[n - 1].t - t0 < 0.25) break
            if (win.size >= 5) {
                win.sort()
                val med = win[win.size / 2]
                val spread = win[win.size - 1] - win[0]
                val body = if (noses.size >= 3) med - median(noses) else null
                val tol = max((body ?: 300.0) * 0.015 + 2.0, 4.5 * sigma)
                if (spread <= tol) {
                    cands.add(med to tol)
                    if (body != null && body > 40) lengths.add(body)
                }
            }
            i += max(1, (j - i) / 3)
        }
        if (cands.isEmpty()) return
        // Suelo: el grupo de niveles estables más bajos (y mayor), no el máximo suelto.
        val mx = cands.maxOf { it.first }
        val tol = cands[0].second
        floor = median(cands.filter { it.first >= mx - tol }.map { it.first }.toMutableList())
        if (lengths.isNotEmpty()) pxPerCm = median(lengths) / (0.91 * stature)
    }

    private data class Run(val a: Int, val b: Int, val contact: Boolean)

    private fun runsOf(c: BooleanArray): MutableList<Run> {
        val out = ArrayList<Run>()
        var s = 0
        for (i in 1..c.size) if (i == c.size || c[i] != c[s]) { out.add(Run(s, i - 1, c[s])); s = i }
        return out
    }

    private fun detect(fl: Double, k: Double): Event? {
        val thr = thrPx(k)
        val idx = buf.indices.filter { buf[it].foot != null }
        if (idx.size < 6) return null
        fun t(i: Int) = buf[idx[i]].t
        fun foot(i: Int) = buf[idx[i]].foot!!
        fun yM(i: Int) = (fl - foot(i)) / k / 100.0     // altura del pie sobre el suelo (m)

        val c = BooleanArray(idx.size) { foot(it) >= fl - thr }
        // Parpadeos de un cuadro.
        for (i in 1 until c.size - 1) if (c[i] != c[i - 1] && c[i] != c[i + 1]) c[i] = !c[i]
        var runs = runsOf(c)
        // Vuelos cortados por ruido: apoyos interiores de menos de 0,09 s entre cuadros de aire.
        var changed = false
        for (ri in 1 until runs.size - 1) {
            val r = runs[ri]
            if (r.contact && t(r.b + 1) - t(r.a - 1) < 0.09) {
                for (i in r.a..r.b) c[i] = false
                changed = true
            }
        }
        if (changed) runs = runsOf(c)

        fun dur(r: Run) = t(r.b) - t(r.a)
        val last = runs.last()
        if (!last.contact || dur(last) < settleS) return null

        val flights = runs.withIndex().filter { (ri, r) ->
            !r.contact && ri > 0 && ri < runs.size - 1 && t(r.a) > reportedUntil &&
                (t(r.b + 1) - t(r.a - 1)) in 0.12..1.3 && gapsOk(idx, r)
        }
        if (flights.isEmpty()) return null
        // El atleta debe haber aterrizado en el suelo conocido; si no, el suelo aún se está recalibrando.
        val landed = (last.a..last.b).map { foot(it) }.toMutableList()
        if (median(landed) > fl + thr) return null
        val endT = t(flights.last().value.b + 1)
        val thrM = thr / k / 100.0

        /** Cruce del umbral entre el cuadro i y el i+1 (respaldo si falla el ajuste). */
        fun cross(i: Int): Double {
            val level = fl - thr
            val fa = foot(i); val fb = foot(i + 1)
            val f = if (abs(fb - fa) < 1e-6) 0.5 else ((level - fa) / (fb - fa)).coerceIn(0.0, 1.0)
            return t(i) + f * (t(i + 1) - t(i))
        }

        /** Tiempo de vuelo: parábola de caída libre ajustada a los cuadros cercanos al despegue y al aterrizaje. */
        fun flight(r: Run): Triple<Double, Double, Double> {
            val t0 = t(r.a); val t1 = t(r.b)
            val w = min(0.12, (t1 - t0) / 3)
            val ids = (r.a..r.b).filter { t(it) - t0 <= w || t1 - t(it) <= w }
            if (ids.size >= 4) {
                // y + g/2·u² = A − g/2·tc² + g·tc·u  → recta en u
                var su = 0.0; var sz = 0.0; var suu = 0.0; var suz = 0.0
                for (i in ids) {
                    val u = t(i) - t0
                    val z = yM(i) + g / 2 * u * u
                    su += u; sz += z; suu += u * u; suz += u * z
                }
                val nn = ids.size.toDouble()
                val den = nn * suu - su * su
                if (abs(den) > 1e-12) {
                    val c1 = (nn * suz - su * sz) / den
                    val c0 = (sz - c1 * su) / nn
                    val tc = c1 / g
                    val apex = c0 + g / 2 * tc * tc
                    if (apex > 0) {
                        val tv = 2 * sqrt(2 * apex / g)
                        val to = t0 + tc - tv / 2
                        val ld = t0 + tc + tv / 2
                        if (to in (t(r.a - 1) - 0.03)..(t(r.a) + 0.01) && ld in (t(r.b) - 0.01)..(t(r.b + 1) + 0.03))
                            return Triple(tv, to, ld)
                    }
                }
            }
            val to = cross(r.a - 1)
            val ld = cross(r.b)
            var tv = ld - to
            repeat(3) { tv = (ld - to) + 2 * thrM / (g * max(tv, 0.05) / 2) }
            val corr = (tv - (ld - to)) / 2
            return Triple(tv, to - corr, ld + corr)
        }

        fun rep(r: Run, contactS: Double?): Rep? {
            val (tv, to, ld) = flight(r)
            val h = Calc.heightCm(tv)
            if (h < 2.0 || h > 100.0) return null
            return Rep(tv, h, contactS, to, ld)
        }

        return when (test.base) {
            TestType.RJ -> {
                val reps = ArrayList<Rep>()
                var prevLanding: Double? = null
                for ((ri, r) in flights) {
                    if (prevLanding == null && reps.isEmpty() && dur(runs[ri - 1]) < minQuietS) continue
                    val one = rep(r, null) ?: continue
                    val tc = prevLanding?.let { one.takeoffT - it }?.takeIf { it in 0.05..1.0 }
                    reps.add(one.copy(contactS = tc))
                    prevLanding = one.landingT
                }
                reportedUntil = endT
                if (reps.isEmpty()) null else Event.Series(reps)
            }
            TestType.DJ -> {
                // Caída del cajón: tramo elevado (de pie sobre el cajón y la caída), contacto corto y vuelo.
                val (ri, r) = flights.last()
                val cRun = runs[ri - 1]
                val before = runs.getOrNull(ri - 2)
                if (before == null || before.contact || dur(cRun) > 1.0) return null
                val (_, to, _) = flight(r)
                // Inicio del contacto: parábola de la caída (últimos cuadros antes de tocar el suelo).
                val fall = (max(before.a, cRun.a - 3) until cRun.a).toList()
                var tIn: Double? = null
                if (fall.size >= 2) {
                    val tr = t(fall[0])
                    var su = 0.0; var sz = 0.0; var suu = 0.0; var suz = 0.0
                    for (i in fall) {
                        val u = t(i) - tr
                        val z = yM(i) + g / 2 * u * u
                        su += u; sz += z; suu += u * u; suz += u * z
                    }
                    val nn = fall.size.toDouble()
                    val den = nn * suu - su * su
                    if (abs(den) > 1e-12) {
                        val bb = (nn * suz - su * sz) / den
                        val a = (sz - bb * su) / nn
                        val disc = bb * bb + 2 * g * a
                        if (disc >= 0) {
                            val root = tr + (bb + sqrt(disc)) / g
                            if (root in t(cRun.a - 1)..(t(cRun.a) + 0.02)) tIn = root
                        }
                    }
                }
                if (tIn == null) {
                    val vIn = boxCm?.takeIf { it > 0 }?.let { sqrt(2 * g * it / 100.0) } ?: 2.0
                    tIn = cross(cRun.a - 1) + thrM / vIn
                }
                val tc = to - tIn
                if (tc !in 0.08..1.0) return null
                reportedUntil = endT
                rep(r, tc)?.let { Event.Jump(it) }
            }
            else -> {
                val (ri, r) = flights.last()
                reportedUntil = endT
                // Exige apoyo quieto antes del despegue (descarta pasos o trotes).
                if (dur(runs[ri - 1]) < minQuietS) return null
                rep(r, null)?.let { Event.Jump(it) }
            }
        }
    }

    /** Sin huecos largos (cuadros sin cuerpo) durante el vuelo. */
    private fun gapsOk(idx: List<Int>, r: Run): Boolean {
        for (i in max(1, r.a - 1)..min(idx.size - 1, r.b + 1))
            if (buf[idx[i]].t - buf[idx[i - 1]].t > 0.15) return false
        return true
    }
}
