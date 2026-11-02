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
 * - Teléfonos lentos (menos de 20 análisis por segundo) o sin cuadros cerca del suelo a un lado: la nariz
 *   sigue en el aire una parábola de caída libre; su punto más alto, ajustado con todos los cuadros del
 *   vuelo, fija el centro del vuelo y cada cuadro del pie cerca del suelo da el tiempo de vuelo.
 * - Un cuadro en el suelo entre dos cuadros apenas levantados por ruido no se "corrige" hacia el aire
 *   (antes podía crear un despegue falso unos cuadros antes).
 * - Solo cuentan los saltos de verdad (desde la versión 1.6.11). Si el pie lejano no se ve bien (vista de
 *   costado), al dar un paso o acomodar un pie parecía que "volaba". Ahora cada vuelo debe levantar todo el
 *   cuerpo: la cabeza (o la cadera) sube al menos el 45 % de la altura calculada y se curva como un cuerpo
 *   en caída libre (entre 0,35 y 1,7 g). En CMJ y SJ, además, la cabeza baja 8 cm o más antes de despegar
 *   (contramovimiento o posición de 90°). En la simulación, de 1.800 saltos falsos en 1.200 videos sin
 *   saltar (pasos, trote, cambios de apoyo, sentadillas) se pasa a 6, sin perder saltos reales.
 * - Altura = g·t²/8, igual que con plataforma de contacto y con el modo de 240 fps.
 *
 * Simulación (pose con ruido 2,5 px correlacionado, inferencia de 45 ms, 2 % de picos, piernas que se
 * recogen 6 cm): CMJ 60/60, sesgo +1,2 cm, desviación 1,1 cm, máximo 3,4 cm (antes 6,3). A 10 análisis
 * por segundo: desviación 2,3 cm, máximo 7,8 cm (antes 11,2). A 60 fps: desviación 0,8 cm.
 */
class PoseJumpDetector(private val test: TestType, statureCm: Double?, private val boxCm: Double? = null) {

    /** Un cuadro: tiempo (s) y coordenadas verticales en píxeles (y crece hacia abajo). null = no se ve. */
    data class Sample(val t: Double, val foot: Double?, val nose: Double?, val hip: Double? = null)

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

    companion object { @JvmStatic var HI = 0.22; @JvmStatic var NS = 2; @JvmStatic var VERTEX = true; @JvmStatic var CHECK_RISE = true; @JvmStatic var CURV = 0.35; @JvmStatic var MINPTS = 4; @JvmStatic var CURVMAX = 1.7; @JvmStatic var lastReject = "" }

    /** Movimientos descartados por no ser un salto (pasos, cambios de apoyo, sentadillas). */
    var rejections = 0
        private set

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
    private fun despike(v: DoubleArray, k: Double, fl: Double, thr: Double) {
        val big = maxOf(6.0 * sigma, 5.0 * k)
        for (i in 1 until v.size - 1) {
            val a = v[i - 1]; val b = v[i + 1]; val x = v[i]
            if (abs(a - b) < big * 0.6 && abs(x - a) > big && abs(x - b) > big && (x - a) * (x - b) > 0) {
                // Un cuadro en el suelo entre dos cuadros apenas levantados (ruido) es real: no se sube al aire.
                if (x > a && (a + b) / 2 > fl - 2 * thr) continue
                v[i] = (a + b) / 2
            }
        }
    }

    private fun detect(fl: Double, k: Double): Event? {
        // Umbral de vuelo alto (6 cm): el balanceo y la deriva del punto del pie al flexionar no cuentan como vuelo.
        // En saltos repetidos (pogo: vuelos de ~10 cm) basta con 4 cm: no hay sentadilla previa que mueva el punto.
        val thr = maxOf((if (test.isRepeated) 4.0 else 6.0) * k, (if (test.isRepeated) 4.0 else 5.0) * sigma, 3.0)
        val idx = buf.indices.filter { buf[it].foot != null }
        if (idx.size < 6) return null
        val fv = DoubleArray(idx.size) { buf[idx[it]].foot!! }
        despike(fv, k, fl, thr)
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
         * Instante del punto más alto del vuelo según la nariz: ajuste de y = A + B·t + a·t² con la curvatura
         * de caída libre conocida (a = g en píxeles), quitando un punto atípico. null si hay pocos cuadros.
         */
        fun vertexT(r: Run, k: Double): Double? {
            val a = g * 100.0 * k / 2
            val pts = (r.a..r.b).mapNotNull { i -> buf[idx[i]].nose?.let { t(i) to it } }.toMutableList()
            if (pts.size < 3 || pts.last().first - pts.first().first < 0.12) return null
            val t0 = pts[0].first
            fun fit(ps: List<Pair<Double, Double>>): Pair<Double, Double> {
                var su = 0.0; var sz = 0.0; var suu = 0.0; var suz = 0.0
                for ((tt, y) in ps) { val u = tt - t0; val z = y - a * u * u; su += u; sz += z; suu += u * u; suz += u * z }
                val n = ps.size.toDouble(); val den = n * suu - su * su
                if (abs(den) < 1e-12) return 0.0 to 0.0
                val b = (n * suz - su * sz) / den
                return (sz - b * su) / n to b
            }
            var (A, B) = fit(pts)
            if (pts.size >= 5) {
                val res = pts.map { (tt, y) -> val u = tt - t0; abs(y - (A + B * u + a * u * u)) }
                val worst = res.indices.maxByOrNull { res[it] }!!
                if (res[worst] > maxOf(4.0 * sigma, 3.0 * k)) { pts.removeAt(worst); fit(pts).let { A = it.first; B = it.second } }
            }
            return t0 - B / (2 * a)
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
            // Con la cabeza: en el aire sigue una parábola de caída libre simétrica respecto del punto más alto.
            // Ese instante (vértice) sale con precisión de todos los cuadros del vuelo; así cada cuadro del pie
            // cerca del suelo, de cualquiera de los dos lados, da el tiempo de vuelo.
            val tv0 = if (VERTEX) vertexT(r, k) else null
            // Se usa cuando a un lado no hay cuadros cerca del suelo o con menos de 20 análisis por segundo.
            val slow = (r.a..r.b + 1).map { t(it) - t(it - 1) }.sorted().let { it[it.size / 2] } > 0.05
            if (tv0 != null && tv0 > t(r.a - 1) && tv0 < t(r.b + 1) && (up.isEmpty() || down.isEmpty() || slow)) {
                fun est(ti: Double, h: Double) = 2 * sqrt((ti - tv0) * (ti - tv0) + 2 * max(0.0, h) / g)
                val eUp = up.map { est(t(it), hA(it)) }
                val eDn = down.map { est(t(it), hB(it)) }
                // Sin cuadros cerca del suelo en un lado: el cruce del umbral de vuelo de ese lado.
                val sUp = eUp.takeIf { it.isNotEmpty() }?.average() ?: est(to0, thrM)
                val sDn = eDn.takeIf { it.isNotEmpty() }?.average() ?: est(ld0, thrM)
                // Peso igual a cada lado: una inclinación del tronco que corra el vértice se compensa.
                val wUp = if (eUp.isNotEmpty()) 1.0 else 0.35
                val wDn = if (eDn.isNotEmpty()) 1.0 else 0.35
                val tvv = (sUp * wUp + sDn * wDn) / (wUp + wDn)
                val toV = (tv0 - tvv / 2).coerceIn(t(r.a - 1) - 0.06, t(r.a))
                val ldV = (tv0 + tvv / 2).coerceIn(t(r.b), t(r.b + 1) + 0.06)
                return Triple(ldV - toV, toV, ldV)
            }
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

        /**
         * En un salto de verdad sube todo el cuerpo: desde el despegue hasta el punto más alto, la cabeza (o la
         * cadera) sube lo mismo que el centro de masa, h = g·t²/8. Al dar un paso, acomodar un pie, trotar o
         * hacer una sentadilla el pie se levanta (o el modelo lo "sube"), pero la cabeza casi no sube.
         * Esto evita contar intentos no realizados, sobre todo cuando el pie lejano no se ve bien.
         */
        fun bodyRiseOk(r: Run, to: Double, ld: Double, hCm: Double): Boolean {
            val lo = max(0, r.a - 3); val hi = min(idx.size - 1, r.b + 3)
            fun series(get: (Sample) -> Double?) = (lo..hi).mapNotNull { i -> get(buf[idx[i]])?.let { t(i) to it } }
            val nose = series { it.nose }
            val inAir = { ps: List<Pair<Double, Double>> -> ps.count { it.first in to..ld } }
            val pts = when {
                inAir(nose) >= 2 -> nose
                else -> series { it.hip }.takeIf { inAir(it) >= 2 }
            } ?: return true     // sin cabeza ni cadera visibles no se puede comprobar
            fun at(tt: Double): Double? {
                val b = pts.indexOfFirst { it.first >= tt }
                if (b <= 0) return null
                val (ta, ya) = pts[b - 1]; val (tb, yb) = pts[b]
                return if (tb - ta > 0.2) null else ya + (yb - ya) * (tt - ta) / max(1e-6, tb - ta)
            }
            val base = listOfNotNull(at(to), at(ld)).takeIf { it.isNotEmpty() }?.average() ?: return true
            val peak = pts.filter { it.first in to..ld }.minOf { it.second }
            val riseCm = (base - peak) / k
            if (riseCm < max(0.45 * hCm, 2.5)) { lastReject = String.format("subida %.1f de %.1f cm", riseCm, hCm); rejections++; return false }
            // Curva de caída libre: con 4 cuadros o más en el aire, la cabeza debe curvarse como cae un cuerpo
            // (al menos la mitad de g). Un paso o un trote la mueven unos pocos cm, con mucha menos curvatura.
            val air = pts.filter { it.first in to..ld }.toMutableList()
            if (air.size >= MINPTS) {
                // Ajuste y = c0 + c1·u + c2·u² por mínimos cuadrados (u centrado); se quitan hasta 2 puntos
                // atípicos (errores del modelo en la cabeza) antes de decidir.
                fun fit(ps: List<Pair<Double, Double>>): DoubleArray? {
                    val tm = ps.map { it.first }.average()
                    var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
                    var y0 = 0.0; var y1 = 0.0; var y2 = 0.0
                    for ((tt, y) in ps) { val u = tt - tm; val u2 = u * u
                        s0 += 1; s1 += u; s2 += u2; s3 += u2 * u; s4 += u2 * u2; y0 += y; y1 += u * y; y2 += u2 * y }
                    val m = arrayOf(doubleArrayOf(s0, s1, s2), doubleArrayOf(s1, s2, s3), doubleArrayOf(s2, s3, s4))
                    val v = doubleArrayOf(y0, y1, y2)
                    fun det3(q: Array<DoubleArray>) = q[0][0] * (q[1][1] * q[2][2] - q[1][2] * q[2][1]) -
                        q[0][1] * (q[1][0] * q[2][2] - q[1][2] * q[2][0]) + q[0][2] * (q[1][0] * q[2][1] - q[1][1] * q[2][0])
                    val d = det3(m)
                    if (abs(d) < 1e-18) return null
                    val out = DoubleArray(4)
                    for (col in 0..2) {
                        val q = arrayOf(m[0].clone(), m[1].clone(), m[2].clone())
                        for (row in 0..2) q[row][col] = v[row]
                        out[col] = det3(q) / d
                    }
                    out[3] = tm
                    return out
                }
                var cf = fit(air)
                repeat(2) {
                    val c = cf ?: return@repeat
                    if (air.size <= MINPTS) return@repeat
                    val res = air.map { (tt, y) -> val u = tt - c[3]; abs(y - (c[0] + c[1] * u + c[2] * u * u)) }
                    val w = res.indices.maxByOrNull { res[it] }!!
                    if (res[w] > maxOf(4.0 * sigma, 2.5 * k)) { air.removeAt(w); cf = fit(air) }
                }
                cf?.let { c ->
                    val gHalfPx = g * 100.0 * k / 2
                    if (c[2] < CURV * gHalfPx || c[2] > CURVMAX * gHalfPx) { lastReject = String.format("curvatura %.2f g (n=%d)", c[2] / gHalfPx, air.size); rejections++; return false }
                }
            }
            return true
        }

        fun riseOk(r: Run, one: Rep) = !CHECK_RISE || bodyRiseOk(r, one.takeoffT, one.landingT, one.heightCm)

        fun rep(r: Run, contactS: Double?, check: Boolean = true): Rep? {
            val (tv, to, ld) = flight(r)
            val h = Calc.heightCm(tv)
            if (h < 2.0 || h > 100.0) return null
            if (check && CHECK_RISE && !bodyRiseOk(r, to, ld, h)) return null
            return Rep(tv, h, contactS, to, ld)
        }

        return when (test.base) {
            TestType.RJ -> {
                val reps = ArrayList<Rep>()
                var prevLanding: Double? = null
                var passed = 0
                for ((ri, r) in flights) {
                    if (prevLanding == null && reps.isEmpty() && dur(runs[ri - 1]) < minQuietS) continue
                    val one = rep(r, null, check = false) ?: continue
                    if (riseOk(r, one)) passed++
                    val tc = prevLanding?.let { one.takeoffT - it }?.takeIf { it in 0.05..1.0 }
                    reps.add(one.copy(contactS = tc))
                    prevLanding = one.landingT
                }
                reportedUntil = endT
                // La serie vale si la mayoría de los vuelos levantan todo el cuerpo (no son pasos ni sentadillas).
                if (reps.isEmpty() || passed < max(1.0, 0.6 * reps.size)) null else Event.Series(reps)
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
                val one = rep(r, null) ?: return null
                if (CHECK_RISE && !bentBefore(one.takeoffT, k)) { lastReject = "sin agacharse"; rejections++; return null }
                Event.Jump(one)
            }
        }
    }

    /**
     * CMJ y SJ: antes de despegar el atleta se agacha (contramovimiento o posición de 90°), así que la cabeza
     * baja al menos 8 cm respecto de cuando estaba de pie. Un paso o un pie que se acomoda casi no la mueve.
     * Sin datos suficientes de la cabeza no se descarta.
     */
    private fun bentBefore(to: Double, k: Double): Boolean {
        val pre = buf.filter { it.t in (to - 6.0)..(to - 0.03) }.mapNotNull { s -> s.nose?.let { s.t to it } }
        val near = pre.filter { it.first >= to - 1.2 }.map { it.second }.sorted()
        if (pre.size < 8 || near.size < 3) return true
        val all = pre.map { it.second }.sorted()
        val standing = all[(all.size * 0.1).toInt()]          // de pie: de los más altos (y menor)
        val low = near[near.size - 2]                            // lo más bajo cerca del despegue (sin el extremo)
        return (low - standing) / k >= 8.0
    }

    /** Sin huecos largos (cuadros sin cuerpo) durante el vuelo. */
    private fun gapsOk(idx: List<Int>, r: Run): Boolean {
        for (i in max(1, r.a - 1)..min(idx.size - 1, r.b + 1))
            if (buf[idx[i]].t - buf[idx[i - 1]].t > 0.15) return false
        return true
    }
}
