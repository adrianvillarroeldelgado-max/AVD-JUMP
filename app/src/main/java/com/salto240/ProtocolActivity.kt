package com.salto240

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Protocolo de grabación para que la medición automática funcione al primer intento.
 * Si se abre desde la cámara, muestra primero las indicaciones de la prueba elegida.
 */
class ProtocolActivity : BaseActivity() {

    companion object {
        /** Prueba a destacar ("FV_BATTERY" para la batería F-V). */
        const val EX_FOCUS = "focus"

        private val GENERAL = listOf(
            "1. Lugar" to listOf(
                "Piso firme y plano, sin colchonetas, pasto ni tarimas que se muevan.",
                "Calzado que contraste con el piso (zapatillas oscuras sobre piso claro o al revés). Si no hay contraste, pega cinta clara en el piso donde se salta.",
                "Fondo quieto detrás de los pies (pared o lona). Que nadie camine por detrás a la altura de los pies.",
                "Luz pareja. Evita sol o focos de costado que proyecten la sombra del atleta junto a los pies. Las luces LED sirven: la app compensa su parpadeo.",
                "Pega una cruz de cinta en el piso: ahí se despega y se aterriza."
            ),
            "2. Teléfono" to listOf(
                "En trípode, en vertical. No debe moverse durante la grabación.",
                "Cámara baja: a 20–40 cm del piso.",
                "A 1,5–2,5 m del atleta, de frente o de costado.",
                "Los dos pies en el centro de la imagen: la app no mira los bordes.",
                "Batería cargada y espacio libre (cada salto ocupa unos 10–20 MB). Cierra otras apps de cámara.",
                "Deja «En vivo: Sí» para que la medición sea automática."
            ),
            "3. Línea naranja" to listOf(
                "Con el atleta parado sobre la cruz, arrastra la línea justo donde la suela toca el piso (no a la altura de los tobillos).",
                "La app mira una franja delgada encima de la línea: con los pies apoyados debe ver calzado; en el aire, solo fondo.",
                "La posición se recuerda. Ajústala solo si mueves el teléfono."
            ),
            "4. Cada salto" to listOf(
                "El atleta sobre la cruz. Pulsa Grabar.",
                "Quieto 1–2 s. Si no está quieto antes de despegar, el salto no se cuenta.",
                "Salta y aterriza sobre la cruz, con las piernas extendidas (encoger las piernas en el aire infla la altura).",
                "Quieto al menos 1 s después de aterrizar.",
                "La grabación se detiene sola: primero aparece el valor provisional (≈) y en 1–3 s el definitivo.",
                "Guardar y siguiente si fue válido; Repetir si hubo un error técnico. Sin salto en 15 s, la grabación se reinicia sola."
            )
        )

        private val TESTS = linkedMapOf(
            "CMJ" to ("CMJ" to listOf(
                "Manos en la cadera de principio a fin.",
                "Bajada y subida continuas, sin pausa abajo."
            )),
            "CMJA" to ("CMJ con brazos (Abalakov)" to listOf(
                "Balanceo libre de brazos.",
                "Las manos no deben bajar hasta la altura de los pies."
            )),
            "SJ" to ("Squat jump" to listOf(
                "Bajar a 90° de rodilla y mantener 2–3 s sin moverse.",
                "Saltar sin rebote previo. Si baja antes de subir, repítelo."
            )),
            "CMJ_L" to ("CMJ a una pierna" to listOf(
                "La pierna libre flexionada y bien por encima del piso todo el tiempo.",
                "Aterrizar sobre la misma pierna.",
                "Mide las dos piernas el mismo día: al guardar la segunda aparece la asimetría."
            )),
            "DJ" to ("Drop Jump" to listOf(
                "Cajón al costado o detrás de la cruz, a 20–30 cm, y que no se mueva en toda la serie.",
                "Dejarse caer dando un paso al frente, sin saltar hacia arriba desde el cajón.",
                "Al tocar el piso, rebotar lo más rápido y alto posible; aterrizar sobre la cruz y quedarse quieto.",
                "Si la tarjeta no muestra contacto y RSI, pulsa Revisar y marca el contacto a mano."
            )),
            "RJ" to ("Saltos repetidos" to listOf(
                "Saltos continuos sobre la cruz, con contacto corto.",
                "Al terminar, quieto 1,5–2 s: la app espera más antes de cerrar la serie.",
                "Se abre el análisis con todas las marcas puestas para revisar y guardar."
            )),
            "FV_BATTERY" to ("Perfil fuerza-velocidad (4 cargas)" to listOf(
                "Igual que SJ o CMJ, según el protocolo elegido.",
                "Barra y discos lejos de la franja del suelo.",
                "Al terminar cada carga se abre el análisis con las marcas puestas para guardar y pasar a la siguiente."
            ))
        )

        private val AFTER = listOf(
            "5. Intentos y descansos" to listOf(
                "Calentamiento: 2–3 saltos progresivos, sin guardar.",
                "3 intentos válidos por prueba: el mejor para el rendimiento, la media para el seguimiento.",
                "Descanso de 30–60 s entre intentos y de 2–3 min entre pruebas o cargas.",
                "Repetir si perdió el equilibrio, aterrizó fuera de la cruz, encogió las piernas, soltó la cadera en CMJ o SJ, o si la tarjeta dice «conviene revisarlo»."
            ),
            "6. Si algo falla" to listOf(
                "«No se distinguen los pies del fondo»: más contraste (cinta clara), más luz y cámara más baja.",
                "No detecta el salto: el atleta debe estar quieto antes de saltar y aterrizar en el centro de la imagen.",
                "Salto falso: alguien pasó por detrás de los pies o se movió el cajón. Pulsa Repetir.",
                "Altura rara: pulsa Revisar y corrige despegue y aterrizaje con ±1 cuadro.",
                "Si la barra de la cámara dice «sin modo cámara lenta», el teléfono graba a 60 o 30 fps (±2 a ±4 cm). Para 240 fps graba con la cámara lenta del sistema y usa «Analizar video de la galería» y Auto."
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val focus = intent.getStringExtra(EX_FOCUS)?.let { if (it == "CMJ_R") "CMJ_L" else it }

        val root = vbox(14).apply { setPadding(dp(16), dp(20), dp(16), dp(24)) }
        root.addView(text("Protocolo de grabación", 24f, Col.INK, true))
        root.addView(text("Para que la app mida el salto sola, a 240 fps, al primer intento.", 14f, Col.MUTED))

        // La prueba elegida va primero, destacada.
        TESTS[focus]?.let { (title, items) ->
            root.addView(section("Tu prueba: $title", items, highlight = true), lp())
        }
        for ((title, items) in GENERAL) root.addView(section(title, items), lp())

        val pTests = panel()
        pTests.addView(caption("Indicaciones por prueba"))
        for ((key, pair) in TESTS) {
            if (key == focus) continue
            pTests.addView(text(pair.first, 16f, Col.TRACK, true))
            pTests.addView(text(bullets(pair.second), 14f, Col.INK))
        }
        root.addView(pTests, lp())

        for ((title, items) in AFTER) root.addView(section(title, items), lp())
        root.addView(button("Cerrar", true) { finish() }, lp())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Col.BG)
            addView(root)
        })
    }

    private fun bullets(items: List<String>) = items.joinToString("\n") { "• $it" }

    private fun section(title: String, items: List<String>, highlight: Boolean = false): LinearLayout {
        val p = panel()
        if (highlight) p.background = rounded(0xFFFFF3EE.toInt(), Col.ACCENT)
        p.addView(caption(title))
        p.addView(text(bullets(items), 14f, Col.INK).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        return p
    }
}
