package com.salto240

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Comparte archivos generados por la app (PDF, Excel, CSV) con otras apps sin permisos de almacenamiento.
 * Sirve solo archivos de la carpeta caché "compartir", y solo con permiso temporal de lectura.
 */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileOf(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        val f = File(ShareFiles.dir(ctx), name)
        return if (f.canonicalFile.parentFile == ShareFiles.dir(ctx).canonicalFile && f.exists()) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileOf(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = ShareFiles.mime(uri.lastPathSegment ?: "")

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = fileOf(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { col -> when (col) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } }.toTypedArray())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

object ShareFiles {
    fun dir(ctx: Context): File = File(ctx.cacheDir, "compartir").apply { mkdirs() }

    fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "pdf" -> "application/pdf"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    /** Archivo nuevo en la carpeta compartible (borra los de más de un día). */
    fun newFile(ctx: Context, name: String): File {
        val d = dir(ctx)
        val old = System.currentTimeMillis() - 24 * 3600_000L
        d.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
        return File(d, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
    }

    fun uri(ctx: Context, f: File): Uri = Uri.parse("content://${ctx.packageName}.share/${Uri.encode(f.name)}")

    /** Abre el menú de compartir del sistema con el archivo. */
    fun share(ctx: Context, f: File, subject: String, text: String? = null) {
        val u = uri(ctx, f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime(f.name)
            putExtra(Intent.EXTRA_STREAM, u)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Abre el archivo en una app que lo muestre (visor de PDF, Excel…). */
    fun open(ctx: Context, f: File): Boolean = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(ctx, f), mime(f.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    } catch (_: Exception) { false }
}
