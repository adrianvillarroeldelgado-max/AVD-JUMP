package com.salto240

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache

/**
 * Lee los cuadros de un video por índice. Cada índice es un cuadro capturado,
 * así que funciona igual si el archivo se guardó a 240 fps o se exportó a 30 fps.
 * Decodifica en bloques (más rápido que cuadro suelto) y guarda una caché reducida.
 */
class FrameSource(ctx: Context, uri: Uri) {

    private val mmr = MediaMetadataRetriever()
    val frameCount: Int
    val durationMs: Long
    private val rotation: Int
    private val rawW: Int
    private val rawH: Int
    private val cache: LruCache<Int, Bitmap>

    companion object {
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

    @Synchronized
    fun release() {
        released = true
        try { mmr.release() } catch (_: Exception) {}
        cache.evictAll()
    }
}
