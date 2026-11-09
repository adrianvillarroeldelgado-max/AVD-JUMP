package com.salto240

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Validación frente a un instrumento de referencia (plataforma de fuerza, My Jump Lab, otra app o sistema).
 *
 * Cada par guarda la altura de AVD jump SIN corregir y la de la referencia para el mismo salto.
 * Con los pares se calcula el acuerdo (Bland-Altman), la correlación, el ICC(A,1) y una recta de calibración.
 * La calibración es opcional, se guarda por método (240 fps, IA, video, acelerómetro) y solo vale para el
 * mismo teléfono y la misma forma de grabar con que se obtuvieron los pares.
 */
object Validation {
    private const val PREFS = "salto240"
    private const val KEY = "validation_pairs"
    private const val KEY_CAL = "calibration"

    val METHODS = listOf("240", "ia", "video", "acc")
    fun methodLabel(m: String?): String = when (m) {
        "240" -> "240 fps"; "ia" -> "IA en vivo"; "video" -> "Video de la galería"; "acc" -> "Acelerómetro"; else -> "Sin método"
    }
    val REFERENCES = listOf("Plataforma de fuerza", "My Jump Lab", "Otra app o sistema")

    data class Pair(
        val id: Long, val date: String, val method: String, val test: String, val athlete: String,
        val avd: Double, val ref: Double, val refDevice: String, val jumpId: Long? = null
    ) {
        val diff get() = avd - ref
        val mean get() = (avd + ref) / 2
        fun toJson(): JSONObject = JSONObject().put("id", id).put("date", date).put("method", method).put("test", test)
            .put("athlete", athlete).put("avd", avd).put("ref", ref).put("refDevice", refDevice).apply { jumpId?.let { put("jumpId", it) } }
        companion object {
            fun fromJson(o: JSONObject) = Pair(o.optLong("id"), o.optString("date"), o.optString("method", "240"), o.optString("test"),
                o.optString("athlete"), o.optDouble("avd"), o.optDouble("ref"), o.optString("refDevice"),
                if (o.has("jumpId")) o.optLong("jumpId") else null)
        }
    }

    fun all(ctx: Context): List<Pair> {
        val arr = try { JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> Pair.fromJson(o) } }
            .filter { !it.avd.isNaN() && !it.ref.isNaN() }
    }

    private fun save(ctx: Context, l: List<Pair>) {
        val arr = JSONArray(); l.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, p: Pair) = save(ctx, all(ctx) + p)
    fun delete(ctx: Context, id: Long) = save(ctx, all(ctx).filter { it.id != id })

    /** Altura sin corregir de un salto guardado (si se aplicó calibración, la original). */
    fun rawHeight(j: Jump): Double = j.extra["hRaw"] ?: j.heightCm

    // ---------------------------------------------------------------- estadística

    data class Stats(
        val n: Int, val bias: Double, val sd: Double, val loaLo: Double, val loaHi: Double,
        val biasPct: Double, val r: Double, val icc: Double, val slope: Double, val intercept: Double,
        val see: Double, val te: Double, val propSlope: Double, val propR: Double, val meanRef: Double
    ) {
        fun loaHalf() = 1.96 * sd
    }

    fun stats(ps: List<Pair>): Stats? {
        val n = ps.size
        if (n < 3) return null
        val x = ps.map { it.avd }; val y = ps.map { it.ref }
        val d = ps.map { it.diff }; val m = ps.map { it.mean }
        val bias = d.average()
        val sd = sqrt(d.sumOf { (it - bias) * (it - bias) } / (n - 1))
        val mx = x.average(); val my = y.average()
        val sxx = x.sumOf { (it - mx) * (it - mx) }; val syy = y.sumOf { (it - my) * (it - my) }
        val sxy = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
        val r = if (sxx > 0 && syy > 0) sxy / sqrt(sxx * syy) else Double.NaN
        val slope = if (sxx > 0) sxy / sxx else 1.0
        val intercept = my - slope * mx
        val see = if (n > 2) sqrt(y.indices.sumOf { val e = y[it] - (intercept + slope * x[it]); e * e } / (n - 2)) else Double.NaN
        // ICC(A,1): acuerdo absoluto, dos «evaluadores» (AVD y referencia), modelo de dos vías.
        val g = (mx + my) / 2
        val k = 2.0
        val ssr = k * m.sumOf { (it - g) * (it - g) }
        val ssc = n * ((mx - g) * (mx - g) + (my - g) * (my - g))
        val sst = x.sumOf { (it - g) * (it - g) } + y.sumOf { (it - g) * (it - g) }
        val sse = sst - ssr - ssc
        val msr = ssr / (n - 1); val msc = ssc / (k - 1); val mse = sse / ((n - 1) * (k - 1))
        val icc = (msr - mse) / (msr + (k - 1) * mse + k / n * (msc - mse))
        // Sesgo proporcional: diferencia frente a la media.
        val mm = m.average()
        val smm = m.sumOf { (it - mm) * (it - mm) }
        val smd = m.indices.sumOf { (m[it] - mm) * (d[it] - bias) }
        val sdd = d.sumOf { (it - bias) * (it - bias) }
        val propSlope = if (smm > 0) smd / smm else 0.0
        val propR = if (smm > 0 && sdd > 0) smd / sqrt(smm * sdd) else 0.0
        return Stats(n, bias, sd, bias - 1.96 * sd, bias + 1.96 * sd, bias / my * 100, r, icc, slope, intercept, see,
            sd / sqrt(2.0), propSlope, propR, my)
    }

    fun iccLabel(v: Double) = when { v.isNaN() -> ""; v >= 0.9 -> "excelente"; v >= 0.75 -> "buena"; v >= 0.5 -> "moderada"; else -> "pobre" }

    fun summary(s: Stats): String = String.format(Locale.getDefault(),
        "Pares: %d\n" +
        "Sesgo (AVD − referencia): %+.2f cm (%+.1f %%)\n" +
        "DE de las diferencias: %.2f cm\n" +
        "Límites de acuerdo 95 %%: %+.2f a %+.2f cm (±%.2f)\n" +
        "Error típico: %.2f cm\n" +
        "Correlación r: %.3f\n" +
        "ICC(A,1): %.3f (%s)\n" +
        "Regresión: referencia = %.3f × AVD %+.2f cm (error %.2f cm)\n" +
        "Sesgo proporcional: pendiente %+.3f (r = %.2f)%s",
        s.n, s.bias, s.biasPct, s.sd, s.loaLo, s.loaHi, s.loaHalf(), s.te, s.r, s.icc, iccLabel(s.icc),
        s.slope, s.intercept, s.see, s.propSlope, s.propR,
        if (abs(s.propR) >= 0.4 && s.n >= 8) " · la diferencia cambia con la altura: conviene la corrección lineal" else "")

    // ---------------------------------------------------------------- calibración

    data class Cal(val a: Double, val b: Double, val n: Int, val date: String, val linear: Boolean) {
        fun apply(h: Double) = a + b * h
        fun describe(): String = if (linear) String.format(Locale.getDefault(), "altura = %.3f × medida %+.2f cm (%d pares, %s)", b, a, n, date.take(10))
            else String.format(Locale.getDefault(), "altura = medida %+.2f cm (%d pares, %s)", a, n, date.take(10))
    }

    fun calibration(ctx: Context, method: String?): Cal? {
        if (method == null) return null
        val o = try { JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CAL, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val c = o.optJSONObject(method) ?: return null
        return Cal(c.optDouble("a", 0.0), c.optDouble("b", 1.0), c.optInt("n"), c.optString("date"), c.optBoolean("linear"))
    }

    fun setCalibration(ctx: Context, method: String, cal: Cal?) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = try { JSONObject(sp.getString(KEY_CAL, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        if (cal == null) o.remove(method) else o.put(method, JSONObject().put("a", cal.a).put("b", cal.b).put("n", cal.n)
            .put("date", cal.date).put("linear", cal.linear))
        sp.edit().putString(KEY_CAL, o.toString()).apply()
    }

    /**
     * Aplica la calibración del método al salto (una sola vez: marca extra["cal"]). Recalcula la potencia y el RSI.
     * Los saltos repetidos no se corrigen (cada salto de la serie tiene su propia altura).
     */
    fun calibrate(ctx: Context, j: Jump): Jump {
        if (j.extra.containsKey("cal") || j.test.isRepeated) return j
        val c = calibration(ctx, j.method) ?: return j
        val raw = j.heightCm
        val h = c.apply(raw).coerceAtLeast(0.0)
        val h1 = (h * 10).roundToInt() / 10.0
        // Potencia de Sayers (depende de la altura); en el acelerómetro es la potencia pico medida y no se toca.
        val power = if (j.method == "acc") null else j.massKg?.let { if (j.powerW != null) Calc.sayersPower(h1, it) else null }
        val rsi = j.contactMs?.takeIf { it > 0 && j.rsi != null }?.let { Calc.rsi(h1, it / 1000.0) }
        val rsiMod = j.tttMs?.takeIf { it > 0 }?.let { h1 / 100.0 / (it / 1000.0) }
        return j.copy(heightCm = h1, powerW = power ?: j.powerW, rsi = rsi ?: j.rsi, rsiMod = rsiMod ?: j.rsiMod,
            extra = j.extra + mapOf("hRaw" to raw, "cal" to 1.0))
    }

    fun csv(ps: List<Pair>): String {
        val sb = StringBuilder("fecha;método;prueba;atleta;AVD (cm);referencia (cm);instrumento;diferencia (cm);media (cm)\n")
        for (p in ps) sb.append(String.format(Locale.US, "%s;%s;%s;%s;%.2f;%.2f;%s;%.2f;%.2f\n",
            p.date, methodLabel(p.method), p.test, p.athlete, p.avd, p.ref, p.refDevice, p.diff, p.mean))
        return sb.toString()
    }

    /** Lee pares pegados: una línea por salto con «AVD referencia» separados por espacio, tabulador, ; o |. */
    fun parsePasted(text: String): List<kotlin.Pair<Double, Double>> = text.lines().mapNotNull { line ->
        val nums = Regex("-?\\d+(?:[.,]\\d+)?").findAll(line).map { it.value.replace(',', '.').toDouble() }.toList()
        if (nums.size >= 2) nums[0] to nums[1] else null
    }
}

/** Gráfico de Bland-Altman (diferencia frente a media) o de dispersión AVD frente a referencia. */
class AgreementChartView(ctx: Context) : View(ctx) {
    var pairs: List<Validation.Pair> = emptyList()
    var stats: Validation.Stats? = null
    var scatter = false
        set(v) { field = v; invalidate() }

    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.LINE; strokeWidth = 2f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.MUTED; textSize = 28f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.TRACK }
    private val bias = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.ACCENT; strokeWidth = 3f }
    private val loa = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Col.WARN; strokeWidth = 3f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val d = resources.displayMetrics.density
        label.textSize = 11f * d
        val l = 44f * d; val r = width - 12f * d; val t = 14f * d; val b = height - 34f * d
        if (pairs.size < 2) {
            c.drawText(Lang.tr(context, "Faltan pares para el gráfico").toString(), l, height / 2f, label)
            return
        }
        val xs = pairs.map { if (scatter) it.avd else it.mean }
        val ys = pairs.map { if (scatter) it.ref else it.diff }
        val s = stats
        var x0 = xs.minOrNull()!!; var x1 = xs.maxOrNull()!!
        var y0 = ys.minOrNull()!!; var y1 = ys.maxOrNull()!!
        if (scatter) { x0 = min(x0, y0); x1 = max(x1, y1); y0 = x0; y1 = x1 }
        else if (s != null) { y0 = min(y0, s.loaLo); y1 = max(y1, s.loaHi); val m = max(abs(y0), abs(y1)); y0 = -m; y1 = m }
        val px = (x1 - x0).coerceAtLeast(1.0) * 0.08; x0 -= px; x1 += px
        val py = (y1 - y0).coerceAtLeast(1.0) * 0.08; y0 -= py; y1 += py
        fun X(v: Double) = (l + (v - x0) / (x1 - x0) * (r - l)).toFloat()
        fun Y(v: Double) = (b - (v - y0) / (y1 - y0) * (b - t)).toFloat()
        c.drawLine(l, t, l, b, axis); c.drawLine(l, b, r, b, axis)
        // Marcas de los ejes.
        for (k in 0..4) {
            val vx = x0 + (x1 - x0) * k / 4; val vy = y0 + (y1 - y0) * k / 4
            c.drawText(String.format(Locale.getDefault(), "%.0f", vx), X(vx) - 8 * d, b + 14 * d, label)
            c.drawText(String.format(Locale.getDefault(), "%.1f", vy), 2 * d, Y(vy) + 4 * d, label)
        }
        val xl = if (scatter) "AVD jump (cm)" else "Media de los dos (cm)"
        c.drawText(Lang.tr(context, xl).toString(), l, height - 4f * d, label)
        if (scatter) {
            c.drawLine(X(x0), Y(x0), X(x1), Y(x1), loa)   // identidad
            s?.let { c.drawLine(X(x0), Y(it.intercept + it.slope * x0), X(x1), Y(it.intercept + it.slope * x1), bias) }
        } else if (s != null) {
            c.drawLine(l, Y(0.0), r, Y(0.0), axis)
            c.drawLine(l, Y(s.bias), r, Y(s.bias), bias)
            c.drawLine(l, Y(s.loaLo), r, Y(s.loaLo), loa)
            c.drawLine(l, Y(s.loaHi), r, Y(s.loaHi), loa)
            c.drawText(String.format(Locale.getDefault(), "%+.2f", s.bias), r - 46 * d, Y(s.bias) - 4 * d, label)
            c.drawText(String.format(Locale.getDefault(), "%+.2f", s.loaHi), r - 46 * d, Y(s.loaHi) - 4 * d, label)
            c.drawText(String.format(Locale.getDefault(), "%+.2f", s.loaLo), r - 46 * d, Y(s.loaLo) + 14 * d, label)
        }
        for (i in xs.indices) c.drawCircle(X(xs[i]), Y(ys[i]), 4.5f * d, dot)
    }
}

class ValidationActivity : BaseActivity() {

    private lateinit var methodSpinner: Spinner
    private lateinit var summaryTxt: android.widget.TextView
    private lateinit var chart: AgreementChartView
    private lateinit var listBox: LinearLayout
    private lateinit var calBox: LinearLayout
    private val filters = listOf<String?>(null) + Validation.METHODS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vbox(14).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(text("Validación y calibración", 28f, Col.INK, true))
        root.addView(text("Mide los mismos saltos con AVD jump y con un instrumento de referencia (plataforma de fuerza, My Jump Lab u otro). " +
            "Con 15–30 pares se ve cuánto se parecen (Bland-Altman) y, si quieres, se corrige la diferencia.", 14f, Col.MUTED))

        val p = panel()
        p.addView(caption("Método de AVD jump"))
        methodSpinner = Spinner(this)
        methodSpinner.adapter = TrAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Todos los métodos") + Validation.METHODS.map { Validation.methodLabel(it) })
        p.addView(methodSpinner, lp())
        val addRow = hbox(8)
        addRow.addView(button("Añadir par de un salto", true) { pickJump() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(button("Pegar lista") { pasteList() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        p.addView(addRow, lp())
        p.addView(text("Mide un salto con AVD jump mientras la referencia mide el mismo salto. Luego elige el salto aquí y escribe el valor de la referencia.", 12f, Col.MUTED))
        root.addView(p, lp())

        val pr = panel()
        pr.addView(caption("Acuerdo"))
        summaryTxt = text("", 13f, Col.INK).apply { typeface = android.graphics.Typeface.MONOSPACE }
        pr.addView(summaryTxt)
        chart = AgreementChartView(this)
        pr.addView(chart, lp(h = dp(240)))
        val chartRow = hbox(8)
        chartRow.addView(button("Bland-Altman") { chart.scatter = false }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        chartRow.addView(button("AVD vs referencia") { chart.scatter = true }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pr.addView(chartRow, lp())
        pr.addView(text("Línea naranja: sesgo. Líneas punteadas: límites de acuerdo (95 % de las diferencias). " +
            "En la dispersión, la punteada es la igualdad y la naranja la regresión.", 12f, Col.MUTED))
        pr.addView(button("Compartir datos y resultados") { share() }, lp())
        root.addView(pr, lp())

        calBox = panel()
        root.addView(calBox, lp())

        val pl = panel()
        pl.addView(caption("Pares (mantén pulsado para borrar)"))
        listBox = vbox(6)
        pl.addView(listBox, lp())
        root.addView(pl, lp())

        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })
        methodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = render()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onResume() { super.onResume(); render() }

    private fun method(): String? = filters.getOrNull(methodSpinner.selectedItemPosition)
    private fun pairs(): List<Validation.Pair> = Validation.all(this).filter { method() == null || it.method == method() }
    private fun f(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    private fun render() {
        val ps = pairs()
        val s = Validation.stats(ps)
        summaryTxt.text = s?.let { Validation.summary(it) } ?: "Hacen falta al menos 3 pares (recomendado 15–30)."
        chart.pairs = ps; chart.stats = s; chart.invalidate()
        listBox.removeAllViews()
        if (ps.isEmpty()) listBox.addView(text("Todavía no hay pares.", 13f, Col.MUTED))
        for (p in ps.sortedByDescending { it.id }) {
            val row = text(f("%s · %s · %s\nAVD %.1f · ref. %.1f (%s) · dif. %+.1f cm", p.date.take(16), p.athlete.ifEmpty { "—" },
                Validation.methodLabel(p.method), p.avd, p.ref, p.refDevice, p.diff), 13f, Col.INK)
            row.setPadding(0, dp(4), 0, dp(4))
            row.setOnLongClickListener {
                Dlg(this).setTitle("¿Borrar este par?").setPositiveButton("Borrar") { _, _ -> Validation.delete(this, p.id); render() }
                    .setNegativeButton("Cancelar", null).show(); true
            }
            listBox.addView(row, lp())
        }
        renderCal(ps, s)
    }

    private fun renderCal(ps: List<Validation.Pair>, s: Validation.Stats?) {
        calBox.removeAllViews()
        calBox.addView(caption("Calibración (opcional)"))
        val m = method()
        if (m == null) {
            calBox.addView(text("Elige un método arriba: la corrección se guarda por método.", 13f, Col.MUTED))
            for (mm in Validation.METHODS) Validation.calibration(this, mm)?.let {
                calBox.addView(text(Validation.methodLabel(mm) + ": " + it.describe(), 13f, Col.INK))
            }
            return
        }
        val cur = Validation.calibration(this, m)
        calBox.addView(text(if (cur == null) "Sin corrección: se guarda la altura medida." else "Activa: " + cur.describe(), 13f, if (cur == null) Col.MUTED else Col.OK, cur != null))
        if (s != null && s.n >= 5) {
            val now = Store.today()
            calBox.addView(button(f("Corregir solo el sesgo (%+.2f cm)", -s.bias)) {
                confirmCal(m, Validation.Cal(-s.bias, 1.0, s.n, now, false))
            }, lp())
            if (s.n >= 10) calBox.addView(button(f("Corrección lineal (× %.3f %+.2f)", s.slope, s.intercept)) {
                confirmCal(m, Validation.Cal(s.intercept, s.slope, s.n, now, true))
            }, lp())
            calBox.addView(text("Con menos de 20 pares conviene corregir solo el sesgo. La corrección lineal hace falta si hay sesgo proporcional.", 12f, Col.MUTED))
        } else calBox.addView(text("Con 5 pares o más se puede corregir el sesgo; con 10 o más, también la pendiente.", 12f, Col.MUTED))
        if (cur != null) calBox.addView(button("Quitar la corrección") { Validation.setCalibration(this, m, null); render() }, lp())
        calBox.addView(text("La corrección se aplica a los saltos nuevos de ese método (no a los saltos repetidos). " +
            "Se guarda la altura original y la corregida. Vale solo para este teléfono y esta forma de grabar.", 12f, Col.MUTED))
    }

    private fun confirmCal(m: String, c: Validation.Cal) {
        Dlg(this).setTitle("Aplicar corrección a " + Validation.methodLabel(m))
            .setMessage(c.describe() + "\n\nEjemplo: 30,0 cm medidos → " + f("%.1f cm", c.apply(30.0)) + "\n40,0 cm medidos → " + f("%.1f cm", c.apply(40.0)))
            .setPositiveButton("Aplicar") { _, _ -> Validation.setCalibration(this, m, c); render() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun pickJump() {
        val js = Store.all(this).filter { !it.test.isRepeated && (method() == null || it.method == method()) }.take(60)
        if (js.isEmpty()) {
            Tst.makeText(this, "No hay saltos simples guardados" + (if (method() != null) " con ese método." else "."), Toast.LENGTH_LONG).show()
            manualPair(); return
        }
        val used = Validation.all(this).mapNotNull { it.jumpId }.toSet()
        val labels = js.map { j ->
            f("%s%.1f cm · %s · %s · %s · %s", if (j.id in used) "✓ " else "", Validation.rawHeight(j), j.test.short, j.athlete,
                Validation.methodLabel(j.method), j.date.drop(5))
        }.toTypedArray<CharSequence>()
        Dlg(this).setTitle("Elige el salto")
            .setItems(labels) { _, i -> askRef(js[i]) }
            .setNeutralButton("Escribir valores") { _, _ -> manualPair() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun refInputs(box: LinearLayout): kotlin.Pair<EditText, Spinner> {
        val ref = input("Altura de la referencia (cm)", numeric = true)
        box.addView(ref, lp())
        val dev = Spinner(this)
        dev.adapter = TrAdapter(this, android.R.layout.simple_spinner_dropdown_item, Validation.REFERENCES)
        box.addView(dev, lp())
        return ref to dev
    }

    private fun askRef(j: Jump) {
        val box = vbox(8).apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val raw = Validation.rawHeight(j)
        box.addView(text(f("AVD jump: %.1f cm (%s, %s)", raw, j.test.label, Validation.methodLabel(j.method)), 14f, Col.INK))
        val (ref, dev) = refInputs(box)
        Dlg(this).setTitle("Valor de la referencia").setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val v = ref.number()
                if (v == null || v <= 0) { Tst.makeText(this, "Escribe la altura de la referencia.", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                Validation.add(this, Validation.Pair(System.currentTimeMillis(), j.date, j.method ?: "video", j.test.short, j.athlete,
                    raw, v, Validation.REFERENCES[dev.selectedItemPosition], j.id))
                render()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun methodSpinnerFor(box: LinearLayout): Spinner {
        val ms = Spinner(this)
        ms.adapter = TrAdapter(this, android.R.layout.simple_spinner_dropdown_item, Validation.METHODS.map { Validation.methodLabel(it) })
        method()?.let { ms.setSelection(Validation.METHODS.indexOf(it)) }
        box.addView(ms, lp())
        return ms
    }

    private fun manualPair() {
        val box = vbox(8).apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val avd = input("Altura de AVD jump sin corregir (cm)", numeric = true)
        box.addView(avd, lp())
        val ms = methodSpinnerFor(box)
        val (ref, dev) = refInputs(box)
        Dlg(this).setTitle("Par escrito a mano").setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val a = avd.number(); val r = ref.number()
                if (a == null || r == null || a <= 0 || r <= 0) { Tst.makeText(this, "Faltan valores.", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                Validation.add(this, Validation.Pair(System.currentTimeMillis(), Store.today(), Validation.METHODS[ms.selectedItemPosition], "", "",
                    a, r, Validation.REFERENCES[dev.selectedItemPosition]))
                render()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun pasteList() {
        val box = vbox(8).apply { setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(text("Una línea por salto: altura de AVD jump y de la referencia, separadas por espacio, ; o tabulador. Se puede pegar desde Excel.", 13f, Col.MUTED))
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5; hint = "32,4  33,1\n28,9  29,6"
        }
        box.addView(et, lp())
        val ms = methodSpinnerFor(box)
        val dev = Spinner(this)
        dev.adapter = TrAdapter(this, android.R.layout.simple_spinner_dropdown_item, Validation.REFERENCES)
        box.addView(dev, lp())
        Dlg(this).setTitle("Pegar lista de pares").setView(box)
            .setPositiveButton("Añadir") { _, _ ->
                val rows = Validation.parsePasted(et.text.toString()).filter { it.first > 0 && it.second > 0 }
                val base = System.currentTimeMillis()
                rows.forEachIndexed { i, (a, r) ->
                    Validation.add(this, Validation.Pair(base + i, Store.today(), Validation.METHODS[ms.selectedItemPosition], "", "",
                        a, r, Validation.REFERENCES[dev.selectedItemPosition]))
                }
                Tst.makeText(this, "${rows.size} pares añadidos.", Toast.LENGTH_SHORT).show()
                render()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun share() {
        val ps = pairs()
        if (ps.isEmpty()) return
        val s = Validation.stats(ps)
        val txt = "Validación AVD jump · " + (method()?.let { Validation.methodLabel(it) } ?: "todos los métodos") + "\n\n" +
            (s?.let { Validation.summary(it) + "\n\n" } ?: "") + Validation.csv(ps)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Validación AVD jump")
            putExtra(Intent.EXTRA_TEXT, txt)
        }, "Compartir"))
    }
}
