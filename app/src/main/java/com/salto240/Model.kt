package com.salto240

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TestType(val label: String, val short: String, val help: String) {
    CMJ("CMJ", "CMJ", "Salto con contramovimiento, manos en la cadera. Con carga sirve para el perfil fuerza-velocidad."),
    CMJA("CMJ con brazos", "Abalakov", "Salto con contramovimiento y balanceo libre de brazos."),
    SJ("Squat jump (SJ)", "SJ", "Desde 90° de rodilla, sin contramovimiento, manos en la cadera. Con carga sirve para el perfil fuerza-velocidad."),
    DJ("Drop Jump", "DJ", "Dejarse caer del cajón y rebotar lo más alto y rápido posible."),
    CMJ_L("CMJ pierna izquierda", "CMJ izq.", "CMJ a una pierna, apoyo izquierdo. Úsalo con el derecho para la asimetría."),
    CMJ_R("CMJ pierna derecha", "CMJ der.", "CMJ a una pierna, apoyo derecho. Úsalo con el izquierdo para la asimetría."),
    RJ("Saltos repetidos", "Repetidos", "Saltos continuos con contacto corto (p. ej. 10 saltos o 15 s). Marca cada despegue y aterrizaje.");

    companion object {
        fun of(name: String?): TestType = values().firstOrNull { it.name == name } ?: CMJ
    }
}

data class Jump(
    val id: Long,
    val date: String,
    val athlete: String,
    val test: TestType,
    val flightMs: Double,
    val heightCm: Double,          // en RJ: altura media
    val contactMs: Double?,        // en RJ: contacto medio
    val rsi: Double?,              // en RJ: RSI medio
    val boxCm: Double?,
    val massKg: Double?,
    val powerW: Double?,
    val fps: Int,
    val notes: String,
    val loadKg: Double? = null,               // carga adicional (SJ para perfil F-V)
    val reps: List<Double> = emptyList(),     // RJ: altura de cada salto (cm)
    val repContacts: List<Double> = emptyList(), // RJ: contacto antes de cada salto (ms)
    val fatiguePct: Double? = null,           // RJ: caída de altura, primeros 3 vs últimos 3
    val video: String? = null,                // video de la evaluación (file:// propio o content:// de la galería)
    val marks: List<Int> = emptyList()        // cuadros marcados: [despegue, aterrizaje], DJ [contacto, despegue, aterrizaje], RJ todos
) {
    val day: String get() = date.take(10)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("date", date); put("athlete", athlete); put("test", test.name)
        put("flightMs", flightMs); put("heightCm", heightCm); put("fps", fps); put("notes", notes)
        contactMs?.let { put("contactMs", it) }
        rsi?.let { put("rsi", it) }
        boxCm?.let { put("boxCm", it) }
        massKg?.let { put("massKg", it) }
        powerW?.let { put("powerW", it) }
        loadKg?.let { put("loadKg", it) }
        fatiguePct?.let { put("fatiguePct", it) }
        if (reps.isNotEmpty()) put("reps", JSONArray(reps))
        if (repContacts.isNotEmpty()) put("repContacts", JSONArray(repContacts))
        video?.let { put("video", it) }
        if (marks.isNotEmpty()) put("marks", JSONArray(marks))
    }

    companion object {
        private fun JSONObject.optD(k: String): Double? = if (has(k)) optDouble(k) else null
        private fun JSONObject.optList(k: String): List<Double> {
            val a = optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).map { a.optDouble(it) }
        }

        fun fromJson(o: JSONObject) = Jump(
            id = o.optLong("id"),
            date = o.optString("date"),
            athlete = o.optString("athlete"),
            test = TestType.of(o.optString("test")),
            flightMs = o.optDouble("flightMs"),
            heightCm = o.optDouble("heightCm"),
            contactMs = o.optD("contactMs"),
            rsi = o.optD("rsi"),
            boxCm = o.optD("boxCm"),
            massKg = o.optD("massKg"),
            powerW = o.optD("powerW"),
            fps = o.optInt("fps", 240),
            notes = o.optString("notes"),
            loadKg = o.optD("loadKg"),
            reps = o.optList("reps"),
            repContacts = o.optList("repContacts"),
            fatiguePct = o.optD("fatiguePct"),
            video = if (o.has("video")) o.optString("video") else null,
            marks = o.optJSONArray("marks")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()
        )
    }
}

/** Datos antropométricos del atleta, necesarios para el perfil fuerza-velocidad. */
data class Athlete(
    val name: String,
    val massKg: Double? = null,
    val legLengthCm: Double? = null,   // trocánter mayor → punta del pie en flexión plantar
    val crouchCm: Double? = null       // trocánter mayor → suelo en sentadilla a 90°
) {
    /** Recorrido de empuje (m). */
    val hpo: Double? get() {
        val l = legLengthCm ?: return null
        val c = crouchCm ?: return null
        val d = (l - c) / 100.0
        return if (d > 0.1) d else null
    }
}

/** Fórmulas del método de tiempo de vuelo (Bosco 1983) y método simple de Samozino (2008). */
object Calc {
    const val G = 9.81

    /** Altura en cm a partir del tiempo de vuelo en segundos: h = g·t²/8 */
    fun heightCm(flightS: Double) = G * flightS * flightS / 8.0 * 100.0

    /** Velocidad de despegue en m/s: v = g·t/2 */
    fun takeoffVelocity(flightS: Double) = G * flightS / 2.0

    /** Potencia pico estimada (Sayers et al. 1999), en W. */
    fun sayersPower(heightCm: Double, massKg: Double) = 60.7 * heightCm + 45.3 * massKg - 2055.0

    /** Índice de fuerza reactiva: altura (m) / tiempo de contacto (s). */
    fun rsi(heightCm: Double, contactS: Double) = (heightCm / 100.0) / contactS

    /** Porcentaje de aporte de los brazos: (CMJ brazos − CMJ) / CMJ × 100 */
    fun armIndex(cmj: Double, cmja: Double) = (cmja - cmj) / cmj * 100.0

    /** Asimetría (Bishop et al. 2018): (mejor − peor) / mejor × 100 */
    fun asymmetry(a: Double, b: Double): Double {
        val hi = maxOf(a, b); val lo = minOf(a, b)
        return if (hi <= 0) 0.0 else (hi - lo) / hi * 100.0
    }

    /** Fuerza media de empuje relativa (N/kg), Samozino 2008: F = g·(h/hpo + 1)·(m+carga)/m */
    fun forceRel(heightCm: Double, hpo: Double, mass: Double, load: Double) =
        G * (heightCm / 100.0 / hpo + 1.0) * (mass + load) / mass

    /** Velocidad media de empuje (m/s): v = √(g·h/2) */
    fun meanVelocity(heightCm: Double) = Math.sqrt(G * (heightCm / 100.0) / 2.0)

    /** Caída de rendimiento: media de los 3 primeros vs los 3 últimos. */
    fun fatigue(values: List<Double>): Double? {
        if (values.size < 4) return null
        val k = minOf(3, values.size / 2)
        val first = values.take(k).average()
        val last = values.takeLast(k).average()
        return if (first <= 0) null else (first - last) / first * 100.0
    }
}

object Store {
    private const val PREFS = "salto240"
    private const val KEY = "jumps"

    fun all(ctx: Context): MutableList<Jump> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val out = mutableListOf<Jump>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(Jump.fromJson(it)) }
        }
        return out
    }

    internal fun save(ctx: Context, list: List<Jump>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, j: Jump) {
        val list = all(ctx)
        list.add(0, j)
        save(ctx, list)
    }

    fun delete(ctx: Context, id: Long) {
        val list = all(ctx)
        val gone = list.filter { it.id == id }
        save(ctx, list.filter { it.id != id })
        gone.forEach { Videos.deleteIfUnused(ctx, it.video) }
    }

    /** Reemplaza un salto ya guardado (misma id), por ejemplo al volver a medirlo a mano. */
    fun update(ctx: Context, j: Jump) {
        save(ctx, all(ctx).map { if (it.id == j.id) j else it })
    }

    fun byId(ctx: Context, id: Long): Jump? = all(ctx).firstOrNull { it.id == id }

    fun ofAthlete(ctx: Context, name: String): List<Jump> = all(ctx).filter { it.athlete == name }

    /** Nombres de clientes (fichas) más los que solo aparecen en saltos antiguos. */
    fun athletes(ctx: Context): List<String> {
        val fromClients = Clients.all(ctx).map { it.name }
        val fromJumps = all(ctx).map { it.athlete }
        return (fromClients + fromJumps).filter { it.isNotBlank() }.distinct()
    }

    /** Datos de medición del cliente (masa y medidas de pierna). */
    fun athlete(ctx: Context, name: String): Athlete =
        Clients.byName(ctx, name)?.toAthlete() ?: Athlete(name)

    fun saveAthlete(ctx: Context, a: Athlete) = Clients.mergeAthlete(ctx, a)

    fun renameAthlete(ctx: Context, old: String, new: String) {
        save(ctx, all(ctx).map { if (it.athlete == old) it.copy(athlete = new) else it })
    }

    fun deleteAthleteJumps(ctx: Context, name: String) {
        val list = all(ctx)
        val gone = list.filter { it.athlete == name }
        save(ctx, list.filter { it.athlete != name })
        gone.forEach { Videos.deleteIfUnused(ctx, it.video) }
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    fun csv(list: List<Jump>, groupOf: (String) -> String = { "" }): String {
        fun q(s: String) = if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s
        fun n(d: Double?, dec: Int) = d?.let { String.format(Locale.US, "%.${dec}f", it) } ?: ""
        val sb = StringBuilder("fecha,atleta,grupo,prueba,altura_cm,tiempo_vuelo_ms,tiempo_contacto_ms,rsi,cajon_cm,carga_kg,masa_kg,potencia_w,saltos,fatiga_pct,alturas_cm,fps,nota\n")
        for (j in list) {
            sb.append(listOf(
                j.date, q(j.athlete), q(groupOf(j.athlete)), j.test.label, n(j.heightCm, 1), n(j.flightMs, 1), n(j.contactMs, 1),
                n(j.rsi, 2), n(j.boxCm, 0), n(j.loadKg, 1), n(j.massKg, 1), n(j.powerW, 0),
                if (j.reps.isEmpty()) "" else j.reps.size.toString(), n(j.fatiguePct, 1),
                q(j.reps.joinToString(" ") { String.format(Locale.US, "%.1f", it) }),
                j.fps.toString(), q(j.notes)
            ).joinToString(",")).append('\n')
        }
        return sb.toString()
    }
}
