package com.salto240

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlin.math.abs

/**
 * Lee los cuadros de un video por índice. Cada índice es un cuadro capturado,
 * así que funciona igual si el archivo se guardó a 240 fps o se exportó a 30 fps.
 * Decodifica en bloques (más rápido que cuadro suelto) y guarda una caché reducida.
 */
class FrameSource(private val ctx: Context, private val uri: Uri) {

    private val mmr = MediaMetadataRetriever()
    val frameCount: Int
    val durationMs: Long
    private val rotation: Int
    private val rawW: Int
    private val rawH: Int
    private val cache: LruCache<Int, Bitmap>

    companion object {
        /** Lectura rápida con el decodificador de hardware (se puede desactivar para pruebas). */
        @JvmStatic var FAST = true
        private const val CHUNK = 12
        private const val MAX_SIDE = 960
    }

    init {
        if (uri.scheme == "file") mmr.setDataSource(uri.path) else mmr.setDataSource(ctx, uri)
        frameCount = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
        durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        rawW = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        rawH = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val maxBytes = (Runtime.getRuntime().maxMemory() / 4).toInt()
        cache = object : LruCache<Int, Bitmap>(maxBytes) {
            override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
        }
    }

    private fun meta(key: Int): String? = try { mmr.extractMetadata(key) } catch (e: Exception) { null }

    /** Cuadros por segundo del archivo (no necesariamente los de captura). */
    val fileFps: Double get() = if (durationMs > 0) frameCount * 1000.0 / durationMs else 0.0

    @Synchronized
    fun frame(i: Int): Bitmap? {
        if (released || i < 0 || i >= frameCount) return null
        cache.get(i)?.let { return it }
        val start = (i / CHUNK) * CHUNK
        val n = minOf(CHUNK, frameCount - start)
        try {
            val list: List<Bitmap> = if (Build.VERSION.SDK_INT >= 30) {
                val p = MediaMetadataRetriever.BitmapParams()
                p.preferredConfig = Bitmap.Config.RGB_565
                mmr.getFramesAtIndex(start, n, p)
            } else {
                mmr.getFramesAtIndex(start, n)
            }
            list.forEachIndexed { k, b -> cache.put(start + k, prepare(b)) }
        } catch (e: Throwable) {
            // Algunos decodificadores fallan en bloque: probamos el cuadro suelto.
            try { mmr.getFrameAtIndex(i)?.let { cache.put(i, prepare(it)) } } catch (_: Throwable) {}
        }
        return cache.get(i)
    }

    /** Rota (si el decodificador no lo hizo) y reduce el cuadro. */
    private fun prepare(b: Bitmap): Bitmap {
        val needRotate = (rotation == 90 || rotation == 270 || rotation == 180) &&
                rawW > 0 && b.width == rawW && b.height == rawH &&
                (rawW != rawH || rotation == 180)
        val big = maxOf(b.width, b.height)
        val scale = if (big > MAX_SIDE) MAX_SIDE.toFloat() / big else 1f
        if (!needRotate && scale == 1f) return b
        val m = Matrix()
        if (scale != 1f) m.postScale(scale, scale)
        if (needRotate) m.postRotate(rotation.toFloat())
        val out = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (out !== b) b.recycle()
        return out
    }

    /** ¿El decodificador entrega este cuadro sin girar? */
    private fun rawNeedsRotate(b: Bitmap) = (rotation == 90 || rotation == 270 || rotation == 180) &&
            rawW > 0 && b.width == rawW && b.height == rawH && (rawW != rawH || rotation == 180)

    /** Índice del píxel (en el cuadro tal como lo entrega el decodificador) para coordenadas normalizadas de la imagen mostrada. */
    private fun rawIndex(w: Int, h: Int, u: Float, v: Float, rotate: Boolean): Int {
        var x: Float; var y: Float
        if (!rotate) { x = u; y = v } else when (rotation) {
            90 -> { x = v; y = 1f - u }
            270 -> { x = 1f - v; y = u }
            else -> { x = 1f - u; y = 1f - v }
        }
        val px = (x * w).toInt().coerceIn(0, w - 1)
        val py = (y * h).toInt().coerceIn(0, h - 1)
        return py * w + px
    }

    private var pixBuf = IntArray(0)

    private fun gray(c: Int) = (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10f

    /**
     * Recorre todos los cuadros y devuelve, para cada uno, los grises de una franja
     * (coordenadas normalizadas de la imagen mostrada). Lo usa la detección automática.
     */
    @Synchronized
    fun scanStrip(x0: Float, x1: Float, y0: Float, y1: Float, cols: Int = AutoDetect.COLS, rows: Int = AutoDetect.ROWS,
                  progress: (Int) -> Unit, cancelled: () -> Boolean,
                  from: Int = 0, to: Int = frameCount): List<FloatArray>? {
        // Camino rápido: decodificador de hardware leyendo solo el brillo (Y) de la franja. Si no está disponible
        // o no coincide con el camino clásico en el primer cuadro, se usa el clásico.
        if (FAST && !released) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val fast = try { scanStripCodec(x0, x1, y0, y1, cols, rows, progress, cancelled, from, to) } catch (_: Throwable) { null }
            if (fast != null) {
                lastScanMs = android.os.SystemClock.elapsedRealtime() - t0
                lastScanFast = true
                return fast
            }
            if (cancelled()) return null
        }
        val t1 = android.os.SystemClock.elapsedRealtime()
        val slow = scanStripMmr(x0, x1, y0, y1, cols, rows, progress, cancelled, from, to)
        lastScanMs = android.os.SystemClock.elapsedRealtime() - t1
        lastScanFast = false
        return slow
    }

    /** Duración del último recorrido y si fue por el camino rápido (para mostrarlo o depurar). */
    var lastScanMs = 0L
        private set
    var lastScanFast = false
        private set

    private fun scanStripMmr(x0: Float, x1: Float, y0: Float, y1: Float, cols: Int, rows: Int,
                             progress: (Int) -> Unit, cancelled: () -> Boolean,
                             from: Int, to: Int): List<FloatArray>? {
        val first = from.coerceIn(0, frameCount)
        val last = to.coerceIn(first, frameCount)
        val total = (last - first).coerceAtLeast(1)
        val out = ArrayList<FloatArray>(last - first)
        val chunk = 16
        var start = first
        while (start < last) {
            if (released || cancelled()) return null
            val n = minOf(chunk, last - start)
            val frames: List<Bitmap?> = try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val p = MediaMetadataRetriever.BitmapParams()
                    p.preferredConfig = Bitmap.Config.RGB_565
                    mmr.getFramesAtIndex(start, n, p)
                } else mmr.getFramesAtIndex(start, n)
            } catch (e: Throwable) {
                (start until start + n).map { i -> try { mmr.getFrameAtIndex(i) } catch (_: Throwable) { null } }
            }
            for (b in frames) {
                val strip = FloatArray(cols * rows)
                if (b != null) {
                    val rot = rawNeedsRotate(b)
                    val w = b.width; val h = b.height
                    if (pixBuf.size != w * h) pixBuf = IntArray(w * h)
                    b.getPixels(pixBuf, 0, w, 0, 0, w, h)   // una sola copia por cuadro: mucho más rápido que getPixel
                    // Cada celda promedia 2×2 píxeles vecinos: menos ruido a 240 fps con poca luz.
                    val du = 0.5f / w; val dv = 0.5f / h
                    for (r in 0 until rows) {
                        val v = y0 + (y1 - y0) * (r + 0.5f) / rows
                        for (c in 0 until cols) {
                            val u = x0 + (x1 - x0) * (c + 0.5f) / cols
                            strip[r * cols + c] = (gray(pixBuf[rawIndex(w, h, u, v, rot)]) +
                                gray(pixBuf[rawIndex(w, h, u + du, v, rot)]) +
                                gray(pixBuf[rawIndex(w, h, u, v + dv, rot)]) +
                                gray(pixBuf[rawIndex(w, h, u + du, v + dv, rot)])) / 4f
                        }
                    }
                    b.recycle()
                } else if (out.isNotEmpty()) {
                    out.last().copyInto(strip)   // cuadro ilegible: repetimos el anterior
                }
                out.add(strip)
            }
            start += n
            progress((start - first) * 100 / total)
        }
        return out
    }

    private var released = false

    /**
     * Franja de un cuadro leída con el camino clásico (para comprobar el rápido).
     */
    private fun stripMmr(i: Int, x0: Float, x1: Float, y0: Float, y1: Float, cols: Int, rows: Int): FloatArray? =
        scanStripMmr(x0, x1, y0, y1, cols, rows, {}, { false }, i, i + 1)?.firstOrNull()

    /**
     * Camino rápido: MediaExtractor + MediaCodec (decodificador de hardware) en modo búfer. De cada cuadro se
     * lee solo el plano de brillo (Y) en los puntos de la franja: no se crean imágenes en color. Es varias
     * veces más rápido que pedir cuadros completos a MediaMetadataRetriever.
     */
    private fun scanStripCodec(x0: Float, x1: Float, y0: Float, y1: Float, cols: Int, rows: Int,
                               progress: (Int) -> Unit, cancelled: () -> Boolean,
                               from: Int, to: Int): List<FloatArray>? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "file") ex.setDataSource(uri.path!!) else ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            // Instantes de todos los cuadros, en orden de presentación: el índice i es el i-ésimo cuadro.
            val ptsList = ArrayList<Long>(maxOf(16, frameCount))
            while (true) {
                val tt = ex.sampleTime
                if (tt < 0) break
                ptsList.add(tt)
                if (!ex.advance()) break
            }
            if (ptsList.isEmpty()) return null
            ptsList.sort()
            val pts = ptsList.toLongArray()
            val nAll = pts.size
            val first = from.coerceIn(0, nAll)
            val last = to.coerceIn(first, nAll)
            if (last <= first) return emptyList()
            val fullRange = try { fmt.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL } catch (_: Exception) { false }

            ex.seekTo(pts[first], MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            try {
                fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                fmt.setInteger(MediaFormat.KEY_PRIORITY, 1)
                c.configure(fmt, null, null, 0)
            } catch (_: Exception) {
                fmt.removeKey(MediaFormat.KEY_OPERATING_RATE); fmt.removeKey(MediaFormat.KEY_PRIORITY)
                c.reset()
                c.configure(fmt, null, null, 0)
            }
            c.start()

            val out = arrayOfNulls<FloatArray>(last - first)
            var got = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val endPts = pts[last - 1]
            // Puntos de la franja en la imagen mostrada (se giran según la rotación del video).
            val us = FloatArray(cols) { x0 + (x1 - x0) * (it + 0.5f) / cols }
            val vs = FloatArray(rows) { y0 + (y1 - y0) * (it + 0.5f) / rows }
            var checked = false
            var stalls = 0
            while (!outputDone) {
                if (released || cancelled()) return null
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        val t = ex.sampleTime
                        if (size < 0 || t < 0 || t > endPts + 200_000) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, size, t, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 5_000)
                if (oi >= 0) {
                    stalls = 0
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    if (info.size > 0 || (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                        // Índice del cuadro por su instante (el más cercano).
                        var k = java.util.Arrays.binarySearch(pts, info.presentationTimeUs)
                        if (k < 0) {
                            val ins = -k - 1
                            k = when {
                                ins <= 0 -> 0
                                ins >= nAll -> nAll - 1
                                info.presentationTimeUs - pts[ins - 1] <= pts[ins] - info.presentationTimeUs -> ins - 1
                                else -> ins
                            }
                        }
                        if (k in first until last && out[k - first] == null) {
                            val img = c.getOutputImage(oi)
                            if (img == null) { c.releaseOutputBuffer(oi, false); return null }
                            val strip = FloatArray(cols * rows)
                            img.use { im ->
                                val crop = im.cropRect
                                val w = crop.width(); val h = crop.height()
                                val plane = im.planes[0]
                                val bb = plane.buffer
                                val rs = plane.rowStride; val ps = plane.pixelStride
                                val rot = rotation == 90 || rotation == 270 || rotation == 180
                                fun lum(u: Float, v: Float): Float {
                                    var x: Float; var y: Float
                                    if (!rot) { x = u; y = v } else when (rotation) {
                                        90 -> { x = v; y = 1f - u }
                                        270 -> { x = 1f - v; y = u }
                                        else -> { x = 1f - u; y = 1f - v }
                                    }
                                    val px = (x * w).toInt().coerceIn(0, w - 1) + crop.left
                                    val py = (y * h).toInt().coerceIn(0, h - 1) + crop.top
                                    val yv = (bb.get(py * rs + px * ps).toInt() and 0xFF).toFloat()
                                    return if (fullRange) yv else ((yv - 16f) * 1.164f).coerceIn(0f, 255f)
                                }
                                // Mismo muestreo que el camino clásico: 2×2 puntos vecinos por celda.
                                val dispW = if (rotation == 90 || rotation == 270) h else w
                                val dispH = if (rotation == 90 || rotation == 270) w else h
                                val du = 0.5f / dispW; val dv = 0.5f / dispH
                                for (r in 0 until rows) {
                                    val v = vs[r]
                                    for (cc in 0 until cols) {
                                        val u = us[cc]
                                        strip[r * cols + cc] = (lum(u, v) + lum(u + du, v) + lum(u, v + dv) + lum(u + du, v + dv)) / 4f
                                    }
                                }
                            }
                            // Comprobación con el camino clásico en el primer cuadro leído: misma imagen y orientación.
                            if (!checked) {
                                checked = true
                                val ref = stripMmr(k, x0, x1, y0, y1, cols, rows)
                                if (ref != null && !similar(strip, ref)) { c.releaseOutputBuffer(oi, false); return null }
                            }
                            out[k - first] = strip
                            got++
                            if (got % 16 == 0) progress(got * 100 / (last - first))
                        }
                        if (info.presentationTimeUs >= endPts && got >= last - first) outputDone = true
                    }
                    c.releaseOutputBuffer(oi, false)
                } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone && ++stalls > 200) outputDone = true    // ~1 s sin salida: se termina
                }
            }
            if (got < (last - first) * 0.9) return null
            // Cuadros que el decodificador no entregó: se repite el anterior (como el camino clásico).
            val list = ArrayList<FloatArray>(last - first)
            for (i in out.indices) {
                val f = out[i] ?: (list.lastOrNull()?.copyOf() ?: out.firstOrNull { it != null }?.copyOf() ?: return null)
                list.add(f)
            }
            progress(100)
            return list
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }

    /** Dos franjas son la misma imagen (con tolerancia por la conversión de color y la compresión). */
    private fun similar(a: FloatArray, b: FloatArray): Boolean {
        val ma = a.average().toFloat(); val mb = b.average().toFloat()
        var d = 0f; var sa = 0f
        for (i in a.indices) { d += abs((a[i] - ma) - (b[i] - mb)); sa += abs(a[i] - ma) }
        val n = a.size.toFloat()
        return d / n <= maxOf(8f, 0.35f * sa / n)
    }

    @Synchronized
    fun release() {
        released = true
        try { mmr.release() } catch (_: Exception) {}
        cache.evictAll()
    }
}
