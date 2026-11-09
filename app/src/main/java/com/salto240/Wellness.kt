package com.salto240

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Cuestionario de bienestar diario (tipo Hooper / McLean): sueño, fatiga, estrés y dolor muscular, de 1 a 5.
 * En todas las preguntas 5 es lo mejor (durmió muy bien, nada cansado, nada estresado, sin dolor).
 * Total de 4 a 20. Se compara con los últimos 10 días del atleta (mínimo 3), como el CMJ en la disposición diaria.
 */
object Wellness {
    private const val PREFS = "salto240"
    private const val KEY = "wellness"

    data class Item(val key: String, val label: String, val low: String, val high: String)

    val ITEMS = listOf(
        Item("sleep", "Calidad del sueño", "Muy mala", "Muy buena"),
        Item("fatigue", "Fatiga", "Muy cansado", "Nada cansado"),
        Item("stress", "Estrés", "Muy estresado", "Nada estresado"),
        Item("soreness", "Dolor muscular", "Mucho dolor", "Sin dolor")
    )

    private val SHORT = mapOf("sleep" to "Sueño", "fatigue" to "Fatiga", "stress" to "Estrés", "soreness" to "Dolor")

    data class Entry(val day: String, val values: Map<String, Int>) {
        val total: Int get() = ITEMS.sumOf { values[it.key] ?: 0 }
        val complete: Boolean get() = ITEMS.all { (values[it.key] ?: 0) in 1..5 }
        fun low(): List<String> = ITEMS.filter { (values[it.key] ?: 5) <= 2 }.map { it.label.lowercase() }
        fun line(): String = ITEMS.joinToString(" · ") { "${SHORT[it.key]} ${values[it.key] ?: "—"}" } + " · total $total/20"
    }

    private fun root(ctx: Context): JSONObject =
        try { JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun entries(ctx: Context, athlete: String): List<Entry> {
        val a = root(ctx).optJSONObject(athlete) ?: return emptyList()
        return a.keys().asSequence().mapNotNull { d ->
            val o = a.optJSONObject(d) ?: return@mapNotNull null
            Entry(d, ITEMS.associate { it.key to o.optInt(it.key, 0) })
        }.filter { it.complete }.sortedBy { it.day }.toList()
    }

    fun of(ctx: Context, athlete: String, day: String): Entry? = entries(ctx, athlete).firstOrNull { it.day == day }

    fun save(ctx: Context, athlete: String, day: String, values: Map<String, Int>) {
        val r = root(ctx)
        val a = r.optJSONObject(athlete) ?: JSONObject()
        val o = JSONObject(); values.forEach { (k, v) -> o.put(k, v) }
        a.put(day, o); r.put(athlete, a)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, r.toString()).apply()
    }

    /** Todos los registros (atleta, registro), para Excel. */
    fun all(ctx: Context): List<kotlin.Pair<String, Entry>> {
        val r = root(ctx)
        return r.keys().asSequence().flatMap { n -> entries(ctx, n).map { n to it } }.toList()
    }

    data class Status(val entry: Entry, val mean: Double?, val z: Double?, val nBase: Int, val light: Readiness.Light, val reason: String)

    /** Semáforo del bienestar de un día frente a la línea base del atleta. */
    fun status(ctx: Context, athlete: String, day: String): Status? {
        val es = entries(ctx, athlete)
        val e = es.firstOrNull { it.day == day } ?: return null
        val base = es.filter { it.day < day }.takeLast(10).map { it.total.toDouble() }
        val low = e.low()
        var light = Readiness.Light.GREEN
        var reason = "Bienestar dentro de lo habitual."
        var mean: Double? = null; var z: Double? = null
        if (base.size >= 3) {
            val m = base.average(); mean = m
            val sd = max(sqrt(base.sumOf { (it - m) * (it - m) } / (base.size - 1)), 1.0)
            val zz = (e.total - m) / sd; z = zz
            if (zz <= -2) { light = Readiness.Light.RED; reason = "Bienestar muy por debajo de su media." }
            else if (zz < -1) { light = Readiness.Light.YELLOW; reason = "Bienestar por debajo de su media." }
        }
        if (e.total <= 9) { light = Readiness.Light.RED; reason = "Bienestar muy bajo (total ${e.total}/20)." }
        if (low.isNotEmpty() && light == Readiness.Light.GREEN) {
            light = Readiness.Light.YELLOW; reason = "Valor bajo en: " + low.joinToString(", ") + "."
        } else if (low.isNotEmpty()) reason += " Bajo en: " + low.joinToString(", ") + "."
        return Status(e, mean, z, base.size, light, reason)
    }

    /** Peor de los dos semáforos (salto y bienestar). Sin dato en uno, vale el otro. */
    fun combine(a: Readiness.Light?, b: Readiness.Light?): Readiness.Light? {
        fun rank(l: Readiness.Light?) = when (l) { Readiness.Light.RED -> 0; Readiness.Light.YELLOW -> 1; Readiness.Light.GREEN -> 2; else -> 3 }
        val ok = listOfNotNull(a, b).filter { it != Readiness.Light.NONE }
        return ok.minByOrNull { rank(it) } ?: a ?: b
    }

    /** Cuestionario: 4 preguntas con botones del 1 al 5. Guarda en el día de hoy y llama a [done]. */
    fun ask(act: Activity, athlete: String, done: () -> Unit = {}) {
        val day = Store.today().take(10)
        val prev = of(act, athlete, day)?.values ?: emptyMap()
        val chosen = HashMap<String, Int>(prev)
        val box = act.vbox(10).apply { setPadding(act.dp(18), act.dp(8), act.dp(18), 0) }
        box.addView(act.text("5 es lo mejor en todas. Responde cómo se siente ahora, antes de entrenar.", 13f, Col.MUTED))
        for (it in ITEMS) {
            box.addView(act.text(it.label, 15f, Col.INK, true))
            val row = act.hbox(4)
            val btns = ArrayList<Button>()
            fun paint() = btns.forEachIndexed { i, b ->
                val on = chosen[it.key] == i + 1
                b.background = act.rounded(if (on) Col.ACCENT else Col.SURFACE, if (on) Col.ACCENT else Col.LINE, 8)
                b.setTextColor(if (on) 0xFFFFFFFF.toInt() else Col.INK)
            }
            for (v in 1..5) {
                val b = Button(act).apply {
                    text = "$v"; isAllCaps = false; stateListAnimator = null
                    minWidth = 0; minimumWidth = 0
                    setOnClickListener { _ -> chosen[it.key] = v; paint() }
                }
                btns.add(b)
                row.addView(b, LinearLayout.LayoutParams(0, act.dp(44), 1f))
            }
            paint()
            box.addView(row, lp())
            val ends = act.hbox(0)
            ends.addView(act.text(it.low, 11f, Col.MUTED), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            ends.addView(act.text(it.high, 11f, Col.MUTED).apply { gravity = Gravity.END }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(ends, lp())
        }
        val dlg = Dlg(act).setTitle("Bienestar de hoy · $athlete")
            .setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .show()
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (ITEMS.any { chosen[it.key] == null }) {
                Tst.makeText(act, "Responde las 4 preguntas.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            save(act, athlete, day, chosen)
            dlg.dismiss()
            val st = status(act, athlete, day)
            Tst.makeText(act, String.format(Locale.getDefault(), "Bienestar: %d/20", chosen.values.sum()) +
                (st?.let { " · " + it.light.label } ?: ""), Toast.LENGTH_SHORT).show()
            done()
        }
    }
}
