package com.salto240

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Punto del perfil F-V: una carga y su mejor salto. */
data class FvPoint(val loadKg: Double, val heightCm: Double, val force: Double, val velocity: Double)

/**
 * Tipos de ejercicio del programa individualizado según el perfil F-V
 * (Jiménez-Reyes, Samozino, Brughelli y Morin, Front. Physiol. 2017, tabla 2).
 */
enum class TrainType(val label: String, val exercises: List<String>) {
    STRENGTH("Fuerza", listOf(
        "Sentadilla trasera al 80–90 % de 1RM",
        "Prensa de piernas al 90–95 % de 1RM",
        "Peso muerto al 90–95 % de 1RM")),
    STRENGTH_POWER("Fuerza-potencia", listOf(
        "Tirón de cargada (clean pull) al 80 % de 1RM",
        "Peso muerto al 80 % de 1RM",
        "Squat jump con más del 70 % de la masa corporal",
        "CMJ con más del 80 % de la masa corporal")),
    POWER("Potencia", listOf(
        "Squat jump con 20–30 % de la masa corporal",
        "CMJ con 35–45 % de la masa corporal",
        "Squat jump a una pierna sin carga",
        "CMJ a una pierna con 10 % de la masa corporal",
        "Tirón de cargada con salto al 65 % de 1RM")),
    POWER_SPEED("Potencia-velocidad", listOf(
        "Drop jumps",
        "Squat jump sin carga",
        "CMJ con 10 % de la masa corporal",
        "Salto máximo al cajón")),
    SPEED("Velocidad", listOf(
        "Empuje máximo de piernas asistido, con menos que el peso corporal (p. ej. plataforma deslizante o salto con banda que descarga)",
        "CMJ con brazos sin carga"))
}

/**
 * Categorías por perfil F-V real / óptimo (tabla 1 del mismo estudio).
 * [plan]: ejercicios por semana de cada tipo, en el orden de TrainType (6 ejercicios, 3 series cada uno).
 */
enum class FvCategory(val label: String, val color: Int, val range: String, val plan: IntArray, val focus: String) {
    HIGH_F("Déficit de fuerza alto", Col.ACCENT, "menos del 60 % del óptimo", intArrayOf(3, 2, 1, 0, 0),
        "Orientar el entrenamiento a la fuerza máxima para inclinar el perfil hacia el óptimo."),
    LOW_F("Déficit de fuerza leve", Col.WARN, "60–90 % del óptimo", intArrayOf(2, 2, 2, 0, 0),
        "Predominio de fuerza y fuerza-potencia, con trabajo de potencia."),
    BALANCED("Perfil equilibrado", Col.OK, "90–110 % del óptimo", intArrayOf(1, 1, 2, 1, 1),
        "El perfil ya es eficiente: trabajo mixto para subir la potencia máxima sin desequilibrarlo."),
    LOW_V("Déficit de velocidad leve", Col.WARN, "110–140 % del óptimo", intArrayOf(0, 0, 2, 2, 2),
        "Predominio de potencia-velocidad y velocidad, con trabajo de potencia."),
    HIGH_V("Déficit de velocidad alto", Col.ACCENT, "más del 140 % del óptimo", intArrayOf(0, 0, 1, 2, 3),
        "Orientar el entrenamiento a la velocidad para inclinar el perfil hacia el óptimo.");

    /** Resumen de una línea: "3 fuerza · 2 fuerza-potencia · 1 potencia". */
    val planLine: String
        get() = TrainType.values().withIndex().filter { plan[it.index] > 0 }
            .joinToString(" · ") { "${plan[it.index]} ${it.value.label.lowercase()}" }

    /** Texto completo del programa semanal. */
    val training: String
        get() {
            val sb = StringBuilder(focus).append("\n\nCada semana, 6 ejercicios de 3 series: ").append(planLine).append(".\n")
            TrainType.values().forEachIndexed { i, t ->
                if (plan[i] > 0) {
                    sb.append("\n").append(t.label).append(" (").append(plan[i])
                        .append(if (plan[i] == 1) " ejercicio" else " ejercicios").append(" a elegir):\n")
                    t.exercises.forEach { sb.append("  • ").append(it).append('\n') }
                }
            }
            sb.append("\n2 sesiones por semana separadas por 48 h, durante 9 semanas. Reevalúa el perfil al terminar el bloque.")
            return sb.toString()
        }
}

data class FvProfile(
    val points: List<FvPoint>,
    val f0: Double,        // N/kg
    val v0: Double,        // m/s
    val pmax: Double,      // W/kg
    val sfv: Double,       // pendiente real
    val sfvOpt: Double,    // pendiente óptima
    val ratio: Double,     // Sfv / Sfvopt × 100 (100 = óptimo)
    val imbalance: Double, // |100 − ratio|
    val r2: Double,
    val hpo: Double,
    val hNow: Double,      // salto teórico sin carga con el perfil actual (cm)
    val hOpt: Double,      // salto teórico con el perfil óptimo y la misma Pmax (cm)
    val category: FvCategory
) {
    val f0Opt: Double get() = sqrt(4 * pmax * -sfvOpt)
    val v0Opt: Double get() = f0Opt / -sfvOpt
}

enum class Level(val label: String, val color: Int) {
    PRIORITY("Prioridad", Col.ACCENT),
    WATCH("Vigilar", Col.WARN),
    GOOD("Bien", Col.OK),
    INFO("Falta medir", Col.MUTED)
}

data class Rec(val level: Level, val title: String, val body: String)

object Analytics {

    // ------------------------------------------------------------------
    // Perfil fuerza-velocidad vertical (Samozino et al. 2008, 2012)
    // ------------------------------------------------------------------

    /** Velocidad de despegue según el modelo de Samozino para un perfil F0-V0 (relativos) y un recorrido hpo. */
    private fun takeoffVel(f0: Double, v0: Double, hpo: Double): Double {
        val b = f0 * hpo / (2 * v0)
        val disc = b * b + 2 * (f0 - Calc.G) * hpo
        return if (disc <= 0) 0.0 else -b + sqrt(disc)
    }

    /** Altura teórica (cm) del salto sin carga para una Pmax y una pendiente dadas. */
    fun modelHeight(pmax: Double, sfv: Double, hpo: Double): Double {
        val f0 = sqrt(4 * pmax * -sfv)
        val v0 = f0 / -sfv
        val v = takeoffVel(f0, v0, hpo)
        return v * v / (2 * Calc.G) * 100
    }

    /** Pendiente óptima: la que maximiza la altura sin carga con la misma potencia máxima. */
    fun optimalSfv(pmax: Double, hpo: Double): Double {
        var a = -80.0
        var b = -0.5
        repeat(200) {
            val m1 = a + (b - a) / 3
            val m2 = b - (b - a) / 3
            if (modelHeight(pmax, m1, hpo) < modelHeight(pmax, m2, hpo)) a = m1 else b = m2
        }
        return (a + b) / 2
    }

    /** Pruebas válidas para el perfil F-V. */
    val FV_TESTS = listOf(TestType.SJ, TestType.CMJ)

    /** Mejor salto por carga del día indicado (o del día más reciente) para el protocolo elegido (SJ o CMJ). */
    fun fvPoints(jumps: List<Jump>, athlete: Athlete, day: String? = null, protocol: TestType = TestType.SJ): List<FvPoint> {
        val mass = athlete.massKg ?: return emptyList()
        val hpo = athlete.hpo ?: return emptyList()
        val sj = jumps.filter { it.test == protocol && it.athlete == athlete.name }
        val d = day ?: sj.maxOfOrNull { it.day } ?: return emptyList()
        return sj.filter { it.day == d }
            .groupBy { ((it.loadKg ?: 0.0) * 2).roundToInt() / 2.0 }
            .map { (load, list) ->
                val h = list.maxOf { it.heightCm }
                FvPoint(load, h, Calc.forceRel(h, hpo, mass, load), Calc.meanVelocity(h))
            }
            .sortedBy { it.loadKg }
    }

    /** Días con saltos del protocolo; para CMJ solo cuentan los días con al menos un salto con carga. */
    fun fvDays(jumps: List<Jump>, name: String, protocol: TestType): List<String> {
        val js = jumps.filter { it.test == protocol && it.athlete == name }
        val loadedDays = js.filter { (it.loadKg ?: 0.0) > 0 }.map { it.day }.toSet()
        return js.map { it.day }.distinct()
            .filter { protocol == TestType.SJ || it in loadedDays }
            .sortedDescending()
    }

    /** Perfil más reciente del atleta, con SJ o CMJ (el que tenga la sesión más nueva). */
    fun latestProfile(js: List<Jump>, a: Athlete): Pair<TestType, FvProfile>? {
        val hpo = a.hpo ?: return null
        return FV_TESTS.mapNotNull { t ->
            val d = fvDays(js, a.name, t).firstOrNull() ?: return@mapNotNull null
            fvProfile(fvPoints(js, a, d, t), hpo)?.let { Triple(d, t, it) }
        }.maxByOrNull { it.first }?.let { it.second to it.third }
    }

    fun fvProfile(points: List<FvPoint>, hpo: Double): FvProfile? {
        if (points.size < 2) return null
        val n = points.size.toDouble()
        val mx = points.sumOf { it.velocity } / n
        val my = points.sumOf { it.force } / n
        val sxx = points.sumOf { (it.velocity - mx) * (it.velocity - mx) }
        val sxy = points.sumOf { (it.velocity - mx) * (it.force - my) }
        if (sxx <= 0) return null
        val slope = sxy / sxx
        if (slope >= 0) return null
        val f0 = my - slope * mx
        val v0 = -f0 / slope
        val ssTot = points.sumOf { (it.force - my) * (it.force - my) }
        val ssRes = points.sumOf { val p = f0 + slope * it.velocity; (it.force - p) * (it.force - p) }
        val r2 = if (ssTot > 0) 1 - ssRes / ssTot else 1.0
        val pmax = f0 * v0 / 4
        val opt = optimalSfv(pmax, hpo)
        val ratio = slope / opt * 100
        val cat = when {
            ratio < 60 -> FvCategory.HIGH_F
            ratio < 90 -> FvCategory.LOW_F
            ratio <= 110 -> FvCategory.BALANCED
            ratio <= 140 -> FvCategory.LOW_V
            else -> FvCategory.HIGH_V
        }
        return FvProfile(points, f0, v0, pmax, slope, opt, ratio, abs(100 - ratio), r2, hpo,
            modelHeight(pmax, slope, hpo), modelHeight(pmax, opt, hpo), cat)
    }

    /** Cargas sugeridas para el protocolo: 0, 25, 50, 75 y 100 % de la masa corporal. */
    fun suggestedLoads(mass: Double): List<Double> =
        listOf(0.0, 0.25, 0.5, 0.75, 1.0).map { (mass * it / 2.5).roundToInt() * 2.5 }.distinct()

    // ------------------------------------------------------------------
    // Resumen y recomendaciones
    // ------------------------------------------------------------------

    private fun f1(d: Double) = String.format(Locale.getDefault(), "%.1f", d)
    private fun f2(d: Double) = String.format(Locale.getDefault(), "%.2f", d)

    /** Mejor salto sin carga adicional. */
    fun best(js: List<Jump>, t: TestType): Double? =
        js.filter { it.test == t && (it.loadKg ?: 0.0) < 0.5 }.maxOfOrNull { it.heightCm }
    fun bestSjUnloaded(js: List<Jump>): Double? =
        js.filter { it.test == TestType.SJ && (it.loadKg ?: 0.0) < 0.5 }.maxOfOrNull { it.heightCm }

    /** Asimetría con el día más reciente en que se midieron ambas piernas. */
    fun asymmetry(js: List<Jump>): Triple<Double, Double, Double>? {
        val days = js.filter { it.test == TestType.CMJ_L }.map { it.day }.toSet()
            .intersect(js.filter { it.test == TestType.CMJ_R }.map { it.day }.toSet())
        val d = days.maxOrNull() ?: return null
        val l = js.filter { it.test == TestType.CMJ_L && it.day == d }.maxOf { it.heightCm }
        val r = js.filter { it.test == TestType.CMJ_R && it.day == d }.maxOf { it.heightCm }
        return Triple(l, r, Calc.asymmetry(l, r))
    }

    /** Asimetría reactiva: RSI del Drop Jump a una pierna, día más reciente con ambas piernas. */
    fun reactiveAsymmetry(js: List<Jump>): Triple<Double, Double, Double>? {
        fun days(t: TestType) = js.filter { it.test == t && it.rsi != null }.map { it.day }.toSet()
        val d = days(TestType.DJ_L).intersect(days(TestType.DJ_R)).maxOrNull() ?: return null
        val l = js.filter { it.test == TestType.DJ_L && it.day == d }.mapNotNull { it.rsi }.max()
        val r = js.filter { it.test == TestType.DJ_R && it.day == d }.mapNotNull { it.rsi }.max()
        return Triple(l, r, Calc.asymmetry(l, r))
    }

    /** Último valor de RSI de una prueba de saltos repetidos (10/5 o pogo). */
    fun latestRsi(js: List<Jump>, t: TestType): Jump? = js.filter { it.test == t && it.rsi != null }.maxByOrNull { it.date }

    fun recommendations(js: List<Jump>, athlete: Athlete): List<Rec> {
        val out = mutableListOf<Rec>()

        // Perfil F-V
        val latest = latestProfile(js, athlete)
        val fv = latest?.second
        if (fv != null) {
            val lvl = when (fv.category) {
                FvCategory.BALANCED -> Level.GOOD
                FvCategory.LOW_F, FvCategory.LOW_V -> Level.WATCH
                else -> Level.PRIORITY
            }
            out.add(Rec(lvl, "Perfil F-V (${latest.first.short}): ${fv.category.label} (${f1(fv.ratio)} % del óptimo)",
                fv.category.focus + " Plan semanal: " + fv.category.planLine + " (3 series por ejercicio, 2 sesiones por semana, 9 semanas). " +
                "Ganancia teórica al corregirlo: ${f1(fv.hOpt - fv.hNow)} cm."))
        } else {
            out.add(Rec(Level.INFO, "Perfil fuerza-velocidad",
                "Registra SJ o CMJ con 3–5 cargas el mismo día y completa masa, largo de pierna y altura en sentadilla en la pantalla Perfil F-V."))
        }

        // Asimetría
        val asy = asymmetry(js)
        if (asy != null) {
            val (l, r, pct) = asy
            val weak = if (l < r) "izquierda" else "derecha"
            val body = "Izquierda ${f1(l)} cm · derecha ${f1(r)} cm. "
            out.add(when {
                pct >= 15 -> Rec(Level.PRIORITY, "Asimetría entre piernas: ${f1(pct)} %",
                    body + "Supera el 15 %: añade trabajo unilateral (sentadilla búlgara, step-up, saltos a una pierna) con una serie extra para la pierna $weak, y revisa molestias o lesiones previas.")
                pct >= 10 -> Rec(Level.WATCH, "Asimetría entre piernas: ${f1(pct)} %",
                    body + "Entre 10 y 15 %: incluye ejercicios unilaterales 1–2 veces por semana y vuelve a medir en 4 semanas.")
                else -> Rec(Level.GOOD, "Asimetría entre piernas: ${f1(pct)} %", body + "Por debajo del 10 %: dentro de lo esperable.")
            })
        } else {
            out.add(Rec(Level.INFO, "Asimetría", "Mide CMJ a una pierna, izquierda y derecha, el mismo día."))
        }

        // Asimetría reactiva (Drop Jump a una pierna)
        reactiveAsymmetry(js)?.let { (l, r, pct) ->
            val weak = if (l < r) "izquierda" else "derecha"
            val body = "RSI izquierda ${f2(l)} · derecha ${f2(r)}. "
            out.add(when {
                pct >= 15 -> Rec(Level.PRIORITY, "Asimetría reactiva: ${f1(pct)} %",
                    body + "Supera el 15 %: pliometría a una pierna (saltos laterales, pogo y drop jumps bajos) con más volumen para la pierna $weak. Útil en vuelta a la competición tras lesión.")
                pct >= 10 -> Rec(Level.WATCH, "Asimetría reactiva: ${f1(pct)} %", body + "Entre 10 y 15 %: incluye pliometría unilateral y vuelve a medir en 4 semanas.")
                else -> Rec(Level.GOOD, "Asimetría reactiva: ${f1(pct)} %", body + "Por debajo del 10 %: dentro de lo esperable.")
            })
        }

        // Test 10/5 y pogo
        latestRsi(js, TestType.RJ105)?.let { j ->
            val v = j.rsi!!
            out.add(when {
                v < 1.5 -> Rec(Level.WATCH, "Test 10/5 (RSI ${f2(v)})", "Fuerza reactiva en saltos seguidos baja. Prioriza contactos cortos: pogo, saltos de tobillo y vallas bajas.")
                else -> Rec(Level.GOOD, "Test 10/5 (RSI ${f2(v)})", "Buena capacidad reactiva en saltos seguidos.")
            })
        }
        latestRsi(js, TestType.POGO)?.let { j ->
            val tc = j.contactMs
            out.add(if (tc != null && tc > 200) Rec(Level.WATCH, "Pogo jumps (contacto ${tc.toInt()} ms)",
                "Contacto largo para pogo (lo deseable es menos de 200 ms): trabaja rigidez de tobillo con saltos de tobillo, comba y pogo progresivo.")
            else Rec(Level.GOOD, "Pogo jumps (RSI ${j.rsi?.let { f2(it) } ?: "—"})", "Buena rigidez de tobillo."))
        }

        // Fuerza reactiva (Drop Jump)
        val rsi = js.filter { it.test == TestType.DJ }.mapNotNull { it.rsi }.maxOrNull()
        if (rsi != null) {
            out.add(when {
                rsi < 1.0 -> Rec(Level.PRIORITY, "Fuerza reactiva (RSI ${f2(rsi)})",
                    "RSI bajo. Pliometría progresiva de contacto corto: saltos pogo, vallas bajas y drop jumps desde 20–30 cm buscando contactos menores de 250 ms. 2 sesiones por semana, 60–100 contactos.")
                rsi < 1.5 -> Rec(Level.WATCH, "Fuerza reactiva (RSI ${f2(rsi)})",
                    "RSI moderado. Mantén pliometría de contacto corto y progresa la altura del cajón solo si el contacto sigue por debajo de 250 ms.")
                rsi < 2.0 -> Rec(Level.GOOD, "Fuerza reactiva (RSI ${f2(rsi)})", "RSI bueno. Prueba otras alturas de cajón para encontrar la óptima.")
                else -> Rec(Level.GOOD, "Fuerza reactiva (RSI ${f2(rsi)})", "RSI muy alto. Mantén el estímulo pliométrico y prioriza otras capacidades.")
            })
        } else {
            out.add(Rec(Level.INFO, "Fuerza reactiva", "Mide un Drop Jump desde 30 cm para obtener el RSI."))
        }

        // Aprovechamiento elástico (CMJ / SJ)
        val cmj = best(js, TestType.CMJ)
        val sj = bestSjUnloaded(js)
        if (cmj != null && sj != null && sj > 0) {
            val eur = cmj / sj
            out.add(when {
                eur < 1.05 -> Rec(Level.WATCH, "Uso del ciclo estiramiento-acortamiento (CMJ/SJ ${f2(eur)})",
                    "El contramovimiento casi no suma altura. Incluye pliometría y saltos con contramovimiento rápido.")
                eur > 1.20 -> Rec(Level.WATCH, "Uso del ciclo estiramiento-acortamiento (CMJ/SJ ${f2(eur)})",
                    "Buen uso elástico pero SJ bajo en comparación: hay margen en fuerza concéntrica (sentadilla, SJ con carga).")
                else -> Rec(Level.GOOD, "Uso del ciclo estiramiento-acortamiento (CMJ/SJ ${f2(eur)})", "Relación normal entre CMJ y SJ.")
            })
        }

        // Brazos
        val cmja = best(js, TestType.CMJA)
        if (cmj != null && cmja != null) {
            val arm = Calc.armIndex(cmj, cmja)
            out.add(if (arm < 10) Rec(Level.WATCH, "Uso de brazos: ${f1(arm)} %",
                "Los brazos aportan poco (lo habitual es 10–20 %). Trabaja la coordinación del balanceo: saltos con brazos marcando el ritmo, lanzamientos verticales.")
            else Rec(Level.GOOD, "Uso de brazos: ${f1(arm)} %", "Aporte de brazos dentro de lo habitual."))
        }

        // Saltos repetidos
        val rj = js.filter { it.test == TestType.RJ }.maxByOrNull { it.date }
        rj?.fatiguePct?.let { fat ->
            out.add(if (fat > 15) Rec(Level.WATCH, "Saltos repetidos: caída ${f1(fat)} %",
                "La altura cae más de un 15 % al final. Trabaja resistencia a la fuerza reactiva: series de 10–20 saltos continuos con pausas incompletas.")
            else Rec(Level.GOOD, "Saltos repetidos: caída ${f1(fat)} %", "Mantiene bien la altura a lo largo de la serie."))
        }

        return out.sortedBy { it.level.ordinal }
    }
}
