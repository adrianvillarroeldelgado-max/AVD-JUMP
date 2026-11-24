package com.salto240

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Diagnóstico del teléfono: qué cámara lenta ofrece a otras apps, cuántos fps graba de verdad,
 * cuántos análisis por segundo da la IA y a qué frecuencia mide el acelerómetro.
 * Con eso recomienda el método de medición. Se guarda (por teléfono, no se copia ni se sincroniza)
 * y va dentro de cada «Reportar medición».
 */
object Diagnostics {
    private const val PREFS = "salto240"
    const val KEY = "diagnostic"
    const val KEY_SHOWN = "diagnosticShown"
    private const val K_240 = "diag240"
    private const val K_AI = "diagAi"

    data class Cam(val id: String, val modes: List<Pair<Int, String>>)   // fps de alta velocidad → tamaños

    data class Out(
        val cams: List<Cam>, val max240: Boolean, val maxHs: Int, val normalMax: Int,
        val aiMs: Double?, val aiRate: Double?, val accHz: Double?, val freeMb: Long,
        val real240: String?, val realAi: Double?
    ) {
        fun json(): JSONObject = JSONObject().put("maxHighSpeedFps", maxHs).put("has240", max240).put("normalMaxFps", normalMax)
            .put("aiMs", aiMs ?: JSONObject.NULL).put("aiRate", aiRate ?: JSONObject.NULL).put("accHz", accHz ?: JSONObject.NULL)
            .put("freeMb", freeMb).put("real240", real240 ?: JSONObject.NULL).put("realAiFps", realAi ?: JSONObject.NULL)
            .put("cams", cams.joinToString(" | ") { c -> c.id + ": " + c.modes.joinToString(", ") { "${it.first} fps ${it.second}" } })
            .put("date", Store.today())
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun last(ctx: Context): JSONObject? = prefs(ctx).getString(KEY, null)?.let { try { JSONObject(it) } catch (_: Exception) { null } }

    /** Lo que se vio en la última grabación real de cámara lenta: pedidos y obtenidos. */
    fun observe240(ctx: Context, requested: Int, fileFps: Double) {
        if (fileFps <= 1) return
        prefs(ctx).edit().putString(K_240, String.format(Locale.US, "%d→%.0f", requested, fileFps)).apply()
    }

    fun observeAi(ctx: Context, fps: Double) {
        prefs(ctx).edit().putFloat(K_AI, fps.toFloat()).apply()
    }

    fun shouldShow(ctx: Context) = !prefs(ctx).getBoolean(KEY_SHOWN, false)
    fun markShown(ctx: Context) = prefs(ctx).edit().putBoolean(KEY_SHOWN, true).apply()

    fun cameras(ctx: Context): List<Cam> {
        val mgr = ctx.getSystemService(CameraManager::class.java) ?: return emptyList()
        val out = ArrayList<Cam>()
        for (id in try { mgr.cameraIdList } catch (_: Exception) { emptyArray() }) {
            val ch = try { mgr.getCameraCharacteristics(id) } catch (_: Exception) { continue }
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val modes = HashMap<Int, MutableList<String>>()
            if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                for (s in try { map.highSpeedVideoSizes } catch (_: Exception) { emptyArray() }) {
                    for (r in try { map.getHighSpeedVideoFpsRangesFor(s) } catch (_: Exception) { emptyArray() }) {
                        if (r.lower == r.upper) modes.getOrPut(r.upper) { ArrayList() }.add("${s.width}×${s.height}")
                    }
                }
            }
            out.add(Cam(id, modes.entries.sortedByDescending { it.key }.map { it.key to it.value.distinct().joinToString("/") }))
        }
        return out
    }

    private fun normalMaxFps(ctx: Context): Int {
        val mgr = ctx.getSystemService(CameraManager::class.java) ?: return 30
        var m = 30
        for (id in try { mgr.cameraIdList } catch (_: Exception) { emptyArray() }) {
            val ch = try { mgr.getCameraCharacteristics(id) } catch (_: Exception) { continue }
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.forEach { if (it.lower == it.upper) m = maxOf(m, it.upper) }
        }
        return m
    }

    /** Tiempo medio de la IA (ms por imagen) con una imagen de prueba de 480×640. Llamar fuera del hilo principal. */
    fun aiSpeed(): Double? {
        val det = PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
        return try {
            val bmp = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(0xFF9AA5B1.toInt())
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2F36.toInt(); strokeWidth = 28f }
            // Silueta simple: cabeza, tronco, brazos y piernas.
            c.drawCircle(240f, 120f, 40f, p)
            c.drawLine(240f, 160f, 240f, 380f, p); c.drawLine(240f, 200f, 160f, 320f, p); c.drawLine(240f, 200f, 320f, 320f, p)
            c.drawLine(240f, 380f, 190f, 580f, p); c.drawLine(240f, 380f, 290f, 580f, p)
            val img = InputImage.fromBitmap(bmp, 0)
            fun once(): Boolean {
                val latch = java.util.concurrent.CountDownLatch(1)
                var ok = false
                det.process(img).addOnCompleteListener { t -> ok = t.isSuccessful; latch.countDown() }
                return latch.await(10, TimeUnit.SECONDS) && ok
            }
            if (!once()) return null   // la primera carga el modelo
            val n = 12
            val t0 = System.nanoTime()
            repeat(n) { if (!once()) return null }
            (System.nanoTime() - t0) / 1e6 / n
        } catch (_: Exception) { null } finally { try { det.close() } catch (_: Exception) {} }
    }

    /** Frecuencia real del acelerómetro durante 1,5 s. Bloquea; llamar fuera del hilo principal. */
    fun accRate(ctx: Context): Double? {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
        val ts = ArrayList<Long>()
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { synchronized(ts) { ts.add(e.timestamp) } }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val th = android.os.HandlerThread("acc").apply { start() }
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_FASTEST, android.os.Handler(th.looper))
        Thread.sleep(1500)
        sm.unregisterListener(l)
        th.quitSafely()
        val t = synchronized(ts) { ArrayList(ts) }
        if (t.size < 10) return null
        return (t.size - 1) / ((t.last() - t.first()) / 1e9)
    }

    fun run(ctx: Context): Out {
        val cams = cameras(ctx)
        val maxHs = cams.flatMap { it.modes.map { m -> m.first } }.maxOrNull() ?: 0
        val ms = aiSpeed()
        val acc = accRate(ctx)
        val free = try { StatFs(ctx.filesDir.absolutePath).availableBytes / (1024 * 1024) } catch (_: Exception) { -1L }
        val p = prefs(ctx)
        val out = Out(cams, cams.any { c -> c.modes.any { it.first >= 240 } }, maxHs, normalMaxFps(ctx),
            ms, ms?.let { minOf(30.0, 1000.0 / it) }, acc, free,
            p.getString(K_240, null), p.getFloat(K_AI, 0f).toDouble().takeIf { it > 0 })
        p.edit().putString(KEY, out.json().toString()).apply()
        return out
    }

    /** Texto con el resultado y la recomendación. */
    fun advice(o: Out): List<Pair<String, Int>> {
        fun f(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)
        val l = ArrayList<Pair<String, Int>>()
        // Cámara lenta
        when {
            o.max240 -> l.add("✓ Cámara lenta a 240 fps disponible para la app." to Col.OK)
            o.maxHs >= 120 -> l.add(f("◐ Cámara lenta hasta %d fps (no 240). Sirve; el error por cuadro es el doble.", o.maxHs) to Col.WARN)
            else -> l.add(f("✗ El teléfono no ofrece cámara lenta a otras apps (máx. %d fps). Graba con la cámara del sistema en cámara lenta y usa «Analizar video».", o.normalMax) to Col.WARN)
        }
        o.real240?.let { r ->
            val parts = r.split("→"); val req = parts.getOrNull(0)?.toDoubleOrNull(); val got = parts.getOrNull(1)?.toDoubleOrNull()
            if (req != null && got != null) l.add(
                if (got >= req * 0.88) f("✓ Última grabación: %.0f fps reales.", got) to Col.OK
                else f("✗ Última grabación: pidió %.0f fps y grabó %.0f. Se mide con los fps reales.", req, got) to Col.WARN)
        }
        // IA
        val rate = o.realAi ?: o.aiRate
        when {
            rate == null -> l.add("✗ No se pudo probar la IA (ML Kit)." to Col.WARN)
            rate >= 24 -> l.add(f("✓ IA rápida: unos %.0f análisis por segundo%s.", rate, if (o.realAi != null) " (medido en la cámara)" else "") to Col.OK)
            rate >= 15 -> l.add(f("◐ IA media: unos %.0f análisis por segundo. Error esperado de 2–4 cm.", rate) to Col.WARN)
            else -> l.add(f("✗ IA lenta: unos %.0f análisis por segundo. Error de más de 5 cm: usa 240 fps.", rate) to Col.WARN)
        }
        // Acelerómetro
        when {
            o.accHz == null -> l.add("✗ Sin acelerómetro." to Col.WARN)
            o.accHz >= 180 -> l.add(f("✓ Acelerómetro a %.0f muestras por segundo: sirve para la curva de fuerza.", o.accHz) to Col.OK)
            o.accHz >= 95 -> l.add(f("◐ Acelerómetro a %.0f muestras por segundo: curva de fuerza con más error.", o.accHz) to Col.WARN)
            else -> l.add(f("✗ Acelerómetro a %.0f muestras por segundo: no alcanza para la curva de fuerza.", o.accHz) to Col.WARN)
        }
        if (o.freeMb in 0..999) l.add(f("⚠ Queda poco espacio (%d MB). Cada video de 240 fps ocupa 10–40 MB.", o.freeMb) to Col.WARN)
        // Recomendación
        val rec = when {
            o.max240 -> "Recomendado: cámara lenta 240 fps de costado para CMJ, SJ y Drop Jump. IA en vivo para sesiones de grupo rápidas."
            o.maxHs >= 120 -> "Recomendado: cámara lenta a ${o.maxHs} fps de costado. " + if ((rate ?: 0.0) >= 24) "La IA también va bien en este teléfono." else ""
            (rate ?: 0.0) >= 24 -> "Recomendado: IA en vivo en diagonal (45°), o grabar en cámara lenta con la cámara del sistema y analizar el video."
            else -> "Recomendado: grabar en cámara lenta con la cámara del sistema y usar «Analizar video»."
        }
        l.add(rec to Col.INK)
        return l
    }
}

class DiagnosticsActivity : BaseActivity() {
    private lateinit var box: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.markShown(this)
        val root = vbox(14).apply { setPadding(dp(16), dp(20), dp(16), dp(24)) }
        root.addView(text("Diagnóstico del teléfono", 26f, Col.INK, true))
        root.addView(text("Comprueba qué puede medir este teléfono: cámara lenta, velocidad de la IA y acelerómetro. Deja el teléfono quieto unos segundos.", 14f, Col.MUTED))
        val p = panel()
        status = text("Probando…", 15f, Col.INK, true)
        p.addView(status)
        box = vbox(8)
        p.addView(box, lp())
        root.addView(p, lp())
        root.addView(text("Modelo: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · AVD jump ${BuildConfig.VERSION_NAME}", 12f, Col.MUTED))
        root.addView(button("Repetir diagnóstico") { start() }, lp())
        root.addView(button("Listo", true) { finish() }, lp())
        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })
        start()
    }

    private fun start() {
        status.text = "Probando…"
        box.removeAllViews()
        val app = applicationContext
        Thread {
            val o = try { Diagnostics.run(app) } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (o == null) { status.text = "No se pudo completar el diagnóstico."; return@runOnUiThread }
                status.text = "Resultado"
                for ((t, c) in Diagnostics.advice(o)) box.addView(text(t, 14f, c, c == Col.INK), lp())
                if (o.cams.isNotEmpty()) box.addView(text("Modos de cámara lenta: " + o.cams.joinToString("; ") { c ->
                    "cámara ${c.id}: " + (c.modes.joinToString(", ") { "${it.first} fps (${it.second})" }.ifEmpty { "ninguno" }) }, 12f, Col.MUTED), lp())
                o.aiMs?.let { box.addView(text(String.format(Locale.getDefault(), "IA: %.0f ms por imagen de prueba.", it), 12f, Col.MUTED), lp()) }
            }
        }.start()
    }
}

/** Abre el diagnóstico la primera vez (después de la guía). */
fun maybeShowDiagnostics(ctx: Context): Boolean {
    if (!Diagnostics.shouldShow(ctx)) return false
    ctx.startActivity(Intent(ctx, DiagnosticsActivity::class.java))
    return true
}
