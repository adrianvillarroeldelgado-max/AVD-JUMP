package com.salto240

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Curva de fuerza (en veces el peso) y velocidad, con las fases del salto. */
class ForceCurveView(ctx: Context) : View(ctx) {
    var r: AccelJump.Result? = null
        set(v) { field = v; invalidate() }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.ACCENT; strokeWidth = 4f; style = Paint.Style.STROKE }
    private val vel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.TRACK; strokeWidth = 3f; style = Paint.Style.STROKE }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.LINE; strokeWidth = 2f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.MUTED }
    private val shade = Paint().apply { style = Paint.Style.FILL }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val res = r ?: return
        val d = resources.displayMetrics.density
        label.textSize = 10.5f * d
        val l = 34f * d; val rt = width - 34f * d; val top = 10f * d; val b = height - 22f * d
        val t = res.t; val n = t.size
        if (n < 2) return
        val fMax = max(2.0, (res.forceN.maxOrNull() ?: 0.0) / res.bodyWeightN * 1.08).coerceAtMost(8.0)
        val vAbs = max(0.5, res.velocity.maxOf { abs(it) } * 1.1)
        fun X(tt: Double) = (l + (tt - t[0]) / (t[n - 1] - t[0]) * (rt - l)).toFloat()
        fun Yf(fbw: Double) = (b - fbw.coerceIn(0.0, fMax) / fMax * (b - top)).toFloat()
        fun Yv(v: Double) = ((top + b) / 2 - v / vAbs * (b - top) / 2).toFloat()
        // Fases: descarga, frenado, impulso, vuelo.
        val phases = listOf(
            Triple(t[res.iOnset], t[res.iVmin], 0x22FFC04D),
            Triple(t[res.iVmin], t[res.iBottom], 0x22FF6B6B),
            Triple(t[res.iBottom], res.takeoffT, 0x224CD07D),
            Triple(res.takeoffT, res.landingT, 0x158C99AD))
        for ((a, z, col) in phases) if (z > a) { shade.color = col; c.drawRect(X(a), top, X(z), b, shade) }
        c.drawLine(l, b, rt, b, axis); c.drawLine(l, top, l, b, axis)
        c.drawLine(l, Yf(1.0), rt, Yf(1.0), axis)
        c.drawText("1×", 4 * d, Yf(1.0) + 4 * d, label)
        c.drawText(String.format(Locale.getDefault(), "%.0f×", fMax), 4 * d, top + 10 * d, label)
        val pf = Path(); val pv = Path()
        for (i in 0 until n) {
            val x = X(t[i])
            val yf = Yf(res.forceN[i] / res.bodyWeightN); val yv = Yv(res.velocity[i])
            if (i == 0) { pf.moveTo(x, yf); pv.moveTo(x, yv) } else { pf.lineTo(x, yf); if (t[i] <= res.takeoffT) pv.lineTo(x, yv) }
        }
        c.drawPath(pf, line); c.drawPath(pv, vel)
        c.drawText(String.format(Locale.getDefault(), "%.1f m/s", vAbs), rt + 2 * d, top + 10 * d, label)
        for (k in 0..4) {
            val tt = t[0] + (t[n - 1] - t[0]) * k / 4
            c.drawText(String.format(Locale.getDefault(), "%.1f s", tt), X(tt) - 10 * d, height - 6f * d, label)
        }
    }
}

/**
 * Salto con el acelerómetro del teléfono sujeto a la cintura (experimental).
 * Flujo: «Empezar» → 2 s quieto (reposo) → «¡Salta!» → se detiene sola al aterrizar → curva y resultados.
 */
class AccelActivity : BaseActivity(), SensorEventListener {

    private lateinit var sm: SensorManager
    private var sensor: Sensor? = null
    private val main = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null

    private enum class St { IDLE, QUIET, JUMP, DONE }
    private var st = St.IDLE
    private val buf = ArrayList<AccelJump.S>()
    private var t0Ns = 0L
    private var quietFrom = 0.0
    private var quietEnd = 0.0
    private var goAt = 0.0
    private var lowSince = -1.0
    private var flightSeen = false
    private var landedAt = -1.0

    private lateinit var statusTxt: TextView
    private lateinit var bigTxt: TextView
    private lateinit var detailTxt: TextView
    private lateinit var chart: ForceCurveView
    private lateinit var startBtn: android.widget.Button
    private lateinit var saveBtn: android.widget.Button
    private lateinit var testSpinner: Spinner
    private lateinit var massInput: android.widget.EditText
    private val tests = listOf(TestType.CMJ, TestType.CMJA, TestType.SJ)
    private var athlete = ""
    private var result: AccelJump.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        athlete = intent.getStringExtra(MainActivity.EX_ATHLETE) ?: "Sin nombre"
        val mass0 = intent.getDoubleExtra(MainActivity.EX_MASS, 0.0).takeIf { it > 0 } ?: Clients.byName(this, athlete)?.massKg

        val root = vbox(12).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(text("Curva de fuerza con acelerómetro", 24f, Col.INK, true))
        root.addView(text("EXPERIMENTAL · $athlete", 13f, Col.WARN, true))
        root.addView(text("Sujeta el teléfono firme a la cintura: cinturón elástico o riñonera, en la espalda baja o sobre el ombligo, " +
            "sin que baile. Manos en la cadera (salvo CMJ con brazos). Pulsa Empezar, quédate quieto 2 s y salta al oír «¡Salta!». " +
            "Aterriza y quédate quieto 1 s.", 13f, Col.MUTED))

        val p = panel()
        val row = hbox(8)
        testSpinner = Spinner(this)
        testSpinner.adapter = TrAdapter(this, android.R.layout.simple_spinner_dropdown_item, tests.map { it.label })
        row.addView(testSpinner, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        massInput = input("Masa (kg)", numeric = true).apply { mass0?.let { setText(String.format(Locale.US, "%.1f", it)) } }
        row.addView(massInput, lp(dp(110)))
        p.addView(row, lp())
        startBtn = button("Empezar", true) { start() }
        p.addView(startBtn, lp())
        statusTxt = text(if (sensor == null) "Este teléfono no tiene acelerómetro." else "Listo.", 15f, Col.INK, true)
        p.addView(statusTxt)
        root.addView(p, lp())

        val pr = panel()
        bigTxt = text("—", 40f, Col.ACCENT, true)
        pr.addView(bigTxt)
        chart = ForceCurveView(this)
        pr.addView(chart, lp(h = dp(220)))
        pr.addView(text("Naranja: fuerza (veces el peso). Azul: velocidad. Fondo: descarga (amarillo), frenado (rojo), impulso (verde), vuelo (gris).", 11f, Col.MUTED))
        detailTxt = text("", 13f, Col.INK).apply { typeface = android.graphics.Typeface.MONOSPACE }
        pr.addView(detailTxt)
        saveBtn = button("Guardar", true) { save() }
        saveBtn.isEnabled = false
        pr.addView(saveBtn, lp())
        root.addView(pr, lp())
        root.addView(text("Cómo se calcula: la fuerza sale de la aceleración del teléfono (F = m·a), la velocidad de integrarla y la altura " +
            "del tiempo de vuelo (como en los otros métodos). Es una estimación: el teléfono se mueve algo respecto del cuerpo y el " +
            "sensor de muchos teléfonos mide a 100–400 muestras por segundo, frente a 1000 de una plataforma de fuerza. " +
            "Úsalo para comparar al mismo atleta con el mismo teléfono y la misma sujeción. Valídalo en Validación y calibración.", 12f, Col.MUTED))
        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })
        Voice.init(this, force = true)
    }

    private fun beep(long: Boolean = false) {
        try {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 100).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP, if (long) 450 else 150)
        } catch (_: Exception) {}
    }

    private fun mass(): Double? = massInput.number()?.takeIf { it in 20.0..250.0 }

    private fun start() {
        if (sensor == null) return
        if (mass() == null) { Tst.makeText(this, "Escribe la masa del atleta (kg).", Toast.LENGTH_SHORT).show(); return }
        buf.clear(); result = null; saveBtn.isEnabled = false
        st = St.QUIET; t0Ns = 0L; quietFrom = 0.0; flightSeen = false; landedAt = -1.0; lowSince = -1.0
        statusTxt.text = "Quieto…"; statusTxt.setTextColor(Col.INK)
        bigTxt.text = "—"; detailTxt.text = ""; chart.r = null
        startBtn.isEnabled = false
        Voice.say(this, "Quieto", force = true)
        // SENSOR_DELAY_FASTEST: lo más rápido que dé el teléfono (necesita HIGH_SAMPLING_RATE_SENSORS en Android 12+).
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        main.postDelayed(watchdog, 12000)
    }

    private val watchdog = Runnable {
        if (st == St.QUIET || st == St.JUMP) finishRecording(timeout = true)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (t0Ns == 0L) t0Ns = e.timestamp
        val t = (e.timestamp - t0Ns) / 1e9
        val a = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2].toDouble())
        buf.add(AccelJump.S(t, a))
        when (st) {
            St.QUIET -> {
                // 2 s seguidos sin moverse; si se mueve, vuelve a contar.
                if (t - quietFrom >= 0.5) {
                    val w = buf.filter { it.t >= max(quietFrom, t - 2.0) }
                    val q = AccelJump.quiet(w)
                    if (q == null) { quietFrom = t; return }
                    if (t - quietFrom >= 2.0) {
                        quietEnd = t; goAt = t; st = St.JUMP
                        main.post { statusTxt.text = "¡Salta!"; statusTxt.setTextColor(Col.OK); beep(true); Voice.say(this, "¡Salta!", force = true) }
                    }
                }
            }
            St.JUMP -> {
                // Vuelo: módulo < 25 % de g; aterrizaje: vuelve a > 50 % tras un vuelo de 120 ms o más.
                val g = 9.81
                if (a < 0.25 * g) { if (lowSince < 0) lowSince = t }
                else if (a > 0.5 * g && lowSince >= 0) {
                    if (t - lowSince >= 0.12) { flightSeen = true; landedAt = t }
                    lowSince = -1.0
                }
                if (flightSeen && t - landedAt >= 1.0) { st = St.DONE; main.post { finishRecording(false) } }
                if (t - goAt > 8.0) { st = St.DONE; main.post { finishRecording(true) } }
            }
            else -> {}
        }
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    private fun finishRecording(timeout: Boolean) {
        sm.unregisterListener(this)
        main.removeCallbacks(watchdog)
        startBtn.isEnabled = true
        startBtn.text = "Repetir"
        if (st == St.QUIET) {
            st = St.IDLE
            statusTxt.text = "No hubo 2 s quieto. Sujeta bien el teléfono y vuelve a empezar."
            statusTxt.setTextColor(Col.WARN); return
        }
        st = St.IDLE
        val test = tests[testSpinner.selectedItemPosition]
        val m = mass() ?: return
        val samples = ArrayList(buf)
        try {
            val r = AccelJump.analyze(samples, quietEnd, m, test)
            result = r
            show(r, test)
        } catch (e: AccelJump.Fail) {
            statusTxt.text = e.message; statusTxt.setTextColor(Col.WARN)
            Voice.say(this, "No se pudo medir. Repite el salto.", force = true)
        } catch (e: Exception) {
            statusTxt.text = "No se pudo analizar: ${e.message}"; statusTxt.setTextColor(Col.WARN)
        }
    }

    private fun f(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    private fun calibratedHeight(h: Double): Double = Validation.calibration(this, "acc")?.apply(h) ?: h

    private fun show(r: AccelJump.Result, test: TestType) {
        val h = calibratedHeight(r.heightFlightCm)
        bigTxt.text = f("%.1f cm", h)
        statusTxt.text = f("Medido · %.0f muestras/s", r.hz); statusTxt.setTextColor(Col.OK)
        chart.r = r
        val bw = r.bodyWeightN; val m = r.massKg
        val sb = StringBuilder()
        fun row(k: String, v: String) = sb.append(String.format("%-22s %s\n", k, v))
        row("Tiempo de vuelo", f("%.0f ms", r.flightS * 1000))
        row("Altura por impulso", f("%.1f cm (v %.2f m/s)", r.heightImpulseCm, r.takeoffV))
        if (test != TestType.SJ) {
            row("Tiempo hasta despegue", f("%.2f s", r.tttS))
            row("RSI modificado", f("%.2f", h / 100 / r.tttS))
            row("Descarga · frenado", f("%.0f · %.0f ms", r.unweightS * 1000, r.brakingS * 1000))
            row("Contramovimiento", f("%.0f cm", r.depthCm))
            row("RFD excéntrico", f("%.0f N/s", r.brakingRfd))
            row("Impulso de frenado", f("%.0f N·s", r.brakingImpulse))
        }
        row("Impulso (propulsión)", f("%.0f ms · %.0f N·s", r.propulsionS * 1000, r.propImpulse))
        row("Fuerza pico", f("%.0f N · %.1f× peso", r.peakForceN, r.peakForceN / bw))
        row("Fuerza media impulso", f("%.0f N · %.1f N/kg", r.meanPropForceN, r.meanPropForceN / m))
        row("Potencia pico", f("%.0f W · %.1f W/kg", r.peakPowerW, r.peakPowerW / m))
        row("Potencia media", f("%.0f W · %.1f W/kg", r.meanPropPowerW, r.meanPropPowerW / m))
        r.landingPeakN?.let { row("Pico al aterrizar", f("%.1f× peso", it / bw)) }
        if (abs(h - r.heightFlightCm) > 0.05) sb.append(f("Corregido por calibración (medido %.1f cm)\n", r.heightFlightCm))
        r.warnings.forEach { sb.append("⚠ ").append(it).append('\n') }
        detailTxt.text = sb.toString()
        saveBtn.isEnabled = true
        Voice.say(this, "$athlete, " + Voice.num(this, h) + " centímetros", force = true)
    }

    private fun save() {
        val r = result ?: return
        val test = tests[testSpinner.selectedItemPosition]
        val m = r.massKg
        val j = Jump(
            id = System.currentTimeMillis(), date = Store.today(), athlete = athlete, test = test,
            flightMs = r.flightS * 1000, heightCm = Math.round(r.heightFlightCm * 10) / 10.0,
            contactMs = null, rsi = null, boxCm = null, massKg = m, powerW = r.peakPowerW,
            fps = r.hz.toInt(), notes = "Acelerómetro (experimental)",
            method = "acc",
            tttMs = if (test != TestType.SJ) r.tttS * 1000 else null,
            rsiMod = if (test != TestType.SJ) r.heightFlightCm / 100 / r.tttS else null,
            extra = mapOf(
                "peakForceN" to r.peakForceN, "peakForceBW" to r.peakForceN / r.bodyWeightN,
                "meanPropForceN" to r.meanPropForceN, "peakPowerW" to r.peakPowerW, "meanPropPowerW" to r.meanPropPowerW,
                "hImpulseCm" to r.heightImpulseCm, "takeoffV" to r.takeoffV, "depthCm" to r.depthCm,
                "rfdEcc" to r.brakingRfd, "propImpulse" to r.propImpulse, "brakingImpulse" to r.brakingImpulse,
                "sampleHz" to r.hz
            ) + (r.landingPeakN?.let { mapOf("landingPeakBW" to it / r.bodyWeightN) } ?: emptyMap())
        )
        Store.add(this, j)
        saveBtn.isEnabled = false
        val saved = Store.byId(this, j.id)
        Tst.makeText(this, f("Guardado: %.1f cm", saved?.heightCm ?: j.heightCm) +
            (Attempts.afterSave(this, saved ?: j)?.let { "\n$it" } ?: ""), Toast.LENGTH_LONG).show()
    }

    override fun onPause() {
        super.onPause()
        if (st == St.QUIET || st == St.JUMP) {
            sm.unregisterListener(this); main.removeCallbacks(watchdog); st = St.IDLE
            startBtn.isEnabled = true; statusTxt.text = "Se detuvo al salir de la pantalla."
        }
    }

    override fun onDestroy() {
        try { tone?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
