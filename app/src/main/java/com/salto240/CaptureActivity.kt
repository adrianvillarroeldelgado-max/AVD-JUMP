package com.salto240

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
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
import java.io.File
import java.util.Locale

/**
 * Graba el salto con la sesión de alta velocidad de Camera2 (240 fps cuando el
 * teléfono lo permite). Al detener, abre el análisis cuadro a cuadro.
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
            info.text = String.format(Locale.getDefault(), "● Grabando  %.1f s", s)
            // Límite de seguridad: los videos de alta velocidad ocupan mucho.
            if (s >= 15.0) stopRecording(true) else main.postDelayed(this, 100)
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

        val test = TestType.of(intent.getStringExtra(MainActivity.EX_TEST))
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

        val bottom = vbox(10).apply {
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(0x99000000.toInt())
        }
        info = text("Encuadra y pulsa Grabar. Detén después de aterrizar.", 14f, 0xFFFFFFFF.toInt())
        recBtn = button("Grabar", true) { onRecordPressed() }
        bottom.addView(info)
        bottom.addView(recBtn, lp())
        root.addView(bottom, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

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
        else texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
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
                        recording = true; starting = false
                        startMs = SystemClock.elapsedRealtime()
                        recBtn.text = "Detener"
                        recBtn.isEnabled = true
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
        info.text = "Encuadra y pulsa Grabar."
        releaseRecorder()
        outFile?.delete()
        try { session?.close() } catch (_: Exception) {}
        session = null
        startPreview()
    }

    private fun stopRecording(openAnalysis: Boolean) {
        val c = cfg ?: return
        recording = false
        main.removeCallbacks(tick)
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        var ok = true
        try { recorder?.stop() } catch (e: RuntimeException) { ok = false }
        releaseRecorder()
        try { session?.close() } catch (_: Exception) {}
        session = null

        val f = outFile
        if (!openAnalysis) {
            if (!ok) f?.delete()
            return
        }
        if (!ok || f == null || !f.exists() || f.length() == 0L) {
            f?.delete()
            Toast.makeText(this, "La grabación fue demasiado corta. Intenta de nuevo.", Toast.LENGTH_LONG).show()
            recBtn.text = "Grabar"
            info.text = "Encuadra y pulsa Grabar."
            startPreview()
            return
        }
        val i = MainActivity.forward(intent, Intent(this, AnalysisActivity::class.java))
        i.putExtra(MainActivity.EX_URI, Uri.fromFile(f).toString())
        i.putExtra(MainActivity.EX_FPS, c.fps)
        i.putExtra(EX_FLOOR_V, guideFrac)
        getSharedPreferences("salto240", MODE_PRIVATE).edit().putFloat("guideFrac", guideFrac).apply()
        startActivity(i)
        finish()
    }
}
