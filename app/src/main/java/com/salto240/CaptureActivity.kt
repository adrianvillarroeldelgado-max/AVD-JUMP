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
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
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
    /** Hilo de la detección en vivo: la vista previa no se traba mientras se analiza. */
    private val liveExec = Executors.newSingleThreadExecutor()
    @Volatile private var liveBusy = 0
    /** Petición de grabación vigente (para bloquear la exposición una vez estabilizada). */
    private var recRequest: CaptureRequest.Builder? = null
    private val prefs get() = getSharedPreferences("salto240", MODE_PRIVATE)

    // --- Sesión de grupo, voz, nivel y control remoto ---
    private var groupMode = false
    private lateinit var rootFrame: FrameLayout
    private lateinit var topText: TextView
    private lateinit var level: LevelView
    /** Botón principal de la tarjeta (lo pulsa el control remoto). */
    private var cardPrimary: Button? = null
    private var autoSave: Runnable? = null

    // --- Temporizador: cuenta atrás hablada, «¡Salta!» y análisis del tramo posterior ---
    /** Segundos de cuenta atrás (0 = sin temporizador). */
    private var timerS = 0
    private lateinit var timerBtn: Button
    private lateinit var countText: TextView
    private var countdown: Runnable? = null
    /** Momento de la señal «¡Salta!» (elapsedRealtime), 0 si no hay. */
    private var goAtMs = 0L
    private var tone: ToneGenerator? = null
    private val timerEnd = Runnable { finishTimer() }

    companion object {
        /** Posición vertical de la línea del suelo en la imagen (0 arriba, 1 abajo). */
        const val EX_FLOOR_V = "floorV"
        private val TIMER_OPTIONS = listOf(0, 3, 5, 10)
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
            if (timerS > 0) {
                info.text = when {
                    countdown != null -> String.format(Locale.getDefault(), "● Grabando  %.1f s · prepárate", s)
                    goAtMs != 0L -> String.format(Locale.getDefault(), "● Grabando  %.1f s · ¡salta!", s)
                    else -> String.format(Locale.getDefault(), "● Grabando  %.1f s", s)
                }
            }
            // Límite de seguridad: los videos de alta velocidad ocupan mucho.
            if (s >= (if (timerS > 0) 6.0 + postJumpS() else 15.0)) {
                if (timerS > 0) finishTimer()
                else if (live) restartLive("No se detectó ningún salto en 15 s. Se reinició la grabación.")
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
            Tst.makeText(this, "No se encontró una cámara trasera.", Toast.LENGTH_LONG).show()
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
        groupMode = intent.getBooleanExtra(GroupSession.EX_SESSION, false) && GroupSession.get(this)?.finished == false
        detector = LiveDetector(test)
        live = groupMode || prefs.getBoolean("liveMode", true)
        timerS = prefs.getInt("timerS", 0).takeIf { it in TIMER_OPTIONS } ?: 0
        rootFrame = root
        val top = text(topLabel(), 14f, 0xFFFFFFFF.toInt(), true).apply {
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        topText = top
        root.addView(top, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(12), dp(12), dp(124), 0)
        })
        val helpBtn = button("Protocolo") {
            if (!recording && !starting) startActivity(Intent(this, ProtocolActivity::class.java)
                .putExtra(ProtocolActivity.EX_FOCUS, if (battery) "FV_BATTERY" else test.name))
        }
        root.addView(helpBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(dp(12), dp(12), dp(12), 0)
        })

        // Nivel de la cámara (acelerómetro).
        level = LevelView(this)
        root.addView(level, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(84)
        })
        Voice.init(this, force = timerS > 0)
        if (groupMode) GroupSession.get(this)?.let { speak(GroupSession.announce(it)) }

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
        timerBtn = button(timerLabel()) { cycleTimer() }
        val row = hbox(8)
        row.addView(liveBtn, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(timerBtn, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("IA") { switchToAi() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        bottom.addView(info)
        bottom.addView(recBtn, lp())
        bottom.addView(row, lp())
        root.addView(bottom, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Número grande de la cuenta atrás
        countText = text("", 150f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            setShadowLayer(18f, 0f, 0f, 0xFF000000.toInt())
            visibility = android.view.View.GONE
        }
        root.addView(countText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

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
        level.start()
    }

    private var askedPermission = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Tst.makeText(this, "Sin permiso de cámara no se puede grabar.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onPause() {
        level.stop()
        stopTimer()
        cancelAutoSave()
        if (recording) stopRecording(false)
        if (starting && ::recBtn.isInitialized) { starting = false; recBtn.isEnabled = true }
        closeCamera()
        camThread?.quitSafely()
        camThread = null; camHandler = null
        super.onPause()
    }

    private fun toast(msg: String) = main.post { Tst.makeText(this, msg, Toast.LENGTH_LONG).show() }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val c = cfg ?: return
        val mgr = getSystemService(CameraManager::class.java) ?: return
        try {
            mgr.openCamera(c.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    main.post { startPreview() }
                    // Sesión de grupo: la grabación en vivo arranca sola.
                    if (groupMode) main.postDelayed({
                        if (groupMode && camera != null && !recording && !starting && countdown == null &&
                            card.visibility != android.view.View.VISIBLE) { if (timerS > 0) startTimer() else startRecording() }
                    }, 1500)
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
        if (starting && timerS == 0) return
        card.visibility = android.view.View.GONE
        when {
            timerS > 0 && countdown != null -> cancelTimer()
            timerS > 0 && goAtMs != 0L -> finishTimer()
            timerS > 0 && !recording && !starting -> startTimer()
            starting -> {}
            recording -> stopRecording(true)
            else -> startRecording()
        }
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
                    // Bloquea exposición y balance de blancos tras estabilizarse: la luz no cambia a mitad del salto.
                    recRequest = b
                    main.postDelayed({ lockExposure(s, b) }, 600)
                    main.post {
                        // Temporizador cancelado mientras la cámara se preparaba: se descarta esta grabación.
                        if (timerS > 0 && countdown == null && goAtMs == 0L) {
                            recording = true; starting = false
                            haltRecording()?.delete()
                            recBtn.isEnabled = true
                            info.text = idleHint()
                            startPreview()
                            return@post
                        }
                        liveGen++
                        liveExec.execute { detector.reset() }
                        lastPreviewTs = 0L
                        liveHandled = false
                        recording = true; starting = false
                        startMs = SystemClock.elapsedRealtime()
                        recBtn.text = if (countdown != null) "Cancelar" else "Detener"
                        recBtn.isEnabled = true
                        liveBtn.isEnabled = false
                        timerBtn.isEnabled = false
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

    private fun lockExposure(s: CameraCaptureSession, b: CaptureRequest.Builder) {
        if (!recording || session !== s) return
        val c = cfg ?: return
        try {
            b.set(CaptureRequest.CONTROL_AE_LOCK, true)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            if (c.highSpeed && s is CameraConstrainedHighSpeedCaptureSession) {
                s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()), null, camHandler)
            } else {
                s.setRepeatingRequest(b.build(), null, camHandler)
            }
        } catch (_: Exception) {
            // Algunos teléfonos no aceptan el bloqueo en cámara lenta: se sigue sin él.
        }
    }

    private fun failBackToPreview() {
        starting = false
        recBtn.isEnabled = true
        recBtn.text = "Grabar"
        liveBtn.isEnabled = true
        timerBtn.isEnabled = true
        stopTimer()
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
        timerBtn.isEnabled = countdown == null
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
            Tst.makeText(this, "La grabación fue demasiado corta. Intenta de nuevo.", Toast.LENGTH_LONG).show()
            info.text = idleHint()
            startPreview()
            return
        }
        prefs.edit().putFloat("guideFrac", guideFrac).apply()
        val real = FpsCheck.ofFile(f, c.fps)
        if (real != null) Tst.makeText(this, "El teléfono grabó a $real fps reales (no ${c.fps}): se mide con $real fps.", Toast.LENGTH_LONG).show()
        startActivity(analysisIntent(f, real ?: c.fps, null))
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

    private fun idleHint() = when {
        timerS > 0 -> "Temporizador de $timerS s: pulsa Grabar, escucha la cuenta atrás y salta al oír «¡Salta!». " +
            "La app mide sola el salto que sigue a la señal y dice la altura."
        else -> idleHintLive()
    }

    private fun idleHintLive() =
        if (live) "En vivo: arrastra la línea naranja a los pies y pulsa Grabar. El atleta quieto 1 s y luego salta: el resultado aparece solo."
        else "Encuadra y pulsa Grabar. Detén después de aterrizar."

    private fun liveLabel() = if (live) "En vivo: Sí" else "En vivo: No"

    /** Cambia a la medición con IA (cámara normal, sin línea del suelo). */
    private fun switchToAi() {
        if (recording || starting) return
        CaptureMode.set(this, ai = true)
        startActivity(MainActivity.forward(intent, Intent(this, AiCaptureActivity::class.java)))
        finish()
    }

    private fun toggleLive() {
        if (recording || starting) return
        live = !live
        prefs.edit().putBoolean("liveMode", live).apply()
        liveBtn.text = liveLabel()
        info.text = idleHint()
    }

    // ------------------------------------------------------------------
    // Temporizador
    // ------------------------------------------------------------------

    private fun timerLabel() = if (timerS == 0) "⏱ Sin temporizador" else "⏱ $timerS s"

    private fun cycleTimer() {
        if (recording || starting || countdown != null) return
        timerS = TIMER_OPTIONS[(TIMER_OPTIONS.indexOf(timerS) + 1) % TIMER_OPTIONS.size]
        prefs.edit().putInt("timerS", timerS).apply()
        timerBtn.text = timerLabel()
        info.text = idleHint()
        if (timerS > 0) Voice.init(this, force = true)
    }

    private fun speak(t: String) = Voice.say(this, t, force = timerS > 0)

    /** Segundos que se sigue grabando después de «¡Salta!». */
    private fun postJumpS(): Double = when {
        test == TestType.RJ -> 16.0
        test.isRepeated -> 9.0
        test.isDrop -> 3.5
        else -> 3.0
    }

    private fun beep(long: Boolean) {
        try {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 100).also { tone = it }
            t.startTone(if (long) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, if (long) 450 else 140)
        } catch (_: Exception) {}
    }

    private fun showCount(s: String, color: Int = 0xFFFFFFFF.toInt()) {
        countText.text = s
        countText.setTextColor(color)
        countText.visibility = android.view.View.VISIBLE
    }

    /**
     * Cuenta atrás: «Prepárate», N… 1 y «¡Salta!». La grabación a alta velocidad arranca a 3 s del final
     * (así el video no ocupa de más) y se detiene sola poco después de la señal.
     */
    private fun startTimer() {
        if (countdown != null || recording || starting || camera == null) return
        card.visibility = android.view.View.GONE
        BigResult.hide(rootFrame)
        goAtMs = 0L
        var left = timerS
        recBtn.text = "Cancelar"
        liveBtn.isEnabled = false
        timerBtn.isEnabled = false
        info.text = "Prepárate: salta al oír «¡Salta!»"
        if (!groupMode) speak("Prepárate")
        val r = object : Runnable {
            override fun run() {
                if (countdown !== this) return
                if (left > 0) {
                    if (left <= 3 && !recording && !starting) startRecording()
                    showCount("$left")
                    beep(false)
                    speak("$left")
                    left--
                    main.postDelayed(this, 1000)
                } else {
                    countdown = null
                    goAtMs = SystemClock.elapsedRealtime()
                    showCount(Lang.tr(this@CaptureActivity, "¡SALTA!"), 0xFF6CF08A.toInt())
                    beep(true)
                    speak("¡Salta!")
                    recBtn.text = "Detener"
                    main.postDelayed({ if (countdown == null) countText.visibility = android.view.View.GONE }, 1200)
                    main.postDelayed(timerEnd, (postJumpS() * 1000).toLong())
                }
            }
        }
        countdown = r
        main.postDelayed(r, if (groupMode) 2200L else 1300L)
    }

    /** Anula la cuenta atrás y lo grabado. */
    private fun cancelTimer() {
        stopTimer()
        if (recording) haltRecording()?.delete()
        recBtn.text = "Grabar"
        recBtn.isEnabled = !starting
        liveBtn.isEnabled = true
        timerBtn.isEnabled = true
        info.text = idleHint()
        if (!starting) startPreview()
    }

    private fun stopTimer() {
        countdown?.let { main.removeCallbacks(it) }
        countdown = null
        main.removeCallbacks(timerEnd)
        goAtMs = 0L
        if (::countText.isInitialized) countText.visibility = android.view.View.GONE
    }

    /** Fin del tramo tras la señal: se detiene la grabación y se analiza desde justo antes de «¡Salta!». */
    private fun finishTimer() {
        main.removeCallbacks(timerEnd)
        if (goAtMs == 0L) return
        if (starting) { main.postDelayed(timerEnd, 300); return }
        countText.visibility = android.view.View.GONE
        if (!recording) {
            goAtMs = 0L
            restartLive("La grabación falló. Se reinició.")
            return
        }
        val goS = ((goAtMs - startMs) / 1000.0).coerceAtLeast(0.0)
        goAtMs = 0L
        liveHandled = true
        onLiveJump(LiveDetector.Live(null, null, null, emptyList(), 0.0), timerGoS = goS)
    }

    /** Cada cuadro de la vista previa: se lee la franja del suelo y se busca un salto terminado. */
    private fun onPreviewFrame() {
        if (!recording || !live || liveHandled || timerS > 0) return
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
        val strip = LiveDetector.strip(b, guideFrac)
        // Si el análisis se atrasa, se descartan cuadros sueltos en lugar de acumular trabajo.
        if (liveBusy > 3) return
        liveBusy++
        val gen = liveGen
        liveExec.execute {
            val res = try { detector.add(t, strip) } catch (_: Exception) { null }
            main.post {
                liveBusy--
                if (res != null && gen == liveGen && recording && !liveHandled) {
                    liveHandled = true
                    onLiveJump(res)
                }
            }
        }
    }

    /** Cambia en cada grabación: descarta resultados de una grabación anterior. */
    @Volatile private var liveGen = 0

    private fun fmt(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    /** Salto detectado en la vista previa: valor provisional y medición definitiva a alta velocidad. */
    private fun onLiveJump(l: LiveDetector.Live, timerGoS: Double? = null) {
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
        if (timerGoS != null) info.text = "Midiendo el salto…"
        showCard(
            status = if (timerGoS != null) fmt("Midiendo a %d fps el salto tras la señal…", c.fps)
                else fmt("Provisional (vista previa, %.0f fps) · midiendo a %d fps…", l.previewFps, c.fps),
            big = when {
                test.isRepeated -> fmt("%d saltos", l.eventsS.size / 2)
                else -> l.flightS?.let { fmt("≈ %.1f cm", Calc.heightCm(it)) } ?: "…"
            },
            bigColor = 0xFF8C99AD.toInt(),
            detail = l.flightS?.takeIf { !test.isRepeated }?.let { fmt("Vuelo ≈ %.0f ms", it * 1000) } ?: ""
        )
        cardButtons.removeAllViews()
        cardPrimary = null

        val g = guideFrac
        val t = test
        val tech = Technique.enabled(this)
        work.execute {
            var r: AutoDetect.Result? = null
            var fps = c.fps
            var flags: List<String> = emptyList()
            var fpsNote: String? = null
            try {
                val src = FrameSource(this, Uri.fromFile(f))
                try {
                    // Comprobación de los fps reales del archivo: algunos teléfonos entregan menos de lo pedido sin avisar.
                    FpsCheck.real(src.fileFps, c.fps)?.let { real ->
                        fpsNote = "El teléfono grabó a $real fps reales (no ${c.fps}): se mide con $real fps."
                        fps = real
                    }
                    fun scan(range: IntRange?): AutoDetect.Result? {
                        val strips = if (range == null) src.scanStrip(AutoDetect.X0, AutoDetect.X1, AutoDetect.bandTop(g), AutoDetect.bandBottom(g), progress = {}, cancelled = { isFinishing })
                            else src.scanStrip(AutoDetect.X0, AutoDetect.X1, AutoDetect.bandTop(g), AutoDetect.bandBottom(g), progress = {}, cancelled = { isFinishing },
                                from = range.first, to = range.last + 1)
                        val res = strips?.let { AutoDetect.detect(it, fps, t) } ?: return null
                        return if (range == null) res else res.shift(range.first)
                    }
                    fun ok(x: AutoDetect.Result?) = x != null &&
                        (if (t.isRepeated) x.events.size >= 2 else x.takeoff != null && x.landing != null)
                    fun flightS(x: AutoDetect.Result?): Double? = when {
                        x == null -> null
                        t.isRepeated -> x.events.takeIf { it.size >= 2 }?.let { (it[1] - it[0]).toDouble() / fps }
                        x.takeoff != null && x.landing != null -> (x.landing - x.takeoff).toDouble() / fps
                        else -> null
                    }
                    val liveFlight = if (t.isRepeated) l.eventsS.takeIf { it.size >= 2 }?.let { it[1] - it[0] } else l.flightS
                    fun off(x: AutoDetect.Result?): Double = flightS(x)?.let { f -> liveFlight?.let { abs(f - it) } } ?: 9.0
                    val n = src.frameCount
                    if (n > 1) {
                        // Temporizador: desde 1,2 s antes de «¡Salta!» (el atleta aún quieto) hasta el final.
                        val win = if (timerGoS != null)
                            (((timerGoS - 1.2) * fps).toInt().coerceIn(0, n - 1))..(n - 1)
                            else LiveDetector.window(l, t, fps, n)
                        r = scan(win)
                        // Si no salió, o difiere mucho de la vista previa, se analiza el video entero y se queda el más coherente.
                        if (!ok(r) || (liveFlight != null && off(r) > 0.08)) {
                            val full = scan(null)
                            if (ok(full) && (!ok(r) || off(full) < off(r))) r = full
                        }
                        if (!ok(r)) r = null
                    }
                    // Control de técnica con el modelo de pose (si el cuerpo se ve en el video).
                    val rr = r
                    if (tech && rr?.takeoff != null && rr.landing != null && !t.isRepeated) {
                        val tk = listOfNotNull(src.frame(rr.takeoff - 3), src.frame(rr.takeoff))
                        val ld = listOfNotNull(src.frame(rr.landing - 2), src.frame(rr.landing))
                        flags = Technique.checkVideo(t, tk, ld)
                    }
                } finally {
                    src.release()
                }
            } catch (_: Exception) {
                r = null
            }
            val fpsFinal = fps; val flagsFinal = flags; val noteFinal = fpsNote
            main.post { if (!isFinishing && !isDestroyed) onPrecise(f, fpsFinal, l, r, flagsFinal, noteFinal) }
        }
    }

    private fun onPrecise(f: File, fps: Int, l: LiveDetector.Live, r: AutoDetect.Result?,
                          flags: List<String> = emptyList(), fpsNote: String? = null) {
        // Batería F-V y saltos repetidos tienen su propio flujo de guardado: van al análisis con las marcas puestas.
        if (battery || (test.isRepeated && !groupMode)) {
            startActivity(analysisIntent(f, fps, r))
            finish()
            return
        }
        val mass = (if (groupMode) null else intent.getDoubleExtra(MainActivity.EX_MASS, 0.0).takeIf { it > 0 })
            ?: Store.athlete(this, athleteName()).massKg
        val box = intent.getDoubleExtra(MainActivity.EX_BOX, 0.0).takeIf { it > 0 }
        val load = if (test == TestType.SJ || test == TestType.CMJ) intent.getDoubleExtra(MainActivity.EX_LOAD, 0.0) else null
        val out = when {
            test.isRepeated -> r?.events?.takeIf { it.size >= 2 }?.let { ev ->
                // Serie de saltos (sesión de grupo): vuelos y contactos desde las marcas.
                val n = ev.size / 2
                val flights = (0 until n).map { (ev[2 * it + 1] - ev[2 * it]).toDouble() / fps }
                val contacts = (1 until n).map { (ev[2 * it] - ev[2 * it - 1]).toDouble() / fps }
                JumpCalc.repeated(athleteName(), flights, contacts, mass, fps, test)?.let { j ->
                    JumpCalc.Out(j, fmt("%d saltos · altura media %.1f cm", n, j.heightCm) +
                        (j.rsi?.let { fmt(" · RSI %.2f", it) } ?: "") + (j.contactMs?.let { fmt(" · contacto %.0f ms", it) } ?: ""))
                }
            }
            r?.takeoff != null && r.landing != null ->
                JumpCalc.single(this, test, athleteName(), fps, r.takeoff, r.landing, r.contact, mass, box, load)
            else -> null
        }

        cardButtons.removeAllViews()
        cardPrimary = null
        recBtn.isEnabled = true
        if (out == null) {
            info.text = "Revisa el salto cuadro a cuadro."
            showCard(
                status = "No se pudo medir a $fps fps con seguridad.",
                big = l.flightS?.let { fmt("≈ %.1f cm", Calc.heightCm(it)) } ?: "—",
                bigColor = 0xFF8C99AD.toInt(),
                detail = if (test.isDrop) "Falta el contacto inicial tras la caída. Márcalo a mano en la revisión."
                    else "Revisa la línea del suelo y marca a mano el despegue y el aterrizaje."
            )
            cardButtons.addView(button("Revisar cuadro a cuadro", true) {
                startActivity(analysisIntent(f, fps, r)); finish()
            }, lp())
            cardButtons.addView(button("Repetir salto") { f.delete(); restartLive(null) }, lp())
            cardPrimary = null
            speak("No se pudo medir. Repite el salto.")
            return
        }

        val diff = l.flightS?.let { abs(out.jump.flightMs / 1000.0 - it) }
        val status = if (diff != null && diff > 0.1)
            "Definitivo a $fps fps · difiere de la vista previa: conviene revisarlo"
        else "Definitivo · ${test.short} · ${athleteName()}"
        info.text = "Salto medido."
        val warn = (listOfNotNull(fpsNote) + flags).joinToString("") { "\n⚠ $it" }
        val bigTxt = if (test.isRepeated && out.jump.rsi != null) fmt("RSI %.2f", out.jump.rsi) else fmt("%.1f cm", out.jump.heightCm)
        showCard(status, bigTxt, if (flags.isEmpty()) 0xFFFFFFFF.toInt() else 0xFFFFC04D.toInt(), out.detail + warn)
        // Resultado en voz alta y en grande.
        val said = when {
            test.isRepeated && out.jump.rsi != null -> "RSI " + Voice.num(this, out.jump.rsi, 2)
            test.isDrop && out.jump.rsi != null -> Voice.num(this, out.jump.heightCm) + " centímetros. RSI " + Voice.num(this, out.jump.rsi, 2)
            else -> Voice.num(this, out.jump.heightCm) + " centímetros"
        }
        speak("${athleteName()}, $said" + if (flags.contains(Technique.MSG_KNEES)) ". Atención: rodillas flexionadas al aterrizar." else "")
        BigResult.show(rootFrame, athleteName(), bigTxt, out.detail.lineSequence().firstOrNull() ?: "",
            if (flags.isEmpty()) 0xFFFFFFFF.toInt() else 0xFFFFC04D.toInt())

        val doSave = {
            cancelAutoSave()
            BigResult.hide(rootFrame)
            // El video queda ligado al salto: se puede revisar o volver a medir a mano desde el historial.
            val keep = Videos.enabled(this)
            val mk = when {
                test.isRepeated -> r?.events
                test.isDrop -> listOfNotNull(r?.contact, r?.takeoff, r?.landing).takeIf { it.size == 3 }
                else -> listOfNotNull(r?.takeoff, r?.landing).takeIf { it.size == 2 }
            }
            val saved = out.jump.copy(video = if (keep) Uri.fromFile(f).toString() else null, marks = mk ?: emptyList(), flags = flags)
            Store.add(this, saved)
            val note = listOfNotNull(JumpCalc.asymmetryNote(this, saved), Attempts.afterSave(this, saved)).joinToString("\n").ifEmpty { null }
            Tst.makeText(this, fmt("Guardado: %.1f cm", saved.heightCm) + (if (keep) " · con video" else "") +
                (note?.let { "\n$it" } ?: ""), Toast.LENGTH_LONG).show()
            if (!keep) f.delete()
            nextTurn()
        }
        val saveBtn = button(if (groupMode) "Guardar y siguiente atleta" else "Guardar y siguiente", true) { doSave() }
        cardButtons.addView(saveBtn, lp())
        cardPrimary = saveBtn
        val row = hbox(8)
        row.addView(button("Revisar") { cancelAutoSave(); startActivity(analysisIntent(f, fps, r)); finish() },
            lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Repetir") { cancelAutoSave(); f.delete(); restartLive(null) },
            lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cardButtons.addView(row, lp())
        // En la sesión de grupo se guarda solo a los 4 s, salvo que se pulse Repetir o Revisar.
        if (groupMode) startAutoSave(saveBtn, 4) { doSave() }
    }

    private fun startAutoSave(b: Button, seconds: Int, action: () -> Unit) {
        cancelAutoSave()
        val base = b.text.toString()
        var left = seconds
        val r = object : Runnable {
            override fun run() {
                if (autoSave !== this) return
                if (left <= 0) { autoSave = null; action(); return }
                b.text = "$base ($left)"
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
    }

    private fun topLabel(): String {
        val c = cfg
        val s = if (groupMode) GroupSession.get(this) else null
        val who = s?.label() ?: "${test.label} · ${intent.getStringExtra(MainActivity.EX_ATHLETE) ?: ""}"
        return (if (s != null) "${test.label} · " else "") + who + "\n${c?.fps} fps · ${c?.size?.width}×${c?.size?.height}" +
            if (c?.highSpeed == false) "  (sin modo cámara lenta)" else ""
    }

    /** Tras guardar: siguiente salto del mismo atleta, o siguiente turno en la sesión de grupo. */
    private fun nextTurn() {
        if (!groupMode) { restartLive(null); return }
        val s = GroupSession.advance(this)
        if (s == null || s.finished) {
            speak("Sesión terminada. Buen trabajo.")
            card.visibility = android.view.View.GONE
            finish()
            return
        }
        topText.text = topLabel()
        speak(GroupSession.announce(s))
        restartLive(null)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (RemoteKeys.isTrigger(keyCode)) {
            if (event.repeatCount > 0) return true
            val p = cardPrimary
            when {
                card.visibility == android.view.View.VISIBLE && p != null && p.isEnabled -> p.performClick()
                card.visibility != android.view.View.VISIBLE && !starting -> onRecordPressed()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        if (RemoteKeys.isTrigger(keyCode)) true else super.onKeyUp(keyCode, event)

    private fun athleteName() = (if (groupMode) GroupSession.get(this)?.current?.first else null)
        ?: intent.getStringExtra(MainActivity.EX_ATHLETE) ?: "Sin nombre"

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
        cancelAutoSave()
        stopTimer()
        cardPrimary = null
        card.visibility = android.view.View.GONE
        if (recording) haltRecording()?.delete()
        liveHandled = false
        msg?.let { Tst.makeText(this, it, Toast.LENGTH_LONG).show() }
        info.text = idleHint()
        if (timerS > 0) {
            // Con temporizador: vuelve a la vista previa y empieza otra cuenta atrás.
            recBtn.isEnabled = true
            liveBtn.isEnabled = true
            timerBtn.isEnabled = true
            startPreview()
            if (camera != null) main.postDelayed({ if (!isFinishing && !recording && !starting && countdown == null &&
                card.visibility != android.view.View.VISIBLE) startTimer() }, 1500)
            return
        }
        if (camera != null) startRecording() else startPreview()
    }

    override fun onDestroy() {
        try { tone?.release() } catch (_: Exception) {}
        tone = null
        work.shutdownNow()
        liveExec.shutdownNow()
        super.onDestroy()
    }
}
