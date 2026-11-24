package com.salto240

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max

/** Registro de sesiones (individual y de grupo). */
object LoadUi {

    private fun pickDay(act: Activity, btn: android.widget.Button, set: (LocalDate) -> Unit) {
        val d0 = LocalDate.now()
        DatePickerDialog(act, { _, y, m, d -> val ld = LocalDate.of(y, m + 1, d); set(ld); btn.text = Evaluations.pretty(ld.toString()) },
            d0.year, d0.monthValue - 1, d0.dayOfMonth).show()
    }

    /** Una sesión de un atleta. */
    fun addSession(act: Activity, athlete: String, done: () -> Unit = {}) {
        var day = LocalDate.now()
        val box = act.vbox(8).apply { setPadding(act.dp(20), act.dp(8), act.dp(20), 0) }
        val dayBtn = act.button(Evaluations.pretty(day.toString())) {}
        dayBtn.setOnClickListener { pickDay(act, dayBtn) { day = it } }
        box.addView(act.caption("Día"))
        box.addView(dayBtn, lp())
        box.addView(act.caption("Esfuerzo percibido de la sesión (0–10)"))
        val rpe = Spinner(act).apply { adapter = TrAdapter(act, android.R.layout.simple_spinner_dropdown_item, Load.RPE); setSelection(5) }
        box.addView(rpe, lp())
        val min = act.input("Minutos de la sesión", numeric = true)
        box.addView(min, lp())
        val type = Spinner(act).apply { adapter = TrAdapter(act, android.R.layout.simple_spinner_dropdown_item, Load.TYPES) }
        box.addView(type, lp())
        box.addView(act.text("Pregunta unos 30 minutos después de terminar: «¿Qué tan dura fue la sesión?»", 12f, Col.MUTED))
        Dlg(act).setTitle("Sesión · $athlete").setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("Guardar") { _, _ ->
                val m = min.number()?.toInt()
                if (m == null || m <= 0) { Tst.makeText(act, "Escribe los minutos.", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                Load.add(act, Load.Session(System.currentTimeMillis(), day.toString(), athlete, rpe.selectedItemPosition, m, Load.TYPES[type.selectedItemPosition]))
                done()
            }.setNegativeButton("Cancelar", null).show()
    }

    /** Sesión de todo un grupo: mismos minutos, esfuerzo de cada atleta. */
    fun addGroup(act: Activity, done: () -> Unit = {}) {
        val groups = Clients.groups(act)
        val names = listOf("Todos los clientes") + groups.map { it.name }
        Dlg(act).setTitle("¿Qué grupo?").setItems(names.toTypedArray<CharSequence>()) { _, w ->
            val cl = if (w == 0) Clients.all(act) else Clients.inGroup(act, groups[w - 1].id)
            if (cl.isEmpty()) { Tst.makeText(act, "El grupo no tiene clientes.", Toast.LENGTH_SHORT).show(); return@setItems }
            groupForm(act, cl.map { it.name }, done)
        }.show()
    }

    private fun groupForm(act: Activity, athletes: List<String>, done: () -> Unit) {
        var day = LocalDate.now()
        val box = act.vbox(8).apply { setPadding(act.dp(20), act.dp(8), act.dp(20), 0) }
        val dayBtn = act.button(Evaluations.pretty(day.toString())) {}
        dayBtn.setOnClickListener { pickDay(act, dayBtn) { day = it } }
        box.addView(dayBtn, lp())
        val min = act.input("Minutos de la sesión", numeric = true)
        box.addView(min, lp())
        val type = Spinner(act).apply { adapter = TrAdapter(act, android.R.layout.simple_spinner_dropdown_item, Load.TYPES) }
        box.addView(type, lp())
        box.addView(act.text("Esfuerzo de cada atleta (0–10). «—» = no entrenó.", 12f, Col.MUTED))
        val opts = listOf("—") + (0..10).map { "$it" }
        val spins = athletes.map { n ->
            val row = act.hbox(8)
            row.addView(act.text(n, 14f, Col.INK), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val sp = Spinner(act).apply { adapter = TrAdapter(act, android.R.layout.simple_spinner_dropdown_item, opts) }
            row.addView(sp, lp(act.dp(90)))
            box.addView(row, lp())
            n to sp
        }
        Dlg(act).setTitle("Sesión del grupo").setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("Guardar") { _, _ ->
                val m = min.number()?.toInt()
                if (m == null || m <= 0) { Tst.makeText(act, "Escribe los minutos.", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val base = System.currentTimeMillis()
                val l = spins.mapIndexedNotNull { i, (n, sp) ->
                    val p = sp.selectedItemPosition
                    if (p == 0) null else Load.Session(base + i, day.toString(), n, p - 1, m, Load.TYPES[type.selectedItemPosition])
                }
                Load.addAll(act, l)
                Tst.makeText(act, "${l.size} sesiones guardadas.", Toast.LENGTH_SHORT).show()
                done()
            }.setNegativeButton("Cancelar", null).show()
    }
}

/**
 * Seguimiento de 6 semanas: barras de carga diaria y línea de ACWR (arriba), mejor CMJ del día con su
 * banda habitual (medio) y bienestar total (abajo).
 */
class MonitorChartView(ctx: Context) : View(ctx) {
    var days: List<LocalDate> = emptyList()
    var load: List<Double> = emptyList()
    var acwr: List<Double?> = emptyList()
    var cmj: Map<LocalDate, Double> = emptyMap()
    var cmjMean: Double? = null
    var cmjSd: Double? = null
    var wellness: Map<LocalDate, Int> = emptyMap()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (days.size < 2) return
        val d = resources.displayMetrics.density
        val l = 8f * d; val r = width - 40f * d
        val n = days.size
        fun X(i: Int) = l + (i + 0.5f) * (r - l) / n
        val bw = (r - l) / n * 0.7f
        val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.MUTED; textSize = 10f * d }
        val h = height.toFloat()
        val bands = listOf(0f to h * 0.42f, h * 0.46f to h * 0.74f, h * 0.78f to h - 16f * d)
        // 1) carga y ACWR
        val (t1, b1) = bands[0]
        val lMax = max(1.0, load.maxOrNull() ?: 1.0)
        p.color = 0x553C7DD9; p.style = Paint.Style.FILL
        load.forEachIndexed { i, v -> if (v > 0) c.drawRect(X(i) - bw / 2, (b1 - v / lMax * (b1 - t1 - 14 * d)).toFloat(), X(i) + bw / 2, b1, p) }
        fun Ya(v: Double) = (b1 - (v.coerceIn(0.0, 2.0) / 2.0) * (b1 - t1 - 14 * d)).toFloat()
        p.color = 0x2217A34A; c.drawRect(l, Ya(1.3), r, Ya(0.8), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = Col.ACCENT
        val path = Path(); var started = false
        acwr.forEachIndexed { i, v -> if (v != null) { if (!started) { path.moveTo(X(i), Ya(v)); started = true } else path.lineTo(X(i), Ya(v)) } }
        c.drawPath(path, p)
        c.drawText(Lang.tr(context, "Carga y ACWR"), l, t1 + 10 * d, lab)
        c.drawText("1,5", r + 4 * d, Ya(1.5) + 4 * d, lab); c.drawText("1,0", r + 4 * d, Ya(1.0) + 4 * d, lab)
        // 2) CMJ
        val (t2, b2) = bands[1]
        c.drawText(Lang.tr(context, "CMJ (cm)"), l, t2 + 10 * d, lab)
        if (cmj.isNotEmpty()) {
            val vals = cmj.values + listOfNotNull(cmjMean)
            val lo = vals.minOrNull()!! - 2; val hi = vals.maxOrNull()!! + 2
            fun Yc(v: Double) = (b2 - (v - lo) / max(1e-6, hi - lo) * (b2 - t2 - 14 * d)).toFloat()
            val m = cmjMean; val s = cmjSd
            if (m != null && s != null) { p.style = Paint.Style.FILL; p.color = 0x221E4F8C; c.drawRect(l, Yc(m + s), r, Yc(m - s), p) }
            p.style = Paint.Style.FILL; p.color = Col.TRACK
            days.forEachIndexed { i, day -> cmj[day]?.let { c.drawCircle(X(i), Yc(it), 4f * d, p) } }
            c.drawText(String.format(Locale.getDefault(), "%.0f", hi), r + 4 * d, Yc(hi) + 10 * d, lab)
            c.drawText(String.format(Locale.getDefault(), "%.0f", lo), r + 4 * d, Yc(lo), lab)
        }
        // 3) bienestar
        val (t3, b3) = bands[2]
        c.drawText(Lang.tr(context, "Bienestar (4–20)"), l, t3 + 10 * d, lab)
        fun Yw(v: Int) = (b3 - (v - 4) / 16f * (b3 - t3 - 14 * d))
        p.style = Paint.Style.FILL
        days.forEachIndexed { i, day -> wellness[day]?.let { v ->
            p.color = when { v <= 9 -> Col.ACCENT; v <= 13 -> Col.WARN; else -> Col.OK }
            c.drawCircle(X(i), Yw(v), 4f * d, p)
        } }
        // fechas
        val fmt = { ld: LocalDate -> "${ld.dayOfMonth}/${ld.monthValue}" }
        for (i in listOf(0, n / 2, n - 1)) c.drawText(fmt(days[i]), X(i) - 10 * d, h - 2 * d, lab)
    }
}

/** Panel del atleta: carga (ACWR, monotonía), CMJ y bienestar juntos, y registro de sesiones. */
class AthletePanelActivity : BaseActivity() {
    private lateinit var athlete: String
    private lateinit var summary: LinearLayout
    private lateinit var chart: MonitorChartView
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        athlete = intent.getStringExtra(MainActivity.EX_ATHLETE) ?: "Sin nombre"
        val root = vbox(14).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(text("Panel del atleta", 26f, Col.INK, true))
        root.addView(text(athlete, 17f, Col.ACCENT, true))
        summary = panel()
        root.addView(summary, lp())
        val pc = panel()
        pc.addView(caption("Últimas 6 semanas"))
        chart = MonitorChartView(this)
        pc.addView(chart, lp(h = dp(300)))
        pc.addView(text("Barras: carga diaria (esfuerzo × minutos). Línea naranja: relación aguda:crónica; franja verde 0,8–1,3. " +
            "Puntos azules: mejor CMJ del día; franja: su media ± 1 DE. Abajo: bienestar.", 11f, Col.MUTED))
        root.addView(pc, lp())
        val row = hbox(8)
        row.addView(button("+ Sesión", true) { LoadUi.addSession(this, athlete) { render() } }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Bienestar de hoy") { Wellness.ask(this, athlete) { render() } }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row, lp())
        val pl = panel()
        pl.addView(caption("Sesiones (mantén pulsado para borrar)"))
        listBox = vbox(4)
        pl.addView(listBox, lp())
        root.addView(pl, lp())
        root.addView(text("Carga por sesión (Foster): esfuerzo percibido 0–10 × minutos. Relación aguda:crónica con medias exponenciales " +
            "de 7 y 28 días; necesita 3 semanas de registros. Por encima de 1,5 la carga subió de golpe. Monotonía = media / DE de la carga " +
            "diaria de la semana; más de 2 indica entrenamientos muy parecidos todos los días.", 12f, Col.MUTED))
        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })
    }

    override fun onResume() { super.onResume(); render() }

    private fun f(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    private fun render() {
        val today = LocalDate.now()
        val ses = Load.of(this, athlete)
        val st = Load.status(ses, today)
        val js = Store.ofAthlete(this, athlete)
        val rd = Readiness.of(js)
        val wl = Wellness.status(this, athlete, today.toString())

        summary.removeAllViews()
        summary.addView(caption("Hoy"))
        if (st != null) {
            summary.addView(text(f("Carga · semana %d UA", st.week) + (st.acwr?.let { f(" · ACWR %.2f", it) } ?: "") +
                (st.monotony?.let { f(" · monotonía %.1f", it) } ?: ""), 15f, st.light.takeIf { it != Readiness.Light.NONE }?.color ?: Col.INK, true))
            summary.addView(text(st.text, 13f, Col.MUTED))
        } else summary.addView(text("Sin sesiones registradas.", 14f, Col.MUTED))
        if (rd != null) summary.addView(text(f("%s %s: %.1f cm · ", rd.test.short, Evaluations.pretty(rd.day), rd.value) + rd.light.label, 15f, rd.light.color, true))
        if (wl != null) summary.addView(text("Bienestar: " + wl.entry.line() + " · " + wl.light.label, 14f, wl.light.color))
        val worst = Wellness.combine(Wellness.combine(rd?.light, wl?.light), st?.light?.takeIf { it == Readiness.Light.RED })
        worst?.let { summary.addView(text(it.advice, 13f, Col.MUTED)) }

        // Gráfico
        val from = today.minusDays(41)
        val firstSes = ses.minOfOrNull { LocalDate.parse(it.day) }
        val calcFrom = listOfNotNull(firstSes, from).minOrNull()!!
        val all = Load.daily(ses, calcFrom, today)
        val e = Load.ewma(all.map { it.second })
        val idx0 = all.indexOfFirst { !it.first.isBefore(from) }
        val days = all.drop(idx0).map { it.first }
        chart.days = days
        chart.load = all.drop(idx0).map { it.second }
        chart.acwr = e.indices.drop(idx0).map { i -> if (firstSes != null && i >= 20 && e[i].second > 0 && !all[i].first.isBefore(firstSes)) e[i].first / e[i].second else null }
        val cmjT = rd?.test ?: TestType.CMJ
        chart.cmj = js.filter { it.test == cmjT && (it.loadKg ?: 0.0) < 0.5 }.groupBy { LocalDate.parse(it.day) }
            .filterKeys { !it.isBefore(from) }.mapValues { (_, l) -> l.maxOf { it.heightCm } }
        chart.cmjMean = rd?.mean; chart.cmjSd = rd?.sd
        chart.wellness = Wellness.entries(this, athlete).mapNotNull { w -> try { LocalDate.parse(w.day) to w.total } catch (_: Exception) { null } }
            .filter { !it.first.isBefore(from) }.toMap()
        chart.invalidate()

        listBox.removeAllViews()
        if (ses.isEmpty()) listBox.addView(text("Todavía no hay sesiones.", 13f, Col.MUTED))
        for (s in ses.sortedByDescending { it.day }.take(40)) {
            val t = text(f("%s · %s · esfuerzo %d × %d min = %d UA", Evaluations.pretty(s.day), s.type, s.rpe, s.minutes, s.ua), 13f, Col.INK)
            t.setPadding(0, dp(4), 0, dp(4))
            t.setOnLongClickListener {
                Dlg(this).setTitle("¿Borrar esta sesión?").setPositiveButton("Borrar") { _, _ -> Load.delete(this, s.id); render() }
                    .setNegativeButton("Cancelar", null).show(); true
            }
            listBox.addView(t, lp())
        }
    }
}
