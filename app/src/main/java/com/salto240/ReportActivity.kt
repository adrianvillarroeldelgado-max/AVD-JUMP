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
import java.util.Locale

/** Informe del atleta: resumen de todas las pruebas y sugerencias de entrenamiento priorizadas. */
class ReportActivity : BaseActivity() {

    private lateinit var spinner: Spinner
    private lateinit var summary: TextView
    private lateinit var recBox: LinearLayout
    private var names: List<String> = emptyList()
    private var lastReport = ""

    private fun fmt(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vbox(14).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(text("Informe y recomendaciones", 28f, Col.INK, true))

        val pA = panel()
        pA.addView(caption("Atleta"))
        spinner = Spinner(this)
        pA.addView(spinner, lp())
        summary = text("", 13f, Col.INK).apply { typeface = android.graphics.Typeface.MONOSPACE }
        pA.addView(summary)
        root.addView(pA, lp())

        root.addView(caption("Sugerencias de entrenamiento"))
        recBox = vbox(10)
        root.addView(recBox, lp())
        root.addView(text(
            "Orientativo. Umbrales: asimetría (Bishop et al. 2018), perfil F-V (Samozino 2012; Jiménez-Reyes 2017), RSI en drop jump. " +
            "Ajusta según deporte, momento de la temporada e historial de lesiones.", 11f, Col.MUTED))
        root.addView(button("Historial de evaluaciones") {
            names.getOrNull(spinner.selectedItemPosition)?.let { n ->
                startActivity(Intent(this, ClientHistoryActivity::class.java).putExtra(MainActivity.EX_ATHLETE, n))
            }
        }, lp())
        root.addView(button("Compartir informe", true) { share() }, lp())

        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })

        names = Store.athletes(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (names.isEmpty()) listOf("Sin atletas") else names)
        val idx = names.indexOf(intent.getStringExtra(MainActivity.EX_ATHLETE))
        if (idx >= 0) spinner.setSelection(idx)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = render()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        render()
    }

    private fun render() {
        val name = names.getOrNull(spinner.selectedItemPosition)
        recBox.removeAllViews()
        if (name == null) {
            summary.text = "Todavía no hay saltos guardados."
            return
        }
        val a = Store.athlete(this, name)
        val js = Store.ofAthlete(this, name)
        fun cm(d: Double?) = d?.let { fmt("%.1f cm", it) } ?: "—"
        val sb = StringBuilder()
        Clients.byName(this, name)?.let { c ->
            sb.append(c.summary()).append('\n')
            Clients.groupName(this, c.groupId)?.let { sb.append("Grupo: ").append(it).append('\n') }
            c.dominantLeg?.let { sb.append("Pierna dominante: ").append(it).append('\n') }
            sb.append('\n')
        }
        sb.append(fmt("%-20s %s\n", "CMJ", cm(Analytics.best(js, TestType.CMJ))))
        sb.append(fmt("%-20s %s\n", "CMJ con brazos", cm(Analytics.best(js, TestType.CMJA))))
        sb.append(fmt("%-20s %s\n", "SJ sin carga", cm(Analytics.bestSjUnloaded(js))))
        val dj = js.filter { it.test == TestType.DJ && it.rsi != null }.maxByOrNull { it.rsi!! }
        sb.append(fmt("%-20s %s\n", "Drop Jump (RSI)", dj?.let { fmt("%.2f · %.1f cm · %.0f ms", it.rsi, it.heightCm, it.contactMs) } ?: "—"))
        val asy = Analytics.asymmetry(js)
        sb.append(fmt("%-20s %s\n", "Asimetría", asy?.let { fmt("%.1f %% (I %.1f · D %.1f)", it.third, it.first, it.second) } ?: "—"))
        val rj = js.filter { it.test == TestType.RJ }.maxByOrNull { it.date }
        sb.append(fmt("%-20s %s\n", "Saltos repetidos", rj?.let {
            fmt("%d saltos · %.1f cm · RSI %s", it.reps.size, it.heightCm, it.rsi?.let { r -> fmt("%.2f", r) } ?: "—")
        } ?: "—"))
        val fv = Analytics.latestProfile(js, a)
        sb.append(fmt("%-20s %s", "Perfil F-V", fv?.let { fmt("%s (%s, %.0f %%)", it.second.category.label, it.first.short, it.second.ratio) } ?: "—"))
        summary.text = sb.toString()

        val recs = Analytics.recommendations(js, a)
        val report = StringBuilder("Informe AVD jump — $name\n\n").append(sb).append("\n\nSugerencias:\n")
        for (r in recs) {
            val card = panel()
            val chip = text(r.level.label.uppercase(), 11f, r.level.color, true).apply { letterSpacing = 0.08f }
            card.addView(chip)
            card.addView(text(r.title, 17f, Col.INK, true))
            card.addView(text(r.body, 14f, Col.INK))
            recBox.addView(card, lp())
            report.append("• [${r.level.label}] ${r.title}\n  ${r.body}\n")
        }
        lastReport = report.toString()
    }

    private fun share() {
        if (lastReport.isEmpty()) return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Informe AVD jump")
            putExtra(Intent.EXTRA_TEXT, lastReport)
        }
        startActivity(Intent.createChooser(i, "Compartir informe"))
    }

    override fun onResume() {
        super.onResume()
        if (::spinner.isInitialized && names.isNotEmpty()) render()
    }
}
