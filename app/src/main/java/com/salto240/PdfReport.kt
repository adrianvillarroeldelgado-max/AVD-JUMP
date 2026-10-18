package com.salto240

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import java.io.File
import java.util.Locale

/**
 * Informe en PDF para entregar al cliente: logo, ficha, resultados de la última evaluación con
 * la comparación anterior, intentos, disposición, evolución, perfil F-V, percentil y sugerencias.
 * Tamaño A4 (595 × 842 puntos).
 */
object PdfReport {

    private const val W = 595
    private const val H = 842
    private const val M = 40f

    private class Writer(val ctx: Context, val doc: PdfDocument, val title: String) {
        var page: PdfDocument.Page? = null
        var c: Canvas? = null
        var y = 0f
        var n = 0
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Col.INK }
        val muted = TextPaint(body).apply { color = Col.MUTED; textSize = 8.5f }
        val bold = TextPaint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val h1 = TextPaint(bold).apply { textSize = 20f }
        val h2 = TextPaint(bold).apply { textSize = 13f; color = Col.TRACK }
        val line = Paint().apply { color = Col.LINE; strokeWidth = 0.7f }

        fun newPage() {
            finishPage()
            n++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, n).create())
            page = p; c = p.canvas; y = M
            if (n > 1) {
                c!!.drawText(title, M, y, muted)
                y += 14f
            }
        }

        fun finishPage() {
            val p = page ?: return
            val cc = p.canvas
            val foot = tr("AVD jump · página $n")
            cc.drawText(foot, W - M - muted.measureText(foot), H - 22f, muted)
            cc.drawText(tr("Medición por tiempo de vuelo (h = g·t²/8)."), M, H - 22f, muted)
            doc.finishPage(p)
            page = null
        }

        fun ensure(h: Float) { if (y + h > H - 46f) newPage() }

        fun tr(s: String) = Lang.tr(ctx, s)

        fun text(s0: String, paint: TextPaint = body, x: Float = M, width: Float = W - 2 * M, gap: Float = 4f) {
            val s = tr(s0)
            val l = StaticLayout.Builder.obtain(s, 0, s.length, paint, width.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(1.5f, 1f).build()
            ensure(l.height.toFloat())
            c!!.save(); c!!.translate(x, y); l.draw(c!!); c!!.restore()
            y += l.height + gap
        }

        fun heading(s0: String) {
            val s = tr(s0)
            ensure(40f)
            y += 8f
            c!!.drawText(s, M, y + 12f, h2)
            y += 18f
            c!!.drawLine(M, y, W - M, y, line)
            y += 8f
        }

        /** Tabla simple: columnas con anchos relativos; primera fila en negrita. */
        fun table(rows: List<List<String>>, widths: List<Float>, colors: List<Int?> = emptyList()) {
            val total = widths.sum()
            val avail = W - 2 * M
            rows.forEachIndexed { ri, r ->
                val paint = if (ri == 0) bold else body
                val layouts = r.map { tr(it) }.mapIndexed { i, s ->
                    val p = TextPaint(paint)
                    if (ri > 0) colors.getOrNull(ri)?.let { col -> if (i == r.size - 1) p.color = col }
                    StaticLayout.Builder.obtain(s, 0, s.length, p, (avail * widths[i] / total - 6).toInt().coerceAtLeast(20)).build()
                }
                val h = (layouts.maxOfOrNull { it.height } ?: 12) + 6f
                ensure(h)
                if (ri == 0) {
                    val bg = Paint().apply { color = 0xFFE6ECF4.toInt() }
                    c!!.drawRect(M, y - 2f, W - M, y + h - 2f, bg)
                }
                var x = M
                layouts.forEachIndexed { i, l ->
                    c!!.save(); c!!.translate(x + 3f, y + 1f); l.draw(c!!); c!!.restore()
                    x += avail * widths[i] / total
                }
                y += h
                c!!.drawLine(M, y - 2f, W - M, y - 2f, line)
            }
            y += 6f
        }

        /** Dibuja una vista (gráfico) escalada a [w]×[h] puntos. */
        fun view(v: View, w: Float, h: Float, x: Float = M) {
            ensure(h + 6f)
            val d = ctx.resources.displayMetrics.density
            val pw = (w * d).toInt(); val ph = (h * d).toInt()
            v.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, pw, ph)
            c!!.save(); c!!.translate(x, y); c!!.scale(1f / d, 1f / d); v.draw(c!!); c!!.restore()
        }
    }

    private fun f(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    /** Crea el PDF del atleta y devuelve el archivo (en la carpeta para compartir). */
    fun build(ctx: Context, name: String): File {
        val client = Clients.byName(ctx, name)
        val athlete = Store.athlete(ctx, name)
        val js = Store.ofAthlete(ctx, name)
        val evals = Evaluations.list(js, athlete)
        val doc = PdfDocument()
        val w = Writer(ctx, doc, Lang.tr(ctx, "Informe de evaluación") + " · $name")
        w.newPage()
        val c = w.c!!

        // Encabezado con logo
        try {
            val logo = BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_avd_jump)
            if (logo != null) {
                val lh = 52f; val lw = lh * logo.width / logo.height
                c.drawBitmap(logo, null, RectF(W - M - lw, M - 6f, W - M, M - 6f + lh), Paint(Paint.FILTER_BITMAP_FLAG))
            }
        } catch (_: Exception) {}
        c.drawText(w.tr("Informe de evaluación"), M, w.y + 18f, w.h1)
        w.y += 28f
        c.drawText(name, M, w.y + 14f, TextPaint(w.bold).apply { textSize = 15f })
        w.y += 22f
        val info = listOfNotNull(
            client?.summary(),
            client?.let { Clients.groupName(ctx, it.groupId) }?.let { "Grupo: $it" },
            client?.dominantLeg?.let { "Pierna dominante: $it" },
            "Fecha del informe: " + Evaluations.pretty(Store.today().take(10))
        ).joinToString("\n")
        w.text(info, w.muted)
        w.y += 6f

        if (evals.isEmpty()) {
            w.text("Todavía no hay saltos guardados para este atleta.")
            w.finishPage()
            return save(ctx, doc, name)
        }

        // Última evaluación
        val e = evals.first()
        w.heading("Última evaluación · " + Evaluations.pretty(e.day))
        val rows = ArrayList<List<String>>()
        val colors = ArrayList<Int?>()
        rows.add(listOf("Indicador", "Valor", "Anterior", "Cambio")); colors.add(null)
        for (m in Metric.values()) {
            val v = e.values[m] ?: continue
            val prev = evals.drop(1).firstNotNullOfOrNull { it.values[m] }
            val d = Evaluations.delta(evals, 0, m)
            rows.add(listOf(m.label, m.format(v), prev?.let { m.format(it) } ?: "—", d ?: "—"))
            colors.add(when { d == null -> Col.MUTED; d.startsWith("▲") -> Col.OK; d.startsWith("▼") -> Col.ACCENT; else -> Col.MUTED })
        }
        if (rows.size > 1) w.table(rows, listOf(3f, 1.6f, 1.6f, 2f), colors)

        // Percentil
        Norms.lines(ctx, name).takeIf { it.isNotEmpty() }?.let { lines ->
            w.text("Comparación: " + lines.joinToString(" · "), w.muted)
        }

        // Disposición
        Readiness.of(js, e.day)?.let { r ->
            w.text("Disposición del día: ${r.light.label}. ${r.line()}. ${r.light.advice}", TextPaint(w.body).apply { color = r.light.color })
        }

        // Intentos
        val att = Attempts.summarize(e.jumps)
        if (att.isNotEmpty()) {
            w.heading("Intentos")
            val t = ArrayList<List<String>>()
            t.add(listOf("Prueba", "Intentos", "Mejor", "Media", "CV"))
            for (s in att) t.add(listOf(s.test.label + (if (s.loadKg > 0.4) f(" +%.1f kg", s.loadKg) else ""), s.n.toString(),
                s.fmt(s.best), if (s.n > 1) s.fmt(s.mean) else "—", s.cv?.let { f("%.1f %%", it) } ?: "—"))
            w.table(t, listOf(3f, 1f, 1.5f, 1.5f, 1f))
            att.flatMap { it.warnings() }.distinct().forEach { w.text("⚠ $it", TextPaint(w.muted).apply { color = Col.WARN }, gap = 2f) }
        }

        // Evolución: hasta 4 indicadores con más de una evaluación
        val trendMetrics = Metric.values().filter { m -> Evaluations.trend(evals, m).size >= 2 }.take(4)
        if (trendMetrics.isNotEmpty()) {
            w.heading("Evolución")
            val cw = (W - 2 * M - 12f) / 2; val ch = 130f
            trendMetrics.chunked(2).forEach { pair ->
                w.ensure(ch + 22f)
                pair.forEachIndexed { i, m ->
                    val x = M + i * (cw + 12f)
                    w.c!!.drawText(w.tr(m.label), x, w.y + 10f, w.bold)
                }
                w.y += 16f
                pair.forEachIndexed { i, m ->
                    val v = TrendChartView(ctx).apply { set(Evaluations.trend(evals, m), m) }
                    w.view(v, cw, ch, M + i * (cw + 12f))
                }
                w.y += ch + 10f
            }
        }

        // Perfil F-V
        Analytics.latestProfile(js, athlete)?.let { (t, p) ->
            w.heading("Perfil fuerza-velocidad (${t.short} con carga)")
            w.text(f("F0 %.1f N/kg · V0 %.2f m/s · Pmax %.1f W/kg · R² %.2f\nPerfil al %.0f %% del óptimo: %s. Salto actual teórico %.1f cm; con el perfil óptimo %.1f cm.",
                p.f0, p.v0, p.pmax, p.r2, p.ratio, p.category.label, p.hNow, p.hOpt))
            val v = FvChartView(ctx).apply { profile = p }
            w.view(v, W - 2 * M, 180f)
            w.y += 186f
            w.text(p.category.training, w.body)
        }

        // Sugerencias
        val recs = Analytics.recommendations(js, athlete)
        if (recs.isNotEmpty()) {
            w.heading("Sugerencias de entrenamiento")
            for (r in recs) {
                w.text(r.level.label.uppercase(), TextPaint(w.bold).apply { color = r.level.color; textSize = 8f }, gap = 1f)
                w.text(r.title, w.bold, gap = 1f)
                w.text(r.body, w.body, gap = 8f)
            }
            w.text("Orientativo. Umbrales: asimetría (Bishop et al. 2018), perfil F-V (Samozino 2012; Jiménez-Reyes 2017). " +
                "Ajusta según deporte, momento de la temporada e historial de lesiones.", w.muted)
        }
        w.finishPage()
        return save(ctx, doc, name)
    }

    private fun save(ctx: Context, doc: PdfDocument, name: String): File {
        val f = ShareFiles.newFile(ctx, "Informe AVD jump $name ${Store.today().take(10)}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }
}

/** Crea el informe PDF en segundo plano y abre el menú para compartirlo. */
object PdfShare {
    fun run(act: android.app.Activity, name: String) {
        val pd = android.app.ProgressDialog(act).apply { setMessage(Lang.tr(act, "Creando PDF…")); setCancelable(false); show() }
        Thread {
            val r = try { Result.success(PdfReport.build(act, name)) } catch (e: Exception) { Result.failure(e) }
            act.runOnUiThread {
                try { pd.dismiss() } catch (_: Exception) {}
                r.onSuccess { f -> ShareFiles.share(act, f, Lang.tr(act, "Informe de evaluación") + " — $name") }
                 .onFailure { e -> Tst.makeText(act, "No se pudo crear el PDF: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
