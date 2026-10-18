package com.salto240

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/** Importar clientes desde CSV o Excel, y exportar todos los datos a Excel. */
object DataIO {

    // ---------------------------------------------------------------- importar clientes

    data class ImportResult(val created: Int, val updated: Int, val groupsCreated: Int, val skipped: List<String>)

    private fun norm(s: String): String = Normalizer.normalize(s.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Columnas reconocidas (encabezado normalizado → campo). */
    private val FIELDS = linkedMapOf(
        "name" to listOf("nombre", "nombre y apellido", "nombre completo", "atleta", "cliente", "name", "nome"),
        "group" to listOf("grupo", "equipo", "categoria", "group", "team", "equipe"),
        "birth" to listOf("fecha de nacimiento", "fecha nacimiento", "nacimiento", "f nacimiento", "birth", "birth date", "date of birth", "data de nascimento"),
        "age" to listOf("edad", "age", "idade"),
        "sex" to listOf("sexo", "genero", "sex", "gender"),
        "mass" to listOf("peso", "masa", "peso kg", "masa kg", "weight", "mass", "body mass"),
        "height" to listOf("estatura", "talla", "altura", "estatura cm", "talla cm", "height", "stature"),
        "sport" to listOf("deporte", "sport", "esporte", "modalidad"),
        "position" to listOf("posicion", "puesto", "prueba", "position", "posicao"),
        "leg" to listOf("pierna dominante", "pierna", "lateralidad", "dominant leg", "perna dominante"),
        "legLength" to listOf("largo de pierna", "largo pierna", "longitud de pierna", "leg length", "comprimento da perna"),
        "crouch" to listOf("altura 90", "altura a 90", "altura sentadilla", "altura a 90 grados", "crouch", "squat height"),
        "notes" to listOf("notas", "observaciones", "notes", "obs")
    )

    private fun field(header: String): String? {
        val h = norm(header)
        return FIELDS.entries.firstOrNull { (_, keys) -> keys.any { h == it } }?.key
            ?: FIELDS.entries.firstOrNull { (_, keys) -> keys.any { h.startsWith(it) } }?.key
    }

    /** CSV con coma, punto y coma o tabulador; respeta comillas. */
    fun parseCsv(text: String): List<List<String>> {
        val first = text.lineSequence().firstOrNull() ?: return emptyList()
        val sep = listOf(';', '\t', ',').maxByOrNull { c -> first.count { it == c } } ?: ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>(); val cell = StringBuilder(); var q = false; var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            when {
                q && c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> q = !q
                !q && c == sep -> { row.add(cell.toString()); cell.clear() }
                !q && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.clear()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        row.add(cell.toString()); if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }

    private fun num(s: String?): Double? = s?.trim()?.replace(',', '.')?.replace(Regex("[^0-9.\\-]"), "")?.toDoubleOrNull()

    /** Fecha en dd/mm/aaaa, aaaa-mm-dd o número de serie de Excel → aaaa-mm-dd. */
    private fun date(s: String?): String? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        t.toDoubleOrNull()?.let { serial ->
            if (serial in 3000.0..80000.0) return LocalDate.of(1899, 12, 30).plusDays(serial.toLong()).toString()
        }
        Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})").find(t)?.let { m ->
            val (y, mo, d) = m.destructured
            return try { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).toString() } catch (_: Exception) { null }
        }
        Regex("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})").find(t)?.let { m ->
            val (d, mo, y0) = m.destructured
            var y = y0.toInt(); if (y < 100) y += if (y > (LocalDate.now().year % 100)) 1900 else 2000
            return try { LocalDate.of(y, mo.toInt(), d.toInt()).toString() } catch (_: Exception) { null }
        }
        return null
    }

    private fun sex(s: String?): String? = when (norm(s ?: "").take(1)) {
        "m", "h", "v" -> if (norm(s ?: "").startsWith("mu")) "Femenino" else "Masculino"
        "f" -> "Femenino"
        else -> null
    }

    private fun leg(s: String?): String? = when {
        s == null -> null
        norm(s).startsWith("d") || norm(s).startsWith("r") -> "Derecha"
        norm(s).startsWith("i") || norm(s).startsWith("l") || norm(s).startsWith("e") -> "Izquierda"
        else -> null
    }

    /** Lee un archivo CSV o Excel elegido por el usuario. */
    fun readTable(ctx: Context, uri: Uri): List<List<String>> {
        val name = (try {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null } ?: uri.lastPathSegment ?: "").lowercase(Locale.ROOT)
        val type = ctx.contentResolver.getType(uri) ?: ""
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("No se pudo abrir el archivo.")
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        return when {
            isZip || name.endsWith(".xlsx") || type.contains("spreadsheetml") -> Xlsx.readFirstSheet(bytes.inputStream())
            name.endsWith(".xls") -> error("Formato .xls antiguo: guárdalo como .xlsx o .csv desde Excel.")
            else -> {
                val txt = try { String(bytes, Charsets.UTF_8).also { if (it.contains('�')) error("") } }
                    catch (_: Exception) { String(bytes, Charsets.ISO_8859_1) }
                parseCsv(txt)
            }
        }
    }

    /** Crea o completa fichas a partir de la tabla. Los clientes que ya existen (mismo nombre) se actualizan con los datos no vacíos. */
    fun importClients(ctx: Context, table: List<List<String>>): ImportResult {
        if (table.isEmpty()) error("El archivo está vacío.")
        val headerIdx = table.take(5).indexOfFirst { r -> r.any { field(it) == "name" } }
        if (headerIdx < 0) error("No se encontró la columna «Nombre». Usa la plantilla.")
        val cols = table[headerIdx].map { field(it) }
        var created = 0; var updated = 0; var groupsCreated = 0
        val skipped = ArrayList<String>()
        var nextId = System.currentTimeMillis()
        for ((ri, r) in table.drop(headerIdx + 1).withIndex()) {
            fun v(f: String): String? = cols.indexOf(f).takeIf { it >= 0 }?.let { r.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
            val name = v("name")
            if (name == null) { if (r.any { it.isNotBlank() }) skipped.add("Fila ${headerIdx + ri + 2}: sin nombre"); continue }
            val groupId = v("group")?.let { g ->
                Clients.groups(ctx).firstOrNull { it.name.equals(g, ignoreCase = true) }?.id
                    ?: Clients.addGroup(ctx, g)?.also { groupsCreated++ }
            }
            val birth = date(v("birth")) ?: num(v("age"))?.takeIf { it in 3.0..100.0 }?.let { LocalDate.now().minusYears(it.toLong()).withDayOfYear(1).toString() }
            val prev = Clients.byName(ctx, name)
            val base = prev ?: Client(nextId++, name)
            val c = base.copy(
                groupId = groupId ?: base.groupId,
                birthDate = birth ?: base.birthDate,
                sex = sex(v("sex")) ?: base.sex,
                massKg = num(v("mass"))?.takeIf { it in 15.0..250.0 } ?: base.massKg,
                heightCm = num(v("height"))?.let { if (it in 1.0..2.5) it * 100 else it }?.takeIf { it in 80.0..250.0 } ?: base.heightCm,
                sport = v("sport") ?: base.sport,
                position = v("position") ?: base.position,
                dominantLeg = leg(v("leg")) ?: base.dominantLeg,
                legLengthCm = num(v("legLength"))?.takeIf { it in 40.0..140.0 } ?: base.legLengthCm,
                crouchCm = num(v("crouch"))?.takeIf { it in 20.0..100.0 } ?: base.crouchCm,
                notes = v("notes") ?: base.notes
            )
            val err = Clients.save(ctx, c)
            if (err != null) skipped.add("$name: $err") else if (prev == null) created++ else updated++
        }
        return ImportResult(created, updated, groupsCreated, skipped)
    }

    /** Plantilla para cargar clientes en Excel. */
    fun templateFile(ctx: Context): File {
        val f = ShareFiles.newFile(ctx, "Plantilla clientes AVD jump.xlsx")
        f.outputStream().use { out ->
            Xlsx.write(out, listOf(Xlsx.Sheet("Clientes", listOf(
                listOf("Nombre", "Grupo", "Fecha de nacimiento", "Sexo", "Peso (kg)", "Estatura (cm)", "Deporte", "Posición",
                    "Pierna dominante", "Largo de pierna (cm)", "Altura a 90° (cm)", "Notas"),
                listOf("Ana Pérez", "Sub-17", "15/03/2009", "Femenino", 58.5, 165, "Vóley", "Central", "Derecha", 92, 55, ""),
                listOf("Luis Rojas", "Sub-17", "02/11/2008", "Masculino", 71, 178, "Fútbol", "Delantero", "Izquierda", null, null, "")
            ), listOf(22, 12, 18, 11, 10, 13, 12, 12, 16, 18, 16, 20))))
        }
        return f
    }

    // ---------------------------------------------------------------- exportar a Excel

    private fun r1(d: Double?) = d?.let { Math.round(it * 10) / 10.0 }
    private fun r2(d: Double?) = d?.let { Math.round(it * 100) / 100.0 }

    /** Libro con hojas Saltos, Evaluaciones, Intentos, Disposición y Clientes. [names] = atletas a incluir (null = todos). */
    fun excel(ctx: Context, names: Set<String>? = null): File {
        val clients = Clients.all(ctx).filter { names == null || it.name in names }
        val jumps = Store.all(ctx).filter { names == null || it.athlete in names }.sortedWith(compareBy({ it.athlete }, { it.date }))
        fun group(n: String) = Clients.groupName(ctx, Clients.byName(ctx, n)?.groupId) ?: ""

        val sJumps = ArrayList<List<Any?>>()
        sJumps.add(listOf("Fecha", "Hora", "Atleta", "Grupo", "Prueba", "Altura (cm)", "Vuelo (ms)", "Contacto (ms)", "RSI",
            "Cajón (cm)", "Carga (kg)", "Masa (kg)", "Potencia (W)", "Saltos", "Caída (%)", "Alturas de la serie", "fps", "Avisos", "Notas"))
        for (j in jumps) sJumps.add(listOf(Evaluations.pretty(j.day), j.date.drop(11), j.athlete, group(j.athlete), j.test.label,
            r1(j.heightCm), r1(j.flightMs), r1(j.contactMs), r2(j.rsi), j.boxCm, j.loadKg, j.massKg, j.powerW?.let { Math.round(it).toDouble() },
            j.reps.size.takeIf { it > 0 }, r1(j.fatiguePct), j.reps.joinToString(" ") { String.format(Locale.getDefault(), "%.1f", it) }.ifEmpty { null },
            j.fps, j.flags.joinToString(" / ").ifEmpty { null }, j.notes.ifEmpty { null }))

        val metrics = Metric.values().toList()
        val sEval = ArrayList<List<Any?>>()
        sEval.add(listOf("Fecha", "Atleta", "Grupo", "Saltos") + metrics.map { m -> m.label + if (m.unit.isNotEmpty()) " (${m.unit})" else "" })
        val sAtt = ArrayList<List<Any?>>()
        sAtt.add(listOf("Fecha", "Atleta", "Prueba", "Carga (kg)", "Intentos", "Medida", "Mejor", "Media", "CV (%)", "Avisos"))
        val sReady = ArrayList<List<Any?>>()
        sReady.add(listOf("Fecha", "Atleta", "Grupo", "Prueba", "Valor (cm)", "Línea base (cm)", "Días base", "Cambio (%)", "z", "Semáforo"))
        for (n in jumps.map { it.athlete }.distinct()) {
            val a = Store.athlete(ctx, n)
            val mine = jumps.filter { it.athlete == n }
            for (e in Evaluations.list(mine, a).reversed()) {
                sEval.add(listOf(Evaluations.pretty(e.day), n, group(n), e.jumps.size) +
                    metrics.map { m -> e.values[m]?.let { v -> if (m.decimals >= 2) r2(v) else r1(v) } })
                for (s in Attempts.summarize(e.jumps)) sAtt.add(listOf(Evaluations.pretty(e.day), n, s.test.label, s.loadKg.takeIf { it > 0 },
                    s.n, if (s.usesRsi) "RSI" else "Altura (cm)", if (s.usesRsi) r2(s.best) else r1(s.best),
                    if (s.usesRsi) r2(s.mean) else r1(s.mean), r1(s.cv), s.warnings().joinToString(" ").ifEmpty { null }))
                Readiness.of(mine, e.day)?.let { r ->
                    sReady.add(listOf(Evaluations.pretty(r.day), n, group(n), r.test.short, r1(r.value), r1(r.mean), r.nBase,
                        r1(r.changePct), r2(r.z), r.light.label))
                }
            }
        }

        val sClients = ArrayList<List<Any?>>()
        sClients.add(listOf("Nombre", "Grupo", "Fecha de nacimiento", "Edad", "Sexo", "Peso (kg)", "Estatura (cm)", "Deporte", "Posición",
            "Pierna dominante", "Largo de pierna (cm)", "Altura a 90° (cm)", "Notas"))
        for (c in clients) sClients.add(listOf(c.name, Clients.groupName(ctx, c.groupId), c.birthDate?.let { Evaluations.pretty(it) }, c.age,
            c.sex, c.massKg, c.heightCm, c.sport.ifEmpty { null }, c.position.ifEmpty { null }, c.dominantLeg, c.legLengthCm, c.crouchCm, c.notes.ifEmpty { null }))

        val stamp = Store.today().take(10)
        val f = ShareFiles.newFile(ctx, (if (names?.size == 1) "AVD jump ${names.first()} $stamp" else "AVD jump datos $stamp") + ".xlsx")
        f.outputStream().use { out ->
            Xlsx.write(out, listOf(
                Xlsx.Sheet("Saltos", sJumps, listOf(11, 7, 22, 12, 22, 10, 10, 11, 7, 9, 9, 9, 10, 7, 8, 24, 6, 30, 20)),
                Xlsx.Sheet("Evaluaciones", sEval, listOf(11, 22, 12, 7) + metrics.map { 14 }),
                Xlsx.Sheet("Intentos", sAtt, listOf(11, 22, 22, 9, 9, 11, 9, 9, 8, 40)),
                Xlsx.Sheet("Disposición", sReady, listOf(11, 22, 12, 8, 10, 13, 9, 10, 7, 12)),
                Xlsx.Sheet("Clientes", sClients, listOf(22, 12, 14, 6, 10, 9, 11, 12, 12, 14, 16, 14, 24))
            ))
        }
        return f
    }
}
