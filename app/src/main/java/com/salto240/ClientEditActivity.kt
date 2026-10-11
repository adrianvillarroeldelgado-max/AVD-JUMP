package com.salto240

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.util.Locale

/** Ficha del cliente: crear o editar. Extra "clientId" para editar; "groupId" para preseleccionar grupo. */
class ClientEditActivity : BaseActivity() {

    companion object {
        const val EX_CLIENT_ID = "clientId"
        const val EX_GROUP_ID = "groupId"
    }

    private var editing: Client? = null
    private var birth: String? = null
    private var groups: List<Group> = emptyList()

    private lateinit var name: EditText
    private lateinit var groupSpinner: Spinner
    private lateinit var birthBtn: Button
    private lateinit var sex: Spinner
    private lateinit var mass: EditText
    private lateinit var height: EditText
    private lateinit var sport: EditText
    private lateinit var position: EditText
    private lateinit var dominant: Spinner
    private lateinit var leg: EditText
    private lateinit var crouch: EditText
    private lateinit var notes: EditText
    private lateinit var derived: TextView

    private val sexOpts = listOf("—", "Masculino", "Femenino")
    private val legOpts = listOf("—", "Derecha", "Izquierda")

    private fun fmt(d: Double?) = d?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EX_CLIENT_ID, -1L)
        editing = if (id >= 0) Clients.byId(this, id) else null
        val c = editing
        birth = c?.birthDate

        val root = vbox(14).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(text(if (c == null) "Nuevo cliente" else "Ficha del cliente", 28f, Col.INK, true))

        // Datos personales
        val p1 = panel()
        p1.addView(caption("Datos personales"))
        name = input("Nombre y apellido").apply { setText(c?.name ?: "") }
        p1.addView(name, lp())

        p1.addView(caption("Grupo"))
        val gRow = hbox(8)
        groupSpinner = Spinner(this)
        gRow.addView(groupSpinner, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        gRow.addView(button("Nuevo grupo") { newGroup() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        p1.addView(gRow, lp())
        loadGroups(c?.groupId ?: intent.getLongExtra(EX_GROUP_ID, -1L).takeIf { it >= 0 })

        p1.addView(caption("Fecha de nacimiento"))
        birthBtn = button("") { pickBirth() }
        p1.addView(birthBtn, lp())
        p1.addView(caption("Sexo"))
        sex = spinner(sexOpts, c?.sex)
        p1.addView(sex, lp())
        root.addView(p1, lp())

        // Medidas
        val p2 = panel()
        p2.addView(caption("Medidas"))
        val mRow = hbox(8)
        mass = input("Peso (kg)", true).apply { setText(fmt(c?.massKg)) }
        height = input("Estatura (cm)", true).apply { setText(fmt(c?.heightCm)) }
        mRow.addView(mass, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        mRow.addView(height, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        p2.addView(mRow, lp())
        p2.addView(caption("Para el perfil fuerza-velocidad"))
        val lRow = hbox(8)
        leg = input("Largo de pierna (cm)", true).apply { setText(fmt(c?.legLengthCm)) }
        crouch = input("Altura a 90° (cm)", true).apply { setText(fmt(c?.crouchCm)) }
        lRow.addView(leg, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        lRow.addView(crouch, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        p2.addView(lRow, lp())
        p2.addView(text(
            "Largo de pierna: del trocánter mayor a la punta del pie, tumbado con el tobillo extendido. " +
            "Altura a 90°: del trocánter mayor al suelo en la posición de salida del salto.", 12f, Col.MUTED))
        derived = text("", 13f, Col.INK)
        p2.addView(derived)
        root.addView(p2, lp())

        // Deporte
        val p3 = panel()
        p3.addView(caption("Deporte"))
        sport = input("Deporte (p. ej. fútbol, vóley, atletismo)").apply { setText(c?.sport ?: "") }
        position = input("Posición o prueba (opcional)").apply { setText(c?.position ?: "") }
        p3.addView(sport, lp()); p3.addView(position, lp())
        p3.addView(caption("Pierna dominante"))
        dominant = spinner(legOpts, c?.dominantLeg)
        p3.addView(dominant, lp())
        p3.addView(caption("Notas"))
        notes = input("Lesiones, objetivos, observaciones").apply {
            setText(c?.notes ?: "")
            setSingleLine(false); minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        p3.addView(notes, lp())
        root.addView(p3, lp())

        root.addView(button("Guardar", true) { save() }, lp())
        if (c != null) root.addView(button("Borrar cliente") { confirmDelete(c) }, lp())

        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })
        refreshBirth()
        refreshDerived()
        listOf(mass, height, leg, crouch).forEach { e -> e.setOnFocusChangeListener { _, _ -> refreshDerived() } }
    }

    private fun spinner(opts: List<String>, value: String?): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@ClientEditActivity, android.R.layout.simple_spinner_dropdown_item, opts)
        setSelection(opts.indexOf(value).coerceAtLeast(0))
    }

    private fun loadGroups(selectId: Long?) {
        groups = Clients.groups(this)
        groupSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Sin grupo") + groups.map { it.name })
        val idx = groups.indexOfFirst { it.id == selectId }
        groupSpinner.setSelection(if (idx >= 0) idx + 1 else 0)
    }

    private fun newGroup() {
        val e = input("Nombre del grupo (p. ej. Sub-17, Velocistas)")
        val box = vbox().apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e, lp()) }
        AlertDialog.Builder(this)
            .setTitle("Nuevo grupo")
            .setView(box)
            .setPositiveButton("Crear") { _, _ ->
                val id = Clients.addGroup(this, e.text.toString())
                if (id == null) Toast.makeText(this, "Nombre vacío o repetido.", Toast.LENGTH_SHORT).show()
                else loadGroups(id)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun refreshBirth() {
        val c = Client(0, "", birthDate = birth)
        birthBtn.text = if (birth == null) "Elegir fecha" else "$birth  ·  ${c.age ?: "?"} años"
    }

    private fun pickBirth() {
        val d = try { birth?.let { LocalDate.parse(it) } } catch (e: Exception) { null } ?: LocalDate.now().minusYears(20)
        DatePickerDialog(this, { _, y, m, day ->
            birth = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, day)
            refreshBirth()
        }, d.year, d.monthValue - 1, d.dayOfMonth).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun refreshDerived() {
        val c = build(0)
        val parts = mutableListOf<String>()
        c.bmi?.let { parts.add(String.format(Locale.getDefault(), "IMC %.1f", it)) }
        c.toAthlete().hpo?.let { parts.add(String.format(Locale.getDefault(), "Recorrido de empuje (hpo) %.1f cm", it * 100)) }
        derived.text = parts.joinToString(" · ")
    }

    private fun build(id: Long) = Client(
        id = id,
        name = name.text.toString().trim(),
        groupId = groups.getOrNull(groupSpinner.selectedItemPosition - 1)?.id,
        birthDate = birth,
        sex = sexOpts[sex.selectedItemPosition].takeIf { it != "—" },
        massKg = mass.number(),
        heightCm = height.number(),
        sport = sport.text.toString().trim(),
        position = position.text.toString().trim(),
        dominantLeg = legOpts[dominant.selectedItemPosition].takeIf { it != "—" },
        legLengthCm = leg.number(),
        crouchCm = crouch.number(),
        notes = notes.text.toString().trim()
    )

    private fun save() {
        val c = build(editing?.id ?: System.currentTimeMillis())
        val err = Clients.save(this, c)
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            return
        }
        if (c.massKg == null) Toast.makeText(this, "Guardado. Sin peso no se calculan potencia ni perfil F-V.", Toast.LENGTH_LONG).show()
        else Toast.makeText(this, "Cliente guardado", Toast.LENGTH_SHORT).show()
        // Queda elegido para medir.
        getSharedPreferences("salto240", MODE_PRIVATE).edit().putString("lastAthlete", c.name).apply()
        finish()
    }

    private fun confirmDelete(c: Client) {
        val n = Store.ofAthlete(this, c.name).size
        AlertDialog.Builder(this)
            .setTitle("¿Borrar a ${c.name}?")
            .setMessage(if (n > 0) "Tiene $n saltos guardados." else "No tiene saltos guardados.")
            .setPositiveButton(if (n > 0) "Borrar todo" else "Borrar") { _, _ ->
                Clients.delete(this, c.id, deleteJumps = true); finish()
            }
            .apply {
                if (n > 0) setNeutralButton("Borrar ficha, conservar saltos") { _, _ ->
                    Clients.delete(this@ClientEditActivity, c.id, deleteJumps = false); finish()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
