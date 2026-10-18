package com.salto240

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import java.util.Locale

/**
 * Sesión de evaluación de un grupo con avisos de voz: se elige grupo, prueba e intentos, y la cámara
 * va llamando a cada atleta, anuncia su resultado, lo guarda solo y pasa al siguiente.
 */
class GroupSessionActivity : BaseActivity() {

    companion object { const val EX_GROUP = "group" }

    private val tests = TestType.values().toList()
    private lateinit var root: LinearLayout
    private var groups: List<Group> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = vbox(14).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(text("Sesión de grupo", 28f, Col.INK, true))
        val s = GroupSession.get(this)
        if (s != null) renderActive(s) else renderSetup()
    }

    // ---------------------------------------------------------------- sesión en curso

    private fun renderActive(s: GroupSession) {
        val p = panel()
        p.addView(caption(if (s.finished) "Sesión terminada" else "En curso"))
        p.addView(text("${s.test.label} · ${s.groupName} · ${s.athletes.size} atletas · ${s.attempts} intentos" +
            if (s.rotate) " (rotación)" else " (seguidos)", 15f, Col.INK, true))
        if (!s.finished) {
            p.addView(text("Turno ${s.pos + 1} de ${s.total}: ${s.current?.first} (intento ${s.current?.second})", 15f, Col.ACCENT, true))
            p.addView(button("Continuar con la cámara", true) { launch(s) }, lp())
            p.addView(button("Saltar este turno") { GroupSession.advance(this, skip = true); render() }, lp())
        }
        p.addView(button(if (s.finished) "Cerrar sesión" else "Terminar sesión") {
            Dlg(this).setTitle("¿Terminar la sesión?")
                .setMessage("Los saltos ya guardados se conservan.")
                .setPositiveButton("Terminar") { _, _ -> GroupSession.save(this, null); render() }
                .setNegativeButton("Cancelar", null).show()
        }, lp())
        root.addView(p, lp())

        // Resultados de la sesión
        val res = panel()
        res.addView(caption("Resultados de la sesión"))
        val all = Store.all(this).filter { it.date >= s.started && it.test == s.test && it.athlete in s.athletes }
        val lines = ArrayList<Pair<String, Double?>>()
        for (n in s.athletes) {
            val js = all.filter { it.athlete == n }
            val sm = Attempts.summary(s.test, s.load ?: 0.0, js.sortedBy { it.date })
            val row = hbox(8)
            row.addView(text(n, 15f, Col.INK, true), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(text(if (sm == null) "—" else sm.fmt(sm.best) + "  (${sm.n})", 15f, if (sm == null) Col.MUTED else Col.INK, sm != null))
            res.addView(row, lp())
            if (sm != null) {
                val extra = (if (sm.n > 1) "media " + sm.fmt(sm.mean) else "") + (sm.cv?.let { String.format(Locale.getDefault(), " · CV %.1f %%", it) } ?: "")
                if (extra.isNotEmpty()) res.addView(text(extra, 12f, Col.MUTED))
                sm.warnings().forEach { res.addView(text("⚠ $it", 12f, Col.WARN)) }
            }
            if (s.test == TestType.CMJ) Readiness.of(Store.ofAthlete(this, n), Store.today().take(10))?.let { r ->
                res.addView(text("Disposición: ${r.light.label}", 12f, r.light.color))
            }
            lines.add(n to sm?.best)
        }
        root.addView(res, lp())
        root.addView(button("Compartir resultados") {
            val txt = "Sesión ${s.test.label} · ${s.groupName} · ${Evaluations.pretty(s.started.take(10))}\n" +
                lines.sortedByDescending { it.second ?: -1.0 }.joinToString("\n") { (n, v) ->
                    "$n: " + (v?.let { if (s.test.isDrop || s.test.isRepeated) String.format(Locale.getDefault(), "%.2f", it) else String.format(Locale.getDefault(), "%.1f cm", it) } ?: "—")
                }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Lang.tr(this, txt)), Lang.tr(this, "Compartir")))
        }, lp())
    }

    // ---------------------------------------------------------------- preparar

    private fun renderSetup() {
        root.addView(text("La cámara llama a cada atleta por su nombre, dice el resultado en voz alta, lo guarda y pasa al siguiente. " +
            "Solo tienes que mirar. Con un botón Bluetooth de selfie puedes repetir o saltar turnos a distancia.", 14f, Col.MUTED))
        val p = panel()
        p.addView(caption("Grupo"))
        groups = Clients.groups(this)
        val gs = Spinner(this).apply {
            adapter = TrAdapter(this@GroupSessionActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Todos los clientes") + groups.map { it.name })
            val pre = intent.getLongExtra(EX_GROUP, -1L)
            groups.indexOfFirst { it.id == pre }.takeIf { it >= 0 }?.let { setSelection(it + 1) }
        }
        p.addView(gs, lp())
        p.addView(caption("Prueba"))
        val ts = Spinner(this).apply {
            adapter = TrAdapter(this@GroupSessionActivity, android.R.layout.simple_spinner_dropdown_item, tests.map { it.label })
            setSelection(tests.indexOf(TestType.of(intent.getStringExtra(MainActivity.EX_TEST))))
        }
        p.addView(ts, lp())
        val boxIn = input("Altura del cajón en cm", true).apply { setText("30") }
        val loadIn = input("Carga adicional en kg (0 = sin carga)", true).apply { setText("0") }
        p.addView(boxIn, lp()); p.addView(loadIn, lp())
        p.addView(caption("Intentos por atleta"))
        val att = Spinner(this).apply {
            adapter = TrAdapter(this@GroupSessionActivity, android.R.layout.simple_spinner_dropdown_item, (1..5).map { "$it" })
            setSelection(2)
        }
        p.addView(att, lp())
        p.addView(caption("Orden"))
        val ord = Spinner(this).apply {
            adapter = TrAdapter(this@GroupSessionActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Rotación: todos el intento 1, luego el 2… (descansan entre intentos)", "Seguidos: cada atleta hace todos sus intentos"))
        }
        p.addView(ord, lp())
        root.addView(p, lp())

        val pa = panel()
        pa.addView(caption("Atletas (desmarca a quien no esté)"))
        val checks = vbox(2)
        pa.addView(checks, lp())
        root.addView(pa, lp())

        fun fillAthletes() {
            checks.removeAllViews()
            val g = groups.getOrNull(gs.selectedItemPosition - 1)
            for (c in Clients.inGroup(this, g?.id)) checks.addView(CheckBox(this).apply {
                text = c.name; isChecked = true; setTextColor(Col.INK); textSize = 15f
            })
        }
        fun fields() {
            val t = tests[ts.selectedItemPosition]
            boxIn.visibility = if (t.isDrop) View.VISIBLE else View.GONE
            loadIn.visibility = if (t == TestType.SJ || t == TestType.CMJ) View.VISIBLE else View.GONE
        }
        gs.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = fillAthletes()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        ts.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = fields()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        fillAthletes(); fields()

        root.addView(button("Empezar sesión", true) {
            val names = (0 until checks.childCount).mapNotNull { (checks.getChildAt(it) as? CheckBox)?.takeIf { c -> c.isChecked }?.text?.toString() }
            if (names.isEmpty()) { Tst.makeText(this, "Marca al menos un atleta.", Toast.LENGTH_LONG).show(); return@button }
            val t = tests[ts.selectedItemPosition]
            val s = GroupSession(
                groupName = groups.getOrNull(gs.selectedItemPosition - 1)?.name ?: "Todos",
                test = t, attempts = att.selectedItemPosition + 1, rotate = ord.selectedItemPosition == 0, athletes = names,
                box = if (t.isDrop) (boxIn as EditText).number() else null,
                load = if (t == TestType.SJ || t == TestType.CMJ) loadIn.number() else null,
                pos = 0, started = Store.today())
            GroupSession.save(this, s)
            Voice.init(this)
            launch(s)
        }, lp())
    }

    private fun launch(s: GroupSession) {
        val c = s.current ?: return
        val i = MainActivity.captureIntent(this, s.test, c.first, Clients.byName(this, c.first)?.massKg, s.box, s.load)
        i.putExtra(GroupSession.EX_SESSION, true)
        startActivity(i)
    }
}
