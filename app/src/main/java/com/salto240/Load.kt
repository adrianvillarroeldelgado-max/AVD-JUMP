package com.salto240

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Carga de entrenamiento por sesión (sRPE, Foster): esfuerzo percibido 0–10 × minutos = unidades de carga (UA).
 *
 * - Relación aguda:crónica (ACWR) con medias móviles exponenciales (Williams 2017): aguda de 7 días, crónica de 28.
 *   Se muestra con al menos 21 días desde el primer registro. Verde 0,8–1,3; amarillo 1,3–1,5 o < 0,8; rojo > 1,5.
 * - Monotonía (Foster): media diaria / DE diaria de los últimos 7 días (días sin sesión = 0). Esfuerzo = carga semanal × monotonía.
 */
object Load {
    private const val PREFS = "salto240"
    const val KEY = "loads"

    val TYPES = listOf("Entrenamiento", "Partido o competencia", "Gimnasio", "Recuperación", "Otro")

    /** Escala CR-10 de Borg modificada (Foster). */
    val RPE = listOf(
        "0 · Reposo", "1 · Muy, muy fácil", "2 · Fácil", "3 · Moderado", "4 · Algo duro", "5 · Duro",
        "6 · Duro", "7 · Muy duro", "8 · Muy duro", "9 · Muy duro", "10 · Máximo"
    )

    data class Session(val id: Long, val day: String, val athlete: String, val rpe: Int, val minutes: Int,
                       val type: String = TYPES[0], val notes: String = "") {
        val ua: Int get() = rpe * minutes
        fun toJson(): JSONObject = JSONObject().put("id", id).put("day", day).put("athlete", athlete).put("rpe", rpe)
            .put("min", minutes).put("type", type).put("notes", notes)
        companion object {
            fun fromJson(o: JSONObject) = Session(o.optLong("id"), o.optString("day"), o.optString("athlete"),
                o.optInt("rpe"), o.optInt("min"), o.optString("type", TYPES[0]), o.optString("notes"))
        }
    }

    fun all(ctx: Context): List<Session> {
        val arr = try { JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> Session.fromJson(o) } }
    }

    fun save(ctx: Context, l: List<Session>) {
        val arr = JSONArray(); l.sortedBy { it.day }.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, s: Session) = save(ctx, all(ctx) + s)
    fun addAll(ctx: Context, s: List<Session>) = save(ctx, all(ctx) + s)
    fun delete(ctx: Context, id: Long) { save(ctx, all(ctx).filter { it.id != id }); Sync.tomb(ctx, "load", id.toString()) }
    fun of(ctx: Context, athlete: String) = all(ctx).filter { it.athlete == athlete }

    data class Status(
        val day: String, val acute: Double, val chronic: Double, val acwr: Double?, val week: Int,
        val monotony: Double?, val strain: Double?, val light: Readiness.Light, val text: String
    )

    fun daily(sessions: List<Session>, from: LocalDate, to: LocalDate) = LoadCalc.daily(sessions.map { it.day to it.ua.toDouble() }, from, to)
    fun ewma(loads: List<Double>) = LoadCalc.ewma(loads)

    fun lightOf(zone: Int?): Readiness.Light = when (zone) {
        null -> Readiness.Light.NONE
        2 -> Readiness.Light.RED
        1 -> Readiness.Light.YELLOW
        else -> Readiness.Light.GREEN
    }

    fun status(sessions: List<Session>, day: LocalDate): Status? {
        val c = LoadCalc.status(sessions.map { it.day to it.ua.toDouble() }, day) ?: return null
        val text = when {
            c.acwr == null -> "Faltan datos: la relación aguda:crónica necesita 3 semanas de registros (" + c.days + " días)."
            c.acwr > 1.5 -> "Subida brusca de la carga: más riesgo de lesión. Conviene moderar."
            c.acwr > 1.3 -> "La carga está subiendo rápido."
            c.acwr < 0.8 -> "Carga baja respecto de las últimas semanas (puede perder forma)."
            else -> "Carga en la zona habitual."
        } + if ((c.monotony ?: 0.0) > 2.0) " Monotonía alta: varía la carga entre días." else ""
        return Status(day.toString(), c.acute, c.chronic, c.acwr, c.week, c.monotony, c.strain, lightOf(c.zone), text)
    }

    fun status(ctx: Context, athlete: String, day: String = Store.today().take(10)): Status? =
        try { status(of(ctx, athlete), LocalDate.parse(day)) } catch (_: Exception) { null }
}
