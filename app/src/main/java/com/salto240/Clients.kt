package com.salto240

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Period
import java.util.Locale

data class Group(val id: Long, val name: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)

    companion object {
        fun fromJson(o: JSONObject) = Group(o.optLong("id"), o.optString("name"))
    }
}

/** Ficha del cliente con los datos que necesitan las mediciones. */
data class Client(
    val id: Long,
    val name: String,
    val groupId: Long? = null,
    val birthDate: String? = null,     // yyyy-MM-dd
    val sex: String? = null,           // "Masculino" / "Femenino"
    val massKg: Double? = null,
    val heightCm: Double? = null,
    val sport: String = "",
    val position: String = "",         // posición o prueba
    val dominantLeg: String? = null,   // "Derecha" / "Izquierda"
    val legLengthCm: Double? = null,   // trocánter mayor → punta del pie (perfil F-V)
    val crouchCm: Double? = null,      // trocánter mayor → suelo a 90° (perfil F-V)
    val notes: String = ""
) {
    val age: Int?
        get() = try {
            birthDate?.let { Period.between(LocalDate.parse(it), LocalDate.now()).years }
        } catch (e: Exception) { null }

    val bmi: Double?
        get() {
            val m = massKg ?: return null
            val h = heightCm ?: return null
            return if (h > 0) m / ((h / 100) * (h / 100)) else null
        }

    fun toAthlete() = Athlete(name, massKg, legLengthCm, crouchCm)

    /** Línea breve: "28 años · 72 kg · 178 cm · Fútbol (delantero)". */
    fun summary(): String {
        val parts = mutableListOf<String>()
        age?.let { parts.add("$it años") }
        massKg?.let { parts.add(String.format(Locale.getDefault(), "%.1f kg", it)) }
        heightCm?.let { parts.add(String.format(Locale.getDefault(), "%.0f cm", it)) }
        if (sport.isNotBlank()) parts.add(if (position.isNotBlank()) "$sport ($position)" else sport)
        return if (parts.isEmpty()) "Sin datos: completa la ficha" else parts.joinToString(" · ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("sport", sport); put("position", position); put("notes", notes)
        groupId?.let { put("groupId", it) }
        birthDate?.let { put("birthDate", it) }
        sex?.let { put("sex", it) }
        massKg?.let { put("massKg", it) }
        heightCm?.let { put("heightCm", it) }
        dominantLeg?.let { put("dominantLeg", it) }
        legLengthCm?.let { put("legLengthCm", it) }
        crouchCm?.let { put("crouchCm", it) }
    }

    companion object {
        private fun JSONObject.optD(k: String): Double? = if (has(k)) optDouble(k) else null
        private fun JSONObject.optS(k: String): String? = if (has(k)) optString(k) else null

        fun fromJson(o: JSONObject) = Client(
            id = o.optLong("id"),
            name = o.optString("name"),
            groupId = if (o.has("groupId")) o.optLong("groupId") else null,
            birthDate = o.optS("birthDate"),
            sex = o.optS("sex"),
            massKg = o.optD("massKg"),
            heightCm = o.optD("heightCm"),
            sport = o.optString("sport"),
            position = o.optString("position"),
            dominantLeg = o.optS("dominantLeg"),
            legLengthCm = o.optD("legLengthCm"),
            crouchCm = o.optD("crouchCm"),
            notes = o.optString("notes")
        )
    }
}

/** Almacenamiento de clientes y grupos. Los saltos se enlazan al cliente por su nombre. */
object Clients {
    private const val PREFS = "salto240"
    private const val KEY_CLIENTS = "clients"
    private const val KEY_GROUPS = "groups"
    private const val KEY_MIGRATED = "clientsMigrated"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- clientes ----------------

    fun all(ctx: Context): List<Client> {
        migrate(ctx)
        return readClients(ctx).sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    private fun readClients(ctx: Context): MutableList<Client> {
        val arr = try { JSONArray(prefs(ctx).getString(KEY_CLIENTS, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> Client.fromJson(o) } }.toMutableList()
    }

    private fun writeClients(ctx: Context, list: List<Client>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_CLIENTS, arr.toString()).apply()
    }

    fun byName(ctx: Context, name: String): Client? = all(ctx).firstOrNull { it.name == name }
    fun byId(ctx: Context, id: Long): Client? = all(ctx).firstOrNull { it.id == id }

    /**
     * Crea o actualiza un cliente. Devuelve un mensaje de error o null si se guardó.
     * Si cambia el nombre, sus saltos pasan al nombre nuevo.
     */
    fun save(ctx: Context, c: Client): String? {
        val name = c.name.trim()
        if (name.isEmpty()) return "Escribe el nombre."
        val list = readClients(ctx)
        if (list.any { it.id != c.id && it.name.equals(name, ignoreCase = true) }) return "Ya existe un cliente con ese nombre."
        val old = list.firstOrNull { it.id == c.id }
        val saved = c.copy(name = name)
        if (old == null) list.add(saved) else list[list.indexOf(old)] = saved
        writeClients(ctx, list)
        if (old != null && old.name != name) Store.renameAthlete(ctx, old.name, name)
        return null
    }

    fun delete(ctx: Context, id: Long, deleteJumps: Boolean) {
        val list = readClients(ctx)
        val c = list.firstOrNull { it.id == id } ?: return
        writeClients(ctx, list.filter { it.id != id })
        if (deleteJumps) Store.deleteAthleteJumps(ctx, c.name)
    }

    /** Guarda solo los campos no nulos (lo usa el perfil F-V); crea el cliente si no existe. */
    fun mergeAthlete(ctx: Context, a: Athlete) {
        if (a.name.isBlank()) return
        val prev = byName(ctx, a.name) ?: Client(System.currentTimeMillis(), a.name)
        save(ctx, prev.copy(
            massKg = a.massKg ?: prev.massKg,
            legLengthCm = a.legLengthCm ?: prev.legLengthCm,
            crouchCm = a.crouchCm ?: prev.crouchCm
        ))
    }

    /** Pasa los atletas de versiones anteriores (solo nombre y medidas) a fichas de cliente. */
    private fun migrate(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val list = readClients(ctx)
        var id = System.currentTimeMillis()
        val legacy = try { JSONObject(p.getString("athletes", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        for (k in legacy.keys()) {
            if (list.any { it.name == k }) continue
            val o = legacy.getJSONObject(k)
            list.add(Client(id++, k,
                massKg = if (o.has("mass")) o.optDouble("mass") else null,
                legLengthCm = if (o.has("leg")) o.optDouble("leg") else null,
                crouchCm = if (o.has("crouch")) o.optDouble("crouch") else null))
        }
        for (n in Store.all(ctx).map { it.athlete }.distinct()) {
            if (n.isNotBlank() && list.none { it.name == n }) list.add(Client(id++, n))
        }
        writeClients(ctx, list)
        p.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    // ---------------- grupos ----------------

    fun groups(ctx: Context): List<Group> {
        val arr = try { JSONArray(prefs(ctx).getString(KEY_GROUPS, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> Group.fromJson(o) } }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    private fun writeGroups(ctx: Context, list: List<Group>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_GROUPS, arr.toString()).apply()
    }

    fun groupName(ctx: Context, id: Long?): String? = id?.let { g -> groups(ctx).firstOrNull { it.id == g }?.name }

    /** Crea un grupo y devuelve su id, o null si el nombre está vacío o repetido. */
    fun addGroup(ctx: Context, name: String): Long? {
        val n = name.trim()
        val list = groups(ctx).toMutableList()
        if (n.isEmpty() || list.any { it.name.equals(n, ignoreCase = true) }) return null
        val g = Group(System.currentTimeMillis(), n)
        list.add(g); writeGroups(ctx, list)
        return g.id
    }

    fun renameGroup(ctx: Context, id: Long, name: String): Boolean {
        val n = name.trim()
        val list = groups(ctx)
        if (n.isEmpty() || list.any { it.id != id && it.name.equals(n, ignoreCase = true) }) return false
        writeGroups(ctx, list.map { if (it.id == id) it.copy(name = n) else it })
        return true
    }

    /** Borra el grupo; sus clientes quedan sin grupo. */
    fun deleteGroup(ctx: Context, id: Long) {
        writeGroups(ctx, groups(ctx).filter { it.id != id })
        writeClients(ctx, readClients(ctx).map { if (it.groupId == id) it.copy(groupId = null) else it })
    }

    fun inGroup(ctx: Context, groupId: Long?): List<Client> =
        all(ctx).filter { groupId == null || it.groupId == groupId }
}
