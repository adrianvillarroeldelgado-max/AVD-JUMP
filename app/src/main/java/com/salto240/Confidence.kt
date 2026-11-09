package com.salto240

/** Nivel de confianza de una medición automática (0–100). */
object Confidence {
    const val HIGH = 75
    const val MID = 50

    fun level(c: Int?): Int = when { c == null -> -1; c >= HIGH -> 2; c >= MID -> 1; else -> 0 }

    fun label(c: Int?): String = when (level(c)) {
        2 -> "alta"; 1 -> "media"; 0 -> "baja"; else -> ""
    }

    fun color(c: Int?): Int = when (level(c)) {
        2 -> 0xFF4CD07D.toInt(); 1 -> 0xFFFFC04D.toInt(); 0 -> 0xFFFF6B6B.toInt(); else -> 0xFF8C99AD.toInt()
    }

    /** Texto para la tarjeta: «Confianza alta (92)» y, si no es alta, los motivos. */
    fun line(c: Int?, reasons: List<String>): String? {
        if (c == null) return null
        val head = "Confianza ${label(c)} ($c/100)"
        return if (level(c) >= 2 || reasons.isEmpty()) head else head + ": " + reasons.joinToString("; ").replaceFirstChar { it.lowercase() }
    }

    const val FLAG_LOW = "Confianza baja de la detección automática: revisa el despegue y el aterrizaje"
}
