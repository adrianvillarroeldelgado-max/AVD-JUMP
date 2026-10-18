package com.salto240

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.util.Locale
import kotlin.math.abs

/**
 * Batería del perfil fuerza-velocidad (como My Jump Lab): 4 saltos con 4 cargas distintas,
 * un video por carga (grabado a 240 fps o de la galería). Con las 4 listas se abre el perfil.
 */
class BatteryActivity : BaseActivity() {

    companion object {
        const val SLOTS = 4
        const val EX_BATTERY = "battery"
        private const val REQ_PICK = 200
        private const val REQ_CAMERA = 300

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE)

        fun protocol(ctx: Context): TestType =
            TestType.of(prefs(ctx).getString("fvProtocol", TestType.SJ.name)).takeIf { it in Analytics.FV_TESTS } ?: TestType.SJ

        /** Cargas guardadas para el atleta; si no hay, 0 / 25 / 50 / 75 % de su masa. */
        fun loads(ctx: Context, a: Athlete): List<Double> {
            val raw = prefs(ctx).getString("batteryLoads:${a.name}", null)
            if (raw != null) try {
                val arr = JSONArray(raw)
                if (arr.length() == SLOTS) return (0 until SLOTS).map { arr.getDouble(it) }
            } catch (_: Exception) {}
            return suggested(a.massKg)
        }

        fun suggested(mass: Double?): List<Double> {
            val m = mass ?: 60.0
            return listOf(0.0, 0.25, 0.5, 0.75).map { Math.round(m * it / 2.5) * 2.5 }
        }

        fun saveLoads(ctx: Context, name: String, loads: List<Double>) {
            prefs(ctx).edit().putString("batteryLoads:$name", JSONArray(loads).toString()).apply()
        }

        /** Mejor salto de hoy con esa carga y protocolo (null = pendiente). */
        fun done(js: List<Jump>, name: String, protocol: TestType, load: Double): List<Jump> {
            val today = Store.today().take(10)
            return js.filter { it.athlete == name && it.test == protocol && it.day == today && abs((it.loadKg ?: 0.0) - load) < 0.26 }
        }

        /** Índice de la siguiente carga sin salto hoy, o null si están todas. */
        fun nextPending(ctx: Context, name: String): Int? {
            val a = Store.athlete(ctx, name)
            val ls = loads(ctx, a)
            val js = Store.all(ctx)
            val p = protocol(ctx)
            return ls.indices.firstOrNull { done(js, name, p, ls[it]).isEmpty() }
        }

        /** Intent de captura para la carga [index] de la batería. */
        fun captureIntent(ctx: Context, name: String, index: Int): Intent {
            val a = Store.athlete(ctx, name)
            val load = loads(ctx, a)[index]
            return MainActivity.captureIntent(ctx, protocol(ctx), name, a.massKg, null, load)
                .putExtra(EX_BATTERY, true)
        }
    }

    private lateinit var name: String
    private lateinit var info: TextView
    private lateinit var slotsBox: LinearLayout
    private lateinit var progress: TextView
    private lateinit var profileBtn: Button
    private val loadInputs = mutableListOf<EditText>()
    private var pendingCapture: Int? = null

    private fun fmt(p: String, vararg a: Any?) = String.format(Locale.getDefault(), p, *a)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra(MainActivity.EX_ATHLETE) ?: run { finish(); return }
        val a = Store.athlete(this, name)

        val root = vbox(14).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(text("Perfil fuerza-velocidad", 28f, Col.INK, true))
        root.addView(text("Batería de 4 saltos con cargas crecientes, un video por carga, el mismo día.", 13f, Col.MUTED))

        // Cliente y datos necesarios
        val pC = panel()
        pC.addView(caption("Cliente"))
        info = text("", 14f, Col.INK)
        pC.addView(info)
        pC.addView(button("Completar ficha (peso y medidas de pierna)") {
            Clients.byName(this, name)?.let {
                startActivity(Intent(this, ClientEditActivity::class.java).putExtra(ClientEditActivity.EX_CLIENT_ID, it.id))
            }
        }, lp())
        root.addView(pC, lp())

        // Protocolo
        val pP = panel()
        pP.addView(caption("Protocolo"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        Analytics.FV_TESTS.forEachIndexed { i, t ->
            group.addView(RadioButton(this).apply {
                id = 500 + i
                text = if (t == TestType.SJ) "Squat jump con carga" else "CMJ con carga"
                setTextColor(Col.INK); textSize = 15f
                isChecked = t == protocol(this@BatteryActivity)
            }, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        group.setOnCheckedChangeListener { _, id ->
            prefs(this).edit().putString("fvProtocol", Analytics.FV_TESTS[id - 500].name).apply()
            refresh()
        }
        pP.addView(group, lp())
        pP.addView(text(
            "SJ: parte quieto 2–3 s a 90° de rodilla, sin contramovimiento.\n" +
            "CMJ: contramovimiento libre hasta unos 90°, sin brazos.\n" +
            "Carga con barra sobre los hombros o chaleco. 2–3 saltos por carga: se usa el mejor.", 12f, Col.MUTED))
        root.addView(pP, lp())

        // Cargas
        root.addView(caption("Cargas"))
        slotsBox = vbox(10)
        val ls = loads(this, a)
        for (i in 0 until SLOTS) slotsBox.addView(slotView(i, ls[i]), lp())
        root.addView(slotsBox, lp())
        root.addView(button("Restablecer cargas sugeridas (0, 25, 50 y 75 % de la masa)") {
            val s = suggested(Store.athlete(this, name).massKg)
            loadInputs.forEachIndexed { i, e -> e.setText(fmtLoad(s[i])) }
        }, lp())

        // Resultado
        progress = text("", 14f, Col.INK)
        root.addView(progress)
        profileBtn = button("Ver perfil fuerza-velocidad", true) { openProfile() }
        root.addView(profileBtn, lp())

        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun fmtLoad(v: Double) = if (v % 1.0 == 0.0) fmt("%.0f", v) else fmt("%.1f", v)

    private val statusViews = mutableListOf<TextView>()

    private fun slotView(i: Int, load: Double): LinearLayout {
        val card = panel()
        val head = hbox(8)
        head.addView(text("Carga ${i + 1}", 18f, Col.INK, true), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val e = input("kg", true).apply {
            setText(fmtLoad(load))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { persistLoads(); refresh() }
            })
        }
        loadInputs.add(e)
        head.addView(e, LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT))
        head.addView(text("kg", 14f, Col.MUTED))
        card.addView(head, lp())
        val status = text("", 14f, Col.MUTED)
        statusViews.add(status)
        card.addView(status)
        val row = hbox(8)
        row.addView(button("Grabar", true) { record(i) }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Video de la galería") { pick(i) }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row, lp())
        return card
    }

    private fun currentLoads(): List<Double> = loadInputs.map { it.number() ?: 0.0 }

    private fun persistLoads() = saveLoads(this, name, currentLoads())

    private fun refresh() {
        val a = Store.athlete(this, name)
        val c = Clients.byName(this, name)
        val missing = mutableListOf<String>()
        if (a.massKg == null) missing.add("peso")
        if (a.hpo == null) missing.add("largo de pierna y altura a 90°")
        info.text = (c?.summary() ?: name) + "\n" +
            (if (missing.isEmpty()) fmt("hpo %.1f cm · listo para el perfil", a.hpo!! * 100)
             else "Falta: " + missing.joinToString(" y ") + ". Puedes medir igual y completarlo después.")
        info.setTextColor(if (missing.isEmpty()) Col.INK else Col.WARN)

        val p = protocol(this)
        val js = Store.all(this)
        val ls = currentLoads()
        var count = 0
        ls.forEachIndexed { i, load ->
            val d = done(js, name, p, load)
            if (d.isNotEmpty()) {
                count++
                val best = d.maxOf { it.heightCm }
                statusViews[i].text = fmt("✓ %.1f cm · %d %s", best, d.size, if (d.size == 1) "salto" else "saltos")
                statusViews[i].setTextColor(Col.OK)
            } else {
                statusViews[i].text = "Pendiente: ${p.short} con ${fmtLoad(load)} kg"
                statusViews[i].setTextColor(Col.MUTED)
            }
        }
        val distinct = ls.map { Math.round(it * 2) }.distinct().size
        progress.text = when {
            distinct < SLOTS -> "Hay cargas repetidas: usa 4 cargas distintas."
            count == SLOTS -> "Batería completa. Ya puedes ver el perfil."
            count >= 2 -> "$count de $SLOTS cargas. Con 2 ya hay perfil orientativo; con las 4 es más fiable."
            else -> "$count de $SLOTS cargas medidas hoy."
        }
        profileBtn.isEnabled = count >= 2
        profileBtn.alpha = if (count >= 2) 1f else 0.5f
    }

    private fun record(i: Int) {
        persistLoads()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCapture = i
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        startActivity(captureIntent(this, name, i))
    }

    private fun pick(i: Int) {
        persistLoads()
        val pi = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(pi, REQ_PICK + i)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val i = requestCode - REQ_PICK
        if (i in 0 until SLOTS && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            // Permiso permanente de lectura: el video queda enlazado al salto para revisarlo después.
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val a = Store.athlete(this, name)
            val it = Intent(this, AnalysisActivity::class.java).apply {
                putExtra(MainActivity.EX_TEST, protocol(this@BatteryActivity).name)
                putExtra(MainActivity.EX_ATHLETE, name)
                putExtra(MainActivity.EX_MASS, a.massKg ?: 0.0)
                putExtra(MainActivity.EX_LOAD, currentLoads()[i])
                putExtra(MainActivity.EX_URI, uri.toString())
                putExtra(MainActivity.EX_FPS, 0)
                putExtra(EX_BATTERY, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(it)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val i = pendingCapture ?: return
        pendingCapture = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startActivity(captureIntent(this, name, i))
        } else {
            Tst.makeText(this, "Sin permiso de cámara: usa Video de la galería.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openProfile() {
        persistLoads()
        startActivity(Intent(this, FvProfileActivity::class.java).putExtra(MainActivity.EX_ATHLETE, name))
    }
}
