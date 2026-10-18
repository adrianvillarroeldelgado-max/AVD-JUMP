package com.salto240

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copia de seguridad de todos los datos (clientes, grupos, saltos y ajustes) en un archivo .zip,
 * opcionalmente con los videos. Se guarda donde el usuario elija (Drive, Descargas, WhatsApp…)
 * y se restaura en este u otro teléfono, reemplazando o combinando con lo que ya hay.
 *
 * Respaldo automático: si se elige una carpeta, cada 7 días se escribe ahí una copia sin videos
 * al abrir la app (se conservan las 5 más recientes).
 */
object Backup {

    private const val PREFS = "salto240"
    private const val DATA = "avd-jump-datos.json"
    private const val VIDEO_DIR = "videos/"
    private const val K_LAST = "backupLast"
    private const val K_TREE = "backupTree"
    private const val K_AUTO_LAST = "backupAutoLast"
    private const val PREFIX = "AVD-jump-respaldo-"
    /** Ajustes propios del teléfono que no se copian (rutas, permisos y fechas de respaldo). */
    private val LOCAL_KEYS = setOf(K_LAST, K_TREE, K_AUTO_LAST, "updateSkip", "updateLastCheck")

    data class Info(val clients: Int, val groups: Int, val jumps: Int, val videos: Int, val created: String?)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun fileName(withVideos: Boolean) =
        PREFIX + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + (if (withVideos) "-con-videos" else "") + ".zip"

    // ---------------------------------------------------------------- exportar

    private fun prefsJson(ctx: Context): JSONObject {
        val o = JSONObject()
        for ((k, v) in prefs(ctx).all) {
            if (k in LOCAL_KEYS || v == null) continue
            val e = JSONObject()
            when (v) {
                is String -> e.put("t", "s").put("v", v)
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, e)
        }
        return o
    }

    /** Escribe la copia. Devuelve un resumen para el usuario. */
    fun export(ctx: Context, out: OutputStream, withVideos: Boolean): Info {
        val jumps = Store.all(ctx)
        val vids = if (withVideos) jumps.mapNotNull { Videos.ownFile(ctx, it.video) }.filter { it.exists() }.distinctBy { it.name } else emptyList()
        val root = JSONObject()
            .put("app", "AVD jump")
            .put("format", 1)
            .put("version", BuildConfig.VERSION_NAME)
            .put("created", Store.today())
            .put("prefs", prefsJson(ctx))
        ZipOutputStream(out.buffered()).use { z ->
            z.putNextEntry(ZipEntry(DATA))
            z.write(root.toString().toByteArray(Charsets.UTF_8))
            z.closeEntry()
            for (f in vids) {
                z.putNextEntry(ZipEntry(VIDEO_DIR + f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        prefs(ctx).edit().putLong(K_LAST, System.currentTimeMillis()).apply()
        return Info(Clients.all(ctx).size, Clients.groups(ctx).size, jumps.size, vids.size, root.optString("created"))
    }

    fun exportTo(ctx: Context, uri: Uri, withVideos: Boolean): Info =
        (ctx.contentResolver.openOutputStream(uri, "w") ?: error("No se pudo escribir el archivo.")).use { export(ctx, it, withVideos) }

    // ---------------------------------------------------------------- importar

    private class Parsed(val root: JSONObject, val videos: List<File>)

    /** Lee el zip: datos y videos (estos se descomprimen en la carpeta de videos de la app). */
    private fun read(ctx: Context, inp: InputStream): Parsed {
        var root: JSONObject? = null
        val vids = ArrayList<File>()
        val dir = Videos.dir(ctx).apply { mkdirs() }
        ZipInputStream(inp.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when {
                    e.name == DATA -> root = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                    e.name.startsWith(VIDEO_DIR) && !e.isDirectory -> {
                        val name = File(e.name).name
                        if (!name.endsWith(".mp4")) continue
                        val f = File(dir, name)
                        if (!f.exists()) f.outputStream().use { z.copyTo(it) }
                        vids.add(f)
                    }
                }
            }
        }
        val r = root ?: error("El archivo no es una copia de AVD jump.")
        if (r.optString("app") != "AVD jump") error("El archivo no es una copia de AVD jump.")
        return Parsed(r, vids)
    }

    /** Resumen del contenido de una copia sin aplicarla. */
    fun peek(ctx: Context, uri: Uri): Info {
        val stream = ctx.contentResolver.openInputStream(uri) ?: error("No se pudo abrir el archivo.")
        var root: JSONObject? = null
        var nVid = 0
        ZipInputStream(stream.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == DATA) root = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                else if (e.name.startsWith(VIDEO_DIR) && !e.isDirectory) nVid++
            }
        }
        val r = root ?: error("El archivo no es una copia de AVD jump.")
        val p = r.optJSONObject("prefs") ?: JSONObject()
        fun count(k: String) = try { JSONArray(p.optJSONObject(k)?.optString("v") ?: "[]").length() } catch (_: Exception) { 0 }
        return Info(count("clients"), count("groups"), count("jumps"), nVid, r.optString("created"))
    }

    /** Ajusta el enlace al video: los propios pasan a la carpeta de este teléfono; los de galería se quitan si no están. */
    private fun fixVideo(ctx: Context, v: String?, names: Set<String>): String? {
        if (v == null) return null
        val u = Uri.parse(v)
        if (u.scheme == "file") {
            val name = File(u.path ?: return null).name
            val local = File(Videos.dir(ctx), name)
            return if (name in names || local.exists()) Uri.fromFile(local).toString() else null
        }
        return if (Videos.available(ctx, v)) v else null
    }

    /**
     * Aplica la copia. [replace] = true borra los datos actuales y deja los de la copia;
     * false combina: agrega clientes, grupos y saltos que no existan (por nombre e id).
     */
    fun restore(ctx: Context, uri: Uri, replace: Boolean): Info {
        val parsed = (ctx.contentResolver.openInputStream(uri) ?: error("No se pudo abrir el archivo.")).use { read(ctx, it) }
        val p = parsed.root.optJSONObject("prefs") ?: JSONObject()
        val names = parsed.videos.map { it.name }.toSet()
        fun arr(k: String) = try { JSONArray(p.optJSONObject(k)?.optString("v") ?: "[]") } catch (_: Exception) { JSONArray() }
        val inJumps = (0 until arr("jumps").length()).mapNotNull { arr("jumps").optJSONObject(it)?.let { o -> Jump.fromJson(o) } }
            .map { it.copy(video = fixVideo(ctx, it.video, names)) }
        val inClients = (0 until arr("clients").length()).mapNotNull { arr("clients").optJSONObject(it)?.let { o -> Client.fromJson(o) } }
        val inGroups = (0 until arr("groups").length()).mapNotNull { arr("groups").optJSONObject(it)?.let { o -> Group.fromJson(o) } }
        val sp = prefs(ctx)

        if (replace) {
            val keep = sp.all.filterKeys { it in LOCAL_KEYS }
            val ed = sp.edit().clear()
            for (k in p.keys()) {
                if (k in LOCAL_KEYS) continue
                val e = p.optJSONObject(k) ?: continue
                when (e.optString("t")) {
                    "s" -> ed.putString(k, e.optString("v"))
                    "b" -> ed.putBoolean(k, e.optBoolean("v"))
                    "i" -> ed.putInt(k, e.optInt("v"))
                    "l" -> ed.putLong(k, e.optLong("v"))
                    "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                    "set" -> e.optJSONArray("v")?.let { a -> ed.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet()) }
                }
            }
            for ((k, v) in keep) when (v) { is String -> ed.putString(k, v); is Long -> ed.putLong(k, v); is Boolean -> ed.putBoolean(k, v) }
            ed.commit()
            Store.save(ctx, inJumps)
            return Info(inClients.size, inGroups.size, inJumps.size, parsed.videos.size, parsed.root.optString("created"))
        }

        // Combinar: grupos por nombre, clientes por nombre, saltos por id.
        val groupIdMap = HashMap<Long, Long>()
        var addedGroups = 0
        for (g in inGroups) {
            val existing = Clients.groups(ctx).firstOrNull { it.name.equals(g.name, ignoreCase = true) }
            val id = existing?.id ?: Clients.addGroup(ctx, g.name)?.also { addedGroups++ } ?: continue
            groupIdMap[g.id] = id
        }
        var addedClients = 0
        var nextId = System.currentTimeMillis()
        for (c in inClients) {
            if (Clients.byName(ctx, c.name) != null) continue
            if (Clients.save(ctx, c.copy(id = nextId++, groupId = c.groupId?.let { groupIdMap[it] })) == null) addedClients++
        }
        val cur = Store.all(ctx)
        val ids = cur.map { it.id }.toHashSet()
        val add = inJumps.filter { it.id !in ids }
        Store.save(ctx, (cur + add).sortedByDescending { it.date })
        return Info(addedClients, addedGroups, add.size, parsed.videos.size, parsed.root.optString("created"))
    }

    // ---------------------------------------------------------------- recordatorio y respaldo automático

    fun lastBackup(ctx: Context): Long = maxOf(prefs(ctx).getLong(K_LAST, 0L), prefs(ctx).getLong(K_AUTO_LAST, 0L))

    /** ¿Conviene recordar hacer una copia? (hay datos y la última tiene más de 14 días). */
    fun reminderDue(ctx: Context): Boolean {
        if (autoFolder(ctx) != null) return false
        val n = Store.all(ctx).size
        if (n < 5) return false
        return System.currentTimeMillis() - lastBackup(ctx) > 14L * 24 * 3600_000
    }

    fun autoFolder(ctx: Context): Uri? = prefs(ctx).getString(K_TREE, null)?.let { Uri.parse(it) }

    fun setAutoFolder(ctx: Context, tree: Uri?) {
        if (tree != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {}
            prefs(ctx).edit().putString(K_TREE, tree.toString()).putLong(K_AUTO_LAST, 0L).apply()
        } else prefs(ctx).edit().remove(K_TREE).apply()
    }

    /** Escribe una copia en la carpeta automática. Devuelve el nombre del archivo o lanza error. */
    fun writeAuto(ctx: Context): String {
        val tree = autoFolder(ctx) ?: error("No hay carpeta elegida.")
        val cr = ctx.contentResolver
        val dirDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = fileName(false)
        val doc = DocumentsContract.createDocument(cr, dirDoc, "application/zip", name) ?: error("No se pudo crear el archivo.")
        (cr.openOutputStream(doc, "w") ?: error("No se pudo escribir.")).use { export(ctx, it, false) }
        prefs(ctx).edit().putLong(K_AUTO_LAST, System.currentTimeMillis()).apply()
        prune(ctx, tree)
        return name
    }

    /** Conserva las 5 copias automáticas más recientes. */
    private fun prune(ctx: Context, tree: Uri) {
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val found = ArrayList<Pair<String, String>>()
            ctx.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(1) ?: continue
                    if (n.startsWith(PREFIX) && !n.contains("con-videos")) found.add(c.getString(0) to n)
                }
            }
            found.sortedByDescending { it.second }.drop(5).forEach { (id, _) ->
                try { DocumentsContract.deleteDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** Al abrir la app: copia automática si toca (cada 7 días). Llamar fuera del hilo principal. */
    fun autoIfDue(ctx: Context) {
        if (autoFolder(ctx) == null) return
        if (System.currentTimeMillis() - prefs(ctx).getLong(K_AUTO_LAST, 0L) < 7L * 24 * 3600_000) return
        if (Store.all(ctx).isEmpty() && Clients.all(ctx).isEmpty()) return
        try { writeAuto(ctx) } catch (_: Exception) {}
    }

    fun pretty(ms: Long): String =
        if (ms <= 0) "nunca" else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ms))
}
