package com.salto240

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * Pruebas de precisión con datos sintéticos de física conocida. Corren en cada versión (GitHub Actions):
 * si un cambio empeora la medición, la publicación se detiene.
 */
class AutoDetectTest {
    private fun heightErr(fps: Int, flightS: Double, seed: Int): Double {
        val strips = Synth.strips(fps, flightS, seed = seed)
        val r = AutoDetect.detect(strips, fps, TestType.CMJ)
        assertNull("error: ${r.error}", r.error)
        val ff = assertNotNullV(r.flightFrames, "sin vuelo")
        return Synth.heightCm(ff / fps) - Synth.heightCm(flightS)
    }

    @Test fun cmj240fps() {
        for ((i, fl) in listOf(0.42, 0.50, 0.56, 0.62).withIndex()) {
            val e = heightErr(240, fl, i + 1)
            assertTrue("error de altura $e cm con vuelo $fl s", abs(e) < 1.0)
        }
    }

    @Test fun cmj120fps() {
        for ((i, fl) in listOf(0.45, 0.55).withIndex()) {
            val e = heightErr(120, fl, i + 11)
            assertTrue("error de altura $e cm a 120 fps", abs(e) < 1.5)
        }
    }

    @Test fun confidenceHighOnCleanVideo() {
        val r = AutoDetect.detect(Synth.strips(240, 0.52, seed = 5), 240, TestType.CMJ)
        assertTrue("confianza ${r.confidence} ${r.reasons}", (r.confidence ?: 0) >= Confidence.HIGH)
    }
}

class PoseJumpTest {
    private fun run(samples: List<PoseJumpDetector.Sample>, test: TestType = TestType.CMJ): List<PoseJumpDetector.Rep> {
        val d = PoseJumpDetector(test, 175.0)
        val reps = ArrayList<PoseJumpDetector.Rep>()
        for (s in samples) when (val e = d.add(s)) {
            is PoseJumpDetector.Event.Jump -> reps.add(e.rep)
            is PoseJumpDetector.Event.Series -> reps.addAll(e.reps)
            null -> {}
        }
        return reps
    }

    @Test fun cmjAt30fps() {
        for (seed in 1..6) {
            val fl = 0.48 + seed * 0.02
            val reps = run(Synth.poseCmj(30.0, fl, seed = seed))
            assertEquals("saltos detectados (semilla $seed)", 1, reps.size)
            val e = reps[0].heightCm - Synth.heightCm(fl)
            assertTrue("error de altura $e cm", abs(e) < 4.0)
        }
    }

    @Test fun timeToTakeoff() {
        val reps = run(Synth.poseCmj(30.0, 0.52, tCm = 0.55, seed = 3))
        val ttt = assertNotNullV(reps.single().tttS, "sin tiempo hasta el despegue")
        assertTrue("TTT $ttt s", abs(ttt - 0.55) < 0.1)
    }

    @Test fun squatIsNotAJump() {
        for (seed in 1..5) assertEquals("una sentadilla contó como salto", 0, run(Synth.poseSquat(30.0, seed = seed)).size)
    }
}

class AccelJumpTest {
    @Test fun flightHeight() {
        for ((i, v) in listOf(2.2, 2.6, 3.0).withIndex()) for (hz in listOf(200.0, 400.0)) {
            val (s, flight, _) = Synth.accel(v, hz, seed = i + 1)
            val r = AccelJump.analyze(s, 2.8, 75.0, TestType.CMJ)
            val e = r.heightFlightCm - Synth.heightCm(flight)
            assertTrue("error de altura $e cm a $hz Hz", abs(e) < 1.2)
        }
    }

    @Test fun timeToTakeoffAndImpulse() {
        val (s, flight, ttt) = Synth.accel(2.6, 200.0, seed = 7)
        val r = AccelJump.analyze(s, 2.8, 75.0, TestType.CMJ)
        assertTrue("TTT ${r.tttS} frente a $ttt", abs(r.tttS - ttt) < 0.06)
        assertTrue("altura por impulso ${r.heightImpulseCm}", abs(r.heightImpulseCm - Synth.heightCm(flight)) < 3.0)
    }

    @Test fun movementDuringQuietFails() {
        val (s, _, _) = Synth.accel(2.6, 200.0, seed = 3)
        val shaken = s.map { if (it.t in 2.0..2.6) AccelJump.S(it.t, it.a + 3.0 * kotlin.math.sin(it.t * 40)) else it }
        var failed = false
        try { AccelJump.analyze(shaken, 2.8, 75.0, TestType.CMJ) } catch (_: AccelJump.Fail) { failed = true }
        assertTrue("debía rechazar el reposo con movimiento", failed)
    }
}

class LoadCalcTest {
    @Test fun ewmaSteadyIsOne() {
        val start = LocalDate.of(2026, 1, 1)
        val loads = (0 until 60).map { start.plusDays(it.toLong()).toString() to 400.0 }
        val c = assertNotNullV(LoadCalc.status(loads, start.plusDays(59)), "sin estado")
        val r = assertNotNullV(c.acwr, "sin ACWR")
        assertTrue("ACWR con carga constante = $r", abs(r - 1.0) < 0.05)
        assertEquals(0, c.zone)
        assertNull("monotonía sin variación", c.monotony)
    }

    @Test fun spikeIsRed() {
        val start = LocalDate.of(2026, 1, 1)
        val loads = (0 until 42).map { i -> start.plusDays(i.toLong()).toString() to (if (i >= 35) 1100.0 else 300.0) }
        val c = LoadCalc.status(loads, start.plusDays(41))!!
        assertTrue("ACWR ${c.acwr}", (c.acwr ?: 0.0) > 1.5)
        assertEquals(2, c.zone)
    }

    @Test fun needsThreeWeeks() {
        val start = LocalDate.of(2026, 1, 1)
        val loads = (0 until 10).map { start.plusDays(it.toLong()).toString() to 300.0 }
        assertNull(LoadCalc.status(loads, start.plusDays(9))!!.acwr)
    }
}

fun <T : Any> assertNotNullV(v: T?, msg: String): T { assertNotNull(msg, v); return v!! }
