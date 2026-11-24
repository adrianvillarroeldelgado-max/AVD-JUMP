package com.salto240

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Sincronización entre teléfonos por una carpeta compartida (Google Drive, OneDrive, carpeta de red…).
 *
 * Cada teléfono escribe su archivo «AVD-jump-sync-<id>.json» con todos sus datos y lee los de los demás:
 * - Saltos por id: se agregan los que faltan; si el mismo salto se editó en dos teléfonos gana el cambio más reciente.
 * - Clientes y grupos por nombre; bienestar por atleta y día; sesiones de carga y pares de validación por id.
 * - Los borrados viajan como «lápidas» para que un salto borrado en un teléfono no vuelva desde otro.
 * - Los videos no se sincronizan (pesan mucho): el salto llega sin video.
 * Los datos propios del teléfono (diagnóstico, carpetas, permisos) no se comparten.
 */
object Sync {
    private const val PREFS = "salto240"
    private const val K_TREE = "syncTree"
    private const val K_LAST = "syncLast"
    private const val K_DEVICE = "syncDeviceId"
    private const val K_TOMBS = "syncTombs"
    private const val PREFIX = "AVD-jump-sync-"
    /** Claves locales que no deben ir en la copia de seguridad ni sincronizarse. */
    val LOCAL_KEYS = setOf(K_TREE, K_LAST, K_DEVICE)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceId(ctx: Context): String = prefs(ctx).getString(K_DEVICE, null) ?: UUID.randomUUID().toString().take(8).also {
        prefs(ctx).edit().putString(K_DEVICE, it).apply()
    }

    fun folder(ctx: Context): Uri? = prefs(ctx).getString(K_TREE, null)?.let { Uri.parse(it) }

    fun setFolder(ctx: Context, tree: Uri?) {
        if (tree != null) {
            try { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            prefs(ctx).edit().putString(K_TREE, tree.toString()).putLong(K_LAST, 0L).apply()
        } else prefs(ctx).edit().remove(K_TREE).apply()
    }

    fun last(ctx: Context): Long = prefs(ctx).getLong(K_LAST, 0L)

    // ---------------------------------------------------------------- lápidas

    private fun tombs(ctx: Context): JSONObject = try { JSONObject(prefs(ctx).getString(K_TOMBS, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    fun tomb(ctx: Context, kind: String, key: String) {
        val t = tombs(ctx)
        val k = t.optJSONObject(kind) ?: JSONObject()
        k.put(key, System.currentTimeMillis())
        t.put(kind, k)
        prefs(ctx).edit().putString(K_TOMBS, t.toString()).apply()
    }

    fun untomb(ctx: Context, kind: String, key: String) {
        val t = tombs(ctx)
        val k = t.optJSONObject(kind) ?: return
        if (!k.has(key)) return
        k.remove(key); t.put(kind, k)
        prefs(ctx).edit().putString(K_TOMBS, t.toString()).apply()
    }

    private fun tombSet(t: JSONObject, kind: String): Set<String> = t.optJSONObject(kind)?.keys()?.asSequence()?.toSet() ?: emptySet()

    // ---------------------------------------------------------------- instantánea

    private fun snapshot(ctx: Context): JSONObject {
        val jumps = JSONArray(); Store.all(ctx).forEach { jumps.put(it.toJson()) }
        val clients = JSONArray(); Clients.all(ctx).forEach { clients.put(it.toJson()) }
        val groups = JSONArray(); Clients.groups(ctx).forEach { groups.put(it.toJson()) }
        val loads = JSONArray(); Load.all(ctx).forEach { loads.put(it.toJson()) }
        val pairs = JSONArray(); Validation.all(ctx).forEach { pairs.put(it.toJson()) }
        return JSONObject().put("app", "AVD jump").put("format", 1).put("device", deviceId(ctx))
            .put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}").put("version", BuildConfig.VERSION_NAME)
            .put("updated", System.currentTimeMillis())
            .put("jumps", jumps).put("clients", clients).put("groups", groups)
            .put("wellness", Wellness.root(ctx)).put("loads", loads).put("pairs", pairs).put("tombs", tombs(ctx))
    }

    data class Stats(var devices: Int = 0, var jumps: Int = 0, var updated: Int = 0, var deleted: Int = 0,
                     var clients: Int = 0, var wellness: Int = 0, var loads: Int = 0, var pairs: Int = 0) {
        fun text(): String = if (devices == 0) "No hay otros teléfonos en la carpeta todavía. Tus datos ya están ahí."
            else String.format(Locale.getDefault(), "%d teléfono(s) · saltos nuevos %d, actualizados %d, borrados %d · clientes %d · bienestar %d · sesiones %d · pares %d",
                devices, jumps, updated, deleted, clients, wellness, loads, pairs)
    }

    private fun mod(j: Jump) = j.extra["mod"] ?: 0.0

    private fun fixVideo(ctx: Context, v: String?): String? {
        if (v == null) return null
        val u = Uri.parse(v)
        if (u.scheme == "file") {
            val f = File(Videos.dir(ctx), File(u.path ?: return null).name)
            return if (f.exists()) Uri.fromFile(f).toString() else null
        }
        return if (Videos.available(ctx, v)) v else null
    }

    /** Combina los datos de otro teléfono con los de este. */
    fun merge(ctx: Context, o: JSONObject, st: Stats) {
        // Lápidas: unión (así un borrado llega también a un tercer teléfono).
        val myT = tombs(ctx)
        val inT = o.optJSONObject("tombs") ?: JSONObject()
        for (kind in inT.keys()) {
            val a = myT.optJSONObject(kind) ?: JSONObject()
            val b = inT.optJSONObject(kind) ?: continue
            for (k in b.keys()) if (!a.has(k)) a.put(k, b.optLong(k))
            myT.put(kind, a)
        }
        prefs(ctx).edit().putString(K_TOMBS, myT.toString()).apply()
        val deadJ = tombSet(myT, "jump"); val deadC = tombSet(myT, "client")
        val deadL = tombSet(myT, "load"); val deadP = tombSet(myT, "pair")

        // Grupos por nombre.
        val gMap = HashMap<Long, Long>()
        o.optJSONArray("groups")?.let { a ->
            for (i in 0 until a.length()) {
                val g = Group.fromJson(a.optJSONObject(i) ?: continue)
                val ex = Clients.groups(ctx).firstOrNull { it.name.equals(g.name, true) }
                (ex?.id ?: Clients.addGroup(ctx, g.name))?.let { gMap[g.id] = it }
            }
        }
        // Clientes: borrar los que tienen lápida, agregar los que faltan.
        for (c in Clients.all(ctx)) if (c.name.lowercase(Locale.getDefault()) in deadC) {
            Clients.delete(ctx, c.id, deleteJumps = false)
        }
        var nextId = System.currentTimeMillis()
        o.optJSONArray("clients")?.let { a ->
            for (i in 0 until a.length()) {
                val c = Client.fromJson(a.optJSONObject(i) ?: continue)
                if (c.name.lowercase(Locale.getDefault()) in deadC || Clients.byName(ctx, c.name) != null) continue
                if (Clients.save(ctx, c.copy(id = nextId++, groupId = c.groupId?.let { gMap[it] })) == null) st.clients++
            }
        }
        // Saltos.
        val cur = Store.all(ctx)
        val before = cur.size
        val byId = LinkedHashMap<Long, Jump>()
        for (j in cur) if (j.id.toString() !in deadJ) byId[j.id] = j
        st.deleted += before - byId.size
        o.optJSONArray("jumps")?.let { a ->
            for (i in 0 until a.length()) {
                val r = try { Jump.fromJson(a.optJSONObject(i) ?: continue) } catch (_: Exception) { continue }
                if (r.id.toString() in deadJ) continue
                val l = byId[r.id]
                if (l == null) { byId[r.id] = r.copy(video = fixVideo(ctx, r.video)); st.jumps++ }
                else if (mod(r) > mod(l)) { byId[r.id] = r.copy(video = fixVideo(ctx, r.video) ?: l.video); st.updated++ }
            }
        }
        if (st.jumps > 0 || st.updated > 0 || byId.size != before) Store.save(ctx, byId.values.sortedByDescending { it.date })
        // Bienestar: días que falten.
        o.optJSONObject("wellness")?.let { w ->
            val mine = Wellness.root(ctx)
            var n = 0
            for (ath in w.keys()) {
                val src = w.optJSONObject(ath) ?: continue
                val dst = mine.optJSONObject(ath) ?: JSONObject()
                for (d in src.keys()) if (!dst.has(d)) { dst.put(d, src.opt(d)); n++ }
                mine.put(ath, dst)
            }
            if (n > 0) { Wellness.saveRoot(ctx, mine); st.wellness += n }
        }
        // Sesiones de carga.
        o.optJSONArray("loads")?.let { a ->
            val mine = Load.all(ctx).filter { it.id.toString() !in deadL }
            val ids = mine.map { it.id }.toHashSet()
            val add = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { x -> Load.Session.fromJson(x) } }
                .filter { it.id !in ids && it.id.toString() !in deadL }
            st.loads += add.size
            Load.save(ctx, mine + add)
        }
        // Pares de validación.
        o.optJSONArray("pairs")?.let { a ->
            val mine = Validation.all(ctx).filter { it.id.toString() !in deadP }
            val ids = mine.map { it.id }.toHashSet()
            val add = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { x -> Validation.Pair.fromJson(x) } }
                .filter { it.id !in ids && it.id.toString() !in deadP }
            st.pairs += add.size
            Validation.save(ctx, mine + add)
        }
    }

    private class Doc(val id: String, val name: String)

    private fun list(ctx: Context, tree: Uri): List<Doc> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<Doc>()
        ctx.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { c -> while (c.moveToNext()) { val n = c.getString(1) ?: continue; if (n.startsWith(PREFIX)) out.add(Doc(c.getString(0), n)) } }
        return out
    }

    /** Lee los archivos de los demás, combina y escribe el propio. Llamar fuera del hilo principal. */
    @Synchronized
    fun now(ctx: Context): Stats {
        val tree = folder(ctx) ?: error("Elige primero la carpeta compartida.")
        val cr = ctx.contentResolver
        val mine = PREFIX + deviceId(ctx) + ".json"
        val docs = list(ctx, tree)
        val st = Stats()
        for (d in docs) {
            if (d.name == mine) continue
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, d.id)
            val o = try { cr.openInputStream(uri)?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) } } catch (_: Exception) { null } ?: continue
            if (o.optString("app") != "AVD jump") continue
            merge(ctx, o, st)
            st.devices++
        }
        val data = snapshot(ctx).toString().toByteArray(Charsets.UTF_8)
        val existing = docs.firstOrNull { it.name == mine }
        val target = existing?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }
            ?: DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                "application/json", mine) ?: error("No se pudo crear el archivo en la carpeta.")
        (cr.openOutputStream(target, "wt") ?: error("No se pudo escribir en la carpeta.")).use { it.write(data) }
        prefs(ctx).edit().putLong(K_LAST, System.currentTimeMillis()).apply()
        return st
    }

    /** Al abrir la app: sincroniza si pasaron 10 minutos. Llamar fuera del hilo principal. */
    fun autoIfDue(ctx: Context) {
        if (folder(ctx) == null) return
        if (System.currentTimeMillis() - last(ctx) < 10 * 60_000L) return
        try { now(ctx) } catch (_: Exception) {}
    }
}

class SyncActivity : BaseActivity() {
    private lateinit var status: TextView
    private lateinit var folderTxt: TextView
    private val REQ_TREE = 61

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vbox(14).apply { setPadding(dp(16), dp(20), dp(16), dp(24)) }
        root.addView(text("Sincronizar teléfonos", 26f, Col.INK, true))
        root.addView(text("Para medir con dos o más teléfonos (entrenador y ayudante) y ver los mismos datos. " +
            "Cada teléfono elige la misma carpeta compartida, por ejemplo una carpeta de Google Drive. " +
            "Se sincroniza al abrir la app (cada 10 minutos como mucho) y con el botón.", 14f, Col.MUTED))
        val p = panel()
        folderTxt = text("", 14f, Col.INK)
        p.addView(folderTxt)
        p.addView(button("Elegir carpeta compartida") {
            try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE) }
            catch (_: Exception) { Tst.makeText(this, "No hay un selector de carpetas en este teléfono.", Toast.LENGTH_LONG).show() }
        }, lp())
        p.addView(button("Sincronizar ahora", true) { run() }, lp())
        p.addView(button("Dejar de sincronizar") { Sync.setFolder(this, null); refresh() }, lp())
        status = text("", 14f, Col.INK)
        p.addView(status)
        root.addView(p, lp())
        root.addView(text("Qué se comparte: clientes, grupos, saltos, bienestar, sesiones de carga y pares de validación. " +
            "Los videos no viajan (ocupan mucho): el salto llega sin video. Si se edita el mismo salto en dos teléfonos, queda el cambio más reciente. " +
            "Los borrados también se comparten.\n\nCon Google Drive: instala la app de Drive y, en el selector, elige Drive y una carpeta. " +
            "Todos los teléfonos deben usar la misma cuenta o una carpeta compartida entre las cuentas.", 12f, Col.MUTED))
        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })
        refresh()
    }

    private fun refresh() {
        val f = Sync.folder(this)
        folderTxt.text = if (f == null) "Sin carpeta: no se sincroniza." else "Carpeta: " + (f.lastPathSegment?.substringAfterLast(':') ?: f.toString()) +
            "\nÚltima sincronización: " + Backup.pretty(Sync.last(this)) + "\nEste teléfono: " + Sync.deviceId(this)
    }

    private fun run() {
        if (Sync.folder(this) == null) { Tst.makeText(this, "Elige primero la carpeta compartida.", Toast.LENGTH_SHORT).show(); return }
        status.text = "Sincronizando…"; status.setTextColor(Col.INK)
        val app = applicationContext
        Thread {
            val r = try { Result.success(Sync.now(app)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                r.onSuccess { status.text = it.text(); status.setTextColor(Col.OK) }
                    .onFailure { status.text = "No se pudo sincronizar: " + (it.message ?: ""); status.setTextColor(Col.WARN) }
                refresh()
            }
        }.start()
    }

    @Deprecated("API de resultados clásica")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { Sync.setFolder(this, it); refresh(); run() }
        }
    }
}
