package com.salto240

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Referencias para comparar a un atleta:
 * 1. Percentil dentro de tu propia base de datos (mismo sexo y, si hay suficientes, mismo deporte).
 * 2. Tablas de referencia que cargas tú (deporte, sexo, rango de edad, media y desviación),
 *    por ejemplo las de un artículo o las de tu club. Con ellas se da el percentil estimado.
 */
data class NormRef(
    val id: Long,
    val label: String,
    val metric: Metric,
    val sport: String,
    val sex: String?,      // null = ambos
    val ageMin: Int?,
    val ageMax: Int?,
    val mean: Double,
    val sd: Double
) {
    fun matches(c: Client?): Boolean {
        if (c == null) return sport.isBlank() && sex == null && ageMin == null && ageMax == null
        if (sport.isNotBlank() && !c.sport.equals(sport, ignoreCase = true)) return false
        if (sex != null && c.sex != null && c.sex != sex) return false
        if (sex != null && c.sex == null) return false
        val a = c.age
        if (ageMin != null && (a == null || a < ageMin)) return false
        if (ageMax != null && (a == null || a > ageMax)) return false
        return true
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("metric", metric.name).put("sport", sport)
        .put("mean", mean).put("sd", sd).apply {
            sex?.let { put("sex", it) }; ageMin?.let { put("ageMin", it) }; ageMax?.let { put("ageMax", it) }
        }

    fun describe(): String {
        val who = listOfNotNull(sport.ifBlank { null }, sex, when {
            ageMin != null && ageMax != null -> "$ageMin–$ageMax años"
            ageMin != null -> "≥ $ageMin años"
            ageMax != null -> "≤ $ageMax años"
            else -> null
        }).joinToString(" · ").ifEmpty { "todos" }
        return String.format(Locale.getDefault(), "%s · %s: media %s, DE %s (%s)", label, metric.label, metric.format(mean), metric.format(sd), who)
    }

    companion object {
        fun fromJson(o: JSONObject) = NormRef(o.optLong("id"), o.optString("label"),
            Metric.values().firstOrNull { it.name == o.optString("metric") } ?: Metric.CMJ, o.optString("sport"),
            if (o.has("sex")) o.optString("sex") else null,
            if (o.has("ageMin")) o.optInt("ageMin") else null, if (o.has("ageMax")) o.optInt("ageMax") else null,
            o.optDouble("mean"), o.optDouble("sd"))
    }
}

object Norms {
    private const val KEY = "norms"

    fun refs(ctx: Context): List<NormRef> {
        val a = try { JSONArray(ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> NormRef.fromJson(o) } }
    }

    fun saveRefs(ctx: Context, list: List<NormRef>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    /** Función de distribución normal (aproximación de Abramowitz y Stegun). */
    fun phi(z: Double): Double {
        val t = 1 / (1 + 0.2316419 * abs(z))
        val d = 0.3989423 * exp(-z * z / 2)
        val p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))))
        return if (z >= 0) 1 - p else p
    }

    /** Mejor valor histórico de cada indicador para un atleta. */
    fun bestValues(ctx: Context, name: String, all: List<Jump>? = null): Map<Metric, Double> {
        val a = Store.athlete(ctx, name)
        val evals = Evaluations.list(all ?: Store.ofAthlete(ctx, name), a)
        val out = HashMap<Metric, Double>()
        for (e in evals) for ((m, v) in e.values) {
            val prev = out[m]
            if (prev == null || (if (m.higherIsBetter) v > prev else v < prev)) out[m] = v
        }
        return out
    }

    data class Pct(val metric: Metric, val value: Double, val percentile: Int, val n: Int, val scope: String)

    /** Percentil dentro de la base de datos (otros clientes con ese indicador). */
    fun databasePercentiles(ctx: Context, name: String): List<Pct> {
        val me = Clients.byName(ctx, name)
        val all = Store.all(ctx)
        val mine = bestValues(ctx, name, all.filter { it.athlete == name })
        val others = Clients.all(ctx).filter { it.name != name }
        fun pool(filter: (Client) -> Boolean) = others.filter(filter)
        val out = ArrayList<Pct>()
        for ((m, v) in mine) {
            if (m == Metric.ASYM || m == Metric.ASYM_DJ || m == Metric.FV) continue
            val candidates = listOf(
                Triple({ c: Client -> me?.sex != null && c.sex == me.sex && me.sport.isNotBlank() && c.sport.equals(me.sport, true) },
                    "${me?.sport} · ${me?.sex?.lowercase()}", 8),
                Triple({ c: Client -> me?.sex != null && c.sex == me.sex }, "mismo sexo", 8),
                Triple({ _: Client -> true }, "todos tus clientes", 5)
            )
            for ((flt, label, min) in candidates) {
                val vals = pool(flt).mapNotNull { c -> bestValues(ctx, c.name, all.filter { it.athlete == c.name })[m] }
                if (vals.size < min) continue
                val below = vals.count { if (m.higherIsBetter) it < v else it > v } + vals.count { it == v } * 0.5
                out.add(Pct(m, v, (below / vals.size * 100).toInt(), vals.size, label))
                break
            }
        }
        return out.sortedBy { it.metric.ordinal }
    }

    /** Percentil según las tablas de referencia cargadas. */
    fun refPercentiles(ctx: Context, name: String): List<Pair<NormRef, Int>> {
        val c = Clients.byName(ctx, name)
        val mine = bestValues(ctx, name)
        return refs(ctx).filter { it.matches(c) && it.sd > 0 }.mapNotNull { r ->
            val v = mine[r.metric] ?: return@mapNotNull null
            var z = (v - r.mean) / r.sd
            if (!r.metric.higherIsBetter) z = -z
            r to (phi(z) * 100).toInt().coerceIn(1, 99)
        }
    }

    /** Líneas breves para el informe. */
    fun lines(ctx: Context, name: String): List<String> =
        refPercentiles(ctx, name).map { (r, p) -> "${r.metric.label}: percentil $p según «${r.label}»" } +
            databasePercentiles(ctx, name).filter { it.metric == Metric.CMJ || it.metric == Metric.RSI || it.metric == Metric.CMJA }
                .map { "${it.metric.label}: percentil ${it.percentile} entre ${it.n} (${it.scope})" }
}

/** Ranking del grupo por indicador. */
class RankingActivity : BaseActivity() {
    private lateinit var groupSpinner: Spinner
    private lateinit var metricSpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var box: LinearLayout
    private var groups: List<Group> = emptyList()
    private val metrics = Metric.values().toList()
    private var lastText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vbox(14).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(text("Ranking del grupo", 28f, Col.INK, true))
        val p = panel()
        p.addView(caption("Grupo"))
        groupSpinner = Spinner(this); p.addView(groupSpinner, lp())
        p.addView(caption("Indicador"))
        metricSpinner = Spinner(this).apply {
            adapter = TrAdapter(this@RankingActivity, android.R.layout.simple_spinner_dropdown_item, metrics.map { it.label })
        }
        p.addView(metricSpinner, lp())
        p.addView(caption("Valor"))
        modeSpinner = Spinner(this).apply {
            adapter = TrAdapter(this@RankingActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Última evaluación", "Mejor marca", "Últimos 30 días (mejor)"))
        }
        p.addView(modeSpinner, lp())
        root.addView(p, lp())
        box = vbox(8)
        root.addView(box, lp())
        root.addView(button("Compartir ranking", true) {
            if (lastText.isNotEmpty()) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, Lang.tr(this, lastText)), Lang.tr(this, "Compartir ranking")))
        }, lp())
        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })
        groups = Clients.groups(this)
        groupSpinner.adapter = TrAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Todos los clientes") + groups.map { it.name })
        val l = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = render()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        groupSpinner.onItemSelectedListener = l; metricSpinner.onItemSelectedListener = l; modeSpinner.onItemSelectedListener = l
    }

    private fun render() {
        box.removeAllViews()
        val g = groups.getOrNull(groupSpinner.selectedItemPosition - 1)
        val m = metrics[metricSpinner.selectedItemPosition.coerceAtLeast(0)]
        val mode = modeSpinner.selectedItemPosition
        val all = Store.all(this)
        val since = java.time.LocalDate.now().minusDays(30).toString()
        val rows = Clients.inGroup(this, g?.id).mapNotNull { c ->
            val evals = Evaluations.list(all.filter { it.athlete == c.name && (mode != 2 || it.day >= since) }, c.toAthlete())
            val withM = evals.filter { it.values.containsKey(m) }
            val v = when (mode) {
                0 -> withM.firstOrNull()?.values?.get(m)
                else -> withM.mapNotNull { it.values[m] }.let { vs -> if (m.higherIsBetter) vs.maxOrNull() else vs.minOrNull() }
            } ?: return@mapNotNull null
            Triple(c, v, withM.firstOrNull()?.day ?: "")
        }.sortedWith(compareBy { if (m.higherIsBetter) -it.second else it.second })
        if (rows.isEmpty()) { box.addView(text("Nadie del grupo tiene este indicador medido.", 14f, Col.MUTED)); lastText = ""; return }
        val maxV = rows.maxOf { abs(it.second) }.takeIf { it > 0 } ?: 1.0
        val mean = rows.map { it.second }.average()
        val sb = StringBuilder("Ranking ${m.label}" + (g?.let { " · ${it.name}" } ?: "") + "\n")
        rows.forEachIndexed { i, (c, v, day) ->
            val card = panel()
            val head = hbox(10)
            head.addView(text("${i + 1}", 20f, if (i < 3) Col.ACCENT else Col.MUTED, true), lp(dp(34)))
            head.addView(text(c.name, 16f, Col.INK, true), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(text(m.format(v), 16f, Col.INK, true))
            card.addView(head, lp())
            val bar = hbox(0)
            bar.addView(View(this).apply { background = rounded(if (i < 3) Col.ACCENT else Col.TRACK, 0, 4) },
                LinearLayout.LayoutParams(0, dp(8), (abs(v) / maxV).toFloat().coerceIn(0.02f, 1f)))
            bar.addView(View(this), LinearLayout.LayoutParams(0, dp(8), (1 - abs(v) / maxV).toFloat().coerceAtLeast(0f)))
            card.addView(bar, lp())
            if (day.isNotEmpty() && mode == 0) card.addView(text(Evaluations.pretty(day), 12f, Col.MUTED))
            card.setOnClickListener { startActivity(Intent(this, ClientHistoryActivity::class.java).putExtra(MainActivity.EX_ATHLETE, c.name)) }
            box.addView(card, lp())
            sb.append("${i + 1}. ${c.name}: ${m.format(v)}\n")
        }
        box.addView(text("Media del grupo: ${m.format(mean)} · ${rows.size} atletas", 13f, Col.MUTED))
        sb.append("Media: ${m.format(mean)}")
        lastText = sb.toString()
    }
}

/** Altura óptima de caída para el Drop Jump: RSI por altura de cajón. */
class DjHeightActivity : BaseActivity() {
    private lateinit var name: String
    private lateinit var daySpinner: Spinner
    private lateinit var box: LinearLayout
    private var days: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra(MainActivity.EX_ATHLETE) ?: run { finish(); return }
        val root = vbox(14).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(text("Altura óptima de caída", 28f, Col.INK, true))
        root.addView(text("$name · Drop Jump desde varias alturas de cajón (por ejemplo 20, 30, 40, 50 y 60 cm, 2–3 intentos en cada una). " +
            "La altura óptima es la que da el mayor RSI; por encima, el contacto se alarga y el RSI cae.", 14f, Col.MUTED))
        val p = panel()
        p.addView(caption("Sesión"))
        daySpinner = Spinner(this); p.addView(daySpinner, lp())
        root.addView(p, lp())
        box = vbox(10); root.addView(box, lp())
        val m = panel()
        m.addView(caption("Medir"))
        val row = hbox(6)
        for (h in listOf(20, 30, 40, 50, 60)) row.addView(button("$h cm") {
            val c = Clients.byName(this, name)
            startActivity(MainActivity.captureIntent(this, TestType.DJ, name, c?.massKg, h.toDouble(), 0.0))
        }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        m.addView(row, lp())
        m.addView(text("Cada botón abre la cámara con esa altura de cajón ya puesta.", 12f, Col.MUTED))
        root.addView(m, lp())
        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })
        daySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = render()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        val js = Store.ofAthlete(this, name).filter { it.test == TestType.DJ && it.rsi != null && it.boxCm != null }
        days = js.map { it.day }.distinct().sortedDescending()
        daySpinner.adapter = TrAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            days.map { Evaluations.pretty(it) } + "Todas las sesiones")
        render()
    }

    private fun render() {
        box.removeAllViews()
        val sel = daySpinner.selectedItemPosition
        val js = Store.ofAthlete(this, name).filter { it.test == TestType.DJ && it.rsi != null && it.boxCm != null &&
            (sel !in days.indices || it.day == days[sel]) }
        if (js.isEmpty()) { box.addView(text("Aún no hay Drop Jumps con altura de cajón para este atleta.", 14f, Col.MUTED)); return }
        val byBox = js.groupBy { Math.round(it.boxCm!!).toInt() }.toSortedMap()
            .mapValues { (_, l) -> l.maxByOrNull { it.rsi!! }!! }
        val best = byBox.maxByOrNull { it.value.rsi!! }!!
        val maxR = best.value.rsi!!
        val card = panel()
        for ((h, j) in byBox) {
            val row = hbox(8)
            row.addView(text("$h cm", 14f, Col.INK, true), lp(dp(56)))
            val bar = hbox(0)
            bar.addView(View(this).apply { background = rounded(if (h == best.key) Col.ACCENT else Col.TRACK, 0, 4) },
                LinearLayout.LayoutParams(0, dp(14), (j.rsi!! / maxR).toFloat().coerceIn(0.03f, 1f)))
            bar.addView(View(this), LinearLayout.LayoutParams(0, dp(14), (1 - j.rsi!! / maxR).toFloat().coerceAtLeast(0f)))
            row.addView(bar, lp(0, dp(14), 1f))
            row.addView(text(String.format(Locale.getDefault(), "%.2f", j.rsi), 14f, Col.INK, true), lp(dp(48)))
            card.addView(row, lp())
            card.addView(text(String.format(Locale.getDefault(), "      %.1f cm · contacto %.0f ms", j.heightCm, j.contactMs ?: 0.0), 12f, Col.MUTED))
        }
        box.addView(card, lp())
        val res = panel()
        res.addView(caption("Resultado"))
        res.addView(text(String.format(Locale.getDefault(), "Altura óptima: %d cm (RSI %.2f)", best.key, maxR), 20f, Col.INK, true))
        val note = when {
            byBox.size < 3 -> "Mide al menos 3 alturas para confirmar el óptimo."
            best.key == byBox.keys.last() -> "El mejor RSI está en el cajón más alto medido: prueba también una altura mayor."
            best.key == byBox.keys.first() -> "El mejor RSI está en el cajón más bajo: el atleta tolera poca carga excéntrica; prioriza pliometría de baja altura."
            else -> "Usa esta altura para el trabajo pliométrico de drop jumps."
        }
        res.addView(text(note, 14f, Col.MUTED))
        box.addView(res, lp())
    }
}

/** Tablas de referencia que carga el usuario. */
class RefsActivity : BaseActivity() {
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vbox(14).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(text("Valores de referencia", 28f, Col.INK, true))
        root.addView(text("Carga la media y la desviación estándar de un grupo de referencia (de un artículo, de tu club o de una selección) " +
            "para un deporte, sexo y rango de edad. La app calcula el percentil de cada atleta que coincida. " +
            "Además, el informe compara a cada atleta con tus propios clientes.", 14f, Col.MUTED))
        box = vbox(10)
        root.addView(box, lp())
        root.addView(button("Agregar referencia", true) { edit(null) }, lp())
        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })
        render()
    }

    private fun render() {
        box.removeAllViews()
        val list = Norms.refs(this)
        if (list.isEmpty()) box.addView(text("Todavía no cargaste referencias.", 14f, Col.MUTED))
        for (r in list) {
            val card = panel()
            card.addView(text(r.describe(), 14f, Col.INK))
            val row = hbox(8)
            row.addView(button("Editar") { edit(r) }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Borrar") {
                Norms.saveRefs(this, Norms.refs(this).filter { it.id != r.id }); render()
            }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row, lp())
            box.addView(card, lp())
        }
    }

    private fun edit(r: NormRef?) {
        val metrics = Metric.values().toList()
        val form = vbox(8).apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val label = input("Nombre (p. ej. Fútbol sub-17, Smith 2020)").apply { setText(r?.label ?: "") }
        val ms = Spinner(this).apply {
            adapter = TrAdapter(this@RefsActivity, android.R.layout.simple_spinner_dropdown_item, metrics.map { it.label })
            setSelection(metrics.indexOf(r?.metric ?: Metric.CMJ))
        }
        val sport = input("Deporte (vacío = todos)").apply { setText(r?.sport ?: "") }
        val sexes = listOf("Ambos sexos", "Masculino", "Femenino")
        val ss = Spinner(this).apply {
            adapter = TrAdapter(this@RefsActivity, android.R.layout.simple_spinner_dropdown_item, sexes)
            setSelection(when (r?.sex) { "Masculino" -> 1; "Femenino" -> 2; else -> 0 })
        }
        val a0 = input("Edad mínima", true).apply { setText(r?.ageMin?.toString() ?: "") }
        val a1 = input("Edad máxima", true).apply { setText(r?.ageMax?.toString() ?: "") }
        val mean = input("Media", true).apply { setText(r?.mean?.toString() ?: "") }
        val sd = input("Desviación estándar", true).apply { setText(r?.sd?.toString() ?: "") }
        listOf<View>(label, ms, sport, ss, a0, a1, mean, sd).forEach { form.addView(it, lp()) }
        Dlg(this)
            .setTitle(if (r == null) "Nueva referencia" else "Editar referencia")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Guardar") { _, _ ->
                val mv = mean.number(); val sv = sd.number()
                if (label.text.isBlank() || mv == null || sv == null || sv <= 0) {
                    Tst.makeText(this, "Completa nombre, media y desviación (mayor que 0).", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                val n = NormRef(r?.id ?: System.currentTimeMillis(), label.text.toString().trim(), metrics[ms.selectedItemPosition],
                    sport.text.toString().trim(), sexes[ss.selectedItemPosition].takeIf { ss.selectedItemPosition > 0 },
                    a0.number()?.toInt(), a1.number()?.toInt(), mv, sv)
                Norms.saveRefs(this, Norms.refs(this).filter { it.id != n.id } + n)
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
