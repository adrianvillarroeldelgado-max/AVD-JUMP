package com.salto240

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.widget.Toast
import java.util.Locale
import kotlin.math.max

/**
 * Imagen del resultado para compartir (WhatsApp, Instagram): 1080 × 1350 px con el nombre, la prueba,
 * la altura en grande, la evolución de los últimos días y la mejor marca.
 */
object ShareCard {
    private const val W = 1080
    private const val H = 1350

    private fun f(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    fun share(act: Activity, j: Jump) {
        Thread {
            val file = try {
                val bmp = render(act, j)
                val out = ShareFiles.newFile(act, "AVD-jump-${j.athlete.ifBlank { "salto" }}-${j.day}.png")
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                out
            } catch (e: Exception) { null }
            act.runOnUiThread {
                if (file == null) Tst.makeText(act, "No se pudo crear la imagen.", Toast.LENGTH_SHORT).show()
                else ShareFiles.share(act, file, Lang.tr(act, "Resultado AVD jump"))
            }
        }.start()
    }

    /** Valor principal según la prueba: altura (cm) o RSI en saltos repetidos. */
    private fun value(j: Jump): Double = if (j.test.isRepeated && j.rsi != null) j.rsi else j.heightCm

    fun render(act: Activity, j: Jump): Bitmap {
        fun tr(s: String) = Lang.tr(act, s)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Fondo
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, 0f, H.toFloat(), 0xFF0E1726.toInt(), 0xFF1E3350.toInt(), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.ACCENT }
        c.drawRect(0f, 0f, W.toFloat(), 14f, accent)
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = bold }
        val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB7C3D6.toInt() }

        // Logo
        try {
            act.getDrawable(R.drawable.logo_avd_jump)?.let { d ->
                val h = 110; val w = (d.intrinsicWidth.toFloat() / max(1, d.intrinsicHeight) * h).toInt().coerceAtMost(420)
                d.setBounds(72, 64, 72 + w, 64 + h); d.draw(c)
            }
        } catch (_: Exception) {}

        var y = 280f
        white.textSize = 64f
        c.drawText(ellipsize(j.athlete.ifBlank { tr("Atleta") }, white, W - 144f), 72f, y, white)
        y += 62f
        soft.textSize = 40f
        c.drawText(ellipsize(tr(j.test.label) + " · " + Evaluations.pretty(j.day), soft, W - 144f), 72f, y, soft)

        // Valor grande
        y += 250f
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = bold; textSize = 230f }
        val v = value(j)
        val vTxt = if (j.test.isRepeated && j.rsi != null) f("%.2f", v) else f("%.1f", v)
        c.drawText(vTxt, 64f, y, big)
        val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.ACCENT; typeface = bold; textSize = 80f }
        c.drawText(if (j.test.isRepeated && j.rsi != null) "RSI" else "cm", 64f + big.measureText(vTxt) + 24f, y, unit)

        // Datos secundarios
        y += 80f
        soft.textSize = 38f
        val sec = listOfNotNull(
            if (j.flightMs > 0) f(tr("Vuelo") + " %.0f ms", j.flightMs) else null,
            j.contactMs?.let { f(tr("Contacto") + " %.0f ms", it) },
            if (!j.test.isRepeated) j.rsi?.let { f("RSI %.2f", it) } else null,
            j.rsiMod?.let { f(tr("RSI modificado") + " %.2f", it) },
            j.powerW?.let { p -> j.massKg?.takeIf { it > 0 }?.let { m -> f("%.0f W/kg", p / m) } }
        )
        sec.chunked(2).forEach { line -> c.drawText(line.joinToString("   ·   "), 72f, y, soft); y += 52f }

        // Evolución: mejor valor por día, últimos 10 días con esta prueba.
        val hist = Store.ofAthlete(act, j.athlete).filter { it.test == j.test && it.id != j.id } + j
        val perDay = hist.groupBy { it.day }.mapValues { (_, l) -> l.maxOf { value(it) } }.toSortedMap()
        val pts = perDay.entries.toList().takeLast(10)
        val best = perDay.values.maxOrNull() ?: v
        val top = max(y + 40f, 860f); val bot = 1150f
        if (pts.size >= 2) {
            val l = 92f; val r = W - 92f
            val lo = pts.minOf { it.value } * 0.92; val hi = pts.maxOf { it.value } * 1.05
            fun X(i: Int) = l + i * (r - l) / (pts.size - 1)
            fun Y(x: Double) = (bot - (x - lo) / max(1e-6, hi - lo) * (bot - top)).toFloat()
            val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; strokeWidth = 2f }
            c.drawLine(l, bot, r, bot, grid)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7FB2FF.toInt(); strokeWidth = 7f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
            val path = Path()
            pts.forEachIndexed { i, e -> if (i == 0) path.moveTo(X(i), Y(e.value)) else path.lineTo(X(i), Y(e.value)) }
            c.drawPath(path, line)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7FB2FF.toInt() }
            pts.forEachIndexed { i, e ->
                val last = i == pts.size - 1
                c.drawCircle(X(i), Y(e.value), if (last) 20f else 11f, if (last) accent else dot)
            }
            soft.textSize = 32f
            c.drawText(tr("Evolución") + " · " + f(tr("%d días"), pts.size), l, top - 24f, soft)
        }

        // Mejor marca y diferencia con la media anterior
        val prev = pts.dropLast(1).map { it.value }
        var foot = f(tr("Mejor marca") + ": %.1f", best) + if (j.test.isRepeated && j.rsi != null) "" else " cm"
        if (v >= best && perDay.size > 1) foot = tr("¡Mejor marca personal!")
        else if (prev.size >= 2) {
            val m = prev.average()
            foot += f("   ·   %+.1f %% ", (v - m) / m * 100) + tr("frente a su media")
        }
        white.textSize = 42f
        c.drawText(ellipsize(foot, white, W - 144f), 72f, 1235f, white)

        soft.textSize = 30f
        val method = when (j.method) { "240" -> tr("Cámara lenta 240 fps"); "ia" -> tr("IA en vivo"); "acc" -> tr("Acelerómetro"); else -> tr("Análisis de video") }
        c.drawText(tr("Medido con AVD jump") + " · " + method, 72f, 1300f, soft)
        return bmp
    }

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
}
