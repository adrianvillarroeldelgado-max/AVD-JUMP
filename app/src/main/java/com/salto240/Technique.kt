package com.salto240

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.TimeUnit
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Control de técnica con el modelo de pose (el mismo de la medición con IA).
 *
 * - Aterrizaje con rodillas flexionadas: si el atleta encoge las piernas antes de tocar el suelo, el
 *   tiempo de vuelo crece y la altura sale inflada. Se mira el ángulo de rodilla (cadera–rodilla–tobillo)
 *   justo al aterrizar: por debajo de 150° se avisa.
 * - Manos fuera de la cadera en las pruebas sin brazos (CMJ, SJ, a una pierna, 10/5): se compara la
 *   distancia muñeca–cadera con el largo del tronco.
 *
 * Solo avisa: el salto se guarda igual y el entrenador decide si lo repite.
 */
object Technique {

    /** Datos de un cuadro. [knee]: ángulo de rodilla más extendido de las dos piernas visibles (grados). */
    data class Frame(val t: Double, val knee: Double?, val handsOff: Boolean?)

    const val KNEE_MIN = 150.0
    const val MSG_KNEES = "Aterrizó con las rodillas flexionadas: la altura puede salir inflada. Conviene repetirlo."
    const val MSG_HANDS = "Manos fuera de la cadera en el despegue: en esta prueba van en la cadera."

    private const val PREF = "techCheck"
    fun enabled(ctx: Context) = ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).getBoolean(PREF, true)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()

    /** Pruebas en las que las manos van en la cadera. */
    fun handsOnHips(t: TestType) = t == TestType.CMJ || t == TestType.SJ || t == TestType.CMJ_L ||
        t == TestType.CMJ_R || t == TestType.RJ105

    private fun pt(p: Pose, type: Int): PoseLandmark? = p.getPoseLandmark(type)?.takeIf { it.inFrameLikelihood > 0.6f }

    private fun angle(a: PoseLandmark, b: PoseLandmark, c: PoseLandmark): Double {
        val ux = (a.position.x - b.position.x).toDouble(); val uy = (a.position.y - b.position.y).toDouble()
        val vx = (c.position.x - b.position.x).toDouble(); val vy = (c.position.y - b.position.y).toDouble()
        val n = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
        if (n <= 0) return 180.0
        return Math.toDegrees(acos(((ux * vx + uy * vy) / n).coerceIn(-1.0, 1.0)))
    }

    fun kneeAngle(p: Pose): Double? {
        val sides = listOf(
            Triple(PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE),
            Triple(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE)
        )
        return sides.mapNotNull { (h, k, a) ->
            val ph = pt(p, h) ?: return@mapNotNull null
            val pk = pt(p, k) ?: return@mapNotNull null
            val pa = pt(p, a) ?: return@mapNotNull null
            angle(ph, pk, pa)
        }.maxOrNull()
    }

    fun handsOff(p: Pose): Boolean? {
        val sh = pt(p, PoseLandmark.LEFT_SHOULDER) ?: pt(p, PoseLandmark.RIGHT_SHOULDER) ?: return null
        val hip = pt(p, PoseLandmark.LEFT_HIP) ?: pt(p, PoseLandmark.RIGHT_HIP) ?: return null
        val torso = hypot((sh.position.x - hip.position.x).toDouble(), (sh.position.y - hip.position.y).toDouble())
        if (torso <= 1) return null
        val pairs = listOf(15 to PoseLandmark.LEFT_HIP, 16 to PoseLandmark.RIGHT_HIP)
        val d = pairs.mapNotNull { (w, h) ->
            val pw = pt(p, w) ?: return@mapNotNull null
            val ph = pt(p, h) ?: return@mapNotNull null
            hypot((pw.position.x - ph.position.x).toDouble(), (pw.position.y - ph.position.y).toDouble()) / torso
        }
        if (d.isEmpty()) return null
        return d.average() > 0.55
    }

    fun frame(p: Pose, t: Double) = Frame(t, kneeAngle(p), handsOff(p))

    /**
     * Avisos a partir de los cuadros alrededor del despegue y del aterrizaje.
     * Se usa el valor más favorable de cada tramo (rodilla más extendida), para no avisar por ruido.
     */
    fun check(test: TestType, atTakeoff: List<Frame>, atLanding: List<Frame>): List<String> {
        val out = ArrayList<String>()
        val land = atLanding.mapNotNull { it.knee }.maxOrNull()
        val take = atTakeoff.mapNotNull { it.knee }.maxOrNull()
        if (land != null && land < KNEE_MIN && (take == null || take > land + 8)) out.add(MSG_KNEES)
        if (handsOnHips(test)) {
            val h = atTakeoff.mapNotNull { it.handsOff }
            if (h.isNotEmpty() && h.count { it } * 2 > h.size) out.add(MSG_HANDS)
        }
        return out
    }

    /** Ventana de cuadros de una lista con tiempos (s). */
    fun around(frames: List<Frame>, t: Double, before: Double, after: Double) =
        frames.filter { it.t >= t - before && it.t <= t + after }

    /**
     * Versión para el video de 240 fps: analiza los cuadros de despegue y aterrizaje.
     * Llamar fuera del hilo principal. Si el cuerpo no se ve (cámara enfocada a los pies), no avisa.
     */
    fun checkVideo(test: TestType, takeoff: List<Bitmap>, landing: List<Bitmap>): List<String> {
        val det = try {
            PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE).build())
        } catch (_: Throwable) { return emptyList() }
        return try {
            fun run(list: List<Bitmap>) = list.mapNotNull { b ->
                try {
                    val argb = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
                    val pose = Tasks.await(det.process(InputImage.fromBitmap(argb, 0)), 3, TimeUnit.SECONDS)
                    frame(pose, 0.0)
                } catch (_: Throwable) { null }
            }
            check(test, run(takeoff), run(landing))
        } finally {
            try { det.close() } catch (_: Throwable) {}
        }
    }
}

/** Fps reales del video frente a los pedidos a la cámara. */
object FpsCheck {
    /** Devuelve los fps reales si difieren más de un 12 % de los pedidos; null si coinciden. */
    fun real(fileFps: Double, requested: Int): Int? {
        if (fileFps <= 1 || requested <= 0) return null
        return if (kotlin.math.abs(fileFps - requested) / requested > 0.12) Math.round(fileFps).toInt() else null
    }

    fun ofFile(f: java.io.File, requested: Int): Int? {
        val m = android.media.MediaMetadataRetriever()
        return try {
            m.setDataSource(f.absolutePath)
            val n = m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: return null
            val d = m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            if (d <= 0) null else real(n * 1000.0 / d, requested)
        } catch (_: Exception) { null } finally { try { m.release() } catch (_: Exception) {} }
    }
}
