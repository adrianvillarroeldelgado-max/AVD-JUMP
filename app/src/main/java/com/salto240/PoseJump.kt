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
 * - Vuelo: el pie debe subir más de 6 cm sobre el suelo (4 cm en saltos repetidos). Así el balanceo y la
 *   deriva del punto del pie al flexionar las rodillas (el modelo lo "sube" unos cm en sentadilla) no se
 *   confunden con un despegue. Los picos sueltos de un cuadro (errores del modelo) se quitan antes.
 * - Despegue y aterrizaje: con los cuadros más cercanos al suelo (4–22 cm), donde el pie sigue la caída
 *   libre del cuerpo; a mitad del vuelo las piernas se recogen y esos cuadros no se usan. Se resuelve
 *   y = v·u − g·u²/2 con v = g·tv/2 y se itera. Suelo local antes y después (corrige balanceo lento).
 * - Altura = g·t²/8, igual que con plataforma de contacto y con el modo de 240 fps.
 *
 * Simulación (pose con ruido 2,5 px correlacionado, inferencia de 45 ms, 2 % de picos, piernas que se
 * recogen 6 cm): CMJ 60/60 detectados, sesgo +1,2 cm, desviación 1,4 cm; con balanceo de 4 px o deriva
 * del pie de 3 cm, 60/60 (antes 38/60 y 42/60). A 60 fps: desviación 0,8 cm.
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

    companion object { @JvmStatic var HI = 0.22; @JvmStatic var NS = 2 }

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

    /**
     * Quita picos sueltos del pie (errores del modelo de un cuadro): un punto que se separa mucho de sus
     * dos vecinos en el mismo sentido, cuando los vecinos coinciden entre sí, se reemplaza por su media.
     * El vértice del vuelo no se toca porque ahí los vecinos están a poca distancia.
     */
    private fun despike(v: DoubleArray, k: Double) {
        val big = maxOf(6.0 * sigma, 5.0 * k)
        for (i in 1 until v.size - 1) {
            val a = v[i - 1]; val b = v[i + 1]; val x = v[i]
            if (abs(a - b) < big * 0.6 && abs(x - a) > big && abs(x - b) > big && (x - a) * (x - b) > 0) v[i] = (a + b) / 2
        }
    }

    private fun detect(fl: Double, k: Double): Event? {
        // Umbral de vuelo alto (6 cm): el balanceo y la deriva del punto del pie al flexionar no cuentan como vuelo.
        // En saltos repetidos (pogo: vuelos de ~10 cm) basta con 4 cm: no hay sentadilla previa que mueva el punto.
        val thr = maxOf((if (test.isRepeated) 4.0 else 6.0) * k, (if (test.isRepeated) 4.0 else 5.0) * sigma, 3.0)
        val idx = buf.indices.filter { buf[it].foot != null }
        if (idx.size < 6) return null
        val fv = DoubleArray(idx.size) { buf[idx[it]].foot!! }
        despike(fv, k)
        fun t(i: Int) = buf[idx[i]].t
        fun foot(i: Int) = fv[i]
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

        /**
         * Tiempo de vuelo con los cuadros más cercanos al suelo (entre 4 y 30 cm): cerca del despegue y del
         * aterrizaje el pie sigue la caída libre del cuerpo, mientras que a mitad del vuelo las piernas se recogen.
         * Cada cuadro da un instante de despegue (o aterrizaje) resolviendo y = v·u − g·u²/2 con v = g·tv/2;
         * se itera porque v depende del propio tiempo de vuelo.
         */
        fun flight(r: Run): Triple<Double, Double, Double> {
            val lo = maxOf(if (test.isRepeated) 0.025 else 0.04, 3.0 * sigma / k / 100.0)
            val hi = HI
            val mid = (t(r.a) + t(r.b)) / 2
            // Suelo local: mediana del pie en los apoyos antes y después (corrige balanceo e inclinación lenta).
            fun localFloor(a: Int, b: Int): Double? {
                if (b < a) return null
                val xs = (a..b).map { foot(it) }.sorted()
                return xs[xs.size / 2]
            }
            val before = runs.lastOrNull { it.contact && it.b < r.a }
            val after = runs.firstOrNull { it.contact && it.a > r.b }
            val flA = before?.let { rb ->
                val a0 = (rb.a..rb.b).firstOrNull { t(it) >= t(rb.b) - 1.2 } ?: rb.a
                val a1 = (rb.a..rb.b).lastOrNull { t(it) <= t(rb.b) - 0.6 } ?: rb.b
                localFloor(a0, maxOf(a0, a1))
            }?.takeIf { abs(it - fl) < 4 * k } ?: fl
            val flB = after?.let { ra ->
                val b0 = (ra.a..ra.b).firstOrNull { t(it) >= t(ra.a) + 0.35 } ?: ra.a
                val b1 = (ra.a..ra.b).lastOrNull { t(it) <= t(ra.a) + 1.2 } ?: ra.b
                localFloor(b0, maxOf(b0, b1))
            }?.takeIf { abs(it - fl) < 4 * k } ?: fl
            fun hA(i: Int) = (flA - foot(i)) / k / 100.0
            fun hB(i: Int) = (flB - foot(i)) / k / 100.0
            val up = (r.a..r.b).filter { t(it) < mid && hA(it) in lo..hi }.take(NS)
            val down = (r.a..r.b).filter { t(it) > mid && hB(it) in lo..hi }.takeLast(NS)
            // Respaldo: cruce del umbral interpolado entre el último apoyo y el primer cuadro en el aire.
            val to0 = cross(r.a - 1)
            val ld0 = cross(r.b)
            var to = to0; var ld = ld0
            var tv = max(0.1, ld - to)
            repeat(8) {
                val v = g * tv / 2
                fun u(h: Double): Double { val d = v * v - 2 * g * h; return if (d <= 0) v / g else (v - sqrt(d)) / g }
                if (up.isNotEmpty()) to = up.map { t(it) - u(hA(it)) }.average()
                else to = to0 - u(thrM) + 0.0
                if (down.isNotEmpty()) ld = down.map { t(it) + u(hB(it)) }.average()
                else ld = ld0 + u(thrM)
                // Entre el último cuadro de apoyo y el primero en el aire (y al revés al aterrizar).
                to = to.coerceIn(t(r.a - 1) - 0.06, t(r.a))
                ld = ld.coerceIn(t(r.b), t(r.b + 1) + 0.06)
                tv = ld - to
            }
            return Triple(tv, to, ld)
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
