package com.salto240

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

// ------------------------------------------------------------------------------------------
// Voz
// ------------------------------------------------------------------------------------------

/** Avisos hablados: resultado del salto y turno del siguiente atleta (síntesis de voz del teléfono). */
object Voice {
    private const val PREF = "voice"
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var lang: Locale? = null

    fun enabled(ctx: Context) = ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).getBoolean(PREF, true)
    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
        if (!on) stop()
    }

    fun init(ctx: Context, force: Boolean = false) {
        if (!force && !enabled(ctx)) return
        val loc = Lang.locale(ctx)
        if (tts != null && lang == loc) return
        shutdown()
        lang = loc
        val app = ctx.applicationContext
        tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val t = tts ?: return@TextToSpeech
                if (t.setLanguage(loc) < 0) t.setLanguage(Locale("es"))
                t.setSpeechRate(1.05f)
                pending?.let { say(app, it, force = true) }
                pending = null
            }
        }
    }

    /** Dice el texto (corta lo que estuviera diciendo). */
    fun say(ctx: Context, text: String, force: Boolean = false) {
        if (!force && !enabled(ctx)) return
        if (text.isBlank()) return
        val t = tts
        if (t == null) { pending = text; init(ctx, force); return }
        if (!ready) { pending = text; return }
        t.speak(Lang.tr(ctx, text), TextToSpeech.QUEUE_FLUSH, null, "avd" + System.nanoTime())
    }

    /** Número para leer en voz alta en el idioma de la app ("42,3"). */
    fun num(ctx: Context, v: Double, dec: Int = 1): String = String.format(Lang.locale(ctx), "%.${dec}f", v)

    fun stop() { try { tts?.stop() } catch (_: Exception) {} }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null; ready = false
    }
}

// ------------------------------------------------------------------------------------------
// Resultado en pantalla grande
// ------------------------------------------------------------------------------------------

/** Resultado a pantalla completa, legible a varios metros (para que lo vea el atleta). */
object BigResult {
    private const val PREF = "bigResult"
    fun enabled(ctx: Context) = ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).getBoolean(PREF, true)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()

    private val main = Handler(Looper.getMainLooper())

    /** Muestra el resultado encima de la cámara. Se cierra al tocarlo o a los [ms] milisegundos. */
    fun show(root: FrameLayout, name: String, value: String, sub: String, color: Int = 0xFFFFFFFF.toInt(), ms: Long = 4500) {
        val ctx = root.context
        if (!enabled(ctx)) return
        hide(root)
        val box = ctx.vbox(4).apply {
            tag = "bigResult"
            gravity = Gravity.CENTER
            setBackgroundColor(0xE6000000.toInt())
            isClickable = true
            setOnClickListener { hide(root) }
        }
        box.addView(ctx.text(name, 30f, 0xFFB8C2D1.toInt(), true).apply { gravity = Gravity.CENTER }, lp())
        val big = ctx.text(value, 120f, color, true).apply { gravity = Gravity.CENTER; setSingleLine(); }
        // Ajusta el tamaño al ancho de la pantalla.
        big.setAutoSizeTextTypeUniformWithConfiguration(40, 160, 4, android.util.TypedValue.COMPLEX_UNIT_SP)
        box.addView(big, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(190)))
        if (sub.isNotEmpty()) box.addView(ctx.text(sub, 20f, 0xFFE3E8EF.toInt()).apply { gravity = Gravity.CENTER }, lp())
        box.addView(ctx.text("Toca para cerrar", 13f, 0xFF8C99AD.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(18), 0, 0) }, lp())
        root.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        main.postDelayed({ if (box.parent != null) root.removeView(box) }, ms)
    }

    fun hide(root: FrameLayout) {
        for (i in root.childCount - 1 downTo 0) if (root.getChildAt(i).tag == "bigResult") root.removeViewAt(i)
    }
}

// ------------------------------------------------------------------------------------------
// Nivel de la cámara
// ------------------------------------------------------------------------------------------

/** Nivel con el acelerómetro: teléfono vertical, sin inclinación lateral ni hacia delante. */
object CamLevel {
    private const val PREF = "camLevel"
    fun enabled(ctx: Context) = ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).getBoolean(PREF, true)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
}

class LevelView(ctx: Context) : TextView(ctx), SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val g = FloatArray(3)
    private var last = 0L

    init {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 13f
        setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
        background = ctx.rounded(0x99000000.toInt(), 0, 14)
        visibility = if (CamLevel.enabled(ctx)) View.VISIBLE else View.GONE
    }

    fun start() {
        if (!CamLevel.enabled(context)) return
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() { sm?.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        for (i in 0..2) g[i] = 0.85f * g[i] + 0.15f * e.values[i]
        val now = System.currentTimeMillis()
        if (now - last < 150) return
        last = now
        val roll = Math.toDegrees(atan2(g[0].toDouble(), g[1].toDouble()))         // giro lateral
        val pitch = Math.toDegrees(atan2(g[2].toDouble(), sqrt((g[0] * g[0] + g[1] * g[1]).toDouble())))  // hacia delante/atrás
        val okRoll = abs(roll) < 1.5
        val okPitch = abs(pitch) < 6
        text = when {
            okRoll && okPitch -> "✓ Nivelado"
            !okRoll -> String.format(Locale.getDefault(), "Inclinado %.0f° a la %s", abs(roll), if (roll > 0) "izquierda" else "derecha")
            else -> String.format(Locale.getDefault(), "Inclinado %.0f° %s", abs(pitch), if (pitch > 0) "hacia arriba" else "hacia abajo")
        }
        setTextColor(if (okRoll && okPitch) 0xFF6BE39A.toInt() else 0xFFFFC04D.toInt())
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

// ------------------------------------------------------------------------------------------
// Control remoto
// ------------------------------------------------------------------------------------------

/** Teclas de un botón Bluetooth de selfie, de auriculares o de volumen. */
object RemoteKeys {
    fun isTrigger(code: Int) = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
        code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_CAMERA ||
        code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_HEADSETHOOK || code == KeyEvent.KEYCODE_SPACE ||
        code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_NUMPAD_ENTER
}

// ------------------------------------------------------------------------------------------
// Sesión de grupo
// ------------------------------------------------------------------------------------------

/**
 * Sesión de evaluación de un grupo: lista de turnos (atleta e intento) que la cámara recorre sola.
 * Rotación: todos hacen el intento 1, luego el 2… (descansan mientras saltan los demás).
 * Seguidos: cada atleta hace todos sus intentos y pasa el siguiente.
 */
data class GroupSession(
    val groupName: String,
    val test: TestType,
    val attempts: Int,
    val rotate: Boolean,
    val athletes: List<String>,
    val box: Double?,
    val load: Double?,
    val pos: Int,
    val started: String,
    val skipped: List<Int> = emptyList()
) {
    val turns: List<Pair<String, Int>> get() =
        if (rotate) (1..attempts).flatMap { a -> athletes.map { it to a } }
        else athletes.flatMap { n -> (1..attempts).map { n to it } }

    val total get() = turns.size
    val finished get() = pos >= total
    val current: Pair<String, Int>? get() = turns.getOrNull(pos)
    val next: Pair<String, Int>? get() = turns.getOrNull(pos + 1)

    fun label(): String {
        val c = current ?: return "Sesión terminada"
        return "${c.first} · intento ${c.second} de $attempts · turno ${pos + 1} de $total"
    }

    fun toJson(): JSONObject = JSONObject().put("group", groupName).put("test", test.name).put("attempts", attempts)
        .put("rotate", rotate).put("athletes", JSONArray(athletes)).put("pos", pos).put("started", started)
        .put("skipped", JSONArray(skipped)).apply { box?.let { put("box", it) }; load?.let { put("load", it) } }

    companion object {
        private const val KEY = "groupSession"
        const val EX_SESSION = "groupSession"

        fun fromJson(o: JSONObject) = GroupSession(o.optString("group"), TestType.of(o.optString("test")), o.optInt("attempts", 3),
            o.optBoolean("rotate", true),
            o.optJSONArray("athletes")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            if (o.has("box")) o.optDouble("box") else null, if (o.has("load")) o.optDouble("load") else null,
            o.optInt("pos"), o.optString("started"),
            o.optJSONArray("skipped")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList())

        fun get(ctx: Context): GroupSession? = try {
            ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).getString(KEY, null)?.let { fromJson(JSONObject(it)) }
        } catch (_: Exception) { null }

        fun save(ctx: Context, s: GroupSession?) {
            val e = ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).edit()
            if (s == null) e.remove(KEY) else e.putString(KEY, s.toJson().toString())
            e.apply()
        }

        /** Pasa al turno siguiente y devuelve la sesión actualizada. */
        fun advance(ctx: Context, skip: Boolean = false): GroupSession? {
            val s = get(ctx) ?: return null
            val n = s.copy(pos = s.pos + 1, skipped = if (skip) s.skipped + s.pos else s.skipped)
            save(ctx, n)
            return n
        }

        /** Frase para anunciar el turno. */
        fun announce(s: GroupSession): String {
            val c = s.current ?: return "Sesión terminada. Buen trabajo."
            return if (s.attempts > 1) "${c.first}, intento ${c.second}. Cuando quieras." else "${c.first}. Cuando quieras."
        }
    }
}
