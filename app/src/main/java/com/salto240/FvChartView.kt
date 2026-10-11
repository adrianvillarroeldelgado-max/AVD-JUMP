package com.salto240

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import java.util.Locale
import kotlin.math.ceil

/** Gráfico del perfil fuerza-velocidad: puntos medidos, recta real y recta óptima. */
class FvChartView(ctx: Context) : View(ctx) {

    var profile: FvProfile? = null
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.MUTED; strokeWidth = 1 * d }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.LINE; strokeWidth = 1 * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.MUTED; textSize = 11 * d }
    private val real = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.ACCENT; strokeWidth = 2.5f * d }
    private val opt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Col.OK; strokeWidth = 2f * d; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.TRACK }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.SURFACE }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val p = profile
        val l = 40 * d; val t = 14 * d; val r = width - 14 * d; val b = height - 34 * d
        if (p == null) {
            c.drawText("Faltan al menos 2 cargas para trazar el perfil.", l, height / 2f, label)
            return
        }
        val vMax = ceil(maxOf(p.v0, p.v0Opt) * 1.05 * 2) / 2      // múltiplo de 0,5 m/s
        val fMax = ceil(maxOf(p.f0, p.f0Opt) * 1.05 / 10) * 10    // múltiplo de 10 N/kg
        fun x(v: Double) = (l + (v / vMax) * (r - l)).toFloat()
        fun y(f: Double) = (b - (f / fMax) * (b - t)).toFloat()

        // Rejilla y etiquetas
        var f = 0.0
        while (f <= fMax + 0.01) {
            c.drawLine(l, y(f), r, y(f), grid)
            c.drawText(String.format(Locale.getDefault(), "%.0f", f), 6 * d, y(f) + 4 * d, label)
            f += 10.0
        }
        var v = 0.0
        while (v <= vMax + 0.01) {
            c.drawLine(x(v), t, x(v), b, grid)
            c.drawText(String.format(Locale.getDefault(), "%.1f", v), x(v) - 8 * d, b + 14 * d, label)
            v += 0.5
        }
        c.drawLine(l, b, r, b, axis)
        c.drawLine(l, t, l, b, axis)
        c.drawText("Velocidad (m/s)", r - 92 * d, b + 28 * d, label)
        c.drawText("Fuerza (N/kg)", l + 6 * d, t + 10 * d, label)

        // Recta óptima (misma potencia máxima) y recta real
        c.drawLine(x(0.0), y(p.f0Opt), x(p.v0Opt), y(0.0), opt)
        c.drawLine(x(0.0), y(p.f0), x(p.v0), y(0.0), real)

        // Puntos medidos
        for (pt in p.points) {
            c.drawCircle(x(pt.velocity), y(pt.force), 6 * d, dotRing)
            c.drawCircle(x(pt.velocity), y(pt.force), 4.5f * d, dot)
        }
    }
}
