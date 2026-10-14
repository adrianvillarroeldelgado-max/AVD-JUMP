package com.salto240

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

class HistoryActivity : BaseActivity() {

    private lateinit var spinner: Spinner
    private lateinit var videoInfo: TextView
    private lateinit var groupSpinner: Spinner
    private var groups: List<Group> = emptyList()
    private lateinit var summary: TextView
    private lateinit var list: ListView
    private var names: List<String> = emptyList()
    private var rows: List<Jump> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vbox(10).apply {
            setBackgroundColor(Col.BG)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        root.addView(text("Historial", 28f, Col.INK, true))

        val filter = panel()
        filter.addView(caption("Grupo"))
        groupSpinner = Spinner(this)
        filter.addView(groupSpinner, lp())
        filter.addView(caption("Atleta"))
        spinner = Spinner(this)
        filter.addView(spinner, lp())
        summary = text("", 14f, Col.INK)
        filter.addView(summary)
        root.addView(filter, lp())

        list = ListView(this).apply {
            background = rounded(Col.SURFACE, Col.LINE)
            divider = rounded(Col.LINE, 0, 0)
            dividerHeight = 1
        }
        root.addView(list, lp(h = 0, weight = 1f))
        root.addView(text("Toca un salto para ver su video o medirlo a mano. Mantén pulsado para borrarlo.", 12f, Col.MUTED))

        // Videos de las evaluaciones: espacio usado, ajuste y limpieza.
        val vRow = hbox(8)
        videoInfo = text("", 13f, Col.INK)
        vRow.addView(videoInfo, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        vRow.addView(button("Videos") { videoMenu() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        vRow.addView(button("Borrar") { deleteShown() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(vRow, lp())

        root.addView(button("Compartir CSV (Excel, Sheets, WhatsApp…)", true) { shareCsv() }, lp())
        setContentView(root)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        list.setOnItemClickListener { _, _, pos, _ -> jumpMenu(rows[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val j = rows[pos]
            AlertDialog.Builder(this)
                .setTitle("¿Borrar este salto?")
                .setMessage(String.format(Locale.getDefault(), "%s · %s · %.1f cm\n%s", j.athlete, j.test.label, j.heightCm, j.date))
                .setPositiveButton("Borrar") { _, _ -> Store.delete(this, j.id); loadNames() }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }
        groupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = loadNames()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        preselect = intent.getStringExtra(MainActivity.EX_ATHLETE)
        loadGroups()
    }

    private fun selectedGroup(): Group? = groups.getOrNull(groupSpinner.selectedItemPosition - 1)

    private fun loadGroups() {
        val prev = selectedGroup()?.id
        groups = Clients.groups(this)
        groupSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Todos los grupos") + groups.map { it.name })
        val idx = groups.indexOfFirst { it.id == prev }
        if (idx >= 0) groupSpinner.setSelection(idx + 1)
        loadNames()
    }

    private fun loadNames() {
        val prev = spinner.selectedItem as? String
        val g = selectedGroup()
        names = listOf("Todos") + (if (g == null) Store.athletes(this) else Clients.inGroup(this, g.id).map { it.name })
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val wanted = prev ?: preselect
        preselect = null
        val idx = names.indexOf(wanted)
        if (idx >= 0) spinner.setSelection(idx)
        refresh()
    }

    /** Atleta a mostrar al abrir (desde el historial de evaluaciones de un cliente). */
    private var preselect: String? = null

    private fun refreshVideoInfo() {
        val n = Videos.count(this)
        videoInfo.text = (if (Videos.enabled(this)) "Videos: se guardan" else "Videos: no se guardan") +
            " · " + n + (if (n == 1) " salto con video" else " saltos con video") + " · " + Videos.humanSize(Videos.usedBytes(this))
    }

    /** Borra los saltos que se están mostrando (según el grupo y el atleta elegidos). */
    private fun deleteShown() {
        if (rows.isEmpty()) {
            Toast.makeText(this, "No hay saltos para borrar.", Toast.LENGTH_SHORT).show()
            return
        }
        val who = (spinner.selectedItem as? String)?.takeIf { spinner.selectedItemPosition > 0 }
            ?: selectedGroup()?.name?.let { "el grupo $it" } ?: "todos los atletas"
        val n = rows.size
        val nVid = rows.count { it.video != null }
        AlertDialog.Builder(this)
            .setTitle("¿Borrar $n " + (if (n == 1) "salto" else "saltos") + " de $who?")
            .setMessage("Se borran los resultados" + (if (nVid > 0) " y $nVid videos" else "") +
                ". Las fichas de los clientes se conservan. No se puede deshacer.\n\nPara borrar uno solo, tócalo en la lista.")
            .setPositiveButton("Borrar") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Confirma el borrado")
                    .setMessage("Vas a borrar $n " + (if (n == 1) "salto." else "saltos."))
                    .setPositiveButton("Borrar") { _, _ ->
                        val done = Store.deleteMany(this, rows.map { it.id }.toSet())
                        Toast.makeText(this, "$done saltos borrados.", Toast.LENGTH_SHORT).show()
                        loadNames()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun videoMenu() {
        val on = Videos.enabled(this)
        val opts = arrayOf(
            if (on) "No guardar los videos de los próximos saltos" else "Guardar los videos de los próximos saltos",
            "Borrar todos los videos (los resultados se conservan)"
        )
        AlertDialog.Builder(this)
            .setTitle("Videos de las evaluaciones")
            .setItems(opts) { _, w ->
                if (w == 0) { Videos.setEnabled(this, !on); refreshVideoInfo() }
                else AlertDialog.Builder(this)
                    .setTitle("¿Borrar todos los videos?")
                    .setMessage("Se libera " + Videos.humanSize(Videos.usedBytes(this)) + ". Los resultados de los saltos no se borran, pero ya no se podrán volver a medir a mano.")
                    .setPositiveButton("Borrar videos") { _, _ -> Videos.removeAll(this); refresh() }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .show()
    }

    private fun jumpMenu(j: Jump) {
        val has = j.video != null
        val avail = has && Videos.available(this, j.video)
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        if (avail) {
            labels.add("Ver video y medir a mano"); acts.add {
                if (!Videos.openForReview(this, j)) Toast.makeText(this, "El video ya no está disponible.", Toast.LENGTH_LONG).show()
            }
            labels.add("Guardar video en la galería"); acts.add {
                Thread {
                    val msg = Videos.exportToGallery(this, j)
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                }.start()
            }
        }
        if (has) { labels.add("Quitar el video de este salto"); acts.add { Videos.removeFromJump(this, j); refresh() } }
        labels.add("Borrar salto"); acts.add {
            AlertDialog.Builder(this)
                .setTitle("¿Borrar este salto?")
                .setMessage(String.format(Locale.getDefault(), "%s · %s · %.1f cm\n%s", j.athlete, j.test.label, j.heightCm, j.date))
                .setPositiveButton("Borrar") { _, _ -> Store.delete(this, j.id); loadNames() }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        val title = String.format(Locale.getDefault(), "%s · %.1f cm", j.test.label, j.heightCm)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(when {
                avail -> j.date + " · " + j.athlete + " · video guardado (" + j.fps + " fps)"
                has -> j.date + " · " + j.athlete + " · el video ya no está en el teléfono"
                else -> j.date + " · " + j.athlete + " · sin video"
            })
            .setItems(labels.toTypedArray()) { _, w -> acts[w]() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun refresh() {
        val sel = spinner.selectedItemPosition
        val all = Store.all(this)
        val inScope = names.drop(1).toSet()
        rows = if (sel <= 0) all.filter { selectedGroup() == null || it.athlete in inScope } else all.filter { it.athlete == names[sel] }

        fun cm(d: Double?) = d?.let { String.format(Locale.getDefault(), "%.1f cm", it) } ?: "—"
        val bestCmj = Analytics.best(rows, TestType.CMJ)
        val bestCmja = Analytics.best(rows, TestType.CMJA)
        val bestRsi = rows.filter { it.test == TestType.DJ }.mapNotNull { it.rsi }.maxOrNull()
        val sb = StringBuilder()
        sb.append("Mejor CMJ: ").append(cm(bestCmj)).append('\n')
        sb.append("Mejor CMJ brazos: ").append(cm(bestCmja)).append('\n')
        sb.append("Mejor SJ sin carga: ").append(cm(Analytics.bestSjUnloaded(rows))).append('\n')
        sb.append("Mejor RSI: ").append(bestRsi?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "—")
        if (sel > 0 && bestCmj != null && bestCmja != null) {
            sb.append(String.format(Locale.getDefault(), "\nUso de brazos: %.1f %% (habitual 10–20 %%)", Calc.armIndex(bestCmj, bestCmja)))
        }
        if (sel > 0) Analytics.asymmetry(rows)?.let { (l, r, pct) ->
            sb.append(String.format(Locale.getDefault(), "\nAsimetría: %.1f %% (izq. %.1f · der. %.1f cm)", pct, l, r))
        }
        if (rows.isEmpty()) sb.append("\n\nAún no hay saltos guardados.")
        summary.text = sb.toString()
        refreshVideoInfo()

        list.adapter = object : ArrayAdapter<Jump>(this, android.R.layout.simple_list_item_2, android.R.id.text1, rows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val j = getItem(position)!!
                val t1 = v.findViewById<TextView>(android.R.id.text1)
                val t2 = v.findViewById<TextView>(android.R.id.text2)
                val loadTxt = j.loadKg?.takeIf { it > 0 }?.let { String.format(Locale.getDefault(), " +%.1f kg", it) } ?: ""
                val prefix = if (j.test == TestType.RJ) "media " else ""
                t1.text = String.format(Locale.getDefault(), "%s%.1f cm   %s%s", prefix, j.heightCm, j.test.label, loadTxt)
                t1.setTextColor(when (j.test) {
                    TestType.CMJ, TestType.SJ -> Col.TRACK
                    TestType.CMJA -> Col.OK
                    TestType.DJ, TestType.RJ -> Col.ACCENT
                    TestType.CMJ_L, TestType.CMJ_R -> Col.WARN
                })
                var extra = if (j.rsi != null && j.contactMs != null)
                    String.format(Locale.getDefault(), " · TC %.0f ms · RSI %.2f", j.contactMs, j.rsi) else ""
                if (j.reps.isNotEmpty()) extra += String.format(Locale.getDefault(), " · %d saltos", j.reps.size)
                j.fatiguePct?.let { extra += String.format(Locale.getDefault(), " · caída %.1f %%", it) }
                if (j.video != null) extra += " · video"
                t2.text = String.format(Locale.getDefault(), "%s · %s · TV %.0f ms%s%s",
                    j.date, j.athlete, j.flightMs, extra, if (j.notes.isNotEmpty()) " · " + j.notes else "")
                t2.setTextColor(Col.MUTED)
                return v
            }
        }
    }

    private fun shareCsv() {
        val csv = Store.csv(rows) { n -> Clients.groupName(this, Clients.byName(this, n)?.groupId) ?: "" }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Saltos AVD jump")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(i, "Compartir CSV"))
    }

    override fun onResume() {
        super.onResume()
        if (::spinner.isInitialized) loadGroups()
    }
}
