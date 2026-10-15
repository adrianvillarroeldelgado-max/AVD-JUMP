package com.salto240

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : BaseActivity() {

    private var test = TestType.CMJ
    /** Opción del selector: batería del perfil fuerza-velocidad (4 cargas). */
    private var fvBattery = false
    private lateinit var recordBtn: android.widget.Button
    private lateinit var galleryBtn: android.widget.Button
    private lateinit var clientSpinner: Spinner
    private lateinit var clientInfo: TextView
    private var clients: List<Client> = emptyList()
    private lateinit var box: EditText
    private lateinit var load: EditText
    private lateinit var boxRow: LinearLayout
    private lateinit var loadRow: LinearLayout
    private lateinit var testHelp: TextView
    private lateinit var camInfo: TextView

    companion object {
        const val REQ_CAMERA = 10
        const val REQ_PICK = 11
        const val EX_TEST = "test"
        const val EX_ATHLETE = "athlete"
        const val EX_MASS = "mass"
        const val EX_BOX = "box"
        const val EX_LOAD = "load"
        const val EX_URI = "uri"
        const val EX_FPS = "fps"

        /** Copia los datos de la prueba de un intent a otro. */
        fun forward(from: Intent, to: Intent): Intent {
            to.putExtra(EX_TEST, from.getStringExtra(EX_TEST))
            to.putExtra(EX_ATHLETE, from.getStringExtra(EX_ATHLETE))
            to.putExtra(EX_MASS, from.getDoubleExtra(EX_MASS, 0.0))
            to.putExtra(EX_BOX, from.getDoubleExtra(EX_BOX, 0.0))
            to.putExtra(EX_LOAD, from.getDoubleExtra(EX_LOAD, 0.0))
            to.putExtra(BatteryActivity.EX_BATTERY, from.getBooleanExtra(BatteryActivity.EX_BATTERY, false))
            return to
        }

        /** Intent de captura listo para una prueba concreta (lo usa también el Perfil F-V). */
        fun captureIntent(ctx: Context, t: TestType, athlete: String, mass: Double?, box: Double?, load: Double?): Intent =
            Intent(ctx, CaptureMode.cls(ctx)).apply {
                putExtra(EX_TEST, t.name)
                putExtra(EX_ATHLETE, athlete)
                putExtra(EX_MASS, mass ?: 0.0)
                putExtra(EX_BOX, box ?: 0.0)
                putExtra(EX_LOAD, load ?: 0.0)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vbox(14).apply { setPadding(dp(16), dp(20), dp(16), dp(24)) }

        // Encabezado
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo_avd_jump)
            adjustViewBounds = true
            contentDescription = "AVD jump"
        }
        root.addView(logo, LinearLayout.LayoutParams(dp(200), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(text("Altura de salto por tiempo de vuelo, grabado en cámara lenta.", 14f, Col.MUTED).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }, lp())

        // Prueba
        val prefs = getSharedPreferences("salto240", MODE_PRIVATE)
        test = TestType.of(prefs.getString("lastTest", null))
        fvBattery = prefs.getString("lastTest", null) == "FV_BATTERY"
        val pTest = panel()
        pTest.addView(caption("Prueba"))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                TestType.values().map { it.label } + "Perfil fuerza-velocidad (4 cargas)")
            setSelection(if (fvBattery) TestType.values().size else test.ordinal)
        }
        pTest.addView(spinner, lp())
        testHelp = text(test.help, 13f, Col.MUTED)
        pTest.addView(testHelp)

        box = input("Altura del cajón en cm", true).apply { setText(prefs.getString("lastBox", "30")) }
        load = input("Carga adicional en kg (0 = sin carga)", true).apply { setText("0") }
        pTest.addView(caption("Cliente"))
        val cRow = hbox(8)
        clientSpinner = Spinner(this)
        cRow.addView(clientSpinner, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cRow.addView(button("Clientes") { startActivity(Intent(this, ClientsActivity::class.java)) }, lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        pTest.addView(cRow, lp())
        clientInfo = text("", 13f, Col.MUTED)
        pTest.addView(clientInfo)
        clientSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                showClientInfo()
                selectedClient()?.let { c ->
                    getSharedPreferences("salto240", MODE_PRIVATE).edit().putString("lastAthlete", c.name).apply()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        boxRow = vbox(6).apply { addView(caption("Cajón (Drop Jump)")); addView(box, lp()) }
        loadRow = vbox(6).apply { addView(caption("Carga adicional (barra o chaleco)")); addView(load, lp()) }
        pTest.addView(boxRow, lp())
        pTest.addView(loadRow, lp())
        root.addView(pTest, lp())

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                fvBattery = position >= TestType.values().size
                if (!fvBattery) test = TestType.values()[position]
                updateFields()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Medir
        val pAct = panel()
        pAct.addView(caption("Medir"))
        // Modo de medición: IA en vivo (como My Jump Lab) o cámara lenta 240 fps.
        val modes = listOf(
            "IA en vivo · cuerpo entero, sin cámara lenta",
            "Cámara lenta 240 fps · línea del suelo"
        )
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes)
            setSelection(if (CaptureMode.ai(this@MainActivity)) 0 else 1)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    CaptureMode.set(this@MainActivity, position == 0)
                    showModeInfo()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        pAct.addView(modeSpinner, lp())
        modeInfo = text("", 13f, Col.MUTED)
        pAct.addView(modeInfo)
        camInfo = text("Buscando cámara lenta…", 13f, Col.MUTED)
        pAct.addView(camInfo)
        recordBtn = button("Grabar salto", true) { startCapture() }
        galleryBtn = button("Analizar video de la galería") { pickVideo() }
        pAct.addView(recordBtn, lp())
        pAct.addView(galleryBtn, lp())
        root.addView(pAct, lp())
        updateFields()
        showModeInfo()

        // Evaluación
        val pEval = panel()
        pEval.addView(caption("Evaluación"))
        pEval.addView(button("Clientes y grupos") { startActivity(Intent(this, ClientsActivity::class.java)) }, lp())
        pEval.addView(button("Historial de evaluaciones") { openWithAthlete(ClientHistoryActivity::class.java) }, lp())
        pEval.addView(button("Perfil fuerza-velocidad") { openWithAthlete(FvProfileActivity::class.java) }, lp())
        pEval.addView(button("Informe y recomendaciones") { openWithAthlete(ReportActivity::class.java) }, lp())
        pEval.addView(button("Historial") { startActivity(Intent(this, HistoryActivity::class.java)) }, lp())
        root.addView(pEval, lp())

        // Cómo grabar
        val pTips = panel()
        pTips.addView(caption("Cómo grabar"))
        pTips.addView(text(
            "• Teléfono en trípode, a 1,5–2,5 m y a 20–40 cm del piso.\n" +
            "• Línea naranja justo donde la suela toca el piso.\n" +
            "• Quieto 1–2 s, saltar, aterrizar en el mismo sitio con piernas extendidas y quedarse quieto 1 s.",
            14f, Col.INK
        ))
        pTips.addView(button("Protocolo de grabación completo") {
            startActivity(Intent(this, ProtocolActivity::class.java)
                .putExtra(ProtocolActivity.EX_FOCUS, if (fvBattery) "FV_BATTERY" else test.name))
        }, lp())
        root.addView(pTips, lp())

        // Versión y actualizaciones
        val ver = hbox(8)
        ver.addView(text("AVD jump ${BuildConfig.VERSION_NAME}", 13f, Col.MUTED), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (Updater.enabled) {
            ver.addView(button("Buscar actualizaciones") { Updater.check(this, manual = true) },
                lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        root.addView(ver, lp())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Col.BG)
            addView(root)
        })

        Thread {
            val cfg = HighSpeed.find(this)
            runOnUiThread {
                camInfo.text = cfg?.describe() ?: "No se encontró cámara trasera."
                camInfo.setTextColor(if (cfg?.highSpeed == true) Col.OK else Col.WARN)
            }
        }.start()
    }

    private lateinit var modeInfo: TextView

    private fun showModeInfo() {
        val ai = CaptureMode.ai(this)
        modeInfo.text = if (ai)
            "La IA sigue el cuerpo del atleta y muestra cada salto al aterrizar, sin detener la cámara. Usa la estatura de la ficha."
        else "Graba en cámara lenta y mide cuadro a cuadro: la opción más precisa (±0,5 cm)."
        if (::camInfo.isInitialized) camInfo.visibility = if (ai) View.GONE else View.VISIBLE
    }

    private fun updateFields() {
        if (fvBattery) {
            testHelp.text = "Batería de 4 saltos (SJ o CMJ) con 4 cargas distintas, un video por carga, como My Jump Lab. " +
                "Da F0, V0, Pmax, el desequilibrio fuerza-velocidad y la sugerencia de entrenamiento."
            boxRow.visibility = View.GONE
            loadRow.visibility = View.GONE
            recordBtn.text = "Iniciar batería (4 cargas)"
            galleryBtn.visibility = View.GONE
            return
        }
        testHelp.text = test.help
        boxRow.visibility = if (test == TestType.DJ) View.VISIBLE else View.GONE
        loadRow.visibility = if (test == TestType.SJ || test == TestType.CMJ) View.VISIBLE else View.GONE
        recordBtn.text = "Grabar salto"
        galleryBtn.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        loadClients()
        Updater.check(this)
        // Borra en segundo plano las grabaciones descartadas que no quedaron ligadas a ningún salto.
        val app = applicationContext
        Thread { try { Videos.cleanOrphans(app) } catch (_: Exception) {} }.start()
    }

    private fun selectedClient(): Client? = clients.getOrNull(clientSpinner.selectedItemPosition)

    private fun loadClients() {
        clients = Clients.all(this)
        val last = getSharedPreferences("salto240", MODE_PRIVATE).getString("lastAthlete", null)
        clientSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (clients.isEmpty()) listOf("Sin clientes") else clients.map { c ->
                Clients.groupName(this, c.groupId)?.let { "${c.name} · $it" } ?: c.name
            })
        val idx = clients.indexOfFirst { it.name == last }
        if (idx >= 0) clientSpinner.setSelection(idx)
        showClientInfo()
    }

    private fun showClientInfo() {
        val c = selectedClient()
        clientInfo.text = c?.summary() ?: "Crea tu primer cliente con el botón Clientes."
        clientInfo.setTextColor(if (c != null && c.massKg == null) Col.WARN else Col.MUTED)
    }

    /** Cliente elegido; si no hay, abre la ficha para crear uno. */
    private fun requireClient(): Client? {
        val c = selectedClient()
        if (c == null) {
            Toast.makeText(this, "Primero crea un cliente.", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, ClientEditActivity::class.java))
        }
        return c
    }

    private fun athleteName() = selectedClient()?.name ?: "Sin nombre"

    private fun remember() {
        getSharedPreferences("salto240", MODE_PRIVATE).edit()
            .putString("lastAthlete", selectedClient()?.name)
            .putString("lastBox", box.text.toString())
            .putString("lastTest", if (fvBattery) "FV_BATTERY" else test.name)
            .apply()
    }

    private fun testIntent(cls: Class<*>): Intent {
        remember()
        return Intent(this, cls).apply {
            putExtra(EX_TEST, test.name)
            putExtra(EX_ATHLETE, athleteName())
            putExtra(EX_MASS, selectedClient()?.massKg ?: 0.0)
            putExtra(EX_BOX, box.number() ?: 0.0)
            putExtra(EX_LOAD, if (test == TestType.SJ || test == TestType.CMJ) load.number() ?: 0.0 else 0.0)
        }
    }

    private fun openWithAthlete(cls: Class<*>) {
        if (requireClient() == null) return
        remember()
        startActivity(Intent(this, cls).putExtra(EX_ATHLETE, athleteName()))
    }

    private fun startCapture() {
        if (requireClient() == null) return
        if (fvBattery) { openWithAthlete(BatteryActivity::class.java); return }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        startActivity(testIntent(CaptureMode.cls(this)))
    }

    private fun pickVideo() {
        if (requireClient() == null) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            // Permiso permanente de lectura: el video queda enlazado al salto para revisarlo después.
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val i = testIntent(AnalysisActivity::class.java)
            i.putExtra(EX_URI, uri.toString())
            i.putExtra(EX_FPS, 0) // desconocido: se pregunta en el análisis
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startActivity(testIntent(CaptureMode.cls(this)))
            } else {
                Toast.makeText(this, "Sin permiso de cámara no se puede grabar. Puedes analizar videos de la galería.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
