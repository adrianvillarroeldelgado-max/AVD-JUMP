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
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

class HistoryActivity : BaseActivity() {

    private lateinit var spinner: Spinner
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
        root.addView(text("Mantén pulsado un salto para borrarlo.", 12f, Col.MUTED))

        root.addView(button("Compartir CSV (Excel, Sheets, WhatsApp…)", true) { shareCsv() }, lp())
        setContentView(root)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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
        val idx = names.indexOf(prev)
        if (idx >= 0) spinner.setSelection(idx)
        refresh()
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
