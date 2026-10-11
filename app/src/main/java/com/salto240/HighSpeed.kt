package com.salto240

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.util.Size
import android.view.TextureView

/** Configuración de cámara elegida para grabar. */
data class CamConfig(
    val cameraId: String,
    val size: Size,
    val fps: Int,
    val highSpeed: Boolean,   // true = sesión de alta velocidad (cámara lenta real)
    val orientation: Int
) {
    fun describe(): String =
        if (highSpeed) "Cámara lenta disponible: $fps fps a ${size.width}×${size.height}"
        else "Este teléfono no ofrece cámara lenta a otras apps. Máximo: $fps fps a ${size.width}×${size.height}. " +
                "Para 240 fps graba con la cámara del sistema y usa “Analizar video”."
}

object HighSpeed {

    private fun sizeScore(s: Size): Int {
        val big = maxOf(s.width, s.height)
        val small = minOf(s.width, s.height)
        return when {
            big == 1280 && small == 720 -> 3
            big == 1920 && small == 1080 -> 2
            else -> 1
        }
    }

    /** Busca la cámara trasera con mejor modo de alta velocidad (prefiere 240 fps a 720p). */
    fun find(ctx: Context): CamConfig? {
        val mgr = ctx.getSystemService(CameraManager::class.java) ?: return null
        var best: CamConfig? = null
        var bestScore = -1.0

        val ids = try { mgr.cameraIdList } catch (e: Exception) { return null }
        for (id in ids) {
            val ch = try { mgr.getCameraCharacteristics(id) } catch (e: Exception) { continue }
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val orient = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)

            if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
                val sizes = try { map.highSpeedVideoSizes } catch (e: Exception) { emptyArray() }
                for (size in sizes) {
                    val ranges = try { map.getHighSpeedVideoFpsRangesFor(size) } catch (e: Exception) { emptyArray() }
                    for (r in ranges) {
                        if (r.lower != r.upper) continue
                        val fps = r.upper
                        val score = 1_000_000.0 + (if (fps == 240) 500_000.0 else fps * 1000.0) + sizeScore(size)
                        if (score > bestScore) {
                            bestScore = score
                            best = CamConfig(id, size, fps, true, orient)
                        }
                    }
                }
            }

            // Respaldo: sesión normal con el mayor fps fijo disponible.
            val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            val maxFps = ranges.filter { it.lower == it.upper }.maxOfOrNull { it.upper }
                ?: ranges.maxOfOrNull { it.upper } ?: 30
            val outSizes = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
            val size = outSizes.filter { maxOf(it.width, it.height) <= 1920 }
                .maxWithOrNull(compareBy({ sizeScore(it) }, { it.width * it.height }))
            if (size != null) {
                val score = maxFps * 1000.0 + sizeScore(size)
                if (score > bestScore) {
                    bestScore = score
                    best = CamConfig(id, size, maxFps, false, orient)
                }
            }
        }
        return best
    }
}

/** TextureView que conserva la proporción del video. */
class AutoFitTextureView(ctx: Context) : TextureView(ctx) {
    private var ratioW = 0
    private var ratioH = 0

    fun setAspect(w: Int, h: Int) {
        ratioW = w; ratioH = h
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioW == 0 || ratioH == 0) {
            setMeasuredDimension(w, h)
        } else if (w * ratioH < h * ratioW) {
            setMeasuredDimension(w, w * ratioH / ratioW)
        } else {
            setMeasuredDimension(h * ratioW / ratioH, h)
        }
    }
}
