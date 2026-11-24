package com.salto240

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Datos sintéticos para las pruebas: saltos con física conocida. */
object Synth {
    const val G = 9.81

    fun gauss(r: Random): Double = sqrt(-2 * ln(max(r.nextDouble(), 1e-12))) * cos(2 * PI * r.nextDouble())

    fun heightCm(flightS: Double) = G * flightS * flightS / 8 * 100

    // ------------------------------------------------------------ 240 fps: banda del suelo

    /**
     * Banda de [AutoDetect.ROWS]×[AutoDetect.COLS] alrededor de la línea del suelo con un pie oscuro que
     * despega a [t0] s y aterriza tras [flightS] s. [rowsPerCm]: filas de la banda por cm de subida.
     */
    fun strips(fps: Int, flightS: Double, t0: Double = 1.0, total: Double = 2.6, rowsPerCm: Double = 1.3,
               noise: Double = 3.0, seed: Int = 1): List<FloatArray> {
        val rows = AutoDetect.ROWS; val cols = AutoDetect.COLS
        val floorRow = rows * AutoDetect.ABOVE / (AutoDetect.ABOVE + AutoDetect.BELOW)   // línea del suelo (fila con fracción)
        val r = Random(seed)
        val v0 = G * flightS / 2
        val n = (total * fps).toInt()
        val c0 = 46; val c1 = 66            // ancho del calzado
        val wall = FloatArray(rows * cols) { i -> val c = i % cols; (95 + 8 * sin(c * 0.7)).toFloat() }
        return (0 until n).map { k ->
            val t = k.toDouble() / fps
            val u = t - t0
            val liftCm = if (u > 0 && u < flightS) (v0 * u - G * u * u / 2) * 100 else 0.0
            val bottom = floorRow - liftCm * rowsPerCm        // fila del borde inferior de la suela
            val s = FloatArray(rows * cols)
            for (row in 0 until rows) for (c in 0 until cols) {
                val i = row * cols + c
                val yTop = row.toDouble(); val yBot = row + 1.0
                // suelo claro con textura bajo la línea, pared más oscura arriba
                var bg = if (yTop >= floorRow) 150.0 + 6 * sin(c * 1.3 + row) else wall[i].toDouble()
                if (yTop < floorRow && yBot > floorRow) bg = (bg * (floorRow - yTop) + (150.0) * (yBot - floorRow))
                // calzado y pierna: columna oscura desde arriba de la banda hasta [bottom]
                val cover = if (c in c0..c1) ((min(yBot, bottom) - yTop).coerceIn(0.0, 1.0)) else 0.0
                val v = bg * (1 - cover) + 28.0 * cover + gauss(r) * noise
                s[i] = v.coerceIn(0.0, 255.0).toFloat()
            }
            s
        }
    }

    // ------------------------------------------------------------ IA: puntos del cuerpo

    /**
     * CMJ visto por la IA a [fps] análisis por segundo: el pie (y del punto más bajo), la nariz y la cadera en
     * píxeles. Bajada de 30 cm de la cabeza en [tCm] s antes del despegue.
     */
    fun poseCmj(fps: Double, flightS: Double, statureCm: Double = 175.0, kPx: Double = 2.5, tStart: Double = 2.0,
                tCm: Double = 0.55, noise: Double = 1.2, seed: Int = 1): List<PoseJumpDetector.Sample> {
        val r = Random(seed)
        val floor = 900.0
        val body = 0.91 * statureCm * kPx
        val v0 = G * flightS / 2
        val tTo = tStart + tCm
        val out = ArrayList<PoseJumpDetector.Sample>()
        var t = 0.0
        while (t < tTo + flightS + 2.0) {
            var nose = floor - body
            var hip = floor - 0.53 * statureCm * kPx
            var foot = floor
            val p = (t - tStart) / tCm
            if (p in 0.0..1.0) { val d = 30 * kPx * sin(PI * p).let { it * it }; nose += d; hip += d * 0.9 }
            val u = t - tTo
            if (u > 0 && u < flightS) {
                val lift = (v0 * u - G * u * u / 2) * 100 * kPx
                nose -= lift; hip -= lift; foot -= lift
            }
            out.add(PoseJumpDetector.Sample(t, foot + gauss(r) * noise, nose + gauss(r) * noise, hip + gauss(r) * noise))
            t += 1 / fps
        }
        return out
    }

    /** Sentadilla sin saltar (la cabeza baja y sube, los pies no se mueven). */
    fun poseSquat(fps: Double, statureCm: Double = 175.0, kPx: Double = 2.5, seed: Int = 1): List<PoseJumpDetector.Sample> {
        val r = Random(seed)
        val floor = 900.0; val body = 0.91 * statureCm * kPx
        val out = ArrayList<PoseJumpDetector.Sample>()
        var t = 0.0
        while (t < 8.0) {
            val p = (t - 2.0) / 1.6
            val d = if (p in 0.0..1.0) 45 * kPx * sin(PI * p) else 0.0
            out.add(PoseJumpDetector.Sample(t, floor + gauss(r) * 1.2, floor - body + d + gauss(r) * 1.2, floor - 0.53 * statureCm * kPx + d + gauss(r) * 1.2))
            t += 1 / fps
        }
        return out
    }

    // ------------------------------------------------------------ acelerómetro en la cintura

    /** CMJ con despegue a [vTo] m/s: módulo de la aceleración medida (m/s²) a [hz] muestras por segundo. */
    fun accel(vTo: Double, hz: Double = 200.0, noise: Double = 0.06, seed: Int = 1, sj: Boolean = false): Triple<List<AccelJump.S>, Double, Double> {
        val g = AccelJump.G0
        val t0 = 3.0; val t1 = 0.32; val a1 = 0.55 * g; val t2 = 0.30; val t3 = 0.06
        val a2 = (vTo + (if (sj) 0.0 else a1 * 2 * t1 / PI) + g * t3 / 2) / (2 * t2 / PI)
        val tTo = t0 + (if (sj) 0.0 else t1) + t2 + t3
        val flight = 2 * vTo / g
        fun acc(t: Double): Double {
            var u = t - t0
            if (u < 0) return 0.0
            if (!sj) { if (u < t1) return -a1 * sin(PI * u / t1); u -= t1 }
            if (u < t2) return a2 * sin(PI * u / t2)
            u -= t2
            if (u < t3) return -g * (1 - cos(PI * u / t3)) / 2
            u -= t3
            if (u < flight) return -g
            u -= flight
            if (u >= 0.8) return 0.0
            return if (u < 0.03) -g + 5.5 * g * u / 0.03 else -g + 4.5 * g * exp(-(u - 0.03) / 0.06) + g * (1 - exp(-(u - 0.03) / 0.06))
        }
        val r = Random(seed)
        val out = ArrayList<AccelJump.S>()
        var t = 0.0
        while (t < tTo + flight + 1.5) {
            val f = (acc(t) + g) * 9.78 / g
            out.add(AccelJump.S(t, max(0.0, f + gauss(r) * noise)))
            t += 1 / hz * (1 + 0.15 * (r.nextDouble() - 0.5) * 2)
        }
        return Triple(out, flight, tTo - t0)
    }
}
