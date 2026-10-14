package com.salto240

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

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
            "\n${evals.size} evaluaciones · ${evals.sumOf { it.jumps.size }} saltos"

        // Solo los indicadores que tienen datos; primero el que más se repite.
        val prev = metrics.getOrNull(metricSpinner.selectedItemPosition)
        metrics = Metric.values().filter { m -> evals.any { it.values.containsKey(m) } }
            .sortedByDescending { m -> evals.count { it.values.containsKey(m) } }
        metricSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
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
            if (e.values.isEmpty()) card.addView(text("Sin indicadores calculables (p. ej. solo saltos con carga sin completar el perfil).", 12f, Col.MUTED))
            if (e.notes.isNotEmpty()) card.addView(text("Notas: " + e.notes.joinToString(" · "), 12f, Col.MUTED))
            sessionsBox.addView(card, lp())
        }
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Historial de evaluaciones — $name")
            putExtra(Intent.EXTRA_TEXT, Evaluations.summaryText(name, evals))
        }
        startActivity(Intent.createChooser(i, "Compartir historial"))
    }
}
