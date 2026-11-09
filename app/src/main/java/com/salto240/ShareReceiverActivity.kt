package com.salto240

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/**
 * Recibe un video compartido desde otra app (galería, WhatsApp, Drive…: «Compartir › AVD jump»).
 * Copia el archivo a la carpeta de videos de la app (el permiso de la otra app es temporal), deja elegir
 * cliente y prueba y abre el análisis, que pregunta los fps y pide tocar el suelo para la detección automática.
 */
class ShareReceiverActivity : BaseActivity() {

    private var copied: File? = null
    private lateinit var status: TextView
    private lateinit var goBtn: android.widget.Button
    private lateinit var clientSpinner: Spinner
    private lateinit var testSpinner: Spinner
    private var clients: List<Client> = emptyList()
    private val tests = TestType.values().toList()
    private var boxInput: android.widget.EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = sharedUri(intent)
        if (uri == null) {
            Tst.makeText(this, "No llegó ningún video.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val root = vbox(14).apply { setPadding(dp(16), dp(20), dp(16), dp(24)) }
        root.addView(text("Analizar video compartido", 26f, Col.INK, true))
        status = text("Copiando el video…", 14f, Col.MUTED)
        root.addView(status)

        val p = panel()
        p.addView(caption("Cliente"))
        clients = Clients.all(this)
        clientSpinner = Spinner(this)
        val last = getSharedPreferences("salto240", MODE_PRIVATE).getString("lastAthlete", null)
        clientSpinner.adapter = TrAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (clients.isEmpty()) listOf("Sin nombre") else clients.map { it.name })
        clients.indexOfFirst { it.name == last }.takeIf { it >= 0 }?.let { clientSpinner.setSelection(it) }
        p.addView(clientSpinner, lp())
        p.addView(caption("Prueba"))
        testSpinner = Spinner(this)
        testSpinner.adapter = TrAdapter(this, android.R.layout.simple_spinner_dropdown_item, tests.map { it.label })
        testSpinner.setSelection(tests.indexOf(TestType.CMJ).coerceAtLeast(0))
        p.addView(testSpinner, lp())
        val box = input("Altura del cajón en Drop Jump (cm)", numeric = true)
        boxInput = box
        p.addView(box, lp())
        goBtn = button("Analizar", true) { go() }
        goBtn.isEnabled = false
        p.addView(goBtn, lp())
        root.addView(p, lp())
        root.addView(text("En el análisis se pregunta a cuántos fps se grabó y luego se toca el suelo donde apoya la suela: " +
            "la app marca el despegue y el aterrizaje sola.\n\n" +
            "WhatsApp y otras apps de mensajes suelen comprimir el video y bajarlo a 30 fps. Para medir con cámara lenta, " +
            "comparte el archivo original desde la galería o envíalo como documento.", 13f, Col.MUTED))
        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })

        Thread {
            val f = try { copy(uri) } catch (e: Exception) { null }
            val info = f?.let { describe(it) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (f == null) {
                    status.text = "No se pudo leer el video compartido."
                    status.setTextColor(Col.WARN)
                } else {
                    copied = f
                    status.text = "Video listo" + (info?.let { " · $it" } ?: "")
                    goBtn.isEnabled = true
                }
            }
        }.start()
    }

    private fun sharedUri(i: Intent): Uri? {
        if (i.action == Intent.ACTION_VIEW) return i.data
        @Suppress("DEPRECATION")
        val s: Parcelable? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableExtra(Intent.EXTRA_STREAM)
        return (s as? Uri) ?: i.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }

    private fun copy(uri: Uri): File {
        val ext = contentResolver.getType(uri)?.substringAfter('/')?.takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } } ?: "mp4"
        val f = File(Videos.dir(this), "compartido_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it, 1 shl 16) } }
        if (f.length() == 0L) { f.delete(); throw IllegalStateException("vacío") }
        return f
    }

    private fun describe(f: File): String? = try {
        val m = MediaMetadataRetriever()
        m.setDataSource(f.absolutePath)
        val dur = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val fr = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
        val cnt = if (Build.VERSION.SDK_INT >= 28) m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDoubleOrNull() else null
        m.release()
        val fps = fr ?: if (cnt != null && dur != null && dur > 0) cnt / (dur / 1000.0) else null
        listOfNotNull(dur?.let { String.format(Locale.getDefault(), "%.1f s", it / 1000.0) },
            fps?.let { String.format(Locale.getDefault(), "%.0f cuadros/s en el archivo", it) },
            Videos.humanSize(f.length())).joinToString(" · ")
    } catch (e: Exception) { null }

    private fun go() {
        val f = copied ?: return
        val c = clients.getOrNull(clientSpinner.selectedItemPosition)
        val t = tests[testSpinner.selectedItemPosition]
        c?.let { getSharedPreferences("salto240", MODE_PRIVATE).edit().putString("lastAthlete", it.name).apply() }
        val i = Intent(this, AnalysisActivity::class.java).apply {
            putExtra(MainActivity.EX_TEST, t.name)
            putExtra(MainActivity.EX_ATHLETE, c?.name ?: "Sin nombre")
            putExtra(MainActivity.EX_MASS, c?.massKg ?: 0.0)
            putExtra(MainActivity.EX_BOX, if (t.isDrop) boxInput?.number() ?: 0.0 else 0.0)
            putExtra(MainActivity.EX_LOAD, 0.0)
            putExtra(MainActivity.EX_URI, Uri.fromFile(f).toString())
            putExtra(MainActivity.EX_FPS, 0)
            putExtra(AnalysisActivity.EX_SHARED, true)
        }
        copied = null   // ahora el análisis se encarga del archivo
        startActivity(i)
        finish()
    }

    override fun onDestroy() {
        // Si no se llegó a analizar, no queda la copia.
        copied?.delete()
        super.onDestroy()
    }
}
