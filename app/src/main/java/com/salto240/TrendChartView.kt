package com.salto240

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Evolución de un indicador entre evaluaciones: línea con puntos, último valor resaltado. */
class TrendChartView(ctx: Context) : View(ctx) {

    private var points: List<Pair<String, Double>> = emptyList()
    private var metric: Metric = Metric.CMJ

    fun set(points: List<Pair<String, Double>>, metric: Metric) {
        this.points = points; this.metric = metric
        invalidate()
    }

    private val d = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.LINE; strokeWidth = 1 * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.MUTED; textSize = 11 * d }
    private val strong = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.INK; textSize = 13 * d; isFakeBoldText = true }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.TRACK; strokeWidth = 2.5f * d; style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x221E4F8C; style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.TRACK }
    private val last = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.ACCENT }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.SURFACE }

    private fun niceStep(range: Double): Double {
        val raw = range / 4
        val mag = 10.0.pow(floor(log10(raw)))
        val n = raw / mag
        return mag * when { n < 1.5 -> 1.0; n < 3.5 -> 2.0; n < 7.5 -> 5.0; else -> 10.0 }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val l = 44 * d; val t = 22 * d; val r = width - 16 * d; val b = height - 26 * d
        if (points.isEmpty()) {
            c.drawText("Sin datos de este indicador todavía.", l, height / 2f, label)
            return
        }
        val vals = points.map { it.second }
        var lo = vals.minOrNull()!!; var hi = vals.maxOrNull()!!
        if (hi - lo < 1e-6) { lo -= 1; hi += 1 }
        val step = niceStep(hi - lo)
        val yMin = floor(lo / step) * step
        val yMax = ceil(hi / step) * step
        fun y(v: Double) = (b - (v - yMin) / (yMax - yMin) * (b - t)).toFloat()
        fun x(i: Int) = if (points.size == 1) (l + r) / 2 else l + i * (r - l) / (points.size - 1)

        var v = yMin
        while (v <= yMax + step / 2) {
            c.drawLine(l, y(v), r, y(v), grid)
            c.drawText(String.format(Locale.getDefault(), "%.${metric.decimals.coerceAtMost(1)}f", v), 4 * d, y(v) + 4 * d, label)
            v += step
        }

        if (points.size > 1) {
            val area = Path().apply {
                moveTo(x(0), b)
                points.indices.forEach { lineTo(x(it), y(points[it].second)) }
                lineTo(x(points.size - 1), b); close()
            }
            c.drawPath(area, fill)
            val p = Path().apply {
                moveTo(x(0), y(points[0].second))
                for (i in 1 until points.size) lineTo(x(i), y(points[i].second))
            }
            c.drawPath(p, line)
        }
        points.indices.forEach { i ->
            val isLast = i == points.size - 1
            c.drawCircle(x(i), y(points[i].second), (if (isLast) 6f else 4.5f) * d, ring)
            c.drawCircle(x(i), y(points[i].second), (if (isLast) 4.5f else 3.5f) * d, if (isLast) last else dot)
        }
        // Fechas: primera y última; último valor encima de su punto.
        c.drawText(Evaluations.pretty(points.first().first), l, b + 18 * d, label)
        if (points.size > 1) {
            val txt = Evaluations.pretty(points.last().first)
            c.drawText(txt, r - label.measureText(txt), b + 18 * d, label)
        }
        val lv = metric.format(points.last().second)
        val lx = (x(points.size - 1) - strong.measureText(lv)).coerceAtLeast(l)
        c.drawText(lv, lx, (y(points.last().second) - 10 * d).coerceAtLeast(t - 6 * d), strong)
    }
}
