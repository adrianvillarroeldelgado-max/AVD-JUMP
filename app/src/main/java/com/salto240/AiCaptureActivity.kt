package com.salto240

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Medición con IA en vivo, como My Jump Lab: cámara normal (30–60 fps), un modelo de pose sigue el cuerpo
 * y los pies del atleta, y cada salto aparece en pantalla al aterrizar, sin detener la cámara.
 * No hace falta cámara lenta ni línea del suelo. Todo se procesa en el teléfono.
 */
class AiCaptureActivity : BaseActivity() {

    override val darkSystemBars = true

    private data class Setup(val id: String, val preview: Size, val analysis: Size, val fps: Range<Int>, val orientation: Int)

    private lateinit var test: TestType
    private var athlete = ""
    private var battery = false
    private var mass: Double? = null
    private var box: Double? = null
    private var load: Double? = null
    private var stature: Double? = null

    private var setup: Setup? = null
    private lateinit var texture: AutoFitTextureView
    private lateinit var overlay: PoseOverlay
    private lateinit var status: TextView
    private lateinit var bigResult: TextView
    private lateinit var detail: TextView
    private lateinit var fpsText: TextView
    private lateinit var chips: LinearLayout
    private lateinit var saveBtn: Button
    private lateinit var undoBtn: Button

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private var askedPermission = false

    private var pose: PoseDetector? = null
    @Volatile private var busy = false
    private lateinit var detector: PoseJumpDetector
    private val frameTimes = ArrayDeque<Long>()

    /** Saltos de esta sesión (o series en saltos repetidos); kept = se guardará. */
    private class Item(val reps: List<PoseJumpDetector.Rep>, val flags: List<String> = emptyList(), var kept: Boolean = true)
    /** Ángulo de rodilla y posición de manos de los últimos segundos (control de técnica). */
    private val techFrames = ArrayDeque<Technique.Frame>()
    private var techOn = true
    private val items = ArrayList<Item>()

    private fun fmt(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    // Sesión de grupo, voz, nivel y control remoto
    private var groupMode = false
    private lateinit var rootFrame: FrameLayout
    private lateinit var topText: TextView
    private lateinit var level: LevelView
    private var autoSave: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        test = TestType.of(intent.getStringExtra(MainActivity.EX_TEST))
        groupMode = intent.getBooleanExtra(GroupSession.EX_SESSION, false) && GroupSession.get(this)?.finished == false
        athlete = (if (groupMode) GroupSession.get(this)?.current?.first else null)
            ?: intent.getStringExtra(MainActivity.EX_ATHLETE) ?: "Sin nombre"
        battery = intent.getBooleanExtra(BatteryActivity.EX_BATTERY, false)
        mass = intent.getDoubleExtra(MainActivity.EX_MASS, 0.0).takeIf { it > 0 } ?: Store.athlete(this, athlete).massKg
        box = intent.getDoubleExtra(MainActivity.EX_BOX, 0.0).takeIf { it > 0 }
        load = if (test == TestType.SJ || test == TestType.CMJ) intent.getDoubleExtra(MainActivity.EX_LOAD, 0.0) else null
        stature = Clients.byName(this, athlete)?.heightCm
        techOn = Technique.enabled(this)
        detector = PoseJumpDetector(test, stature, box)

        val s = chooseSetup()
        if (s == null) {
            Tst.makeText(this, "No se encontró una cámara trasera.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        setup = s
        buildUi(s)

        pose = PoseDetection.getClient(
            PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
        )
        if (stature == null && !groupMode) askStature()
        Voice.init(this)
        if (groupMode) GroupSession.get(this)?.let { Voice.say(this, GroupSession.announce(it)) }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
    }

    // ------------------------------------------------------------------
    // Interfaz
    // ------------------------------------------------------------------

    private fun buildUi(s: Setup) {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        rootFrame = root
        texture = AutoFitTextureView(this)
        texture.setAspect(s.preview.height, s.preview.width)
        root.addView(texture, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        overlay = PoseOverlay(this, texture)
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Barra superior
        val top = text(topLabel(), 14f, 0xFFFFFFFF.toInt(), true).apply {
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        topText = top
        level = LevelView(this)
        root.addView(level, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(210)
        })
        root.addView(top, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(12), dp(12), dp(124), 0)
        })
        root.addView(button("Protocolo") {
            startActivity(Intent(this, ProtocolActivity::class.java)
                .putExtra(ProtocolActivity.EX_FOCUS, if (battery) "FV_BATTERY" else test.name)
                .putExtra(ProtocolActivity.EX_AI, true))
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(dp(12), dp(12), dp(12), 0)
        })

        // Estado y resultado grande
        val mid = vbox(4).apply { gravity = Gravity.CENTER_HORIZONTAL }
        status = text("Iniciando la cámara…", 18f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xAA000000.toInt(), 0, 20)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        bigResult = text("", 64f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 0f, 0xFF000000.toInt())
            visibility = View.GONE
        }
        mid.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        mid.addView(bigResult, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(mid, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = dp(68)
        })

        // Panel inferior
        val bottom = vbox(8).apply {
            setPadding(dp(14), dp(10), dp(14), dp(20))
            setBackgroundColor(0xB3000000.toInt())
        }
        detail = text(hint(), 13f, 0xFFE3E8EF.toInt())
        fpsText = text("", 12f, 0xFF8C99AD.toInt())
        chips = hbox(6)
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        saveBtn = button(saveLabel(), true) { save() }.apply { isEnabled = false }
        undoBtn = button("Quitar último") { undo() }.apply { isEnabled = false }
        val row = hbox(8)
        row.addView(saveBtn, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(undoBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(button("240 fps") { switchTo240() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        bottom.addView(detail)
        bottom.addView(scroll, lp())
        bottom.addView(row, lp())
        bottom.addView(fpsText)
        root.addView(bottom, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (camHandler != null && camera == null) openCamera()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        setContentView(root)
    }

    private fun hint() = when {
        test.isDrop -> "Cuerpo entero dentro del recuadro. Primero quieto en el suelo 1 s, luego sube al cajón, déjate caer y rebota."
        test.isRepeated -> "Cuerpo entero dentro del recuadro. Quieto 1 s, salta seguido y al terminar quédate quieto 2 s."
        else -> "Cuerpo entero dentro del recuadro. Quieto 1 s, salta y quédate quieto al aterrizar. Puedes saltar las veces que quieras."
    }

    private fun saveLabel(): String {
        val n = items.count { it.kept }
        return when {
            battery -> "Guardar esta carga"
            test.isRepeated -> if (n > 1) "Guardar $n series" else "Guardar serie"
            n == 0 -> "Guardar"
            else -> "Guardar $n ${if (n == 1) "salto" else "saltos"}"
        }
    }

    private fun askStature() {
        val inp = input("Estatura en cm (p. ej. 175)", numeric = true)
        val form = vbox(8).apply { setPadding(dp(20), dp(8), dp(20), 0) }
        form.addView(text("La IA usa la estatura para convertir la imagen a centímetros. Se guarda en la ficha del cliente.", 14f, Col.MUTED))
        form.addView(inp, lp())
        Dlg(this)
            .setTitle("Estatura de $athlete")
            .setView(form)
            .setPositiveButton("Guardar") { _, _ ->
                val v = inp.number()?.takeIf { it in 100.0..230.0 }
                if (v == null) {
                    Tst.makeText(this, "Estatura no válida: se usará 170 cm.", Toast.LENGTH_LONG).show()
                } else {
                    stature = v
                    Clients.byName(this, athlete)?.let { Clients.save(this, it.copy(heightCm = v)) }
                    detector = PoseJumpDetector(test, v, box)
                }
            }
            .setNegativeButton("Usar 170 cm", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Cámara
    // ------------------------------------------------------------------

    private fun chooseSetup(): Setup? {
        val mgr = getSystemService(CameraManager::class.java) ?: return null
        val ids = try { mgr.cameraIdList } catch (_: Exception) { return null }
        for (id in ids) {
            val ch = try { mgr.getCameraCharacteristics(id) } catch (_: Exception) { continue }
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val yuv = map.getOutputSizes(ImageFormat.YUV_420_888) ?: continue
            val prev = map.getOutputSizes(SurfaceTexture::class.java) ?: continue
            fun is43(s: Size) = s.width * 3 == s.height * 4
            val analysis = yuv.filter { is43(it) && it.width <= 800 }.maxByOrNull { it.width * it.height }
                ?: yuv.minByOrNull { abs(it.width - 640) + abs(it.height - 480) } ?: continue
            val ratio = analysis.width.toDouble() / analysis.height
            val preview = prev.filter { abs(it.width.toDouble() / it.height - ratio) < 0.01 && it.width <= 1600 }
                .maxByOrNull { it.width * it.height } ?: analysis
            val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            val fps = ranges.filter { it.upper <= 60 }
                .maxWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower })) ?: Range(30, 30)
            val orient = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            return Setup(id, preview, analysis, fps, orient)
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        if (setup == null) return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!askedPermission) {
                askedPermission = true
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1)
            }
            return
        }
        camThread = HandlerThread("cam-ia").also { it.start() }
        camHandler = Handler(camThread!!.looper)
        if (texture.isAvailable) openCamera()
        level.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Tst.makeText(this, "Sin permiso de cámara no se puede medir.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onPause() {
        if (::level.isInitialized) level.stop()
        cancelAutoSave()
        closeCamera()
        camThread?.quitSafely()
        camThread = null; camHandler = null
        super.onPause()
    }

    override fun onDestroy() {
        try { pose?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val s = setup ?: return
        val mgr = getSystemService(CameraManager::class.java) ?: return
        try {
            mgr.openCamera(s.id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { camera = device; main.post { startSession() } }
                override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); camera = null
                    main.post {
                        Tst.makeText(this@AiCaptureActivity, "Error de cámara ($error). Cierra otras apps que usen la cámara.", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }, camHandler)
        } catch (e: Exception) {
            Tst.makeText(this, "No se pudo abrir la cámara: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
    }

    private fun startSession() {
        val s = setup ?: return
        val cam = camera ?: return
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(s.preview.width, s.preview.height)
        val surface = previewSurface ?: Surface(st).also { previewSurface = it }
        val r = ImageReader.newInstance(s.analysis.width, s.analysis.height, ImageFormat.YUV_420_888, 4)
        reader = r
        r.setOnImageAvailableListener({ rd -> onImage(rd) }, camHandler)
        try {
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(surface, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cs: CameraCaptureSession) {
                    if (camera == null) return
                    session = cs
                    try {
                        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        b.addTarget(surface)
                        b.addTarget(r.surface)
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, s.fps)
                        cs.setRepeatingRequest(b.build(), null, camHandler)
                        main.post { status.text = "Colócate de cuerpo entero en el recuadro" }
                    } catch (e: Exception) {
                        main.post { Tst.makeText(this@AiCaptureActivity, "Cámara no disponible: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                }
                override fun onConfigureFailed(cs: CameraCaptureSession) {
                    main.post { Tst.makeText(this@AiCaptureActivity, "La cámara rechazó la configuración.", Toast.LENGTH_LONG).show() }
                }
            }, camHandler)
        } catch (e: Exception) {
            Tst.makeText(this, "No se pudo iniciar la cámara: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onImage(rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return
        val det = pose
        if (busy || det == null) { img.close(); return }
        busy = true
        val ts = img.timestamp
        val iw = img.width; val ih = img.height
        val rot = setup?.orientation ?: 90
        val input = try { InputImage.fromMediaImage(img, rot) } catch (_: Exception) { img.close(); busy = false; return }
        det.process(input)
            .addOnSuccessListener(mainExecutor) { p -> onPose(p, ts, iw, ih, rot) }
            .addOnCompleteListener(mainExecutor) { _ -> img.close(); busy = false }
    }

    // ------------------------------------------------------------------
    // Pose → salto
    // ------------------------------------------------------------------

    private fun onPose(p: Pose, tsNs: Long, w: Int, h: Int, rot: Int) {
        if (isFinishing) return
        val uw = if (rot == 90 || rot == 270) h else w     // imagen derecha
        val uh = if (rot == 90 || rot == 270) w else h

        frameTimes.addLast(tsNs)
        while (frameTimes.size > 45) frameTimes.removeFirst()
        val fps = effectiveFps()

        fun lm(type: Int) = p.getPoseLandmark(type)?.takeIf { it.inFrameLikelihood > 0.5f }
        fun footOf(heel: Int, toe: Int): Double? {
            val a = lm(heel)?.position?.y; val b = lm(toe)?.position?.y
            return when {
                a != null && b != null -> maxOf(a, b).toDouble()
                else -> (a ?: b)?.toDouble()
            }
        }
        val left = footOf(PoseLandmark.LEFT_HEEL, PoseLandmark.LEFT_FOOT_INDEX)
        val right = footOf(PoseLandmark.RIGHT_HEEL, PoseLandmark.RIGHT_FOOT_INDEX)
        val foot = listOfNotNull(left, right).maxOrNull()
        val nose = lm(PoseLandmark.NOSE)?.position?.y?.toDouble()

        if (techOn) {
            val tS = tsNs / 1e9
            techFrames.addLast(Technique.frame(p, tS))
            while (techFrames.isNotEmpty() && tS - techFrames.first().t > 25.0) techFrames.removeFirst()
        }
        val ev = detector.add(PoseJumpDetector.Sample(tsNs / 1e9, foot, nose))

        overlay.update(p, uw, uh, detector.floorPx, detector.state)
        fpsText.text = fmt("IA en el teléfono · %.0f fps", fps)

        // Indicaciones
        val body = if (foot != null && nose != null) (foot - nose) / uh else null
        status.text = when {
            foot == null || nose == null -> "Que se vea el cuerpo entero, con los pies"
            body != null && body < 0.35 -> "Acércate un poco a la cámara"
            body != null && body > 0.92 -> "Aléjate: la cabeza y los pies deben verse"
            detector.state == PoseJumpDetector.State.CALIBRATING -> "Quieto…"
            detector.state == PoseJumpDetector.State.AIR ->
                if (test.isDrop) "Sobre el cajón / en el aire" else "En el aire"
            test.isDrop -> "¡Listo! Sube al cajón y déjate caer"
            else -> "¡Listo! Salta"
        }

        when (ev) {
            is PoseJumpDetector.Event.Jump -> addItem(listOf(ev.rep))
            is PoseJumpDetector.Event.Series -> addItem(ev.reps)
            null -> {}
        }
    }

    private fun effectiveFps(): Double {
        if (frameTimes.size < 5) return 0.0
        val d = frameTimes.zipWithNext { a, b -> (b - a) / 1e9 }.sorted()
        val m = d[d.size / 2]
        return if (m > 0) 1.0 / m else 0.0
    }

    private fun fpsInt() = effectiveFps().roundToInt().coerceAtLeast(1)

    private fun techFlags(reps: List<PoseJumpDetector.Rep>): List<String> {
        if (!techOn || reps.isEmpty()) return emptyList()
        val fr = techFrames.toList()
        val first = reps.first()
        return if (test.isRepeated) {
            // En series solo se controla la posición de las manos (10/5).
            Technique.check(test, Technique.around(fr, first.takeoffT, 0.1, 0.0), emptyList())
        } else Technique.check(test,
            Technique.around(fr, first.takeoffT, 0.1, 0.0),
            Technique.around(fr, first.landingT, 0.05, 0.035))
    }

    private fun addItem(reps: List<PoseJumpDetector.Rep>) {
        val flags = techFlags(reps)
        items.add(Item(reps, flags))
        val first = reps.first()
        bigResult.visibility = View.VISIBLE
        if (test.isRepeated) {
            bigResult.text = fmt("%.1f cm", reps.map { it.heightCm }.average())
            val tcs = reps.mapNotNull { it.contactS }
            detail.text = fmt("%d saltos · mejor %.1f cm", reps.size, reps.maxOf { it.heightCm }) +
                (if (tcs.isNotEmpty()) fmt(" · contacto medio %.0f ms", tcs.average() * 1000) else "")
        } else {
            bigResult.text = fmt("%.1f cm", first.heightCm)
            val sb = StringBuilder(fmt("Vuelo %.0f ms · despegue %.2f m/s", first.flightS * 1000, Calc.takeoffVelocity(first.flightS)))
            first.contactS?.let { sb.append(fmt(" · contacto %.0f ms · RSI %.2f", it * 1000, Calc.rsi(first.heightCm, it))) }
            detail.text = sb.toString()
        }
        if (flags.isNotEmpty()) detail.append("\n⚠ " + flags.joinToString("\n⚠ "))
        refreshChips()
        // Resultado en voz alta y en grande.
        val big = bigResult.text.toString()
        val said = if (test.isRepeated) fmt("%d saltos, media ", reps.size) + Voice.num(this, reps.map { it.heightCm }.average()) + " centímetros"
            else Voice.num(this, first.heightCm) + " centímetros" + (first.contactS?.let { ". RSI " + Voice.num(this, Calc.rsi(first.heightCm, it), 2) } ?: "")
        Voice.say(this, "$athlete, $said" + if (flags.contains(Technique.MSG_KNEES)) ". Atención: rodillas flexionadas al aterrizar." else "")
        BigResult.show(rootFrame, athlete, big, detail.text.lineSequence().firstOrNull() ?: "",
            if (flags.isEmpty()) 0xFFFFFFFF.toInt() else 0xFFFFC04D.toInt())
        // Sesión de grupo: se guarda solo a los 4 s y pasa al siguiente (Quitar último lo cancela).
        if (groupMode && !battery) startAutoSave(4)
    }

    private fun itemHeight(it: Item) =
        if (test.isRepeated) it.reps.map { r -> r.heightCm }.average() else it.reps.first().heightCm

    private fun refreshChips() {
        chips.removeAllViews()
        val best = items.filter { it.kept }.maxOfOrNull { itemHeight(it) }
        items.forEachIndexed { i, it ->
            val h = itemHeight(it)
            val b = Button(this).apply {
                text = fmt("%d · %.1f", i + 1, h)
                isAllCaps = false
                textSize = 14f
                setTextColor(if (it.kept) 0xFFFFFFFF.toInt() else 0xFF7A8496.toInt())
                paintFlags = if (it.kept) paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv() else paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                background = rounded(
                    if (it.kept && h == best) Col.ACCENT else if (it.kept) 0xFF2A3A55.toInt() else 0x33FFFFFF,
                    if (it.flags.isNotEmpty()) 0xFFFFB020.toInt() else 0, 16)
                if (it.flags.isNotEmpty()) text = "⚠ " + text
                minHeight = dp(36); minimumHeight = dp(36)
                setPadding(dp(12), 0, dp(12), 0)
                stateListAnimator = null
                setOnClickListener { _ -> cancelAutoSave(); it.kept = !it.kept; refreshChips() }
            }
            chips.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))
        }
        val n = items.count { it.kept }
        saveBtn.text = saveLabel()
        saveBtn.isEnabled = n > 0
        undoBtn.isEnabled = items.isNotEmpty()
    }

    private fun startAutoSave(seconds: Int) {
        cancelAutoSave()
        var left = seconds
        val r = object : Runnable {
            override fun run() {
                if (autoSave !== this) return
                if (left <= 0) { autoSave = null; save(); return }
                saveBtn.text = saveLabel() + " ($left)"
                left--
                main.postDelayed(this, 1000)
            }
        }
        autoSave = r
        main.post(r)
    }

    private fun cancelAutoSave() {
        autoSave?.let { main.removeCallbacks(it) }
        autoSave = null
        if (::saveBtn.isInitialized) saveBtn.text = saveLabel()
    }

    private fun topLabel(): String {
        val s = if (groupMode) GroupSession.get(this) else null
        return if (s != null) "${test.label} · ${s.label()}" else "${test.label} · $athlete"
    }

    /** Sesión de grupo: pasa al siguiente turno (otro atleta o el mismo, según el orden). */
    private fun nextTurn() {
        val s = GroupSession.advance(this)
        if (s == null || s.finished) {
            Voice.say(this, "Sesión terminada. Buen trabajo.")
            finish(); return
        }
        val name = s.current!!.first
        if (name != athlete) {
            athlete = name
            val c = Clients.byName(this, name)
            mass = c?.massKg
            stature = c?.heightCm
            detector = PoseJumpDetector(test, stature, box)
            techFrames.clear()
        }
        topText.text = topLabel()
        Voice.say(this, GroupSession.announce(s))
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (RemoteKeys.isTrigger(keyCode)) {
            if (event.repeatCount == 0 && saveBtn.isEnabled) saveBtn.performClick()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        if (RemoteKeys.isTrigger(keyCode)) true else super.onKeyUp(keyCode, event)

    private fun undo() {
        cancelAutoSave()
        if (items.isEmpty()) return
        items.removeAt(items.size - 1)
        if (items.isEmpty()) { bigResult.visibility = View.GONE; detail.text = hint() }
        refreshChips()
    }

    // ------------------------------------------------------------------
    // Guardar
    // ------------------------------------------------------------------

    private fun footer() = fmt("Medido con IA a %d fps", fpsInt())

    private fun buildJump(rep: PoseJumpDetector.Rep, flags: List<String> = emptyList()): Jump? =
        JumpCalc.fromFlight(test, athlete, rep.flightS, rep.contactS, mass, box, load, fpsInt(), footer())
            ?.jump?.copy(notes = "IA en vivo", flags = flags)

    private fun save() {
        cancelAutoSave()
        BigResult.hide(rootFrame)
        val kept = items.filter { it.kept }
        if (kept.isEmpty()) return
        val base = System.currentTimeMillis()
        if (battery) {
            val best = kept.maxByOrNull { itemHeight(it) }!!
            val j = buildJump(best.reps.first(), best.flags)?.copy(id = base) ?: return
            Store.add(this, j)
            items.clear(); refreshChips()
            batterySaved(j)
            return
        }
        val saved = ArrayList<Jump>()
        kept.forEachIndexed { i, it ->
            val j = if (test.isRepeated) {
                val contacts = it.reps.drop(1).map { r -> r.contactS }
                JumpCalc.repeated(athlete, it.reps.map { r -> r.flightS },
                    if (contacts.all { c -> c != null }) contacts.filterNotNull() else emptyList(), mass, fpsInt(), test)
                    ?.copy(notes = "IA en vivo", flags = it.flags)
            } else buildJump(it.reps.first(), it.flags)
            j?.let { x -> saved.add(x.copy(id = base + i)) }
        }
        saved.forEach { Store.add(this, it) }
        items.clear(); refreshChips()
        bigResult.visibility = View.GONE
        detail.text = hint()
        val best = saved.maxByOrNull { it.heightCm }
        var msg = if (saved.size == 1) fmt("Guardado: %.1f cm", saved[0].heightCm)
            else fmt("Guardados %d saltos · mejor %.1f cm", saved.size, best?.heightCm ?: 0.0)
        best?.let { JumpCalc.asymmetryNote(this, it) }?.let { msg += "\n$it" }
        saved.lastOrNull()?.let { Attempts.afterSave(this, it) }?.let { msg += "\n$it" }
        Tst.makeText(this, msg, Toast.LENGTH_LONG).show()
        if (groupMode) nextTurn()
    }

    /** Batería F-V: tras guardar, ofrece la siguiente carga o el perfil. */
    private fun batterySaved(saved: Jump) {
        val next = BatteryActivity.nextPending(this, athlete)
        val b = Dlg(this)
            .setTitle(fmt("Carga %.1f kg: %.1f cm", saved.loadKg ?: 0.0, saved.heightCm))
            .setNegativeButton("Volver a la batería") { _, _ -> finish() }
            .setNeutralButton("Repetir esta carga", null)
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

    private fun switchTo240() {
        if (items.any { it.kept }) {
            Dlg(this)
                .setMessage("Hay saltos sin guardar. ¿Cambiar a cámara lenta 240 fps igualmente?")
                .setPositiveButton("Cambiar") { _, _ -> doSwitch() }
                .setNegativeButton("Cancelar", null)
                .show()
        } else doSwitch()
    }

    private fun doSwitch() {
        CaptureMode.set(this, ai = false)
        startActivity(MainActivity.forward(intent, Intent(this, CaptureActivity::class.java)))
        finish()
    }

    /** Atrás con saltos sin guardar: pregunta antes de salir. */
    private fun handleBack() {
        if (items.any { it.kept } && !battery) {
            Dlg(this)
                .setMessage("Hay saltos sin guardar.")
                .setPositiveButton("Guardar y salir") { _, _ -> save(); finish() }
                .setNegativeButton("Salir sin guardar") { _, _ -> finish() }
                .setNeutralButton("Cancelar", null)
                .show()
        } else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()
}

/** Modo de medición elegido: IA en vivo (por defecto) o cámara lenta 240 fps. */
object CaptureMode {
    private const val KEY = "captureMode"
    fun ai(ctx: Context) = ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).getString(KEY, "ia") == "ia"
    fun set(ctx: Context, ai: Boolean) =
        ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).edit().putString(KEY, if (ai) "ia" else "240").apply()
    fun cls(ctx: Context): Class<*> = if (ai(ctx)) AiCaptureActivity::class.java else CaptureActivity::class.java
}

/** Esqueleto, recuadro guía y suelo detectado sobre la vista de la cámara. */
class PoseOverlay(ctx: Context, private val texture: View) : View(ctx) {

    private var pts: FloatArray? = null       // x,y normalizados (0–1) de los 33 puntos
    private var vis: BooleanArray? = null
    private var floorV: Float? = null
    private var state = PoseJumpDetector.State.NO_BODY

    private val bone = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = 0x99FFFFFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
    }
    private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f; color = Col.ACCENT }

    private val bones = intArrayOf(
        11, 12, 11, 13, 13, 15, 12, 14, 14, 16, 11, 23, 12, 24, 23, 24,
        23, 25, 25, 27, 27, 29, 29, 31, 27, 31, 24, 26, 26, 28, 28, 30, 30, 32, 28, 32
    )

    fun update(p: Pose, w: Int, h: Int, floorPx: Double?, st: PoseJumpDetector.State) {
        val all = p.allPoseLandmarks
        if (all.isEmpty()) { pts = null } else {
            val a = FloatArray(66); val v = BooleanArray(33)
            for (l in all) {
                val i = l.landmarkType
                if (i !in 0..32) continue
                a[2 * i] = l.position.x / w; a[2 * i + 1] = l.position.y / h
                v[i] = l.inFrameLikelihood > 0.5f
            }
            pts = a; vis = v
        }
        floorV = floorPx?.let { (it / h).toFloat() }
        state = st
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val l = texture.left.toFloat(); val t = texture.top.toFloat()
        val w = texture.width.toFloat(); val h = texture.height.toFloat()
        if (w <= 0 || h <= 0) return
        // Recuadro guía: el atleta debe quedar dentro, de cuerpo entero.
        c.drawRect(l + w * 0.15f, t + h * 0.06f, l + w * 0.85f, t + h * 0.94f, guide)
        floorV?.let { fv -> c.drawLine(l + w * 0.1f, t + fv * h, l + w * 0.9f, t + fv * h, floorPaint) }
        val a = pts ?: return
        val v = vis ?: return
        val col = when (state) {
            PoseJumpDetector.State.READY -> 0xFF35C76F.toInt()
            PoseJumpDetector.State.AIR -> Col.ACCENT
            else -> 0xFFB8C2D1.toInt()
        }
        bone.color = col; dot.color = col
        var i = 0
        while (i < bones.size) {
            val p = bones[i]; val q = bones[i + 1]
            if (v[p] && v[q]) c.drawLine(l + a[2 * p] * w, t + a[2 * p + 1] * h, l + a[2 * q] * w, t + a[2 * q + 1] * h, bone)
            i += 2
        }
        if (v[0]) c.drawCircle(l + a[0] * w, t + a[1] * h, 10f, dot)
    }
}
