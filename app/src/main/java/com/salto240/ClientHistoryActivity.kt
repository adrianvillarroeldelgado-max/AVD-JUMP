package com.salto240

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.util.Locale
import android.app.AlertDialog
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Historial de evaluaciones de un cliente: evolución por indicador y detalle de cada día evaluado. */
class ClientHistoryActivity : BaseActivity() {

    private lateinit var name: String
    private lateinit var chart: TrendChartView
    private lateinit var metricSpinner: Spinner
    private lateinit var trendText: TextView
    private lateinit var sessionsBox: LinearLayout
    private lateinit var header: TextView
    private var evals: List<Evaluation> = emptyList()
    private var metrics: List<Metric> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra(MainActivity.EX_ATHLETE) ?: run { finish(); return }

        val root = vbox(14).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(text("Historial de evaluaciones", 28f, Col.INK, true))
        header = text("", 14f, Col.MUTED)
        root.addView(header)

        val pT = panel()
        pT.addView(caption("Evolución"))
        metricSpinner = Spinner(this)
        pT.addView(metricSpinner, lp())
        chart = TrendChartView(this)
        pT.addView(chart, lp(h = dp(220)))
        trendText = text("", 14f, Col.INK)
        pT.addView(trendText)
        root.addView(pT, lp())

        root.addView(caption("Evaluaciones"))
        sessionsBox = vbox(10)
        root.addView(sessionsBox, lp())

        val actions = hbox(8)
        root.addView(button("Saltos y videos (medir a mano)") {
            startActivity(Intent(this, HistoryActivity::class.java).putExtra(MainActivity.EX_ATHLETE, name))
        }, lp())
        actions.addView(button("Informe") {
            startActivity(Intent(this, ReportActivity::class.java).putExtra(MainActivity.EX_ATHLETE, name))
        }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(button("Compartir", true) { share() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions, lp())
        root.addView(button("Informe en PDF con logo", true) { PdfShare.run(this, name) }, lp())
        val more = hbox(8)
        more.addView(button("Altura óptima de Drop Jump") {
            startActivity(Intent(this, DjHeightActivity::class.java).putExtra(MainActivity.EX_ATHLETE, name))
        }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        more.addView(button("Percentiles") { showPercentiles() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(more, lp())

        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })

        metricSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = renderTrend()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val client = Clients.byName(this, name)
        val athlete = client?.toAthlete() ?: Store.athlete(this, name)
        evals = Evaluations.list(Store.all(this), athlete)
        val g = client?.let { Clients.groupName(this, it.groupId) }
        header.text = listOfNotNull(name, g, client?.summary()).joinToString(" · ") +
            "\n${evals.size} evaluaciones · ${evals.sumOf { it.jumps.size }} saltos" +
            (Readiness.of(Store.ofAthlete(this, name))?.let { r ->
                "\nDisposición " + Evaluations.pretty(r.day) + ": " + r.light.label + " · " + r.line()
            } ?: "")

        // Solo los indicadores que tienen datos; primero el que más se repite.
        val prev = metrics.getOrNull(metricSpinner.selectedItemPosition)
        metrics = Metric.values().filter { m -> evals.any { it.values.containsKey(m) } }
            .sortedByDescending { m -> evals.count { it.values.containsKey(m) } }
        metricSpinner.adapter = TrAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (metrics.isEmpty()) listOf("Sin evaluaciones") else metrics.map { it.label })
        metrics.indexOf(prev).takeIf { it >= 0 }?.let { metricSpinner.setSelection(it) }
        renderTrend()
        renderSessions()
    }

    private fun renderTrend() {
        val m = metrics.getOrNull(metricSpinner.selectedItemPosition)
        if (m == null) {
            chart.set(emptyList(), Metric.CMJ)
            trendText.text = "Cuando guardes saltos de $name, cada día de medición aparecerá aquí como una evaluación."
            return
        }
        val pts = Evaluations.trend(evals, m)
        chart.set(pts, m)
        trendText.text = if (pts.size < 2) "Una sola evaluación con este indicador. Repite la medición para ver la evolución."
        else {
            val first = pts.first().second; val lastV = pts.last().second
            val best = if (m.higherIsBetter) pts.maxOf { it.second } else pts.minOf { it.second }
            val change = lastV - first
            val better = (change > 0) == m.higherIsBetter
            "Desde la primera evaluación: ${m.format(first)} → ${m.format(lastV)} " +
                "(${if (Math.abs(change) < 1e-9) "sin cambio" else if (better) "mejora" else "empeora"}). Mejor marca: ${m.format(best)}."
        }
    }

    private fun renderSessions() {
        sessionsBox.removeAllViews()
        if (evals.isEmpty()) {
            sessionsBox.addView(text("Aún no hay evaluaciones.", 14f, Col.MUTED))
            return
        }
        evals.forEachIndexed { i, e ->
            val card = panel()
            val head = hbox(8)
            head.addView(text(Evaluations.pretty(e.day), 18f, Col.INK, true), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val tests = e.jumps.map { it.test.short }.distinct().joinToString(", ")
            head.addView(text("${e.jumps.size} saltos", 13f, Col.MUTED))
            card.addView(head, lp())
            card.addView(text(tests, 12f, Col.MUTED))
            for (m in Metric.values()) {
                val v = e.values[m] ?: continue
                val row = hbox(8)
                row.addView(text(m.label, 14f, Col.INK), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(text(m.format(v), 14f, Col.INK, true))
                card.addView(row, lp())
                Evaluations.delta(evals, i, m)?.let { dTxt ->
                    val color = when {
                        dTxt.startsWith("▲") -> Col.OK
                        dTxt.startsWith("▼") -> Col.ACCENT
                        else -> Col.MUTED
                    }
                    card.addView(text("   $dTxt vs. evaluación anterior", 12f, color))
                }
            }
            // Intentos por prueba: mejor, media, CV y avisos.
            val att = Attempts.summarize(e.jumps)
            if (att.isNotEmpty()) {
                card.addView(caption("Intentos"))
                for (s in att) {
                    card.addView(text(s.line(), 13f, Col.INK))
                    s.warnings().forEach { w -> card.addView(text("   ⚠ $w", 12f, Col.WARN)) }
                }
            }
            if (e.values.isEmpty()) card.addView(text("Sin indicadores calculables (p. ej. solo saltos con carga sin completar el perfil).", 12f, Col.MUTED))
            if (e.notes.isNotEmpty()) card.addView(text("Notas: " + e.notes.joinToString(" · "), 12f, Col.MUTED))
            val btns = hbox(8)
            btns.addView(button("Saltos") { jumpsOf(e) }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            btns.addView(button("Borrar evaluación") { confirmDeleteEvaluation(e) }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(btns, lp())
            sessionsBox.addView(card, lp())
        }
        sessionsBox.addView(button("Borrar todos los resultados de $name") { confirmDeleteAll() }, lp())
    }

    private fun f(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    private fun jumpLabel(j: Jump): String {
        val load = j.loadKg?.takeIf { it > 0 }?.let { f(" +%.1f kg", it) } ?: ""
        val extra = if (j.test.isRepeated) " (media)" else ""
        return f("%s%s · %.1f cm%s · %s%s", j.test.short, load, j.heightCm, extra, j.date.drop(11), if (j.video != null) " · video" else "")
    }

    /** Lista de saltos de la evaluación: se marcan los que se quieren borrar. */
    private fun jumpsOf(e: Evaluation) {
        val js = e.jumps
        val checked = BooleanArray(js.size)
        Dlg(this)
            .setTitle("Saltos del " + Evaluations.pretty(e.day))
            .setMultiChoiceItems(js.map { jumpLabel(it) }.toTypedArray(), checked) { _, w, on -> checked[w] = on }
            .setPositiveButton("Borrar marcados") { _, _ ->
                val ids = js.indices.filter { checked[it] }.map { js[it].id }.toSet()
                if (ids.isEmpty()) {
                    Tst.makeText(this, "No marcaste ningún salto.", Toast.LENGTH_SHORT).show()
                } else confirm(
                    if (ids.size == 1) "¿Borrar 1 salto?" else "¿Borrar ${ids.size} saltos?",
                    "Se borran sus resultados y sus videos. No se puede deshacer."
                ) { Store.deleteMany(this, ids); load() }
            }
            .setNeutralButton("Ver videos") { _, _ ->
                startActivity(Intent(this, HistoryActivity::class.java).putExtra(MainActivity.EX_ATHLETE, name))
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun confirmDeleteEvaluation(e: Evaluation) {
        val nVid = e.jumps.count { it.video != null }
        confirm(
            "¿Borrar la evaluación del " + Evaluations.pretty(e.day) + "?",
            "Se borran sus ${e.jumps.size} saltos" + (if (nVid > 0) " y $nVid videos" else "") + ". No se puede deshacer."
        ) {
            val n = Store.deleteMany(this, e.jumps.map { it.id }.toSet())
            Tst.makeText(this, "Evaluación borrada ($n saltos).", Toast.LENGTH_SHORT).show()
            load()
        }
    }

    private fun confirmDeleteAll() {
        val all = evals.flatMap { it.jumps }
        if (all.isEmpty()) return
        confirm(
            "¿Borrar todos los resultados de $name?",
            "Se borran ${evals.size} evaluaciones y ${all.size} saltos con sus videos. La ficha del cliente se conserva. No se puede deshacer."
        ) {
            // Segunda confirmación: es la acción más destructiva.
            confirm("Confirma el borrado", "Vas a borrar ${all.size} saltos de $name.") {
                Store.deleteMany(this, all.map { it.id }.toSet())
                Tst.makeText(this, "Resultados de $name borrados.", Toast.LENGTH_SHORT).show()
                load()
            }
        }
    }

    private fun confirm(title: String, msg: String, onYes: () -> Unit) {
        Dlg(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Borrar") { _, _ -> onYes() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPercentiles() {
        val lines = Norms.lines(this, name)
        Dlg(this)
            .setTitle("Comparación con referencias")
            .setMessage(if (lines.isEmpty()) "Todavía no hay con quién comparar. Los percentiles se calculan con los demás clientes " +
                "del mismo sexo (y deporte, si hay suficientes) y con los valores de referencia que cargues en Ajustes y datos."
                else lines.joinToString("\n\n"))
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Referencias") { _, _ -> startActivity(Intent(this, RefsActivity::class.java)) }
            .show()
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Historial de evaluaciones — $name")
            putExtra(Intent.EXTRA_TEXT, Lang.tr(this@ClientHistoryActivity, Evaluations.summaryText(name, evals)))
        }
        startActivity(Intent.createChooser(i, "Compartir historial"))
    }
}
