package com.salto240

import android.content.Context
import android.graphics.Bitmap
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Medición en vivo (sistema mixto).
 *
 * 1. Mientras se graba, la vista previa (30–60 fps) se vigila con la misma franja del suelo y el mismo
 *    algoritmo de AutoDetect. Así se sabe al instante que hubo un salto y se muestra un valor provisional.
 * 2. Medio segundo después del aterrizaje se detiene la grabación y se analiza solo el tramo del salto
 *    en el video de 240 fps. Ese es el valor definitivo (±1 cuadro = ±4 ms).
 *
 * Los tiempos de la vista previa salen de las marcas de tiempo del sensor, no del número de cuadros,
 * así que el valor provisional es correcto aunque la vista previa pierda cuadros.
 */
class LiveDetector(private val test: TestType) {

    /** Salto visto en la vista previa. Tiempos en segundos desde el primer cuadro de la grabación. */
    data class Live(
        val takeoffS: Double?,
        val landingS: Double?,
        val contactS: Double?,
        val eventsS: List<Double>,
        val previewFps: Double
    ) {
        val flightS: Double? get() = if (takeoffS != null && landingS != null) landingS - takeoffS else null
        val contactTimeS: Double? get() = if (contactS != null && takeoffS != null) takeoffS - contactS else null
    }

    private val ts = ArrayList<Long>()
    private val strips = ArrayList<FloatArray>()
    private var lastRunNs = 0L

    fun reset() {
        ts.clear(); strips.clear(); lastRunNs = 0L
    }

    /** Tiempo tras el último aterrizaje antes de dar el salto por terminado. */
    private val settleS = if (test == TestType.RJ) 1.2 else 0.45

    /** Agrega un cuadro de la vista previa. Devuelve el salto cuando el atleta ya aterrizó y se estabilizó. */
    fun add(tNs: Long, strip: FloatArray): Live? {
        if (ts.isNotEmpty() && tNs <= ts.last()) return null
        ts.add(tNs); strips.add(strip)
        val dur = (tNs - ts[0]) / 1e9
        if (dur < 1.0 || strips.size < 20) return null
        // AutoDetect es barato con franjas pequeñas, pero no hace falta correrlo en cada cuadro.
        if (tNs - lastRunNs < 150_000_000L) return null
        lastRunNs = tNs

        val fps = (strips.size - 1) / dur
        val r = AutoDetect.detect(strips, fps.roundToInt().coerceAtLeast(10), test)
        fun sec(i: Int) = (ts[i.coerceIn(0, ts.size - 1)] - ts[0]) / 1e9
        // El atleta debe estar quieto sobre el suelo antes de despegar (descarta a alguien que entra caminando).
        // En el Drop Jump el apoyo previo es el contacto corto tras la caída, así que no se exige.
        if (test != TestType.DJ) {
            val pre = r.preContact ?: return null
            if (pre / fps < 0.25) return null
        }

        return if (test == TestType.RJ) {
            val ev = r.events
            if (ev.size < 2 || ev.size % 2 != 0) return null
            if (sec(ev.last()) > dur - settleS) return null
            Live(sec(ev.first()), sec(ev.last()), null, ev.map { sec(it) }, fps)
        } else {
            val to = r.takeoff ?: return null
            val ld = r.landing ?: return null
            if (sec(ld) > dur - settleS) return null
            if (sec(ld) - sec(to) > 1.0) return null   // más de 1 s "en el aire": salió del encuadre, no es un salto
            Live(sec(to), sec(ld), r.contact?.let { sec(it) }, emptyList(), fps)
        }
    }

    companion object {
        /** Tramo del video de alta velocidad (en cuadros) que contiene el salto, con margen. */
        fun window(l: Live, test: TestType, fps: Int, frameCount: Int): IntRange {
            val startS = when {
                test == TestType.RJ -> (l.eventsS.firstOrNull() ?: l.takeoffS ?: 0.0) - 0.8
                test == TestType.DJ -> (l.contactS ?: l.takeoffS ?: 0.0) - 0.8
                else -> (l.takeoffS ?: 0.0) - 0.8
            }
            val endS = (l.eventsS.lastOrNull()?.takeIf { test == TestType.RJ } ?: l.landingS ?: 0.0) + 0.4
            val a = (startS * fps).roundToInt().coerceIn(0, (frameCount - 1).coerceAtLeast(0))
            val b = (endS * fps).roundToInt().coerceIn(a, (frameCount - 1).coerceAtLeast(a))
            return a..b
        }

        /** Grises de la franja del suelo en un cuadro de la vista previa (coordenadas normalizadas de la vista). */
        fun strip(b: Bitmap, floorV: Float, x0: Float = 0.12f, x1: Float = 0.88f, cols: Int = 48, rows: Int = 6): FloatArray {
            val y1 = floorV
            val y0 = (floorV - 0.035f).coerceAtLeast(0f)
            val out = FloatArray(cols * rows)
            val row = IntArray(b.width)
            for (r in 0 until rows) {
                val v = y0 + (y1 - y0) * (r + 0.5f) / rows
                val py = (v * b.height).toInt().coerceIn(0, b.height - 1)
                b.getPixels(row, 0, b.width, 0, py, b.width, 1)
                for (c in 0 until cols) {
                    val u = x0 + (x1 - x0) * (c + 0.5f) / cols
                    val px = (u * b.width).toInt().coerceIn(0, b.width - 1)
                    val p = row[px]
                    out[r * cols + c] = (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10f
                }
            }
            return out
        }
    }
}

/** Desplaza los índices de un resultado de AutoDetect (análisis de un tramo del video). */
fun AutoDetect.Result.shift(off: Int) = copy(
    takeoff = takeoff?.plus(off),
    landing = landing?.plus(off),
    contact = contact?.plus(off),
    events = events.map { it + off }
)

/** Cálculo de un salto simple a partir de las marcas en cuadros (lo comparten el análisis y el modo en vivo). */
object JumpCalc {

    data class Out(val jump: Jump, val detail: String)

    private fun f(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    fun single(
        ctx: Context, test: TestType, athlete: String, fps: Int,
        takeoff: Int, landing: Int, contact: Int?,
        mass: Double?, box: Double?, load: Double?
    ): Out? {
        val flightFrames = landing - takeoff
        if (flightFrames <= 0) return null
        val tv = flightFrames.toDouble() / fps
        val h = Calc.heightCm(tv)
        val sb = StringBuilder()
        sb.append(f("Vuelo %.0f ms · despegue %.2f m/s\n", tv * 1000, Calc.takeoffVelocity(tv)))
        var tcMs: Double? = null
        var rsi: Double? = null
        if (test == TestType.DJ) {
            val c = contact ?: return null
            val tc = (takeoff - c).toDouble() / fps
            if (tc <= 0) return null
            tcMs = tc * 1000
            rsi = Calc.rsi(h, tc)
            sb.append(f("Contacto %.0f ms · RSI %.2f\n", tcMs, rsi))
        }
        val power = mass?.let { Calc.sayersPower(h, it) }
        if (power != null && mass != null && (load ?: 0.0) == 0.0)
            sb.append(f("Potencia %.0f W · %.1f W/kg\n", power, power / mass))
        sb.append(f("Medido a %d fps · ±1 cuadro = ±%.1f ms", fps, 1000.0 / fps))
        val j = Jump(
            id = System.currentTimeMillis(),
            date = Store.today(),
            athlete = athlete,
            test = test,
            flightMs = tv * 1000,
            heightCm = (h * 10).roundToInt() / 10.0,
            contactMs = tcMs,
            rsi = rsi,
            boxCm = if (test == TestType.DJ) box else null,
            massKg = mass,
            powerW = power,
            fps = fps,
            notes = "",
            loadKg = load
        )
        return Out(j, sb.toString())
    }

    /** Texto de asimetría si la otra pierna ya se midió hoy. */
    fun asymmetryNote(ctx: Context, saved: Jump): String? {
        val other = when (saved.test) {
            TestType.CMJ_L -> TestType.CMJ_R
            TestType.CMJ_R -> TestType.CMJ_L
            else -> return null
        }
        val today = Store.ofAthlete(ctx, saved.athlete).filter { it.day == saved.day }
        val best = today.filter { it.test == saved.test }.maxOfOrNull { it.heightCm } ?: return null
        val otherBest = today.filter { it.test == other }.maxOfOrNull { it.heightCm }
            ?: return "Ahora mide la pierna ${if (other == TestType.CMJ_L) "izquierda" else "derecha"} para calcular la asimetría."
        val l = if (saved.test == TestType.CMJ_L) best else otherBest
        val r = if (saved.test == TestType.CMJ_R) best else otherBest
        val pct = Calc.asymmetry(l, r)
        val verdict = when {
            pct >= 15 -> "alta: conviene trabajo unilateral"
            pct >= 10 -> "moderada: vigilar"
            else -> "dentro de lo normal"
        }
        return f("Asimetría de hoy: %.1f %% (%s)\nIzquierda %.1f cm · derecha %.1f cm", pct, verdict, l, r)
    }
}
