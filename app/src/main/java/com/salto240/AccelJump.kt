package com.salto240

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Curva fuerza-tiempo con el acelerómetro del teléfono sujeto a la cintura (experimental).
 *
 * - Se usa el módulo de la aceleración medida (fuerza específica): no depende de cómo esté girado el teléfono.
 *   En el suelo la aceleración del cuerpo es casi solo vertical, así que F = m · |a| / G · g, con G el módulo en reposo.
 * - Reposo (2 s quieto): G y el ruido. Peso corporal = m · g.
 * - Vuelo: |a| cerca de 0 (caída libre, menos del 25 % del peso) durante al menos 120 ms. Despegue y aterrizaje en el
 *   instante en que la fuerza llega a 0: parábola por los cruces del 40, 25 y 12 % del peso.
 * - Inicio del movimiento: primera vez que la fuerza baja del peso menos 5 desviaciones del reposo (o del 8 % del peso),
 *   retrocediendo 30 ms. Velocidad = integral de (F/m − g) desde el inicio; desplazamiento = integral de la velocidad.
 * - Fases: descarga (inicio → velocidad mínima), frenado (→ velocidad 0, punto más bajo), impulso (→ despegue).
 * - Altura principal por tiempo de vuelo (g·t²/8), como en los otros métodos; también por impulso (v²/2g).
 */
object AccelJump {
    const val G0 = 9.80665
    /** Umbral de vuelo: fuerza menor que este porcentaje del peso. */
    private const val FL = 0.25

    /** Una muestra: tiempo (s) y módulo de la aceleración medida (m/s²). */
    data class S(val t: Double, val a: Double)

    data class Result(
        val t: DoubleArray, val forceN: DoubleArray, val velocity: DoubleArray, val disp: DoubleArray,
        val bodyWeightN: Double, val massKg: Double, val hz: Double,
        val iOnset: Int, val iVmin: Int, val iBottom: Int,
        val takeoffT: Double, val landingT: Double, val onsetT: Double,
        val flightS: Double, val heightFlightCm: Double, val heightImpulseCm: Double, val takeoffV: Double,
        val tttS: Double, val rsiMod: Double, val peakForceN: Double, val meanPropForceN: Double,
        val peakPowerW: Double, val meanPropPowerW: Double, val depthCm: Double, val brakingRfd: Double,
        val propImpulse: Double, val brakingImpulse: Double, val landingPeakN: Double?,
        val unweightS: Double, val brakingS: Double, val propulsionS: Double,
        val warnings: List<String>
    )

    class Fail(msg: String) : Exception(msg)

    /** Reposo: G (módulo medio) y ruido; devuelve null si el atleta se movió. */
    fun quiet(samples: List<S>): Pair<Double, Double>? {
        if (samples.size < 50) return null
        val m = samples.map { it.a }.average()
        val sd = sqrt(samples.sumOf { (it.a - m) * (it.a - m) } / (samples.size - 1))
        return if (sd / m < 0.035 && m in 8.0..11.6) m to sd else null
    }

    /** Remuestrea a una frecuencia fija con interpolación lineal. */
    private fun resample(s: List<S>, hz: Double): Pair<DoubleArray, DoubleArray> {
        val t0 = s.first().t; val t1 = s.last().t
        val n = ((t1 - t0) * hz).toInt() + 1
        val tt = DoubleArray(n) { t0 + it / hz }
        val aa = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            while (j < s.size - 2 && s[j + 1].t < tt[i]) j++
            val a = s[j]; val b = s[min(j + 1, s.size - 1)]
            aa[i] = if (b.t > a.t) a.a + (b.a - a.a) * ((tt[i] - a.t) / (b.t - a.t)).coerceIn(0.0, 1.0) else a.a
        }
        return tt to aa
    }

    fun analyze(all: List<S>, quietEnd: Double, massKg: Double, test: TestType): Result {
        val q = quiet(all.filter { it.t in (quietEnd - 1.5)..quietEnd }) ?: throw Fail("Hubo movimiento durante el reposo. Quédate quieto 2 s antes de saltar.")
        val (gMag, gSd) = q
        val raw = all.filter { it.t >= quietEnd - 1.0 }.sortedBy { it.t }
        val span = raw.last().t - raw.first().t
        val rate = (raw.size - 1) / span
        if (rate < 90) throw Fail(String.format("El acelerómetro da solo %.0f muestras por segundo; hacen falta 100 o más.", rate))
        val hz = 1000.0
        val (t, a) = resample(raw, hz)
        val n = t.size
        val bw = massKg * G0
        val f = DoubleArray(n) { a[it] / gMag * bw }
        val sdF = gSd / gMag * bw

        // Vuelo: tramos con F < 25 % del peso; se unen los separados por picos de 15 ms o menos
        // (rebote del teléfono) y se toma el más largo (al menos 120 ms).
        val runs = ArrayList<IntArray>()
        var i = 0
        while (i < n) {
            if (f[i] < FL * bw) {
                var j = i
                while (j + 1 < n && f[j + 1] < FL * bw) j++
                val last = runs.lastOrNull()
                if (last != null && i - last[1] <= 15) last[1] = j else runs.add(intArrayOf(i, j))
                i = j + 1
            } else i++
        }
        val best = runs.filter { (it[1] - it[0]) / hz >= 0.12 }.maxByOrNull { it[1] - it[0] }?.let { it[0] to it[1] }
        val (fa, fb) = best ?: throw Fail("No se encontró el vuelo. Sujeta bien el teléfono a la cintura y salta.")
        if (fa < (0.3 * hz).toInt()) throw Fail("El salto empezó demasiado pronto: quédate quieto hasta oír «¡Salta!».")
        if (fb >= n - 2) throw Fail("La grabación terminó antes del aterrizaje.")

        // Instante en que F cruzaría 0: recta entre los cruces del 40 % y del 15 % del peso.
        fun interp(k0: Int, k1: Int, level: Double): Double {
            val fr = if (f[k1] != f[k0]) ((level - f[k0]) / (f[k1] - f[k0])).coerceIn(0.0, 1.0) else 0.0
            return t[k0] + fr * (t[k1] - t[k0])
        }
        /**
         * Cruce de un nivel junto al borde del vuelo. [edge] = primer cuadro bajo el umbral de vuelo (despegue, step −1)
         * o último (aterrizaje, step +1). Los niveles bajo el umbral se buscan hacia dentro del vuelo (como mucho 100 ms);
         * los niveles sobre el umbral, hacia fuera (como mucho 300 ms).
         */
        fun crossT(level: Double, edge: Int, step: Int): Double? {
            val inward = level <= FL * bw
            if (step < 0) {               // despegue: la fuerza baja
                if (inward) { var k = edge; while (k < min(n, edge + (0.1 * hz).toInt())) { if (f[k] < level) return interp(k - 1, k, level); k++ } }
                else { var k = edge - 1; while (k > max(0, edge - (0.3 * hz).toInt())) { if (f[k] >= level) return interp(k, k + 1, level); k-- } }
            } else {                      // aterrizaje: la fuerza sube
                if (inward) { var k = edge; while (k > max(0, edge - (0.1 * hz).toInt())) { if (f[k] < level) return interp(k, k + 1, level); k-- } }
                else { var k = edge + 1; while (k < min(n, edge + (0.3 * hz).toInt())) { if (f[k] >= level) return interp(k - 1, k, level); k++ } }
            }
            return null
        }
        fun zeroT(iIn: Int, step: Int): Double {
            // Tiempos en que la fuerza pasa por el 40, 25 y 12 % del peso (o 65, 45 y 25 %); parábola por esos 3 puntos y su
            // cruce con 0 (o su mínimo, si no llega a 0). Así sirve tanto si la fuerza cae en recta como si se curva.
            // Niveles más bajos si la señal llega limpia hasta allí (sin balanceo lateral del teléfono).
            val lo = if (crossT(0.12 * bw, iIn, step) != null) 0.12 else 0.25
            val mid = if (lo < 0.2) 0.25 else 0.45
            val hi = if (lo < 0.2) 0.40 else 0.65
            val tA = crossT(lo * bw, iIn, step) ?: return t[iIn]
            val tB = crossT(mid * bw, iIn, step) ?: return tA
            val lin = tA + (tA - tB) * (lo / (mid - lo))
            val tC = crossT(hi * bw, iIn, step) ?: return lin
            // F(t) = p0 + p1·u + p2·u², u = t − tA
            val u1 = tB - tA; val u2 = tC - tA
            val y0 = lo; val y1 = mid; val y2 = hi
            val den = u1 * u2 * (u2 - u1)
            if (abs(den) < 1e-12) return lin
            val p2 = ((y2 - y0) * u1 - (y1 - y0) * u2) / den
            val p1 = (y1 - y0) / u1 - p2 * u1
            val dir = if (step < 0) 1.0 else -1.0       // hacia dónde está el 0: después (despegue) o antes (aterrizaje)
            val disc = p1 * p1 - 4 * p2 * y0
            var best: Double? = null
            if (abs(p2) < 1e-9) best = -y0 / p1
            else if (disc >= 0) {
                val r = listOf((-p1 + sqrt(disc)) / (2 * p2), (-p1 - sqrt(disc)) / (2 * p2)).filter { it * dir > 0 }
                best = r.minByOrNull { abs(it) }
            } else {
                val uv = -p1 / (2 * p2)
                if (uv * dir > 0) best = uv
            }
            val lim = abs(lin - tA) * 2.5 + 0.002
            return best?.takeIf { abs(it) <= lim }?.let { tA + it } ?: lin
        }
        val to = zeroT(fa, -1)
        val ld = zeroT(fb, +1)
        val flight = ld - to
        if (flight !in 0.15..1.2) throw Fail("Vuelo fuera de rango (" + (flight * 1000).roundToInt() + " ms).")
        val iTo = ((to - t[0]) * hz).toInt().coerceIn(0, n - 1)

        // Inicio del movimiento.
        val thr = max(5 * sdF, 0.08 * bw)
        val searchFrom = ((quietEnd - 0.2 - t[0]) * hz).toInt().coerceAtLeast(0)
        var iOn = -1
        if (test == TestType.SJ) {
            // SJ: no hay descarga; el inicio es cuando la fuerza sube sobre el peso.
            for (k in searchFrom until iTo) if (f[k] > bw + thr) { iOn = k; break }
        } else {
            for (k in searchFrom until iTo) if (f[k] < bw - thr) { iOn = k; break }
        }
        val warns = ArrayList<String>()
        if (iOn < 0) { iOn = max(searchFrom, iTo - (0.8 * hz).toInt()); warns.add("No se ve bien el inicio del movimiento.") }
        iOn = max(0, iOn - (0.03 * hz).toInt())

        // Velocidad y desplazamiento desde el inicio (en reposo v = 0).
        val v = DoubleArray(n); val s = DoubleArray(n)
        for (k in iOn + 1 until n) {
            val acc0 = f[k - 1] / massKg - G0; val acc1 = f[k] / massKg - G0
            v[k] = v[k - 1] + (acc0 + acc1) / 2 / hz
            s[k] = s[k - 1] + (v[k - 1] + v[k]) / 2 / hz
        }
        var iVmin = iOn
        for (k in iOn until iTo) if (v[k] < v[iVmin]) iVmin = k
        var iBottom = iVmin
        while (iBottom < iTo && v[iBottom] < 0) iBottom++
        if (test == TestType.SJ) { iVmin = iOn; iBottom = iOn }
        val vTo = v[iTo]
        val hImp = if (vTo > 0) vTo * vTo / (2 * G0) * 100 else 0.0
        val hFl = G0 * flight * flight / 8 * 100
        var peakF = 0.0; var peakP = 0.0
        for (k in iOn..iTo) { peakF = max(peakF, f[k]); peakP = max(peakP, f[k] * v[k]) }
        val prop = (iBottom..iTo)
        val meanPropF = prop.map { f[it] }.average()
        val meanPropP = prop.map { f[it] * v[it] }.average()
        val propImp = prop.sumOf { f[it] - bw } / hz
        val brakImp = if (iBottom > iVmin) (iVmin..iBottom).sumOf { f[it] - bw } / hz else 0.0
        // RFD excéntrico: de la fuerza mínima de la descarga a la fuerza en el punto más bajo.
        var iFmin = iOn
        for (k in iOn..max(iOn, iVmin)) if (f[k] < f[iFmin]) iFmin = k
        val rfd = if (iBottom > iFmin) (f[iBottom] - f[iFmin]) / ((iBottom - iFmin) / hz) else 0.0
        val iLd = ((ld - t[0]) * hz).toInt().coerceIn(0, n - 1)
        val landPeak = (iLd until min(n, iLd + (0.25 * hz).toInt())).maxOfOrNull { f[it] }
        val ttt = to - t[iOn]

        if (abs(hImp - hFl) > max(4.0, 0.2 * hFl)) warns.add(String.format("La altura por impulso (%.1f cm) no coincide con la del vuelo: el teléfono pudo moverse en la cintura.", hImp))
        if (rate < 180) warns.add(String.format("Acelerómetro a %.0f muestras/s: los tiempos tienen más error.", rate))

        // Recorte para el gráfico: de 0,3 s antes del inicio a 0,4 s después del aterrizaje.
        val c0 = max(0, iOn - (0.3 * hz).toInt()); val c1 = min(n - 1, iLd + (0.4 * hz).toInt())
        fun cut(x: DoubleArray) = x.copyOfRange(c0, c1 + 1)
        return Result(cut(t).map { it - t[iOn] }.toDoubleArray(), cut(f), cut(v), cut(s), bw, massKg, rate,
            iOn - c0, iVmin - c0, iBottom - c0, to - t[iOn], ld - t[iOn], t[iOn],
            flight, hFl, hImp, vTo, ttt, hFl / 100 / ttt, peakF, meanPropF, peakP, meanPropP,
            -(s.sliceArray(iOn..iTo).minOrNull() ?: 0.0) * 100, rfd, propImp, brakImp, landPeak,
            (iVmin - iOn) / hz, (iBottom - iVmin) / hz, (iTo - iBottom) / hz, warns)
    }
}
