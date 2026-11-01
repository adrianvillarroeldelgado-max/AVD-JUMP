package com.salto240

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Detección automática de despegue y aterrizaje (versión 2).
 *
 * Se analiza una banda horizontal alrededor de la línea del suelo: desde un 7 % de la imagen por
 * encima hasta un 3,5 % por debajo. Así tolera que la línea quede un poco alta o baja.
 *
 * 1. Se descartan los cuadros con un salto de exposición (arranque de la cámara, parpadeos fuertes).
 * 2. Referencias: la imagen al principio (de pie), al final (de pie, quizá en otro sitio) y la imagen
 *    "sin pies", tomada de los cuadros que más se diferencian de ambas (el vuelo). En el Drop Jump la
 *    imagen sin pies es la del principio, con el atleta sobre el cajón.
 * 3. Máscara de los pies: lo que cambia entre "de pie" y "sin pies". Solo cuentan esos píxeles,
 *    aunque los pies ocupen poco ancho de la imagen.
 * 4. Fila del suelo: la fila más baja de la máscara (donde apoya la suela). En cada cuadro se busca
 *    la fila más baja con pie visible: hay contacto mientras esa fila llega al suelo. Al despegar,
 *    lo último que toca es la punta del pie, así que el talón levantado no adelanta el despegue.
 *
 * Despegue = primer cuadro sin contacto; aterrizaje = primer cuadro con contacto (como a mano).
 * Si el golpe del aterrizaje hace vibrar el teléfono y la suela "sale" de la fila del suelo unos cuadros,
 * cuenta el primer contacto (ver earlyLanding).
 */
object AutoDetect {

    /** Tolerancia en filas bajo la que todavía se considera apoyo (para pruebas). */
    @JvmStatic var TOL = 1
    /** Corregir el aterrizaje cuando la vibración corta el primer contacto (para pruebas). */
    @JvmStatic var EARLY_LANDING = true
    @JvmStatic var DEBUG: ((String) -> Unit)? = null

    /** Geometría de la banda que leen la vista previa y el video. */
    const val ROWS = 36
    const val COLS = 112
    const val ABOVE = 0.07f      // fracción de la altura de la imagen sobre la línea del suelo
    const val BELOW = 0.035f     // fracción bajo la línea
    const val X0 = 0.06f
    const val X1 = 0.94f

    fun bandTop(floorV: Float) = (floorV - ABOVE).coerceIn(0f, 1f)
    fun bandBottom(floorV: Float) = (floorV + BELOW).coerceIn(0f, 1f)

    data class Result(
        val takeoff: Int? = null,
        val landing: Int? = null,
        val contact: Int? = null,            // Drop Jump: primer contacto tras caer del cajón
        val events: List<Int> = emptyList(), // Saltos repetidos: despegue, aterrizaje, …
        val error: String? = null,
        val preContact: Int? = null,         // cuadros de apoyo continuo antes del (primer) despegue
        val quality: Float = 0f              // contraste pies/fondo (0–1), para avisar si es bajo
    )

    private const val E_CONTRAST = "No se distinguen los pies del fondo cerca de la línea del suelo. Pon la línea justo donde apoya la suela, con buena luz y calzado que contraste con el suelo."
    private const val E_NOFLIGHT = "No se encontró ninguna fase de vuelo. Revisa la línea del suelo o marca a mano."

    // ------------------------------------------------------------------ utilidades

    private fun median(a: FloatArray): Float {
        if (a.isEmpty()) return 0f
        val s = a.copyOf(); s.sort()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    private fun percentile(a: FloatArray, p: Float): Float {
        if (a.isEmpty()) return 0f
        val s = a.copyOf(); s.sort()
        return s[((s.size - 1) * p).roundToInt().coerceIn(0, s.size - 1)]
    }

    /** Mediana píxel a píxel de un grupo de cuadros. */
    private fun medianFrame(c: List<FloatArray>, idx: List<Int>): FloatArray {
        val m = c[0].size
        val tmp = FloatArray(idx.size)
        return FloatArray(m) { i ->
            for (k in idx.indices) tmp[k] = c[idx[k]][i]
            median(tmp)
        }
    }

    private fun meanAbs(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }

    private data class Run(val start: Int, val end: Int, val contact: Boolean) {
        val len get() = end - start + 1
    }

    private fun runs(flags: BooleanArray): MutableList<Run> {
        val out = mutableListOf<Run>()
        if (flags.isEmpty()) return out
        var s = 0
        for (i in 1..flags.size) {
            if (i == flags.size || flags[i] != flags[s]) {
                out.add(Run(s, i - 1, flags[s])); s = i
            }
        }
        return out
    }

    /** Elimina tramos interiores más cortos que [minLen] (ruido) invirtiendo su estado. */
    private fun smooth(flags: BooleanArray, minLen: Int) {
        var changed = true
        while (changed) {
            changed = false
            val rs = runs(flags)
            val r = rs.filter { it.len < minLen && it.start > 0 && it.end < flags.size - 1 }.minByOrNull { it.len } ?: break
            for (i in r.start..r.end) flags[i] = !r.contact
            changed = true
        }
    }

    // ------------------------------------------------------------------ detección

    fun detect(strips: List<FloatArray>, fps: Int, test: TestType, rows: Int = ROWS, cols: Int = COLS): Result {
        val n = strips.size
        if (n < 12) return Result(error = "El video es demasiado corto.")
        val m = strips[0].size
        val nRows = if (rows * cols == m) rows else 1
        val nCols = if (rows * cols == m) cols else m

        // 1. Cuadros con salto de exposición: se sustituyen por el vecino válido más cercano.
        val means = FloatArray(n) { t -> strips[t].average().toFloat() }
        val stds = FloatArray(n) { t ->
            val mu = means[t]; var s = 0f
            for (v in strips[t]) s += (v - mu) * (v - mu)
            kotlin.math.sqrt(s / m)
        }
        val medMean = median(means)
        val medStd = median(stds)
        val valid = BooleanArray(n) { t ->
            stds[t] >= 0.35f * medStd && abs(means[t] - medMean) <= 0.4f * medMean + 6f
        }
        if (valid.count { it } < n / 2) return Result(error = "La luz cambia demasiado durante la grabación. Evita contraluces y luces que parpadean.")
        val validIdx = (0 until n).filter { valid[it] }
        val src = IntArray(n) { t ->
            if (valid[t]) t else {
                var pos = validIdx.binarySearch(t)
                if (pos < 0) pos = -pos - 1
                val after = validIdx.getOrNull(pos)
                val before = validIdx.getOrNull(pos - 1)
                when {
                    before == null -> after!!
                    after == null -> before
                    t - before <= after - t -> before
                    else -> after
                }
            }
        }
        // Se resta la media de cada cuadro: los cambios de luz globales (LED a 240 fps) no cuentan como pie.
        val c: List<FloatArray> = List(n) { t ->
            val f = strips[src[t]]; val mu = means[src[t]]
            FloatArray(m) { f[it] - mu }
        }

        // 2. Referencias de pie al principio (S) y al final (E).
        val win = max(3, (0.08 * fps).roundToInt()).coerceAtMost(max(3, n / 5))
        val endIdx = (n - win until n).toList()
        val s0 = min((0.05 * fps).roundToInt(), n / 5)
        val startIdx = (s0 until min(n, s0 + win)).toList()
        val E = medianFrame(c, endIdx)
        val S = medianFrame(c, startIdx)

        // Ruido por píxel (mediana de la desviación respecto al final, que está quieto).
        val devs = FloatArray(endIdx.size * m)
        var k = 0
        for (t in endIdx) for (i in 0 until m) devs[k++] = abs(c[t][i] - E[i])
        val sigma = max(1.5f, median(devs) * 1.4826f)

        // Diferencia = fracción de píxeles que cambiaron de verdad (robusto al ruido de 240 fps con poca luz).
        val tau0 = max(6f, 3.5f * sigma)
        fun changed(a: FloatArray, b: FloatArray): Float {
            var k2 = 0
            for (i in a.indices) if (abs(a[i] - b[i]) > tau0) k2++
            return k2.toFloat() / a.size
        }
        val dE = FloatArray(n) { changed(c[it], E) }
        val dS = FloatArray(n) { changed(c[it], S) }

        // 3. Candidatos de imagen "sin pies", en orden de preferencia.
        val candidates = ArrayList<Pair<IntArray, Boolean>>()   // (cuadros de la referencia, ¿es la del principio?)
        fun core(score: FloatArray, lo: Int, hi: Int): IntArray? {
            if (hi - lo < 1) return null
            var best = lo
            for (t in lo until hi) if (score[t] > score[best]) best = t
            val peak = score[best]
            if (peak <= 0f) return null
            val maxSpan = max(1, (0.12 * fps).roundToInt())
            var a = best; var b = best
            while (a - 1 >= lo && best - (a - 1) <= maxSpan && score[a - 1] >= 0.85f * peak) a--
            while (b + 1 < hi && (b + 1) - best <= maxSpan && score[b + 1] >= 0.85f * peak) b++
            return (a..b).toList().toIntArray()
        }
        // En pleno vuelo la banda queda vacía y quieta: se premia la estabilidad para no tomar
        // como referencia el instante del despegue (talón levantado, que también difiere de todo).
        val kk = max(1, (0.04 * fps).roundToInt())
        val motion = FloatArray(n) { t ->
            val a1 = if (t - kk >= 0) changed(c[t], c[t - kk]) else 0f
            val a2 = if (t + kk < n) changed(c[t], c[t + kk]) else 0f
            max(a1, a2)
        }
        val minScore = FloatArray(n) { min(dE[it], dS[it]) - motion[it] }
        val dEStable = FloatArray(n) { dE[it] - motion[it] }
        val startCore = startIdx.toIntArray()
        if (test.isDrop) {
            candidates.add(startCore to true)
            core(minScore, s0, n - win)?.let { candidates.add(it to false) }
        } else {
            core(minScore, s0, n - win)?.let { candidates.add(it to false) }
            candidates.add(startCore to true)          // el atleta entró en cuadro después de empezar a grabar
            core(dEStable, s0, n - win)?.let { candidates.add(it to false) }
        }

        // Se prueban todas las referencias y se elige la que deja el vuelo más "vacío" (más parecido a la referencia).
        var lastError = E_CONTRAST
        var best: Result? = null
        var bestScore = Float.MAX_VALUE
        for ((aIdx, aIsStart) in candidates) {
            val (r, score) = evaluate(c, aIdx.toList(), aIsStart, E, S, endIdx, startIdx, sigma, fps, test, nRows, nCols)
            val usable = r.error == null || (r.takeoff != null && r.landing != null) || r.events.isNotEmpty()
            if (!usable) { lastError = r.error ?: lastError; continue }
            if (score < bestScore) { best = r; bestScore = score }
        }
        return best ?: Result(error = lastError)
    }

    private fun evaluate(
        c: List<FloatArray>, aIdx: List<Int>, aIsStart: Boolean,
        E: FloatArray, S: FloatArray, endIdx: List<Int>, startIdx: List<Int>,
        sigma: Float, fps: Int, test: TestType, rows: Int, cols: Int
    ): Pair<Result, Float> {
        val r = evaluateInner(c, aIdx, aIsStart, E, S, endIdx, startIdx, sigma, fps, test, rows, cols)
        return r.first to r.second
    }

    private fun evaluateInner(
        c: List<FloatArray>, aIdx: List<Int>, aIsStart: Boolean,
        E: FloatArray, S: FloatArray, endIdx: List<Int>, startIdx: List<Int>,
        sigma: Float, fps: Int, test: TestType, rows: Int, cols: Int
    ): Pair<Result, Float> {
        val n = c.size
        val m = E.size
        val A = medianFrame(c, aIdx)

        // Máscara de los pies: lo que difiere entre "de pie" y "sin pies".
        val diffE = FloatArray(m) { abs(E[it] - A[it]) }
        val diffS = FloatArray(m) { abs(S[it] - A[it]) }
        val tau = max(max(6f, 3.5f * sigma), 0.25f * percentile(diffE, 0.98f))
        val maskE = BooleanArray(m) { diffE[it] > tau }
        val useS = !aIsStart && !test.isDrop
        val maskS = BooleanArray(m) { useS && diffS[it] > tau }
        val nE = maskE.count { it }
        if (nE < max(3, (0.004f * m).roundToInt())) return Result(error = E_CONTRAST) to Float.MAX_VALUE

        // Fila del suelo: la más baja con una porción significativa de pie (de pie al final y, si aplica, al principio).
        fun floorRow(mask: BooleanArray): Int? {
            val counts = IntArray(rows) { r -> (0 until cols).count { mask[r * cols + it] } }
            val peak = counts.maxOrNull() ?: 0
            if (peak < 2) return null
            val need = max(2, ceil(0.3f * peak).toInt())
            for (r in rows - 1 downTo 0) if (counts[r] >= need) return r
            return null
        }
        val floorE = floorRow(maskE) ?: return (Result(error = E_CONTRAST) to Float.MAX_VALUE)
        val floorS = if (useS) floorRow(maskS) ?: floorE else floorE
        val floor = min(floorE, floorS)

        // Columnas donde pueden estar los pies (con margen por si aterriza desplazado).
        val margin = if (test.isRepeated) 8 else 3
        val colOk = BooleanArray(cols)
        for (r in max(0, floor - 8)..max(floorE, floorS)) for (col in 0 until cols) {
            val i = r * cols + col
            if (maskE[i] || maskS[i]) for (d in -margin..margin) {
                val cc = col + d
                if (cc in 0 until cols) colOk[cc] = true
            }
        }
        // Contacto: alguna columna con pie en la fila del suelo y en la de encima (un pie, no un píxel suelto).
        // Basta una columna: al despegar y al aterrizar solo toca la punta del pie.
        val lowRow = floor - TOL
        val topRow = max(floorE, floorS)
        fun touches(t: Int, from: Int): Boolean {
            val f = c[t]
            for (col in 0 until cols) {
                if (!colOk[col]) continue
                for (r in max(1, from)..topRow) {
                    val i = r * cols + col
                    if (abs(f[i] - A[i]) > tau && abs(f[i - cols] - A[i - cols]) > tau) return true
                }
            }
            return false
        }
        val contact = BooleanArray(n) { t -> touches(t, lowRow) }

        // Comprobación de contraste: de pie debe haber contacto y en la referencia "sin pies", no.
        val standOk = endIdx.count { contact[it] } >= 0.7 * endIdx.size
        val airOk = aIdx.count { !contact[it] } >= 0.7 * aIdx.size
        if (!standOk || !airOk) return Result(error = E_CONTRAST) to Float.MAX_VALUE
        val quality = (percentile(diffE, 0.98f) / 80f).coerceIn(0f, 1f)

        DEBUG?.let { d -> d("floor=$floor floorE=$floorE floorS=$floorS tau=$tau aStart=$aIsStart A=${aIdx.first()}..${aIdx.last()}\n" +
            (0 until n).joinToString("") { if (contact[it]) "#" else "." }) }
        val raw = contact.copyOf()
        smooth(contact, max(2, (0.012 * fps).roundToInt()))

        // Contacto estricto (solo la fila del suelo): sirve para afinar el aterrizaje, que con
        // tolerancia se marcaría cuando la punta aún está a unos milímetros del suelo.
        fun strictContact(t: Int): Boolean = touches(t, floor)
        /**
         * El golpe del aterrizaje hace vibrar el teléfono: justo después del primer contacto la imagen se
         * mueve y la suela "desaparece" de la fila del suelo unos cuadros. El filtro de ruido borraría ese
         * primer contacto y el aterrizaje saldría tarde. Si antes del aterrizaje hay un contacto de al menos
         * 2 cuadros separado por menos de 30 ms, el aterrizaje es ese.
         */
        fun earlyLanding(ld: Int, takeoff: Int): Int {
            if (!EARLY_LANDING) return ld
            val maxGap = max(2, (0.03 * fps).roundToInt())
            val look = max(4, (0.06 * fps).roundToInt())
            val minStart = takeoff + max(2, (0.10 * fps).roundToInt())
            var best = ld
            var next = ld          // primer cuadro con contacto encontrado hasta ahora
            var j = ld - 1
            while (j >= max(minStart, ld - look)) {
                if (raw[j]) {
                    var a = j
                    while (a - 1 >= minStart && raw[a - 1]) a--
                    if (next - j - 1 > maxGap) break
                    next = a
                    // Un cuadro suelto puede ser ruido: solo un contacto de 2 cuadros o más adelanta el aterrizaje.
                    if (j - a + 1 >= 2) best = a
                    j = a - 1
                } else j--
            }
            return best
        }
        fun refineLanding(ld: Int): Int {
            if (TOL == 0) return ld
            val lim = max(1, (0.012 * fps).roundToInt())
            for (t in ld..min(n - 1, ld + lim)) if (strictContact(t)) return t
            return ld
        }

        val minFlight = max(2, (0.10 * fps).roundToInt())
        val maxFlight = (1.10 * fps).roundToInt()
        val rs = runs(contact)
        val flights = rs.withIndex().filter { (i, r) ->
            !r.contact && i > 0 && r.start > 0 && r.end < n - 1 && r.len >= minFlight && r.len <= maxFlight
        }
        if (flights.isEmpty()) return Result(error = E_NOFLIGHT, quality = quality) to Float.MAX_VALUE

        val tau0 = max(6f, 3.5f * sigma)
        fun emptiness(a: Int, b: Int): Float {
            val len = b - a
            val lo = a + len / 4; val hi = max(lo + 1, b - len / 4)
            var sum = 0f; var cnt = 0
            for (t in lo until min(hi, n)) {
                var k2 = 0
                for (i in 0 until m) if (abs(c[t][i] - A[i]) > tau0) k2++
                sum += k2.toFloat() / m; cnt++
            }
            return if (cnt == 0) Float.MAX_VALUE else sum / cnt
        }
        val res = when (test.base) {
            TestType.RJ -> {
                val ev = mutableListOf<Int>()
                flights.forEach { (_, r) -> ev.add(r.start); ev.add(refineLanding(earlyLanding(r.end + 1, r.start))) }
                Result(events = ev, preContact = rs.getOrNull(flights.first().index - 1)?.len, quality = quality)
            }
            TestType.DJ -> {
                // Vuelo precedido por un contacto que empieza después de la caída del cajón.
                val f = flights.firstOrNull { (i, _) -> i >= 2 && rs[i - 1].contact && rs[i - 1].start > 0 }
                    ?: flights.maxByOrNull { it.value.len }!!
                val prev = rs.getOrNull(f.index - 1)
                val cIdx = if (prev != null && prev.contact && prev.start > 0) prev.start else null
                Result(takeoff = f.value.start, landing = refineLanding(earlyLanding(f.value.end + 1, f.value.start)), contact = cIdx,
                    preContact = prev?.len, quality = quality,
                    error = if (cIdx == null) "No se detectó el contacto inicial tras la caída; márcalo a mano." else null)
            }
            else -> {
                val f = flights.maxByOrNull { it.value.len }!!
                Result(takeoff = f.value.start, landing = refineLanding(earlyLanding(f.value.end + 1, f.value.start)),
                    preContact = rs.getOrNull(f.index - 1)?.len, quality = quality)
            }
        }
        val score = when {
            res.events.isNotEmpty() -> res.events.chunked(2).filter { it.size == 2 }.map { emptiness(it[0], it[1]) }.average().toFloat()
            res.takeoff != null && res.landing != null -> emptiness(res.takeoff, res.landing)
            else -> Float.MAX_VALUE
        }
        // Un vuelo sin apoyo previo suficiente (salvo Drop Jump) es sospechoso: se penaliza.
        val pre = res.preContact ?: 0
        val penalty = if (!test.isDrop && pre < 0.25 * fps) 1f else 0f
        return res to (score + penalty)
    }
}
