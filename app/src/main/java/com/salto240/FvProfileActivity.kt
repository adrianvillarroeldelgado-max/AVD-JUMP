package com.salto240

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Perfil fuerza-velocidad vertical con el método simple de Samozino:
 * SJ con varias cargas → recta F-V → F0, V0, Pmax, pendiente real vs óptima y recomendación.
 */
class FvProfileActivity : BaseActivity() {

    private lateinit var name: EditText
    private lateinit var mass: EditText
    private lateinit var leg: EditText
    private lateinit var crouch: EditText
    private lateinit var hpoText: TextView
    private lateinit var daySpinner: Spinner
    private lateinit var pointsText: TextView
    private lateinit var loadsText: TextView
    private lateinit var chart: FvChartView
    private lateinit var resultText: TextView
    private lateinit var categoryText: TextView
    private lateinit var trainingText: TextView
    private lateinit var planBar: LinearLayout
    private lateinit var planLegend: TextView
    private var days: List<String> = emptyList()
    private var protocol = TestType.SJ
    private lateinit var protocolHelp: TextView

    private fun fmt(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ath = Store.athlete(this, intent.getStringExtra(MainActivity.EX_ATHLETE) ?: "")

        val root = vbox(14).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(text("Perfil fuerza-velocidad", 28f, Col.INK, true))
        root.addView(text("Saltos con cargas crecientes el mismo día, medidos en cámara lenta. Método de Samozino (2008), como My Jump Lab.", 13f, Col.MUTED))
        protocol = TestType.of(getSharedPreferences("salto240", MODE_PRIVATE).getString("fvProtocol", TestType.SJ.name))
            .takeIf { it in Analytics.FV_TESTS } ?: TestType.SJ

        // Datos del atleta
        val pA = panel()
        pA.addView(caption("Atleta y medidas"))
        name = input("Nombre").apply { setText(ath.name); isEnabled = false }
        mass = input("Masa corporal (kg)", true).apply { ath.massKg?.let { setText(fmt("%.1f", it)) } }
        leg = input("Largo de pierna (cm)", true).apply { ath.legLengthCm?.let { setText(fmt("%.1f", it)) } }
        crouch = input("Altura en sentadilla 90° (cm)", true).apply { ath.crouchCm?.let { setText(fmt("%.1f", it)) } }
        pA.addView(name, lp()); pA.addView(mass, lp()); pA.addView(leg, lp()); pA.addView(crouch, lp())
        pA.addView(text(
            "Largo de pierna: del trocánter mayor a la punta del pie, tumbado con el tobillo extendido.\n" +
            "Altura en sentadilla: del trocánter mayor al suelo en la posición de salida a 90° de rodilla.", 12f, Col.MUTED))
        hpoText = text("", 14f, Col.INK)
        pA.addView(hpoText)
        pA.addView(button("Guardar medidas") { saveAthlete(); reload() }, lp())
        root.addView(pA, lp())

        // Sesión
        val pS = panel()
        pS.addView(caption("Protocolo"))
        val protoSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@FvProfileActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Squat jump con carga", "CMJ con carga"))
            setSelection(Analytics.FV_TESTS.indexOf(protocol))
        }
        pS.addView(protoSpinner, lp())
        protocolHelp = text("", 12f, Col.MUTED)
        pS.addView(protocolHelp)
        protoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val t = Analytics.FV_TESTS[position]
                if (t != protocol) {
                    protocol = t
                    getSharedPreferences("salto240", MODE_PRIVATE).edit().putString("fvProtocol", t.name).apply()
                    reload()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        pS.addView(caption("Sesión de saltos"))
        daySpinner = Spinner(this)
        pS.addView(daySpinner, lp())
        pointsText = text("", 13f, Col.INK).apply { typeface = android.graphics.Typeface.MONOSPACE }
        pS.addView(pointsText)
        loadsText = text("", 12f, Col.MUTED)
        pS.addView(loadsText)
        val row = hbox(8)
        row.addView(button("Medir batería (4 cargas)", true) { openBattery() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Agregar a mano") { addManual() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pS.addView(row, lp())
        root.addView(pS, lp())

        // Resultado
        val pR = panel()
        pR.addView(caption("Perfil"))
        chart = FvChartView(this)
        pR.addView(chart, lp(h = dp(260)))
        pR.addView(text("— real   - - óptimo con la misma potencia máxima", 12f, Col.MUTED))
        categoryText = text("", 22f, Col.INK, true)
        pR.addView(categoryText)
        resultText = text("", 13f, Col.INK).apply { typeface = android.graphics.Typeface.MONOSPACE }
        pR.addView(resultText)
        pR.addView(caption("Sugerencia de entrenamiento"))
        planBar = hbox().apply { minimumHeight = dp(14) }
        pR.addView(planBar, lp(h = dp(14)))
        planLegend = text("", 12f, Col.MUTED)
        pR.addView(planLegend)
        trainingText = text("", 14f, Col.INK)
        pR.addView(trainingText)
        root.addView(pR, lp())

        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })

        daySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = renderProfile()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun athleteName() = name.text.toString().trim().ifEmpty { "Sin nombre" }

    private fun currentAthlete(): Athlete =
        Athlete(athleteName(), mass.number(), leg.number(), crouch.number())

    private fun saveAthlete() {
        Store.saveAthlete(this, currentAthlete())
        Toast.makeText(this, "Medidas guardadas", Toast.LENGTH_SHORT).show()
    }

    private fun reload() {
        val prevDay = daySpinner.selectedItem as? String
        days = Analytics.fvDays(Store.all(this), athleteName(), protocol)
        daySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (days.isEmpty()) listOf("Sin saltos ${protocol.short} con carga todavía") else days)
        val idx = days.indexOf(prevDay)
        if (idx >= 0) daySpinner.setSelection(idx)
        renderProfile()
    }

    private fun renderProfile() {
        val a = currentAthlete()
        val hpo = a.hpo
        protocolHelp.text = if (protocol == TestType.SJ)
            "SJ: parte quieto desde 90° de rodilla (2–3 s), sin contramovimiento. Manos en la cadera o en la barra."
        else
            "CMJ: contramovimiento libre hasta unos 90° de rodilla, sin brazos. La altura en sentadilla se mide en el punto más bajo." 
        hpoText.text = if (hpo != null) fmt("Recorrido de empuje (hpo): %.1f cm", hpo * 100)
            else "Completa largo de pierna y altura en sentadilla para calcular el recorrido de empuje."
        loadsText.text = a.massKg?.let { m ->
            "Cargas sugeridas: " + Analytics.suggestedLoads(m).joinToString(" · ") { fmt("%.1f kg", it) } +
            " (0–100 % de la masa). 2–3 saltos por carga; se usa el mejor."
        } ?: "Indica la masa corporal para sugerir cargas."

        val day = days.getOrNull(daySpinner.selectedItemPosition)
        val all = Store.all(this)
        val points = Analytics.fvPoints(all, a, day, protocol)
        if (day == null) {
            pointsText.text = "Aún no hay saltos ${protocol.short} con carga de ${a.name}."
        } else if (points.isEmpty()) {
            val raw = all.filter { it.test == protocol && it.athlete == a.name && it.day == day }
            pointsText.text = raw.joinToString("\n") { fmt("%6.1f kg  %5.1f cm", it.loadKg ?: 0.0, it.heightCm) } +
                "\n\nFaltan masa o medidas de pierna para calcular fuerza y velocidad."
        } else {
            val sb = StringBuilder(fmt("%8s %7s %8s %7s\n", "carga", "altura", "F N/kg", "V m/s"))
            points.forEach { sb.append(fmt("%5.1f kg %5.1f cm %8.1f %7.2f\n", it.loadKg, it.heightCm, it.force, it.velocity)) }
            pointsText.text = sb.toString().trimEnd()
        }

        val p = if (hpo != null) Analytics.fvProfile(points, hpo) else null
        chart.profile = p
        if (p == null) {
            categoryText.text = "Sin perfil"
            categoryText.setTextColor(Col.MUTED)
            resultText.text = "Necesitas al menos 2 cargas distintas (mejor 3–5), masa y medidas de pierna."
            trainingText.text = "—"
            planBar.removeAllViews(); planLegend.text = ""
            return
        }
        categoryText.text = p.category.label + "\n" + fmt("%.0f %% del óptimo (rango %s)", p.ratio, p.category.range)
        categoryText.setTextColor(p.category.color)
        val sb = StringBuilder()
        sb.append(fmt("%-22s %.1f N/kg\n", "F0 (fuerza máx.)", p.f0))
        sb.append(fmt("%-22s %.2f m/s\n", "V0 (velocidad máx.)", p.v0))
        sb.append(fmt("%-22s %.1f W/kg\n", "Pmax", p.pmax))
        sb.append(fmt("%-22s %.2f\n", "Pendiente real", p.sfv))
        sb.append(fmt("%-22s %.2f\n", "Pendiente óptima", p.sfvOpt))
        sb.append(fmt("%-22s %.0f %% del óptimo\n", "Perfil", p.ratio))
        sb.append(fmt("%-22s %.1f %%\n", "Desequilibrio F-V", p.imbalance))
        sb.append(fmt("%-22s %.1f → %.1f cm\n", "Salto teórico", p.hNow, p.hOpt))
        sb.append(fmt("%-22s %.3f", "Ajuste (R²)", p.r2))
        if (p.r2 < 0.95) sb.append("\nR² bajo: repite cargas con saltos dudosos.")
        if (p.points.size < 3) sb.append("\nCon 2 cargas el perfil es orientativo; usa 3–5.")
        resultText.text = sb.toString()
        // Barra del plan semanal: 6 ejercicios repartidos por tipo, de fuerza (oscuro) a velocidad (claro).
        planBar.removeAllViews()
        val shades = intArrayOf(0xFF152033.toInt(), 0xFF1E4F8C.toInt(), 0xFF4F7FBF.toInt(), 0xFF8DB0DC.toInt(), 0xFFC9D9EE.toInt())
        p.category.plan.forEachIndexed { i, n ->
            if (n > 0) planBar.addView(android.view.View(this).apply { setBackgroundColor(shades[i]) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, n.toFloat()))
        }
        planLegend.text = "Ejercicios por semana: " + p.category.planLine
        trainingText.text = p.category.training +
            "\n\nPrograma de Jiménez-Reyes, Samozino, Brughelli y Morin (2017), el estudio en que se basan las recomendaciones de My Jump 2 y My Jump Lab." +
            " Ajusta las cargas al nivel del atleta y al momento de la temporada."
    }

    private fun numberDialog(title: String, hints: List<String>, defaults: List<String>, onOk: (List<Double?>) -> Unit) {
        val box = vbox(8).apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val fields = hints.mapIndexed { i, h ->
            input(h, true).apply { setText(defaults.getOrElse(i) { "" }) }.also { box.addView(it, lp()) }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Aceptar") { _, _ -> onOk(fields.map { it.number() }) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openBattery() {
        saveAthlete()
        getSharedPreferences("salto240", MODE_PRIVATE).edit().putString("fvProtocol", protocol.name).apply()
        startActivity(android.content.Intent(this, BatteryActivity::class.java).putExtra(MainActivity.EX_ATHLETE, athleteName()))
    }

    @Suppress("unused")
    private fun askLoad() {
        saveAthlete()
        numberDialog("Carga adicional (kg)", listOf("kg (0 = sin carga)"), listOf("0")) { v ->
            val load = v[0] ?: 0.0
            val a = currentAthlete()
            startActivity(MainActivity.captureIntent(this, protocol, a.name, a.massKg, null, load))
        }
    }

    private fun addManual() {
        saveAthlete()
        numberDialog("Agregar ${protocol.short} a mano", listOf("Carga (kg)", "Altura (cm)"), listOf("0", "")) { v ->
            val load = v[0] ?: 0.0
            val h = v[1]
            if (h == null || h <= 0) {
                Toast.makeText(this, "Escribe la altura del salto.", Toast.LENGTH_SHORT).show()
                return@numberDialog
            }
            val tv = Math.sqrt(8 * h / 100 / Calc.G)
            val day = days.getOrNull(daySpinner.selectedItemPosition)
            // Si se está viendo una sesión anterior, el salto se agrega a esa sesión.
            val date = if (day != null && day != Store.today().take(10)) "$day 12:00" else Store.today()
            Store.add(this, Jump(
                id = System.currentTimeMillis(), date = date, athlete = athleteName(), test = protocol,
                flightMs = tv * 1000, heightCm = h, contactMs = null, rsi = null, boxCm = null,
                massKg = mass.number(), powerW = null, fps = 0, notes = "manual", loadKg = load
            ))
            reload()
        }
    }
}
