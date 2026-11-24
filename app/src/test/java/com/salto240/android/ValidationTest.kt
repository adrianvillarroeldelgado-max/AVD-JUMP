package com.salto240

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Estadística de Validación y calibración (Bland-Altman, ICC, regresión). */
class ValidationTest {
    private fun pairs(avd: List<Double>, ref: List<Double>) = avd.indices.map { i ->
        Validation.Pair(i.toLong(), "2026-01-01", "240", "CMJ", "A", avd[i], ref[i], "Plataforma de fuerza")
    }

    private val ref = listOf(25.0, 28.5, 31.0, 33.2, 35.9, 38.4, 40.1, 42.7, 45.0, 47.3)

    @Test fun constantOffset() {
        val s = Validation.stats(pairs(ref.map { it + 2.0 }, ref))!!
        assertEquals(10, s.n)
        assertTrue("sesgo ${s.bias}", abs(s.bias - 2.0) < 1e-9)
        assertTrue("DE ${s.sd}", s.sd < 1e-9)
        assertTrue("r ${s.r}", abs(s.r - 1.0) < 1e-9)
        assertTrue("ICC ${s.icc} debe bajar por el sesgo", s.icc < 1.0 && s.icc > 0.8)
        assertTrue("pendiente ${s.slope}", abs(s.slope - 1.0) < 1e-9)
    }

    @Test fun perfectAgreement() {
        val s = Validation.stats(pairs(ref, ref))!!
        assertTrue("ICC ${s.icc}", abs(s.icc - 1.0) < 1e-9)
        assertTrue("LoA ${s.loaLo}..${s.loaHi}", abs(s.loaLo) < 1e-9 && abs(s.loaHi) < 1e-9)
    }

    @Test fun limitsOfAgreement() {
        val d = listOf(1.0, -1.0, 0.5, -0.5, 1.5, -1.5, 0.0, 0.8, -0.8, 0.0)
        val s = Validation.stats(pairs(ref.indices.map { ref[it] + d[it] }, ref))!!
        val mean = d.average()
        val sd = kotlin.math.sqrt(d.sumOf { (it - mean) * (it - mean) } / (d.size - 1))
        assertTrue(abs(s.bias - mean) < 1e-9)
        assertTrue(abs(s.loaHi - (mean + 1.96 * sd)) < 1e-9)
        assertTrue(abs(s.loaLo - (mean - 1.96 * sd)) < 1e-9)
    }

    @Test fun pastedLines() {
        val p = Validation.parsePasted("32,4  33,1\n28.9;29.6\nnada\n40\t41,2")
        assertEquals(3, p.size)
        assertTrue(abs(p[0].first - 32.4) < 1e-9 && abs(p[2].second - 41.2) < 1e-9)
    }
}
