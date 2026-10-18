package com.salto240

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * Videos de las evaluaciones.
 *
 * Cada salto guardado conserva el video de cámara lenta y los cuadros marcados, para revisarlo
 * o volver a medirlo a mano cuando se quiera. Los videos grabados por la app viven en su carpeta
 * privada (se borran al desinstalar); los elegidos de la galería se enlazan sin copiarlos.
 */
object Videos {

    private const val PREF_SAVE = "saveVideos"

    fun dir(ctx: Context): File = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir

    /** ¿Guardar el video junto al salto? (ajuste del usuario; por defecto sí) */
    fun enabled(ctx: Context) = ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).getBoolean(PREF_SAVE, true)

    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).edit().putBoolean(PREF_SAVE, on).apply()

    /** Archivo propio de la app para un video file://, o null si es de la galería. */
    fun ownFile(ctx: Context, video: String?): File? {
        if (video == null) return null
        val u = Uri.parse(video)
        if (u.scheme != "file") return null
        val f = File(u.path ?: return null)
        return if (f.parentFile?.canonicalPath == dir(ctx).canonicalPath) f else null
    }

    /** ¿Se puede abrir el video? (el archivo existe o la galería todavía da permiso). */
    fun available(ctx: Context, video: String?): Boolean {
        if (video == null) return false
        val u = Uri.parse(video)
        return try {
            if (u.scheme == "file") File(u.path!!).exists()
            else ctx.contentResolver.openFileDescriptor(u, "r")?.use { true } ?: false
        } catch (_: Exception) { false }
    }

    /** Valor a guardar en el salto para el video que se está analizando (si se guardan videos). */
    fun forJump(ctx: Context, uri: String?): String? {
        if (uri == null || !enabled(ctx)) return null
        val u = Uri.parse(uri)
        if (u.scheme == "content") {
            // Conserva el permiso de lectura del video de la galería después de reiniciar el teléfono.
            try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        }
        return uri
    }

    /** Borra el archivo propio si ningún salto lo usa. */
    fun deleteIfUnused(ctx: Context, video: String?) {
        val f = ownFile(ctx, video) ?: return
        if (Store.all(ctx).none { it.video == video }) f.delete()
    }

    /** Espacio que ocupan los videos propios enlazados a saltos, en bytes. */
    fun usedBytes(ctx: Context): Long =
        Store.all(ctx).mapNotNull { ownFile(ctx, it.video) }.distinct().sumOf { if (it.exists()) it.length() else 0L }

    fun count(ctx: Context): Int = Store.all(ctx).count { it.video != null }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.getDefault(), "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> String.format(Locale.getDefault(), "%.0f kB", bytes / 1024.0)
    }

    /** Quita el video de un salto (y borra el archivo propio). */
    fun removeFromJump(ctx: Context, j: Jump) {
        Store.update(ctx, j.copy(video = null))
        deleteIfUnused(ctx, j.video)
    }

    /** Borra todos los videos propios y desvincula los de la galería. */
    fun removeAll(ctx: Context) {
        val list = Store.all(ctx)
        val files = list.mapNotNull { ownFile(ctx, it.video) }
        Store.save(ctx, list.map { it.copy(video = null) })
        files.forEach { it.delete() }
    }

    /**
     * Borra grabaciones que no quedaron ligadas a ningún salto (descartadas o abandonadas).
     * Solo las de más de 2 horas, para no tocar una evaluación en curso.
     */
    fun cleanOrphans(ctx: Context) {
        val used = Store.all(ctx).mapNotNull { ownFile(ctx, it.video)?.canonicalPath }.toSet()
        val limit = System.currentTimeMillis() - 2 * 3600_000L
        dir(ctx).listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".mp4") && f.canonicalPath !in used && f.lastModified() < limit) f.delete()
        }
    }

    /** Copia el video a la galería del teléfono (Películas/AVD jump). Devuelve un mensaje para el usuario. */
    fun exportToGallery(ctx: Context, j: Jump): String {
        val video = j.video ?: return "Este salto no tiene video."
        if (Build.VERSION.SDK_INT < 29) return "Guardar en la galería requiere Android 10 o superior."
        if (!available(ctx, video)) return "El video ya no está disponible."
        val name = String.format(Locale.US, "AVD %s %s %s %.1fcm.mp4",
            j.athlete.replace(Regex("[^\\p{L}\\p{N} _-]"), ""), j.test.short, j.date.replace(":", "").replace(" ", "_"), j.heightCm)
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AVD jump")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val dest = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return "No se pudo crear el archivo en la galería."
            cr.openInputStream(Uri.parse(video))!!.use { inp -> cr.openOutputStream(dest)!!.use { out -> inp.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            cr.update(dest, values, null, null)
            "Video guardado en la galería: Películas/AVD jump."
        } catch (e: Exception) {
            "No se pudo guardar en la galería: ${e.message}"
        }
    }

    /** Abre el salto en el análisis cuadro a cuadro, con sus marcas, para revisarlo o volver a medirlo. */
    fun openForReview(act: Activity, j: Jump): Boolean {
        val video = j.video ?: return false
        if (!available(act, video)) return false
        val i = Intent(act, AnalysisActivity::class.java).apply {
            putExtra(MainActivity.EX_TEST, j.test.name)
            putExtra(MainActivity.EX_ATHLETE, j.athlete)
            putExtra(MainActivity.EX_MASS, j.massKg ?: 0.0)
            putExtra(MainActivity.EX_BOX, j.boxCm ?: 0.0)
            putExtra(MainActivity.EX_LOAD, j.loadKg ?: 0.0)
            putExtra(MainActivity.EX_URI, video)
            putExtra(MainActivity.EX_FPS, j.fps.coerceAtLeast(1))
            putExtra(AnalysisActivity.EX_EDIT_ID, j.id)
            val mk = j.marks
            when {
                j.test.isRepeated && mk.size >= 2 -> putExtra(AnalysisActivity.EX_PRESET_EVENTS, mk.toIntArray())
                j.test.isDrop && mk.size == 3 -> {
                    putExtra(AnalysisActivity.EX_PRESET_CONTACT, mk[0])
                    putExtra(AnalysisActivity.EX_PRESET_TAKEOFF, mk[1])
                    putExtra(AnalysisActivity.EX_PRESET_LANDING, mk[2])
                }
                mk.size == 2 -> {
                    putExtra(AnalysisActivity.EX_PRESET_TAKEOFF, mk[0])
                    putExtra(AnalysisActivity.EX_PRESET_LANDING, mk[1])
                }
            }
            if (Uri.parse(video).scheme == "content") addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        act.startActivity(i)
        return true
    }
}
