package com.salto240

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.LinearLayout
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Graba el salto con la sesión de alta velocidad de Camera2 (240 fps cuando el
 * teléfono lo permite). Al detener, abre el análisis cuadro a cuadro.
 *
 * Modo en vivo: la vista previa se vigila mientras se graba; al aterrizar el atleta la grabación
 * se detiene sola, se mide el tramo del salto a 240 fps y el resultado aparece en pantalla.
 */
class CaptureActivity : BaseActivity() {

    override val darkSystemBars = true

    private lateinit var texture: AutoFitTextureView
    private lateinit var recBtn: Button
    private lateinit var info: TextView
    private var cfg: CamConfig? = null

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var previewSurface: Surface? = null
    private var outFile: File? = null
    private var recording = false
    private var starting = false
    private var startMs = 0L
    private var guideFrac = 0.72f
    private lateinit var floorGuide: android.view.View
    private lateinit var guideText: TextView

    // --- Modo en vivo ---
    private lateinit var test: TestType
    private var battery = false
    private var live = true
    private lateinit var detector: LiveDetector
    @Volatile private var liveHandled = false
    private var liveBmp: Bitmap? = null
    private var lastPreviewTs = 0L
    private lateinit var liveBtn: Button
    private lateinit var card: LinearLayout
    private lateinit var cardStatus: TextView
    private lateinit var cardBig: TextView
    private lateinit var cardDetail: TextView
    private lateinit var cardButtons: LinearLayout
    private val work = Executors.newSingleThreadExecutor()
    private val prefs get() = getSharedPreferences("salto240", MODE_PRIVATE)

    companion object {
        /** Posición vertical de la línea del suelo en la imagen (0 arriba, 1 abajo). */
        const val EX_FLOOR_V = "floorV"
    }

    private fun placeGuide() {
        if (texture.height <= 0) return
        val y = texture.top + guideFrac * texture.height
        floorGuide.translationY = y - floorGuide.height / 2f
        guideText.translationY = y - guideText.height - dp(4)
    }

    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private val main = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = (SystemClock.elapsedRealtime() - startMs) / 1000.0
            info.text = if (live) String.format(Locale.getDefault(), "● En vivo  %.1f s · esperando el salto", s)
                else String.format(Locale.getDefault(), "● Grabando  %.1f s", s)
            // Límite de seguridad: los videos de alta velocidad ocupan mucho.
            if (s >= 15.0) {
                if (live) restartLive("No se detectó ningún salto en 15 s. Se reinició la grabación.")
                else stopRecording(true)
            } else main.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cfg = HighSpeed.find(this)
        val c = cfg
        if (c == null) {
            Toast.makeText(this, "No se encontró una cámara trasera.", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        texture = AutoFitTextureView(this)
        // Teléfono en vertical: el video sale girado (alto × ancho).
        texture.setAspect(c.size.height, c.size.width)
        root.addView(texture, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        test = TestType.of(intent.getStringExtra(MainActivity.EX_TEST))
        battery = intent.getBooleanExtra(BatteryActivity.EX_BATTERY, false)
        detector = LiveDetector(test)
        live = prefs.getBoolean("liveMode", true)
        val top = text("${test.label} · ${intent.getStringExtra(MainActivity.EX_ATHLETE) ?: ""}\n${c.fps} fps · ${c.size.width}×${c.size.height}" +
                if (!c.highSpeed) "  (sin modo cámara lenta)" else "", 14f, 0xFFFFFFFF.toInt(), true).apply {
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(top, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(12), dp(12), dp(12), 0)
        })

        // Línea del suelo (arrastrable): la detección automática analiza la franja justo encima.
        guideFrac = getSharedPreferences("salto240", MODE_PRIVATE).getFloat("guideFrac", 0.72f)
        floorGuide = android.view.View(this).apply { setBackgroundColor(0xFFD9481F.toInt()) }
        root.addView(floorGuide, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP))
        guideText = text("Línea del suelo: arrástrala hasta donde apoyan los pies", 12f, 0xFFFFFFFF.toInt()).apply {
            setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
        }
        root.addView(guideText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.setOnTouchListener { v, e ->
            if (e.action == android.view.MotionEvent.ACTION_DOWN || e.action == android.view.MotionEvent.ACTION_MOVE) {
                if (texture.height > 0) {
                    guideFrac = ((e.y - texture.top) / texture.height).coerceIn(0.05f, 0.98f)
                    placeGuide()
                }
                if (e.action == android.view.MotionEvent.ACTION_DOWN) v.performClick()
            }
            true
        }
        texture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeGuide() }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (camHandler != null && camera == null) openCamera()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = onPreviewFrame()
        }

        val bottom = vbox(10).apply {
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(0x99000000.toInt())
        }
        info = text(idleHint(), 14f, 0xFFFFFFFF.toInt())
        recBtn = button("Grabar", true) { onRecordPressed() }
        liveBtn = button(liveLabel()) { toggleLive() }
        val row = hbox(8)
        row.addView(recBtn, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(liveBtn, lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        bottom.addView(info)
        bottom.addView(row, lp())
        root.addView(bottom, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Tarjeta del resultado en vivo
        card = vbox(10).apply {
            background = rounded(0xE6101826.toInt(), 0x33FFFFFF, 14)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            visibility = android.view.View.GONE
            isClickable = true
        }
        cardStatus = text("", 13f, 0xFFB8C2D1.toInt())
        cardBig = text("", 56f, 0xFFFFFFFF.toInt(), true).apply { gravity = Gravity.CENTER_HORIZONTAL }
        cardDetail = text("", 14f, 0xFFE3E8EF.toInt())
        cardButtons = vbox(8)
        card.addView(cardStatus)
        card.addView(cardBig, lp())
        card.addView(cardDetail)
        card.addView(cardButtons, lp())
        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(16), 0, dp(16), 0)
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (cfg == null) return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!askedPermission) {
                askedPermission = true
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1)
            }
            return
        }
        camThread = HandlerThread("cam").also { it.start() }
        camHandler = Handler(camThread!!.looper)
        if (texture.isAvailable) openCamera()
    }

    private var askedPermission = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Sin permiso de cámara no se puede grabar.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onPause() {
        if (recording) stopRecording(false)
        if (starting && ::recBtn.isInitialized) { starting = false; recBtn.isEnabled = true }
        closeCamera()
        camThread?.quitSafely()
        camThread = null; camHandler = null
        super.onPause()
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val c = cfg ?: return
        val mgr = getSystemService(CameraManager::class.java) ?: return
        try {
            mgr.openCamera(c.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    main.post { startPreview() }
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close(); camera = null
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); camera = null
                    toast("Error de cámara ($error). Cierra otras apps que usen la cámara.")
                    main.post { finish() }
                }
            }, camHandler)
        } catch (e: Exception) {
            toast("No se pudo abrir la cámara: ${e.message}")
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        releaseRecorder()
    }

    private fun startPreview() {
        val c = cfg ?: return
        val cam = camera ?: return
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(c.size.width, c.size.height)
        val surface = previewSurface ?: Surface(st).also { previewSurface = it }
        try {
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera == null) return
                    session = s
                    try {
                        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        b.addTarget(surface)
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        s.setRepeatingRequest(b.build(), null, camHandler)
                    } catch (e: Exception) {
                        toast("Vista previa no disponible: ${e.message}")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    toast("No se pudo iniciar la vista previa.")
                }
            }, camHandler)
        } catch (e: Exception) {
            toast("No se pudo iniciar la vista previa: ${e.message}")
        }
    }

    private fun onRecordPressed() {
        if (starting) return
        card.visibility = android.view.View.GONE
        if (recording) stopRecording(true) else startRecording()
    }

    @Suppress("DEPRECATION")
    private fun legacyRecorder() = MediaRecorder()

    private fun buildRecorder(file: File): MediaRecorder? {
        val c = cfg ?: return null
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else legacyRecorder()
        return try {
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(file.absolutePath)
            val bitrate = (c.size.width.toLong() * c.size.height * c.fps / 8L).coerceIn(10_000_000L, 80_000_000L).toInt()
            r.setVideoEncodingBitRate(bitrate)
            r.setVideoFrameRate(c.fps)
            r.setVideoSize(c.size.width, c.size.height)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setOrientationHint(c.orientation)
            r.prepare()
            r
        } catch (e: Exception) {
            r.release()
            toast("El codificador no acepta ${c.fps} fps: ${e.message}")
            null
        }
    }

    private fun releaseRecorder() {
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    private fun startRecording() {
        val c = cfg ?: return
        val cam = camera ?: return
        val preview = previewSurface ?: return
        starting = true
        recBtn.isEnabled = false
        info.text = "Preparando cámara lenta…"

        try { session?.close() } catch (_: Exception) {}
        session = null

        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val file = File(dir, "salto_${System.currentTimeMillis()}.mp4")
        outFile = file
        val r = buildRecorder(file)
        if (r == null) {
            starting = false; recBtn.isEnabled = true
            startPreview(); return
        }
        recorder = r
        val recSurface = r.surface

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                try {
                    val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    b.addTarget(preview)
                    b.addTarget(recSurface)
                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(c.fps, c.fps))
                    if (c.highSpeed && s is CameraConstrainedHighSpeedCaptureSession) {
                        s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()), null, camHandler)
                    } else {
                        s.setRepeatingRequest(b.build(), null, camHandler)
                    }
                    r.start()
                    main.post {
                        detector.reset()
                        lastPreviewTs = 0L
                        liveHandled = false
                        recording = true; starting = false
                        startMs = SystemClock.elapsedRealtime()
                        recBtn.text = "Detener"
                        recBtn.isEnabled = true
                        liveBtn.isEnabled = false
                        main.post(tick)
                    }
                } catch (e: Exception) {
                    toast("No se pudo grabar: ${e.message}")
                    main.post { failBackToPreview() }
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                toast("La cámara rechazó la configuración de ${c.fps} fps.")
                main.post { failBackToPreview() }
            }
        }

        try {
            if (c.highSpeed) {
                @Suppress("DEPRECATION")
                cam.createConstrainedHighSpeedCaptureSession(listOf(preview, recSurface), callback, camHandler)
            } else {
                @Suppress("DEPRECATION")
                cam.createCaptureSession(listOf(preview, recSurface), callback, camHandler)
            }
        } catch (e: Exception) {
            toast("No se pudo iniciar la grabación: ${e.message}")
            failBackToPreview()
        }
    }

    private fun failBackToPreview() {
        starting = false
        recBtn.isEnabled = true
        recBtn.text = "Grabar"
        liveBtn.isEnabled = true
        info.text = idleHint()
        releaseRecorder()
        outFile?.delete()
        try { session?.close() } catch (_: Exception) {}
        session = null
        startPreview()
    }

    /** Detiene el grabador y devuelve el video si quedó bien guardado. */
    private fun haltRecording(): File? {
        recording = false
        main.removeCallbacks(tick)
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        var ok = true
        try { recorder?.stop() } catch (e: RuntimeException) { ok = false }
        releaseRecorder()
        try { session?.close() } catch (_: Exception) {}
        session = null
        recBtn.text = "Grabar"
        liveBtn.isEnabled = true
        val f = outFile
        if (!ok || f == null || !f.exists() || f.length() == 0L) {
            f?.delete()
            return null
        }
        return f
    }

    private fun stopRecording(openAnalysis: Boolean) {
        val c = cfg ?: return
        val f = haltRecording()
        if (!openAnalysis) return
        if (f == null) {
            Toast.makeText(this, "La grabación fue demasiado corta. Intenta de nuevo.", Toast.LENGTH_LONG).show()
            info.text = idleHint()
            startPreview()
            return
        }
        prefs.edit().putFloat("guideFrac", guideFrac).apply()
        startActivity(analysisIntent(f, c.fps, null))
        finish()
    }

    private fun analysisIntent(f: File, fps: Int, r: AutoDetect.Result?): Intent {
        val i = MainActivity.forward(intent, Intent(this, AnalysisActivity::class.java))
        i.putExtra(MainActivity.EX_URI, Uri.fromFile(f).toString())
        i.putExtra(MainActivity.EX_FPS, fps)
        i.putExtra(EX_FLOOR_V, guideFrac)
        if (r != null) {
            r.takeoff?.let { i.putExtra(AnalysisActivity.EX_PRESET_TAKEOFF, it) }
            r.landing?.let { i.putExtra(AnalysisActivity.EX_PRESET_LANDING, it) }
            r.contact?.let { i.putExtra(AnalysisActivity.EX_PRESET_CONTACT, it) }
            if (r.events.isNotEmpty()) i.putExtra(AnalysisActivity.EX_PRESET_EVENTS, r.events.toIntArray())
        }
        return i
    }

    // ------------------------------------------------------------------
    // Modo en vivo
    // ------------------------------------------------------------------

    private fun idleHint() =
        if (live) "En vivo: arrastra la línea naranja a los pies y pulsa Grabar. El atleta quieto 1 s y luego salta: el resultado aparece solo."
        else "Encuadra y pulsa Grabar. Detén después de aterrizar."

    private fun liveLabel() = if (live) "En vivo: Sí" else "En vivo: No"

    private fun toggleLive() {
        if (recording || starting) return
        live = !live
        prefs.edit().putBoolean("liveMode", live).apply()
        liveBtn.text = liveLabel()
        info.text = idleHint()
    }

    /** Cada cuadro de la vista previa: se lee la franja del suelo y se busca un salto terminado. */
    private fun onPreviewFrame() {
        if (!recording || !live || liveHandled) return
        val st = texture.surfaceTexture ?: return
        var t = st.timestamp
        if (t == 0L) t = System.nanoTime()
        if (t == lastPreviewTs) return
        lastPreviewTs = t
        if (texture.width <= 0 || texture.height <= 0) return
        val w = 240
        val h = (w.toLong() * texture.height / texture.width).toInt().coerceAtLeast(1)
        var b = liveBmp
        if (b == null || b.width != w || b.height != h) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            liveBmp = b
        }
        try { texture.getBitmap(b!!) } catch (_: Exception) { return }
        val res = detector.add(t, LiveDetector.strip(b, guideFrac)) ?: return
        liveHandled = true
        onLiveJump(res)
    }

    private fun fmt(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    /** Salto detectado en la vista previa: valor provisional y medición definitiva a alta velocidad. */
    private fun onLiveJump(l: LiveDetector.Live) {
        val c = cfg ?: return
        val f = haltRecording()
        startPreview()
        prefs.edit().putFloat("guideFrac", guideFrac).apply()
        if (f == null) {
            restartLive("La grabación falló. Se reinició.")
            return
        }
        info.text = "Salto detectado. Midiendo…"
        recBtn.isEnabled = false
        showCard(
            status = fmt("Provisional (vista previa, %.0f fps) · midiendo a %d fps…", l.previewFps, c.fps),
            big = when {
                test == TestType.RJ -> fmt("%d saltos", l.eventsS.size / 2)
                else -> l.flightS?.let { fmt("≈ %.1f cm", Calc.heightCm(it)) } ?: "…"
            },
            bigColor = 0xFF8C99AD.toInt(),
            detail = l.flightS?.takeIf { test != TestType.RJ }?.let { fmt("Vuelo ≈ %.0f ms", it * 1000) } ?: ""
        )
        cardButtons.removeAllViews()

        val fps = c.fps
        val g = guideFrac
        val t = test
        work.execute {
            var r: AutoDetect.Result? = null
            try {
                val src = FrameSource(this, Uri.fromFile(f))
                try {
                    val y0 = (g - 0.035f).coerceAtLeast(0f)
                    fun scan(range: IntRange?): AutoDetect.Result? {
                        val strips = if (range == null) src.scanStrip(0.12f, 0.88f, y0, g, progress = {}, cancelled = { isFinishing })
                            else src.scanStrip(0.12f, 0.88f, y0, g, progress = {}, cancelled = { isFinishing },
                                from = range.first, to = range.last + 1)
                        val res = strips?.let { AutoDetect.detect(it, fps, t) } ?: return null
                        return if (range == null) res else res.shift(range.first)
                    }
                    fun ok(x: AutoDetect.Result?) = x != null &&
                        (if (t == TestType.RJ) x.events.size >= 2 else x.takeoff != null && x.landing != null)
                    val n = src.frameCount
                    if (n > 1) {
                        r = scan(LiveDetector.window(l, t, fps, n))
                        if (!ok(r)) r = scan(null)            // respaldo: todo el video
                        if (!ok(r)) r = null
                    }
                } finally {
                    src.release()
                }
            } catch (_: Exception) {
                r = null
            }
            main.post { if (!isFinishing && !isDestroyed) onPrecise(f, fps, l, r) }
        }
    }

    private fun onPrecise(f: File, fps: Int, l: LiveDetector.Live, r: AutoDetect.Result?) {
        // Batería F-V y saltos repetidos tienen su propio flujo de guardado: van al análisis con las marcas puestas.
        if (battery || test == TestType.RJ) {
            startActivity(analysisIntent(f, fps, r))
            finish()
            return
        }
        val mass = intent.getDoubleExtra(MainActivity.EX_MASS, 0.0).takeIf { it > 0 }
            ?: Store.athlete(this, athleteName()).massKg
        val box = intent.getDoubleExtra(MainActivity.EX_BOX, 0.0).takeIf { it > 0 }
        val load = if (test == TestType.SJ || test == TestType.CMJ) intent.getDoubleExtra(MainActivity.EX_LOAD, 0.0) else null
        val out = if (r?.takeoff != null && r.landing != null)
            JumpCalc.single(this, test, athleteName(), fps, r.takeoff, r.landing, r.contact, mass, box, load) else null

        cardButtons.removeAllViews()
        recBtn.isEnabled = true
        if (out == null) {
            info.text = "Revisa el salto cuadro a cuadro."
            showCard(
                status = "No se pudo medir a $fps fps con seguridad.",
                big = l.flightS?.let { fmt("≈ %.1f cm", Calc.heightCm(it)) } ?: "—",
                bigColor = 0xFF8C99AD.toInt(),
                detail = if (test == TestType.DJ) "Falta el contacto inicial tras la caída. Márcalo a mano en la revisión."
                    else "Revisa la línea del suelo y marca a mano el despegue y el aterrizaje."
            )
            cardButtons.addView(button("Revisar cuadro a cuadro", true) {
                startActivity(analysisIntent(f, fps, r)); finish()
            }, lp())
            cardButtons.addView(button("Repetir salto") { f.delete(); restartLive(null) }, lp())
            return
        }

        val diff = l.flightS?.let { abs(out.jump.flightMs / 1000.0 - it) }
        val status = if (diff != null && diff > 0.1)
            "Definitivo a $fps fps · difiere de la vista previa: conviene revisarlo"
        else "Definitivo · ${test.short} · ${athleteName()}"
        info.text = "Salto medido."
        showCard(status, fmt("%.1f cm", out.jump.heightCm), 0xFFFFFFFF.toInt(), out.detail)
        cardButtons.addView(button("Guardar y siguiente", true) {
            Store.add(this, out.jump)
            val note = JumpCalc.asymmetryNote(this, out.jump)
            Toast.makeText(this, fmt("Guardado: %.1f cm", out.jump.heightCm) + (note?.let { "\n$it" } ?: ""),
                Toast.LENGTH_LONG).show()
            f.delete()
            restartLive(null)
        }, lp())
        val row = hbox(8)
        row.addView(button("Revisar") { startActivity(analysisIntent(f, fps, r)); finish() },
            lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Repetir") { f.delete(); restartLive(null) },
            lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cardButtons.addView(row, lp())
    }

    private fun athleteName() = intent.getStringExtra(MainActivity.EX_ATHLETE) ?: "Sin nombre"

    private fun showCard(status: String, big: String, bigColor: Int, detail: String) {
        cardStatus.text = status
        cardBig.text = big
        cardBig.setTextColor(bigColor)
        cardDetail.text = detail
        cardDetail.visibility = if (detail.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        card.visibility = android.view.View.VISIBLE
    }

    /** Oculta el resultado y vuelve a grabar para el siguiente salto. */
    private fun restartLive(msg: String?) {
        card.visibility = android.view.View.GONE
        if (recording) haltRecording()?.delete()
        liveHandled = false
        msg?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        info.text = idleHint()
        if (camera != null) startRecording() else startPreview()
    }

    override fun onDestroy() {
        work.shutdownNow()
        super.onDestroy()
    }
}
