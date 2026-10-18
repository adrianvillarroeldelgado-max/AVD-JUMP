package com.salto240

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** Guía de primera vez: cómo funciona, cómo colocar el teléfono y cómo trabajar con grupos. */
class OnboardingActivity : BaseActivity() {

    companion object {
        private const val PREF = "onboardingDone"
        fun shouldShow(ctx: Context) = !ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).getBoolean(PREF, false)
        fun markDone(ctx: Context) = ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).edit().putBoolean(PREF, true).apply()
    }

    private data class Page(val kind: Int, val title: String, val body: String)

    private val pages = listOf(
        Page(0, "Bienvenido a AVD jump", "Mide la altura del salto con la cámara del teléfono, por tiempo de vuelo: h = g·t²/8, " +
            "el mismo principio que una plataforma de contacto.\n\nCMJ, CMJ con brazos, SJ, Drop Jump, saltos a una pierna, " +
            "saltos repetidos, test 10/5, pogo y perfil fuerza-velocidad."),
        Page(1, "Dos formas de medir", "IA en vivo: el teléfono sigue el cuerpo entero y muestra cada salto al aterrizar, sin cámara lenta. " +
            "Necesita la estatura del atleta.\n\nCámara lenta 240 fps: el teléfono mira los pies y mide cuadro a cuadro. " +
            "Es la opción más precisa (±0,5 cm) si tu teléfono graba en cámara lenta."),
        Page(2, "Coloca el teléfono", "• En trípode, quieto y nivelado (la pantalla muestra el nivel).\n" +
            "• IA: a 2–3 m, a la altura de la cadera, con el cuerpo entero dentro del recuadro.\n" +
            "• 240 fps: a 1,5–2,5 m y a 20–40 cm del piso; arrastra la línea naranja hasta donde apoyan los pies.\n" +
            "• Buena luz y fondo que contraste con las zapatillas."),
        Page(3, "Salta bien para medir bien", "• Quieto 1 s antes de saltar.\n• Manos en la cadera en CMJ y SJ.\n" +
            "• Aterrizar en el mismo sitio con las piernas extendidas: si encoge las rodillas, la app avisa porque la altura sale inflada.\n" +
            "• 3 intentos válidos por prueba: la app muestra el mejor, la media y la variación (CV)."),
        Page(4, "Equipos y seguimiento", "• Crea clientes y grupos, o impórtalos desde Excel.\n" +
            "• Sesión de grupo: la cámara llama a cada atleta por su nombre, dice el resultado en voz alta y pasa al siguiente. " +
            "Funciona con un botón Bluetooth de selfie.\n" +
            "• Disposición diaria: un CMJ al llegar y un semáforo de fatiga.\n" +
            "• Informe en PDF con tu logo, ranking y exportación a Excel."),
        Page(5, "Protege tus datos", "Todo se guarda solo en este teléfono. En Ajustes y datos puedes crear una copia de seguridad " +
            "o elegir una carpeta (por ejemplo Google Drive) para una copia automática cada semana.")
    )
    private var index = 0
    private lateinit var holder: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        holder = vbox(16).apply { setPadding(dp(20), dp(24), dp(20), dp(24)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(holder) })
        show()
    }

    private fun show() {
        holder.removeAllViews()
        val p = pages[index]
        val dots = hbox(6).apply { gravity = Gravity.CENTER }
        pages.indices.forEach { i ->
            dots.addView(View(this).apply { background = rounded(if (i == index) Col.ACCENT else Col.LINE, 0, 4) },
                LinearLayout.LayoutParams(dp(if (i == index) 22 else 8), dp(8)))
        }
        holder.addView(dots, lp())
        holder.addView(Illustration(this, p.kind), lp(h = dp(230)))
        holder.addView(text(p.title, 26f, Col.INK, true), lp())
        holder.addView(text(p.body, 16f, Col.INK), lp())
        if (index == 0) {
            val lang = panel()
            lang.addView(caption("Idioma / Language / Idioma"))
            val row = hbox(6)
            for ((code, label) in Lang.options().drop(1)) row.addView(button(label, Lang.code(this) == code) {
                Lang.set(this, code)
                recreate()
            }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            lang.addView(row, lp())
            holder.addView(lang, lp())
        }
        val nav = hbox(8)
        if (index > 0) nav.addView(button("Atrás") { index--; show() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        else nav.addView(button("Saltar guía") { done() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (index < pages.size - 1) nav.addView(button("Siguiente", true) { index++; show() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        else nav.addView(button("Empezar", true) { done() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        holder.addView(nav, lp())
        if (index == pages.size - 1) holder.addView(button("Ir a copia de seguridad") {
            markDone(this); startActivity(Intent(this, SettingsActivity::class.java)); finish()
        }, lp())
    }

    private fun done() { markDone(this); finish() }

    /** Dibujos simples de cada página. */
    private class Illustration(ctx: Context, val kind: Int) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.INK; strokeWidth = 4 * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val accent = Paint(ink).apply { color = Col.ACCENT }
        private val track = Paint(ink).apply { color = Col.TRACK }
        private val fillAcc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.ACCENT }
        private val fillInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.INK }
        private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDCE4EE.toInt() }
        private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.MUTED; strokeWidth = 2 * d; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f) }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.MUTED; textSize = 13 * d }
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.INK; textSize = 22 * d; isFakeBoldText = true }

        /** Figura humana: pies en (x, y), altura h; [tuck] = rodillas recogidas, [arms] = brazos arriba. */
        private fun person(c: Canvas, x: Float, y: Float, h: Float, p: Paint, arms: Boolean = false) {
            val head = h * 0.09f
            val hip = y - h * 0.48f; val sh = y - h * 0.8f; val knee = y - h * 0.25f
            c.drawCircle(x, y - h + head, head, p)
            c.drawLine(x, y - h + 2 * head, x, hip, p)
            c.drawLine(x, hip, x - h * 0.06f, knee, p); c.drawLine(x - h * 0.06f, knee, x - h * 0.05f, y, p)
            c.drawLine(x, hip, x + h * 0.06f, knee, p); c.drawLine(x + h * 0.06f, knee, x + h * 0.05f, y, p)
            if (arms) { c.drawLine(x, sh, x - h * 0.16f, sh - h * 0.2f, p); c.drawLine(x, sh, x + h * 0.16f, sh - h * 0.2f, p) }
            else { c.drawLine(x, sh, x - h * 0.12f, hip, p); c.drawLine(x, sh, x + h * 0.12f, hip, p)
                c.drawLine(x - h * 0.12f, hip, x - h * 0.03f, hip, p); c.drawLine(x + h * 0.12f, hip, x + h * 0.03f, hip, p) }
        }

        private fun phone(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
            c.drawRoundRect(RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), 6 * d, 6 * d, fillInk)
            c.drawCircle(x + w * 0.15f, y - h * 0.32f, w * 0.12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6F8FB8.toInt() })
        }

        private fun tripod(c: Canvas, x: Float, top: Float, floor: Float) {
            c.drawLine(x, top, x, floor - (floor - top) * 0.25f, ink)
            c.drawLine(x, floor - (floor - top) * 0.25f, x - 26 * d, floor, ink)
            c.drawLine(x, floor - (floor - top) * 0.25f, x + 26 * d, floor, ink)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.drawRoundRect(RectF(0f, 0f, w, h), 18 * d, 18 * d, soft)
            val floor = h - 34 * d
            c.drawLine(16 * d, floor, w - 16 * d, floor, ink)
            when (kind) {
                0 -> {
                    // Salto: trayectoria y tiempo de vuelo
                    val x0 = w * 0.3f; val x1 = w * 0.7f
                    person(c, x0, floor, h * 0.55f, ink)
                    person(c, w * 0.5f, floor - h * 0.22f, h * 0.55f, accent, arms = true)
                    val arc = Path().apply { moveTo(x0, floor - 6 * d); quadTo(w * 0.5f, floor - h * 0.62f, x1, floor - 6 * d) }
                    c.drawPath(arc, dash)
                    person(c, x1, floor, h * 0.55f, ink)
                    c.drawText(Lang.tr(context, "tiempo de vuelo"), w * 0.5f - 52 * d, floor + 22 * d, label)
                    c.drawText("h = g·t²/8", w - 140 * d, 34 * d, big)
                }
                1 -> {
                    // IA (cuerpo entero) y 240 fps (pies)
                    val mid = w / 2
                    c.drawLine(mid, 14 * d, mid, floor, dash)
                    phone(c, w * 0.12f, h * 0.42f, 26 * d, 46 * d)
                    person(c, w * 0.36f, floor, h * 0.6f, accent)
                    c.drawRect(w * 0.27f, floor - h * 0.66f, w * 0.45f, floor + 2 * d, dash)
                    c.drawText("IA", w * 0.06f, 30 * d, big)
                    phone(c, w * 0.6f, floor - 26 * d, 26 * d, 46 * d)
                    person(c, w * 0.85f, floor, h * 0.6f, ink)
                    c.drawLine(w * 0.76f, floor - 4 * d, w * 0.94f, floor - 4 * d, accent)
                    c.drawText("240 fps", w * 0.56f, 30 * d, big)
                }
                2 -> {
                    // Trípode, distancia y nivel
                    val px = w * 0.18f
                    tripod(c, px, h * 0.42f, floor)
                    phone(c, px, h * 0.36f, 22 * d, 40 * d)
                    person(c, w * 0.78f, floor, h * 0.62f, ink)
                    val ay = floor + 18 * d
                    c.drawLine(px, ay, w * 0.78f, ay, track)
                    c.drawText(Lang.tr(context, "2–3 m (IA) · 1,5–2,5 m (240 fps)"), w * 0.26f, ay - 6 * d, label)
                    c.drawLine(px + 30 * d, h * 0.36f, w * 0.72f, h * 0.36f, dash)
                    c.drawText(Lang.tr(context, "✓ Nivelado"), px - 20 * d, 28 * d, Paint(label).apply { color = Col.OK; isFakeBoldText = true })
                }
                3 -> {
                    // Aterrizaje correcto vs rodillas recogidas
                    person(c, w * 0.3f, floor, h * 0.62f, track)
                    c.drawText("✓", w * 0.28f, 30 * d, Paint(big).apply { color = Col.OK })
                    val x = w * 0.7f; val y = floor - h * 0.1f; val hh = h * 0.55f
                    c.drawCircle(x, y - hh + hh * 0.09f, hh * 0.09f, accent)
                    c.drawLine(x, y - hh * 0.8f, x, y - hh * 0.45f, accent)
                    c.drawLine(x, y - hh * 0.45f, x + hh * 0.22f, y - hh * 0.3f, accent)
                    c.drawLine(x + hh * 0.22f, y - hh * 0.3f, x + hh * 0.02f, y - hh * 0.02f, accent)
                    c.drawText("✗", w * 0.68f, 30 * d, Paint(big).apply { color = Col.ACCENT })
                    c.drawText(Lang.tr(context, "rodillas recogidas = altura inflada"), w * 0.46f, floor + 22 * d, label)
                }
                4 -> {
                    // Grupo con voz
                    for (i in 0 until 4) person(c, w * (0.45f + i * 0.13f), floor, h * 0.5f, if (i == 0) accent else ink)
                    tripod(c, w * 0.15f, h * 0.45f, floor)
                    phone(c, w * 0.15f, h * 0.39f, 22 * d, 40 * d)
                    val bub = RectF(w * 0.06f, 12 * d, w * 0.62f, 58 * d)
                    c.drawRoundRect(bub, 14 * d, 14 * d, fillAcc)
                    c.drawText(Lang.tr(context, "«Ana, 41,8 centímetros»"), bub.left + 12 * d, bub.centerY() + 6 * d,
                        Paint(label).apply { color = 0xFFFFFFFF.toInt(); textSize = 15 * d; isFakeBoldText = true })
                }
                else -> {
                    // Copia de seguridad
                    phone(c, w * 0.3f, h * 0.5f, 60 * d, 110 * d)
                    val cloud = Path().apply {
                        val cx = w * 0.72f; val cy = h * 0.42f
                        addCircle(cx - 30 * d, cy + 10 * d, 26 * d, Path.Direction.CW)
                        addCircle(cx, cy - 8 * d, 34 * d, Path.Direction.CW)
                        addCircle(cx + 34 * d, cy + 10 * d, 24 * d, Path.Direction.CW)
                        addRect(cx - 30 * d, cy + 10 * d, cx + 34 * d, cy + 34 * d, Path.Direction.CW)
                    }
                    c.drawPath(cloud, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB9CCE4.toInt() })
                    c.drawLine(w * 0.42f, h * 0.5f, w * 0.6f, h * 0.5f, accent)
                    c.drawLine(w * 0.6f, h * 0.5f, w * 0.56f, h * 0.46f, accent)
                    c.drawLine(w * 0.6f, h * 0.5f, w * 0.56f, h * 0.54f, accent)
                }
            }
        }
    }
}
