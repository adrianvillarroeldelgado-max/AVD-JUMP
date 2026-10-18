package com.salto240

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import java.util.Locale
import kotlin.math.sqrt

/**
 * Disposición diaria (readiness): el CMJ del día frente a la línea base del atleta.
 *
 * - Línea base: mejor CMJ de cada uno de los últimos 10 días medidos antes de hoy (mínimo 3 días).
 * - Variación normal: desviación entre días (con un mínimo del 2,5 % de la media, para no alarmar por
 *   una línea base demasiado estable).
 * - Cambio mínimo significativo (SWC) = 0,2 × desviación entre días (Hopkins).
 * - Semáforo por puntuación z: verde ≥ −1 (dentro de lo habitual), amarillo entre −1 y −2, rojo ≤ −2.
 *   Por encima de +SWC se indica que el atleta está mejor que su media.
 * Si no hay CMJ se usa el CMJ con brazos.
 */
object Readiness {

    enum class Light(val label: String, val color: Int, val advice: String) {
        GREEN("Listo", Col.OK, "Dentro de su variación normal: puede entrenar lo planificado."),
        YELLOW("Vigilar", Col.WARN, "Por debajo de lo habitual: baja el volumen o la intensidad de la sesión y vuelve a medir mañana."),
        RED("Fatiga", Col.ACCENT, "Muy por debajo de lo habitual: sesión regenerativa o técnica; revisa sueño, carga previa y molestias."),
        NONE("Sin línea base", Col.MUTED, "Hacen falta al menos 3 días previos con CMJ para comparar.")
    }

    data class Result(
        val test: TestType,
        val day: String,
        val value: Double,
        val mean: Double?,
        val sd: Double?,
        val swc: Double?,
        val z: Double?,
        val nBase: Int,
        val light: Light
    ) {
        val changePct: Double? get() = mean?.let { (value - it) / it * 100 }

        fun line(): String {
            val sb = StringBuilder(String.format(Locale.getDefault(), "%s %.1f cm", test.short, value))
            val m = mean
            if (m != null) {
                sb.append(String.format(Locale.getDefault(), " · base %.1f cm (%d días) · %+.1f %%", m, nBase, changePct))
                if (swc != null && value - m > swc && light == Light.GREEN) sb.append(" · por encima de su media")
            }
            return sb.toString()
        }
    }

    private fun dailyBest(js: List<Jump>, t: TestType): Map<String, Double> =
        js.filter { it.test == t && (it.loadKg ?: 0.0) < 0.5 }.groupBy { it.day }.mapValues { e -> e.value.maxOf { it.heightCm } }

    /** Resultado de un día (por defecto, el más reciente con CMJ). */
    fun of(js: List<Jump>, day: String? = null): Result? {
        val t = listOf(TestType.CMJ, TestType.CMJA).firstOrNull { tt -> js.any { it.test == tt && (it.loadKg ?: 0.0) < 0.5 } } ?: return null
        val daily = dailyBest(js, t)
        val d = day ?: daily.keys.maxOrNull() ?: return null
        val v = daily[d] ?: return null
        val base = daily.filterKeys { it < d }.toSortedMap().values.toList().takeLast(10)
        if (base.size < 3) return Result(t, d, v, base.takeIf { it.isNotEmpty() }?.average(), null, null, null, base.size, Light.NONE)
        val mean = base.average()
        val sdRaw = sqrt(base.sumOf { (it - mean) * (it - mean) } / (base.size - 1))
        val sd = maxOf(sdRaw, mean * 0.025)
        val z = (v - mean) / sd
        val light = when {
            z >= -1.0 -> Light.GREEN
            z > -2.0 -> Light.YELLOW
            else -> Light.RED
        }
        return Result(t, d, v, mean, sd, 0.2 * sdRaw, z, base.size, light)
    }
}

/** Pantalla de disposición del grupo: semáforo de cada atleta con el CMJ de hoy. */
class ReadinessActivity : BaseActivity() {

    private lateinit var groupSpinner: Spinner
    private lateinit var listBox: LinearLayout
    private var groups: List<Group> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vbox(14).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(text("Disposición diaria", 28f, Col.INK, true))
        root.addView(text("Un CMJ al llegar (mejor de 2–3 intentos) comparado con la línea base de cada atleta. " +
            "Sirve para ajustar la carga del día.", 14f, Col.MUTED))
        val p = panel()
        p.addView(caption("Grupo"))
        groupSpinner = Spinner(this)
        p.addView(groupSpinner, lp())
        p.addView(button("Medir al grupo (sesión con voz)", true) {
            val g = groups.getOrNull(groupSpinner.selectedItemPosition - 1)
            startActivity(Intent(this, GroupSessionActivity::class.java)
                .putExtra(GroupSessionActivity.EX_GROUP, g?.id ?: -1L)
                .putExtra(MainActivity.EX_TEST, TestType.CMJ.name))
        }, lp())
        root.addView(p, lp())
        listBox = vbox(10)
        root.addView(listBox, lp())
        root.addView(text("Verde: dentro de su variación normal (z ≥ −1). Amarillo: entre −1 y −2 desviaciones. Rojo: más de 2 por debajo. " +
            "La línea base son sus últimos 10 días medidos (mínimo 3). Cambio mínimo significativo (SWC) = 0,2 × desviación entre días.",
            12f, Col.MUTED))
        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })
        groupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = render()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        val prev = groups.getOrNull(groupSpinner.selectedItemPosition - 1)?.id
        groups = Clients.groups(this)
        groupSpinner.adapter = TrAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Todos los clientes") + groups.map { it.name })
        groups.indexOfFirst { it.id == prev }.takeIf { it >= 0 }?.let { groupSpinner.setSelection(it + 1) }
        render()
    }

    private fun render() {
        listBox.removeAllViews()
        val g = groups.getOrNull(groupSpinner.selectedItemPosition - 1)
        val all = Store.all(this)
        val today = Store.today().take(10)
        val clients = Clients.inGroup(this, g?.id)
        if (clients.isEmpty()) { listBox.addView(text("No hay clientes en este grupo.", 14f, Col.MUTED)); return }
        val rows = clients.map { c -> c to Readiness.of(all.filter { it.athlete == c.name }, today) }
            .sortedBy { (_, r) -> when (r?.light) { Readiness.Light.RED -> 0; Readiness.Light.YELLOW -> 1; Readiness.Light.GREEN -> 2; Readiness.Light.NONE -> 3; null -> 4 } }
        var pending = 0
        for ((c, r) in rows) {
            val card = panel()
            val head = hbox(10)
            val dot = View(this).apply { background = rounded(r?.light?.color ?: Col.LINE, 0, 8) }
            head.addView(dot, LinearLayout.LayoutParams(dp(16), dp(16)))
            head.addView(text(c.name, 17f, Col.INK, true), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(text(r?.light?.label ?: "Sin medir hoy", 14f, r?.light?.color ?: Col.MUTED, true))
            card.addView(head, lp())
            if (r == null) {
                pending++
                val last = Readiness.of(all.filter { it.athlete == c.name })
                card.addView(text(last?.let { "Último: " + Evaluations.pretty(it.day) + " · " + it.line() } ?: "Aún no tiene CMJ.", 13f, Col.MUTED))
            } else {
                card.addView(text(r.line(), 14f, Col.INK))
                card.addView(text(r.light.advice, 13f, Col.MUTED))
            }
            card.addView(button(if (r == null) "Medir CMJ ahora" else "Otro intento") {
                startActivity(MainActivity.captureIntent(this, TestType.CMJ, c.name, c.massKg, null, 0.0))
            }, lp())
            card.setOnClickListener {
                startActivity(Intent(this, ClientHistoryActivity::class.java).putExtra(MainActivity.EX_ATHLETE, c.name))
            }
            listBox.addView(card, lp())
        }
        listBox.addView(text("$pending de ${clients.size} sin medir hoy.", 13f, Col.MUTED).apply { gravity = Gravity.END }, lp())
    }
}
