package com.salto240

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Lista de clientes filtrable por grupo, con gestión de grupos. */
class ClientsActivity : BaseActivity() {

    private lateinit var groupSpinner: Spinner
    private lateinit var list: ListView
    private lateinit var count: TextView
    private var groups: List<Group> = emptyList()
    private var rows: List<Client> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vbox(10).apply {
            setBackgroundColor(Col.BG)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        root.addView(text("Clientes y grupos", 28f, Col.INK, true))

        val filter = panel()
        filter.addView(caption("Grupo"))
        groupSpinner = Spinner(this)
        filter.addView(groupSpinner, lp())
        count = text("", 13f, Col.MUTED)
        filter.addView(count)
        root.addView(filter, lp())

        list = ListView(this).apply {
            background = rounded(Col.SURFACE, Col.LINE)
            divider = rounded(Col.LINE, 0, 0)
            dividerHeight = 1
        }
        root.addView(list, lp(h = 0, weight = 1f))

        val actions = hbox(8)
        actions.addView(button("Nuevo cliente", true) { newClient() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(button("Grupos") { manageGroups() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions, lp())
        setContentView(root)

        groupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = renderList()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        list.setOnItemClickListener { _, _, pos, _ -> clientMenu(rows[pos]) }
    }

    override fun onResume() {
        super.onResume()
        loadGroups()
    }

    /** Posición 0 = todos, 1 = sin grupo, 2… = grupos. */
    private fun selectedGroup(): Long? = groups.getOrNull(groupSpinner.selectedItemPosition - 2)?.id

    private fun loadGroups() {
        val prev = groupSpinner.selectedItemPosition
        groups = Clients.groups(this)
        groupSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Todos los clientes", "Sin grupo") + groups.map { it.name })
        if (prev in 0 until groups.size + 2) groupSpinner.setSelection(prev)
        renderList()
    }

    private fun renderList() {
        val pos = groupSpinner.selectedItemPosition
        val all = Clients.all(this)
        rows = when {
            pos <= 0 -> all
            pos == 1 -> all.filter { c -> c.groupId == null || groups.none { it.id == c.groupId } }
            else -> all.filter { it.groupId == selectedGroup() }
        }
        val jumps = Store.all(this)
        count.text = if (rows.isEmpty()) "No hay clientes aquí. Pulsa Nuevo cliente." else "${rows.size} clientes · toca uno para medir, ver su historial o editar"

        list.adapter = object : ArrayAdapter<Client>(this, android.R.layout.simple_list_item_2, android.R.id.text1, rows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val c = getItem(position)!!
                val t1 = v.findViewById<TextView>(android.R.id.text1)
                val t2 = v.findViewById<TextView>(android.R.id.text2)
                val g = Clients.groupName(this@ClientsActivity, c.groupId)
                t1.text = if (g != null) "${c.name}   ·   $g" else c.name
                t1.setTextColor(Col.INK)
                val mine = jumps.filter { it.athlete == c.name }
                val best = Analytics.best(mine, TestType.CMJ)
                val tail = if (best != null) String.format(Locale.getDefault(), " · CMJ %.1f cm", best)
                    else if (mine.isNotEmpty()) " · ${mine.size} saltos" else ""
                t2.text = c.summary() + tail
                t2.setTextColor(Col.MUTED)
                return v
            }
        }
    }

    private fun newClient() {
        val i = Intent(this, ClientEditActivity::class.java)
        selectedGroup()?.let { i.putExtra(ClientEditActivity.EX_GROUP_ID, it) }
        startActivity(i)
    }

    private fun clientMenu(c: Client) {
        val opts = arrayOf("Medir ahora", "Historial de evaluaciones", "Editar ficha", "Informe y recomendaciones", "Perfil fuerza-velocidad")
        AlertDialog.Builder(this)
            .setTitle(c.name)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> {
                        getSharedPreferences("salto240", MODE_PRIVATE).edit().putString("lastAthlete", c.name).apply()
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }
                    1 -> startActivity(Intent(this, ClientHistoryActivity::class.java).putExtra(MainActivity.EX_ATHLETE, c.name))
                    2 -> startActivity(Intent(this, ClientEditActivity::class.java).putExtra(ClientEditActivity.EX_CLIENT_ID, c.id))
                    3 -> startActivity(Intent(this, ReportActivity::class.java).putExtra(MainActivity.EX_ATHLETE, c.name))
                    4 -> startActivity(Intent(this, FvProfileActivity::class.java).putExtra(MainActivity.EX_ATHLETE, c.name))
                }
            }
            .show()
    }

    // ---------------- grupos ----------------

    private fun nameDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val e: EditText = input("Nombre del grupo").apply { setText(initial) }
        val box = vbox().apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e, lp()) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Aceptar") { _, _ -> onOk(e.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun manageGroups() {
        val gs = Clients.groups(this)
        val all = Clients.all(this)
        val labels = (listOf("+ Crear grupo") + gs.map { g -> "${g.name}  (${all.count { it.groupId == g.id }})" }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Grupos")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    nameDialog("Nuevo grupo", "") { n ->
                        if (Clients.addGroup(this, n) == null) Toast.makeText(this, "Nombre vacío o repetido.", Toast.LENGTH_SHORT).show()
                        loadGroups()
                    }
                } else groupMenu(gs[which - 1])
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun groupMenu(g: Group) {
        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setItems(arrayOf("Ver sus clientes", "Cambiar nombre", "Borrar grupo")) { _, which ->
                when (which) {
                    0 -> groupSpinner.setSelection(groups.indexOfFirst { it.id == g.id } + 2)
                    1 -> nameDialog("Cambiar nombre", g.name) { n ->
                        if (!Clients.renameGroup(this, g.id, n)) Toast.makeText(this, "Nombre vacío o repetido.", Toast.LENGTH_SHORT).show()
                        loadGroups()
                    }
                    2 -> AlertDialog.Builder(this)
                        .setTitle("¿Borrar el grupo ${g.name}?")
                        .setMessage("Los clientes no se borran: quedan sin grupo.")
                        .setPositiveButton("Borrar") { _, _ -> Clients.deleteGroup(this, g.id); loadGroups() }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
            .show()
    }
}
