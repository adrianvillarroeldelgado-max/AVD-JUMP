package com.salto240

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/** Revisión cuadro a cuadro y cálculo del salto. */
class AnalysisActivity : BaseActivity() {

    companion object {
        /** Marcas (índices de cuadro) ya detectadas por el modo en vivo. */
        const val EX_PRESET_TAKEOFF = "presetTakeoff"
        const val EX_PRESET_LANDING = "presetLanding"
        const val EX_PRESET_CONTACT = "presetContact"
        const val EX_PRESET_EVENTS = "presetEvents"
        const val EX_PRESET_SUB = "presetSub"
        const val EX_PRESET_EVENTS_SUB = "presetEventsSub"
        const val EX_PRESET_CONF = "presetConf"
        /** Video recibido con «Compartir»: tras elegir los fps se pide tocar el suelo; la copia se borra si no se guarda. */
        const val EX_SHARED = "sharedVideo"
        const val EX_PRESET_REASONS = "presetReasons"
        /** Id de un salto ya guardado que se vuelve a medir: al guardar se actualiza en lugar de crear otro. */
        const val EX_EDIT_ID = "editJumpId"
    }

    private var src: FrameSource? = null
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var wanted = 0

    private var capFps = 240
    private var fromRecording = false
    private var editId: Long = -1L
    private var battery = false
    private lateinit var test: TestType
    private var athlete = ""
    private var mass: Double? = null
    private var box: Double? = null
    private var load: Double? = null

    private var frame = 0
    private val marks = mutableMapOf<String, Int>()
    /** Marcas automáticas con fracción de cuadro: (cuadro entero, posición exacta). Valen mientras no se muevan. */
    private val autoSub = mutableMapOf<String, Pair<Int, Double>>()
    private var autoEvSub: List<Pair<Int, Double>> = emptyList()
    private fun pos(key: String): Double {
        val m = marks[key] ?: return Double.NaN
        return autoSub[key]?.takeIf { it.first == m }?.second ?: m.toDouble()
    }
    private fun evPos(i: Int): Double {
        val e = events[i]
        return autoEvSub.getOrNull(i)?.takeIf { it.first == e }?.second ?: e.toDouble()
    }
    private fun frames(v: Double) = if (abs(v - Math.round(v)) < 0.05) fmt("%d", Math.round(v)) else fmt("%.1f", v)
    /** Saltos repetidos: despegue, aterrizaje, despegue, aterrizaje… */
    private val events = mutableListOf<Int>()
    private var current: Jump? = null
    private var zoom = 1f

    private lateinit var img: ImageView
    private lateinit var hud: TextView
    private lateinit var seek: SeekBar
    private lateinit var fpsBtn: Button
    private lateinit var resultBig: TextView
    private lateinit var resultDetail: TextView
    private lateinit var notes: EditText
    private lateinit var saveBtn: Button
    private var rjBtn: Button? = null
    private val markButtons = mutableMapOf<String, Button>()
    private lateinit var floorLine: android.view.View
    private lateinit var banner: TextView
    private var pickingFloor = false
    @Volatile private var scanCancelled = false
    private val zoomButtons = mutableListOf<Button>()

    // Revisión asistida: zona de los pies (coordenadas normalizadas de la imagen) y cuadros vecinos ampliados.
    private var footU = 0.5f
    private var footV = -1f
    private lateinit var stripBox: LinearLayout
    private val stripImgs = mutableListOf<ImageView>()
    private val stripLabels = mutableListOf<TextView>()
    private var stripOn = true
    private var feetBtn: Button? = null
    // Resultado automático: confianza y marcas tal como las dejó la detección.
    private var autoConf: Int? = null
    private var autoReasons: List<String> = emptyList()
    private var autoMarks: Map<String, Int> = emptyMap()
    private var autoEvents: List<Int> = emptyList()
    private fun marksAreAuto(): Boolean = autoConf != null &&
        (if (isRj) events == autoEvents else markDefs.all { marks[it.first] == autoMarks[it.first] })

    private val isRj get() = test.isRepeated

    private val markDefs: List<Pair<String, String>>
        get() = when (test.base) {
            TestType.DJ -> listOf("contact" to "Contacto", "takeoff" to "Despegue", "landing" to "Aterrizaje")
            TestType.RJ -> emptyList()
            else -> listOf("takeoff" to "Despegue", "landing" to "Aterrizaje")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        test = TestType.of(intent.getStringExtra(MainActivity.EX_TEST))
        athlete = intent.getStringExtra(MainActivity.EX_ATHLETE) ?: "Sin nombre"
        mass = intent.getDoubleExtra(MainActivity.EX_MASS, 0.0).takeIf { it > 0 }
            ?: Store.athlete(this, athlete).massKg
        box = intent.getDoubleExtra(MainActivity.EX_BOX, 0.0).takeIf { it > 0 }
        load = if (test == TestType.SJ || test == TestType.CMJ) intent.getDoubleExtra(MainActivity.EX_LOAD, 0.0) else null
        val recFps = intent.getIntExtra(MainActivity.EX_FPS, 0)
        editId = intent.getLongExtra(EX_EDIT_ID, -1L)
        fromRecording = recFps > 0 && editId < 0
        battery = intent.getBooleanExtra(BatteryActivity.EX_BATTERY, false)
        val uri = Uri.parse(intent.getStringExtra(MainActivity.EX_URI) ?: run { finish(); return })

        val s = try { FrameSource(this, uri) } catch (e: Exception) {
            Tst.makeText(this, "No se pudo abrir el video: ${e.message}", Toast.LENGTH_LONG).show()
            finish(); return
        }
        if (s.frameCount <= 1) {
            Tst.makeText(this, "El video no indica su número de cuadros. Prueba con otro archivo.", Toast.LENGTH_LONG).show()
            s.release(); finish(); return
        }
        src = s
        capFps = if (recFps > 0) recFps else guessCaptureFps(s.fileFps)

        buildUi(s)
        show(0)
        compute()
        pickAfterFps = intent.getBooleanExtra(EX_SHARED, false)
        if (recFps <= 0) askFps(s.fileFps)
        // Grabado con la línea del suelo de la cámara: detección automática directa.
        val floorV = intent.getFloatExtra(CaptureActivity.EX_FLOOR_V, -1f)
        if (floorV in 0.05f..0.98f) footV = floorV
        val preset = presetResult()
        if (fromRecording && floorV in 0.05f..0.98f) {
            img.post {
                imageToViewY(floorV)?.let { floorLine.translationY = it; floorLine.visibility = android.view.View.VISIBLE }
                // Modo en vivo: las marcas ya vienen calculadas a 240 fps.
                if (preset != null) applyAuto(preset) else runAuto(0.5f, floorV, 0.44f)
            }
        } else if (preset != null) {
            img.post { applyAuto(preset) }
        }
    }

    /** Marcas calculadas por el modo en vivo de la cámara, si las hay. */
    private fun presetResult(): AutoDetect.Result? {
        val ev = intent.getIntArrayExtra(EX_PRESET_EVENTS)?.toList() ?: emptyList()
        val to = intent.getIntExtra(EX_PRESET_TAKEOFF, -1).takeIf { it >= 0 }
        val ld = intent.getIntExtra(EX_PRESET_LANDING, -1).takeIf { it >= 0 }
        val c = intent.getIntExtra(EX_PRESET_CONTACT, -1).takeIf { it >= 0 }
        if (ev.isEmpty() && (to == null || ld == null)) return null
        val sub = intent.getDoubleArrayExtra(EX_PRESET_SUB)
        fun sv(k: Int) = sub?.getOrNull(k)?.takeIf { !it.isNaN() }
        val evSub = intent.getDoubleArrayExtra(EX_PRESET_EVENTS_SUB)?.toList() ?: emptyList()
        return AutoDetect.Result(takeoff = to, landing = ld, contact = c, events = ev,
            takeoffSub = sv(0), landingSub = sv(1), contactSub = sv(2), eventsSub = evSub,
            confidence = intent.getIntExtra(EX_PRESET_CONF, -1).takeIf { it >= 0 },
            reasons = intent.getStringArrayExtra(EX_PRESET_REASONS)?.toList() ?: emptyList(),
            error = if (test.isDrop && c == null && ev.isEmpty()) "No se detectó el contacto inicial tras la caída; márcalo a mano." else null)
    }

    /** Si el archivo ya trae muchos cuadros por segundo, ese es el fps de captura; si no, se asume cámara lenta exportada. */
    private fun guessCaptureFps(fileFps: Double): Int {
        val common = listOf(30, 60, 120, 240, 480, 960)
        if (fileFps < 90) return 240
        return common.minByOrNull { abs(it - fileFps) } ?: 240
    }

    private fun title(): String {
        val l = load?.takeIf { it > 0 }?.let { String.format(Locale.getDefault(), " +%.1f kg", it) } ?: ""
        return (if (battery) "Perfil F-V · " else "") + "${test.short}$l · $athlete"
    }

    private fun buildUi(s: FrameSource) {
        val root = vbox(8).apply {
            setBackgroundColor(Col.BG)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        // Barra superior
        val top = hbox(8)
        top.addView(text(title(), 18f, Col.INK, true), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(button("Auto", true) { startFloorPick() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        fpsBtn = button("$capFps fps") { askFps(s.fileFps) }
        top.addView(fpsBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(top, lp())

        // Visor
        val viewer = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            clipChildren = true
        }
        img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        viewer.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        hud = text("", 13f, 0xFFFFFFFF.toInt()).apply {
            typeface = Typeface.MONOSPACE
            setBackgroundColor(0xB30A0E16.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        viewer.addView(hud, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(8), dp(8), 0, 0)
        })
        val zoomRow = hbox(4)
        listOf(1f, 2f, 3f).forEach { z ->
            val b = Button(this).apply {
                text = "${z.toInt()}×"
                isAllCaps = false
                setTextColor(0xFFFFFFFF.toInt())
                minWidth = dp(44); minimumWidth = dp(44)
                minHeight = dp(36); minimumHeight = dp(36)
                stateListAnimator = null
                setOnClickListener { setZoom(z) }
            }
            zoomButtons.add(b)
            zoomRow.addView(b, LinearLayout.LayoutParams(dp(48), dp(40)))
        }
        feetBtn = Button(this).apply {
            text = "Pies"
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            minWidth = dp(52); minimumWidth = dp(52)
            minHeight = dp(36); minimumHeight = dp(36)
            stateListAnimator = null
            background = rounded(0xB30A0E16.toInt(), 0x55FFFFFF, 4)
            setOnClickListener { focusFeet() }
        }
        zoomRow.addView(feetBtn, LinearLayout.LayoutParams(dp(60), dp(40)))
        viewer.addView(zoomRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(8), dp(8))
        })
        floorLine = android.view.View(this).apply { setBackgroundColor(Col.ACCENT); visibility = android.view.View.GONE }
        viewer.addView(floorLine, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(2), Gravity.TOP))
        banner = text("", 13f, 0xFFFFFFFF.toInt()).apply {
            setBackgroundColor(0xE6D9481F.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = android.view.View.GONE
        }
        viewer.addView(banner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(8), 0, dp(8), dp(56))
        })
        // Toque en el visor: elige la línea del suelo (modo Auto) o centra el zoom en los pies.
        viewer.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                if (pickingFloor) {
                    toImage(e.x, e.y)?.let { (u, vv) ->
                        pickingFloor = false
                        banner.visibility = android.view.View.GONE
                        floorLine.translationY = e.y
                        floorLine.visibility = android.view.View.VISIBLE
                        runAuto(u, vv)
                    }
                } else if (zoom > 1f) {
                    img.pivotX = e.x; img.pivotY = e.y
                } else {
                    // Con 1× un toque indica dónde están los pies: ahí se centran las ampliaciones.
                    toImage(e.x, e.y)?.let { (u, vv) -> footU = u; footV = vv; refreshStrip() }
                }
                v.performClick()
            }
            true
        }
        root.addView(viewer, lp(h = 0, weight = 1f))
        setZoom(1f)

        // Cuadros anterior, actual y siguiente, ampliados en los pies: se ve el instante exacto de despegue y aterrizaje.
        stripBox = hbox(4).apply { visibility = android.view.View.GONE }
        for (k in 0..2) {
            val cell = vbox(0)
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFF000000.toInt())
                setOnClickListener { show(frame + k - 1) }
            }
            stripImgs.add(iv)
            cell.addView(iv, lp(h = dp(78)))
            val lab = text("", 11f, if (k == 1) Col.INK else Col.MUTED, k == 1).apply { gravity = Gravity.CENTER }
            stripLabels.add(lab)
            cell.addView(lab, lp())
            stripBox.addView(cell, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(stripBox, lp())

        // Navegación
        seek = SeekBar(this).apply {
            max = s.frameCount - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) show(p) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        root.addView(seek, lp())

        val steps = hbox(6)
        listOf(-10, -1, 1, 10).forEach { d ->
            steps.addView(button(if (d > 0) "+$d" else "−${-d}") { show(frame + d) }.apply {
                typeface = Typeface.MONOSPACE
            }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(steps, lp())

        // Saltar directo a cada marca, con los pies ampliados.
        val goRow = hbox(6)
        if (isRj) {
            goRow.addView(button("◀ Marca anterior") { events.lastOrNull { it < frame }?.let { goTo(it) } }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            goRow.addView(button("Marca siguiente ▶") { events.firstOrNull { it > frame }?.let { goTo(it) } }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            for ((key, name) in markDefs) {
                goRow.addView(button("Ir a ${name.lowercase()}") {
                    val m = marks[key]
                    if (m == null) Tst.makeText(this, "Aún no hay marca de ${name.lowercase()}.", Toast.LENGTH_SHORT).show() else goTo(m)
                }.apply { textSize = 12f }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        root.addView(goRow, lp())

        // Marcas
        val markRow = hbox(6)
        if (isRj) {
            val undo = button("Deshacer") {
                if (events.isNotEmpty()) { events.removeAt(events.size - 1); refreshMarks(); compute() }
            }
            val mark = button("", true) {
                val last = events.lastOrNull()
                if (last != null && frame <= last) {
                    Tst.makeText(this, "Avanza el video: cada marca debe ir después de la anterior.", Toast.LENGTH_SHORT).show()
                } else {
                    events.add(frame); refreshMarks(); compute()
                }
            }
            mark.setOnLongClickListener { events.lastOrNull()?.let { show(it) }; true }
            rjBtn = mark
            markRow.addView(undo, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            markRow.addView(mark, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        } else {
            for ((key, name) in markDefs) {
                val b = button(name) {
                    marks[key] = frame
                    refreshMarks(); compute()
                }
                b.setOnLongClickListener { marks[key]?.let { show(it) }; true }
                markButtons[key] = b
                markRow.addView(b, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        root.addView(markRow, lp())
        root.addView(text(
            (if (isRj) "Marca en orden: despegue, aterrizaje, despegue… · mantén pulsado para volver a la última marca · volumen ± = 1 cuadro"
            else "Toca para marcar el cuadro actual · mantén pulsado para volver a él · volumen ± = 1 cuadro") +
            "\nDespegue: primer cuadro con los pies en el aire. Aterrizaje: primer cuadro en que el pie toca el suelo. Contacto (Drop Jump): primer cuadro en que toca el suelo tras caer.",
            11f, Col.MUTED))

        // Resultado
        val res = panel()
        resultBig = text("—", 40f, Col.ACCENT, true)
        res.addView(resultBig)
        resultDetail = text("", 13f, Col.INK).apply { typeface = Typeface.MONOSPACE }
        res.addView(resultDetail)
        val saveRow = hbox(8)
        notes = input("Nota (opcional)")
        saveRow.addView(notes, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        saveBtn = button(if (editId >= 0) "Actualizar" else "Guardar", true) { save() }
        saveRow.addView(saveBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        res.addView(saveRow, lp())
        val resScroll = ScrollView(this).apply { addView(res) }
        root.addView(resScroll, lp(h = dp(if (isRj) 230 else 190)))

        refreshMarks()
        setContentView(root)
    }

    private fun setZoom(z: Float) {
        zoom = z
        img.scaleX = z; img.scaleY = z
        if (z == 1f) { img.pivotX = img.width / 2f; img.pivotY = img.height / 2f }
        zoomButtons.forEachIndexed { i, b ->
            val on = (i + 1).toFloat() == z
            b.background = rounded(if (on) Col.ACCENT else 0xB30A0E16.toInt(), 0x55FFFFFF, 4)
        }
    }

    private fun show(i: Int) {
        val s = src ?: return
        frame = i.coerceIn(0, s.frameCount - 1)
        seek.progress = frame
        hud.text = String.format(Locale.getDefault(), "cuadro %d · %.3f s", frame, frame.toDouble() / capFps) + markAt(frame)
        wanted = frame
        val req = frame
        exec.execute {
            if (wanted != req) return@execute
            val b = s.frame(req)
            main.post {
                if (wanted == req && b != null && !b.isRecycled) img.setImageBitmap(b)
            }
            if (wanted == req) buildStrip(s, req)
            // Precarga el bloque siguiente para avanzar sin espera.
            if (wanted == req) s.frame(req + 12)
        }
    }

    /** Nombre de la marca que cae en el cuadro [i], para el visor. */
    private fun markAt(i: Int): String {
        if (isRj) {
            val k = events.indexOf(i)
            return if (k < 0) "" else (if (k % 2 == 0) " · DESPEGUE ${k / 2 + 1}" else " · ATERRIZAJE ${k / 2 + 1}")
        }
        val names = markDefs.filter { marks[it.first] == i }.map { it.second.uppercase() }
        return if (names.isEmpty()) "" else " · " + names.joinToString(" / ")
    }

    private fun goTo(i: Int) {
        show(i)
        if (footV > 0f && zoom == 1f) focusFeet()
    }

    /** Amplía 3× centrado en los pies (la línea del suelo y el punto tocado o el centro). */
    private fun focusFeet() {
        if (footV <= 0f) {
            Tst.makeText(this, "Toca los pies en el video (con 1×) o pulsa Auto para indicar el suelo.", Toast.LENGTH_LONG).show()
            return
        }
        val d = img.drawable ?: return
        val m = FloatArray(9)
        img.imageMatrix.getValues(m)
        val x = m[android.graphics.Matrix.MTRANS_X] + footU * d.intrinsicWidth * m[android.graphics.Matrix.MSCALE_X]
        val y = m[android.graphics.Matrix.MTRANS_Y] + footV * d.intrinsicHeight * m[android.graphics.Matrix.MSCALE_Y]
        setZoom(if (zoom >= 3f) 1f else 3f)
        if (zoom > 1f) { img.pivotX = x; img.pivotY = y }
    }

    private fun refreshStrip() {
        val s = src ?: return
        val req = frame
        exec.execute { if (wanted == req) buildStrip(s, req) }
    }

    /** Recorta la zona de los pies de los cuadros i−1, i, i+1 y dibuja la línea del suelo. Corre en el hilo de fondo. */
    private fun buildStrip(s: FrameSource, i: Int) {
        if (!stripOn || footV <= 0f) { main.post { stripBox.visibility = android.view.View.GONE }; return }
        val out = (-1..1).map { d ->
            val b = s.frame(i + d) ?: return@map null
            if (b.isRecycled) return@map null
            val cw = (b.width * 0.34f).toInt().coerceAtLeast(16)
            val ch = (cw * 0.55f).toInt().coerceAtMost(b.height)
            val cx = (footU * b.width).toInt()
            val fy = (footV * b.height).toInt()
            val x0 = (cx - cw / 2).coerceIn(0, b.width - cw)
            val y0 = (fy - (ch * 0.7f).toInt()).coerceIn(0, b.height - ch)
            val bmp = android.graphics.Bitmap.createBitmap(cw, ch, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.drawBitmap(b, android.graphics.Rect(x0, y0, x0 + cw, y0 + ch), android.graphics.Rect(0, 0, cw, ch), null)
            val paint = android.graphics.Paint().apply { color = Col.ACCENT; strokeWidth = (ch / 60f).coerceAtLeast(1.5f); alpha = 200 }
            c.drawLine(0f, (fy - y0).toFloat(), cw.toFloat(), (fy - y0).toFloat(), paint)
            bmp
        }
        main.post {
            if (wanted != i) return@post
            stripBox.visibility = android.view.View.VISIBLE
            for (k in 0..2) {
                out[k]?.let { stripImgs[k].setImageBitmap(it) } ?: stripImgs[k].setImageDrawable(null)
                val f = i + k - 1
                stripLabels[k].text = (if (k == 1) "#$f" else if (k == 0) "◀ #$f" else "#$f ▶") + markAt(f).replace(" · ", " ")
            }
        }
    }

    private fun refreshMarks() {
        rjBtn?.let { b ->
            val n = events.size / 2 + 1
            b.text = if (events.size % 2 == 0) "Despegue $n" else "Aterrizaje $n"
        }
        for ((key, name) in markDefs) {
            val b = markButtons[key] ?: continue
            val v = marks[key]
            b.text = if (v == null) "$name\n—" else "$name\n#$v"
            b.background = rounded(if (v == null) Col.SURFACE else 0xFFE3F1E9.toInt(), if (v == null) Col.LINE else Col.OK, 8)
        }
    }

    private fun askFps(fileFps: Double) {
        val opts = intArrayOf(30, 60, 120, 240, 480, 960)
        val labels = opts.map { "$it fps" }.toTypedArray()
        val sel = opts.indexOf(capFps).coerceAtLeast(0)
        val msg = if (fileFps > 0) String.format(Locale.getDefault(), "Archivo: %.0f cuadros/s. ", fileFps) else ""
        Dlg(this)
            .setTitle(msg + "¿A cuántos fps se grabó?")
            .setSingleChoiceItems(labels, sel) { d, which ->
                capFps = opts[which]
                fpsBtn.text = "$capFps fps"
                show(frame); compute()
                d.dismiss()
                if (pickAfterFps) { pickAfterFps = false; startFloorPick() }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private var pickAfterFps = false

    private fun stat(label: String, value: String) = String.format("%-18s %s\n", label, value)
    private fun fmt(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    private fun fail(msg: String) {
        resultBig.text = "—"
        resultDetail.text = msg
    }

    private fun compute() {
        current = null
        saveBtn.isEnabled = false
        if (isRj) { computeRj(); return }

        val need = markDefs.map { it.first }
        if (!need.all { marks.containsKey(it) }) {
            fail(if (test.isDrop) "Marca contacto inicial, despegue y aterrizaje." else "Marca el despegue y el aterrizaje.")
            return
        }
        val takeoff = pos("takeoff")
        val landing = pos("landing")
        val flightFrames = landing - takeoff
        if (flightFrames <= 0) { fail("El aterrizaje debe estar después del despegue."); return }
        val tv = flightFrames / capFps
        val h = Calc.heightCm(tv)
        val sb = StringBuilder()
        sb.append(stat("Tiempo de vuelo", fmt("%.0f ms (%s cuadros)", tv * 1000, frames(flightFrames))))
        sb.append(stat("Vel. despegue", fmt("%.2f m/s", Calc.takeoffVelocity(tv))))

        var tcMs: Double? = null
        var rsi: Double? = null
        if (test.isDrop) {
            val contactFrames = takeoff - pos("contact")
            if (contactFrames <= 0) { fail("El despegue debe estar después del contacto inicial."); return }
            val tc = contactFrames / capFps
            tcMs = tc * 1000
            rsi = Calc.rsi(h, tc)
            sb.append(stat("Tiempo contacto", fmt("%.0f ms (%s cuadros)", tcMs, frames(contactFrames))))
            sb.append(stat("RSI", fmt("%.2f", rsi)))
            if (tc > 0.25) sb.append("Contacto > 250 ms: rebote lento.\n")
        }
        var power: Double? = null
        val m = mass
        if (m != null) {
            val p = Calc.sayersPower(h, m)
            power = p
            if ((load ?: 0.0) == 0.0)
                sb.append(stat("Potencia (Sayers)", fmt("%.0f W · %.1f W/kg", p, p / m)))
        }
        // SJ o CMJ (con o sin carga): fuerza y velocidad del método de Samozino
        if (test == TestType.SJ || (test == TestType.CMJ && (load ?: 0.0) > 0)) {
            val a = Store.athlete(this, athlete)
            val hpo = a.hpo
            if (m != null && hpo != null) {
                val f = Calc.forceRel(h, hpo, m, load ?: 0.0)
                val v = Calc.meanVelocity(h)
                sb.append(stat("Fuerza media", fmt("%.1f N/kg", f)))
                sb.append(stat("Velocidad media", fmt("%.2f m/s", v)))
                sb.append(stat("Potencia media", fmt("%.1f W/kg", f * v)))
            } else {
                sb.append("Completa masa y medidas de pierna en Perfil F-V para ver fuerza y velocidad.\n")
            }
        }
        sb.append(fmt("±1 cuadro = ±%.1f ms", 1000.0 / capFps))
        sb.append(confNote())

        resultBig.text = fmt("%.1f cm", h)
        resultDetail.text = sb.toString()
        current = Jump(
            id = System.currentTimeMillis(),
            date = Store.today(),
            athlete = athlete,
            test = test,
            flightMs = tv * 1000,
            heightCm = (h * 10).roundToInt() / 10.0,
            contactMs = tcMs,
            rsi = rsi,
            boxCm = if (test.isDrop) box else null,
            massKg = mass,
            powerW = power,
            fps = capFps,
            notes = "",
            loadKg = load
        )
        saveBtn.isEnabled = true
    }

    /** Saltos repetidos: alturas, contactos, RSI por salto y caída de rendimiento. */
    private fun computeRj() {
        val n = events.size / 2
        if (n < 1) {
            fail("Marca el despegue y el aterrizaje de cada salto, en orden.\nMarcas: ${events.size}")
            return
        }
        val heights = mutableListOf<Double>()
        val flights = mutableListOf<Double>()
        val contacts = mutableListOf<Double>()   // contacto previo a cada salto (desde el 2.º)
        val rsis = mutableListOf<Double>()
        val sb = StringBuilder()
        sb.append(String.format("%-3s %7s %7s %7s %5s\n", "#", "cm", "TV ms", "TC ms", "RSI"))
        for (i in 0 until n) {
            val to = evPos(2 * i)
            val ld = evPos(2 * i + 1)
            val tv = (ld - to) / capFps
            val h = Calc.heightCm(tv)
            heights.add(h); flights.add(tv * 1000)
            var tcTxt = "—"; var rsiTxt = "—"
            if (i > 0) {
                val tc = (to - evPos(2 * i - 1)) / capFps
                contacts.add(tc * 1000)
                val r = Calc.rsi(h, tc)
                rsis.add(r)
                tcTxt = fmt("%.0f", tc * 1000); rsiTxt = fmt("%.2f", r)
            }
            sb.append(String.format("%-3d %7s %7s %7s %5s\n", i + 1, fmt("%.1f", h), fmt("%.0f", tv * 1000), tcTxt, rsiTxt))
        }
        val meanH = heights.average()
        val fatigue = Calc.fatigue(heights)
        sb.append('\n')
        sb.append(stat("Saltos", n.toString()))
        sb.append(stat("Altura media", fmt("%.1f cm", meanH)))
        sb.append(stat("Mejor salto", fmt("%.1f cm", heights.maxOrNull() ?: 0.0)))
        if (contacts.isNotEmpty()) {
            sb.append(stat("Contacto medio", fmt("%.0f ms", contacts.average())))
            sb.append(stat("RSI medio", fmt("%.2f", rsis.average())))
            if (test == TestType.RJ105 && rsis.size >= 5)
                sb.append(stat("RSI 10/5", fmt("%.2f (media de los 5 mejores)", rsis.sortedDescending().take(5).average())))
        }
        fatigue?.let { sb.append(stat("Caída de altura", fmt("%.1f %% (3 primeros vs 3 últimos)", it))) }
        val totalS = (events.last() - events.first()).toDouble() / capFps
        sb.append(stat("Duración", fmt("%.2f s", totalS)))
        if (events.size % 2 == 1) sb.append("Falta el aterrizaje del último salto.\n")
        sb.append(confNote().trimStart())

        resultBig.text = fmt("%.1f cm", meanH)
        resultDetail.text = sb.toString()
        current = Jump(
            id = System.currentTimeMillis(),
            date = Store.today(),
            athlete = athlete,
            test = test,
            flightMs = flights.average(),
            heightCm = (meanH * 10).roundToInt() / 10.0,
            contactMs = if (contacts.isEmpty()) null else contacts.average(),
            rsi = when {
                rsis.isEmpty() -> null
                test == TestType.RJ105 -> rsis.sortedDescending().take(5).average()
                else -> rsis.average()
            },
            boxCm = null,
            massKg = mass,
            powerW = null,
            fps = capFps,
            notes = "",
            reps = heights.map { (it * 10).roundToInt() / 10.0 },
            repContacts = contacts.map { it.roundToInt().toDouble() },
            fatiguePct = fatigue
        )
        saveBtn.isEnabled = true
    }

    private fun confNote(): String = when {
        autoConf == null -> ""
        marksAreAuto() -> "\n" + (Confidence.line(autoConf, autoReasons) ?: "")
        else -> "\nMarcas corregidas a mano"
    }

    /** Texto de asimetría si la otra pierna ya se midió hoy. */
    private fun asymmetryNote(saved: Jump): String? = JumpCalc.asymmetryNote(this, saved)

    /** Cuadros marcados, en el orden que guarda Jump.marks. */
    private fun currentMarks(): List<Int> = when {
        isRj -> events.toList()
        test.isDrop -> listOfNotNull(marks["contact"], marks["takeoff"], marks["landing"]).takeIf { it.size == 3 } ?: emptyList()
        else -> listOfNotNull(marks["takeoff"], marks["landing"]).takeIf { it.size == 2 } ?: emptyList()
    }

    private fun save() {
        val j = current ?: return
        val videoUri = intent.getStringExtra(MainActivity.EX_URI)
        if (editId >= 0) {
            // Nueva medición de un salto guardado: se conserva su fecha, atleta y video.
            val old = Store.byId(this, editId)
            if (old != null) {
                val upd = j.copy(id = old.id, date = old.date, athlete = old.athlete,
                    notes = notes.text.toString().trim().ifEmpty { old.notes },
                    video = old.video, marks = currentMarks(), method = old.method ?: "video",
                    confidence = null, flags = old.flags.filter { it != Confidence.FLAG_LOW },
                    tttMs = old.tttMs, rsiMod = old.tttMs?.takeIf { it > 0 }?.let { j.heightCm / 100.0 / (it / 1000.0) }, extra = old.extra - "hRaw" - "cal" + ("revisado" to 1.0))
                Store.update(this, upd)
                saveBtn.isEnabled = false
                Dlg(this)
                    .setTitle(fmt("Medición actualizada: %.1f cm", upd.heightCm))
                    .setMessage(fmt("Antes: %.1f cm (%.0f ms de vuelo)\nAhora: %.1f cm (%.0f ms de vuelo)",
                        old.heightCm, old.flightMs, upd.heightCm, upd.flightMs))
                    .setPositiveButton("Cerrar") { _, _ -> finish() }
                    .show()
                return
            }
        }
        val saved = j.copy(notes = notes.text.toString().trim(),
            video = Videos.forJump(this, videoUri), marks = currentMarks(),
            method = if (intent.getIntExtra(MainActivity.EX_FPS, 0) > 0 && editId < 0) "240" else "video",
            confidence = if (marksAreAuto()) autoConf else null,
            flags = if (marksAreAuto() && Confidence.level(autoConf) == 0) listOf(Confidence.FLAG_LOW) else emptyList(),
            extra = if (autoConf != null && !marksAreAuto()) mapOf("revisado" to 1.0) else emptyMap())
        Store.add(this, saved)
        saveBtn.isEnabled = false
        if (battery) { batterySaved(saved); return }
        var msg = "${saved.test.label} de ${saved.athlete}"
        asymmetryNote(saved)?.let { msg += "\n\n$it" }
        Attempts.afterSave(this, saved)?.let { msg += "\n\n$it" }
        val b = Dlg(this)
            .setTitle(fmt("Guardado: %.1f cm", saved.heightCm))
            .setMessage(msg)
            .setNeutralButton("Historial") { _, _ ->
                startActivity(Intent(this, HistoryActivity::class.java)); finish()
            }
            .setNegativeButton("Cerrar") { _, _ -> finish() }
        if (fromRecording) {
            b.setPositiveButton("Otro salto") { _, _ ->
                startActivity(MainActivity.forward(intent, Intent(this, CaptureActivity::class.java)))
                finish()
            }
        }
        b.show()
    }

    /** Batería F-V: tras guardar, ofrece la siguiente carga o el perfil. */
    private fun batterySaved(saved: Jump) {
        val next = BatteryActivity.nextPending(this, athlete)
        val loadTxt = fmt("%.1f kg", saved.loadKg ?: 0.0)
        val b = Dlg(this)
            .setTitle(fmt("Carga %s: %.1f cm", loadTxt, saved.heightCm))
            .setNegativeButton("Volver a la batería") { _, _ -> finish() }
            .setNeutralButton("Repetir esta carga") { _, _ ->
                if (fromRecording) startActivity(MainActivity.forward(intent, Intent(this, CaptureActivity::class.java)))
                finish()
            }
        if (next != null) {
            val nl = BatteryActivity.loads(this, Store.athlete(this, athlete))[next]
            b.setMessage(fmt("Siguiente: carga %d de %d, %.1f kg.", next + 1, BatteryActivity.SLOTS, nl))
            b.setPositiveButton("Siguiente carga") { _, _ ->
                startActivity(BatteryActivity.captureIntent(this, athlete, next)); finish()
            }
        } else {
            b.setMessage("Batería completa: las 4 cargas tienen salto hoy.")
            b.setPositiveButton("Ver perfil") { _, _ ->
                startActivity(Intent(this, FvProfileActivity::class.java).putExtra(MainActivity.EX_ATHLETE, athlete)); finish()
            }
        }
        b.show()
    }

    // ------------------------------------------------------------------
    // Detección automática
    // ------------------------------------------------------------------

    private fun startFloorPick() {
        setZoom(1f)
        pickingFloor = true
        banner.text = "Toca el suelo justo donde apoya la suela del atleta. Se analizará esa franja en todos los cuadros."
        banner.visibility = android.view.View.VISIBLE
        // Un cuadro con los pies apoyados ayuda a apuntar: el primero (o el contacto en Drop Jump).
    }

    /** Convierte un toque del visor (zoom 1×) a coordenadas normalizadas de la imagen. */
    private fun toImage(x: Float, y: Float): Pair<Float, Float>? {
        val d = img.drawable ?: return null
        val m = FloatArray(9)
        img.imageMatrix.getValues(m)
        val w = d.intrinsicWidth * m[android.graphics.Matrix.MSCALE_X]
        val h = d.intrinsicHeight * m[android.graphics.Matrix.MSCALE_Y]
        val u = (x - m[android.graphics.Matrix.MTRANS_X]) / w
        val v = (y - m[android.graphics.Matrix.MTRANS_Y]) / h
        return if (u in 0f..1f && v in 0f..1f) u to v else null
    }

    private fun imageToViewY(v: Float): Float? {
        val d = img.drawable ?: return null
        val m = FloatArray(9)
        img.imageMatrix.getValues(m)
        return m[android.graphics.Matrix.MTRANS_Y] + v * d.intrinsicHeight * m[android.graphics.Matrix.MSCALE_Y]
    }

    /** Analiza la franja sobre el suelo en todos los cuadros y coloca las marcas. */
    private fun runAuto(u: Float, floorV: Float, halfWidth: Float = 0.36f) {
        val s = src ?: return
        // Banda alrededor de la línea: tolera que quede un poco alta o baja (ver AutoDetect).
        val y0 = AutoDetect.bandTop(floorV)
        val y1 = AutoDetect.bandBottom(floorV)
        if (footV <= 0f || halfWidth < 0.4f) { footU = u }
        footV = floorV
        val x0 = (u - halfWidth).coerceAtLeast(0f)
        val x1 = (u + halfWidth).coerceAtMost(1f)
        scanCancelled = false
        val status = text("Analizando 0 %", 15f, Col.INK).apply { setPadding(dp(24), dp(16), dp(24), dp(8)) }
        val dlg = Dlg(this)
            .setTitle("Detección automática")
            .setView(status)
            .setNegativeButton("Cancelar") { _, _ -> scanCancelled = true }
            .setCancelable(false)
            .show()
        val fps = capFps
        val t = test
        exec.execute {
            val strips = s.scanStrip(x0, x1, y0, y1,
                progress = { p -> main.post { status.text = "Analizando $p %" } },
                cancelled = { scanCancelled })
            val res = strips?.let { AutoDetect.detect(it, fps, t) }
            main.post {
                try { dlg.dismiss() } catch (_: Exception) {}
                if (res != null) applyAuto(res)
            }
        }
    }

    private fun applyAuto(r: AutoDetect.Result) {
        autoConf = r.confidence; autoReasons = r.reasons
        var placed = false
        autoSub.clear(); autoEvSub = emptyList()
        if (isRj && r.events.isNotEmpty()) {
            events.clear(); events.addAll(r.events); placed = true
            if (r.eventsSub.size == r.events.size) autoEvSub = r.events.zip(r.eventsSub)
        } else if (r.takeoff != null && r.landing != null) {
            marks["takeoff"] = r.takeoff
            marks["landing"] = r.landing
            r.takeoffSub?.let { autoSub["takeoff"] = r.takeoff to it }
            r.landingSub?.let { autoSub["landing"] = r.landing to it }
            if (r.contact != null && r.contactSub != null) autoSub["contact"] = r.contact to r.contactSub
            if (test.isDrop) {
                if (r.contact != null) marks["contact"] = r.contact else marks.remove("contact")
            }
            placed = true
        }
        autoMarks = marks.toMap(); autoEvents = events.toList()
        if (!placed) autoConf = null
        refreshMarks(); compute()
        if (placed) {
            goTo(if (isRj) events.first() else (if (test.isDrop) marks["contact"] else null) ?: r.takeoff ?: 0)
            val extra = (r.error?.let { "\n$it" } ?: "") +
                (Confidence.line(r.confidence, r.reasons)?.let { "\n$it" } ?: "")
            Tst.makeText(this, "Marcas automáticas listas. Revisa con ±1 y corrige si hace falta.$extra", Toast.LENGTH_LONG).show()
        } else {
            Dlg(this)
                .setTitle("No se pudo detectar")
                .setMessage((r.error ?: "Sin resultado.") + "\n\nPuedes pulsar Auto y tocar otra zona del suelo, o marcar a mano.")
                .setPositiveButton("Aceptar", null)
                .show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { show(frame + 1); true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { show(frame - 1); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        // Copia de un video compartido: se borra si ningún salto la usa.
        if (intent.getBooleanExtra(EX_SHARED, false)) Videos.deleteIfUnused(this, intent.getStringExtra(MainActivity.EX_URI))
        scanCancelled = true
        exec.shutdownNow()
        src?.release()
        super.onDestroy()
    }
}
