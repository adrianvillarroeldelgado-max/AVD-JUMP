package com.salto240

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Ajustes y datos: idioma, voz, técnica, videos, copia de seguridad, importar y exportar. */
class SettingsActivity : BaseActivity() {

    companion object {
        const val EX_ACTION = "action"
        const val ACTION_IMPORT = "import"
        const val ACTION_BACKUP = "backup"
        private const val REQ_EXPORT = 21
        private const val REQ_EXPORT_VID = 22
        private const val REQ_RESTORE = 23
        private const val REQ_TREE = 24
        private const val REQ_IMPORT = 25
    }

    private lateinit var backupInfo: TextView
    private lateinit var autoInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vbox(14).apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(text("Ajustes y datos", 28f, Col.INK, true))

        // Idioma
        val pLang = panel()
        pLang.addView(caption("Idioma"))
        val langs = Lang.options()
        val cur = Lang.choice(this)
        pLang.addView(button("Idioma: " + langs.first { it.first == cur }.second) {
            Dlg(this)
                .setTitle("Idioma")
                .setSingleChoiceItems(langs.map { it.second }.toTypedArray(), langs.indexOfFirst { it.first == cur }) { d, w ->
                    d.dismiss()
                    Lang.set(this, langs[w].first)
                    // Reinicia la app en el idioma nuevo.
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                .show()
        }, lp())
        root.addView(pLang, lp())

        // Medición
        val pMeas = panel()
        pMeas.addView(caption("Medición"))
        pMeas.addView(switch("Avisos de voz (resultado, turno del siguiente atleta)", Voice.enabled(this)) { Voice.setEnabled(this, it) })
        pMeas.addView(switch("Control de técnica con IA (rodillas al aterrizar, manos en la cadera)", Technique.enabled(this)) { Technique.setEnabled(this, it) })
        pMeas.addView(switch("Resultado en pantalla grande", BigResult.enabled(this)) { BigResult.setEnabled(this, it) })
        pMeas.addView(switch("Nivel de la cámara en pantalla", CamLevel.enabled(this)) { CamLevel.setEnabled(this, it) })
        pMeas.addView(switch("Guardar el video de cada salto", Videos.enabled(this)) { Videos.setEnabled(this, it) })
        pMeas.addView(text("Control remoto: un botón Bluetooth de selfie (o las teclas de volumen) inicia y detiene la grabación, " +
            "guarda el resultado o pasa al siguiente.", 12f, Col.MUTED))
        root.addView(pMeas, lp())

        // Copia de seguridad
        val pB = panel()
        pB.addView(caption("Copia de seguridad"))
        pB.addView(text("Tus clientes, grupos y saltos viven solo en este teléfono. Guarda una copia en Drive, en tu correo o en otro lugar " +
            "para no perderlos si cambias o pierdes el teléfono.", 13f, Col.MUTED))
        backupInfo = text("", 13f, Col.INK)
        pB.addView(backupInfo)
        pB.addView(button("Crear copia (sin videos)", true) { create(Backup.fileName(false), REQ_EXPORT) }, lp())
        pB.addView(button("Crear copia con videos") { create(Backup.fileName(true), REQ_EXPORT_VID) }, lp())
        pB.addView(button("Restaurar una copia") { open(arrayOf("application/zip", "application/octet-stream", "*/*"), REQ_RESTORE) }, lp())
        pB.addView(caption("Copia automática semanal"))
        autoInfo = text("", 13f, Col.INK)
        pB.addView(autoInfo)
        pB.addView(button("Elegir carpeta (p. ej. Google Drive)") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), REQ_TREE)
        }, lp())
        pB.addView(button("Copiar ahora a la carpeta") { autoNow() }, lp())
        pB.addView(button("Desactivar copia automática") { Backup.setAutoFolder(this, null); refresh() }, lp())
        root.addView(pB, lp())

        // Importar y exportar
        val pIO = panel()
        pIO.addView(caption("Importar y exportar"))
        pIO.addView(button("Importar clientes (Excel o CSV)", true) { startImport() }, lp())
        pIO.addView(button("Plantilla de Excel para clientes") {
            work("Preparando…", { DataIO.templateFile(this) }) { f -> ShareFiles.share(this, f, "Plantilla clientes AVD jump") }
        }, lp())
        pIO.addView(button("Exportar todo a Excel") {
            work("Creando Excel…", { DataIO.excel(this) }) { f -> ShareFiles.share(this, f, "Datos AVD jump") }
        }, lp())
        pIO.addView(button("Valores de referencia (percentiles)") { startActivity(Intent(this, RefsActivity::class.java)) }, lp())
        root.addView(pIO, lp())

        val pHelp = panel()
        pHelp.addView(caption("Ayuda"))
        pHelp.addView(button("Guía de inicio") { startActivity(Intent(this, OnboardingActivity::class.java)) }, lp())
        pHelp.addView(button("Protocolo de grabación") { startActivity(Intent(this, ProtocolActivity::class.java)) }, lp())
        root.addView(pHelp, lp())

        setContentView(ScrollView(this).apply { setBackgroundColor(Col.BG); addView(root) })
        refresh()
        when (intent.getStringExtra(EX_ACTION)) {
            ACTION_IMPORT -> startImport()
            ACTION_BACKUP -> create(Backup.fileName(false), REQ_EXPORT)
        }
    }

    private fun switch(label: String, on: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(this).apply {
        text = label
        isChecked = on
        setTextColor(Col.INK)
        textSize = 15f
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onChange(v) }
    }

    private fun refresh() {
        backupInfo.text = "Última copia: " + Backup.pretty(Backup.lastBackup(this))
        val f = Backup.autoFolder(this)
        autoInfo.text = if (f == null) "Desactivada. Elige una carpeta y la app guardará ahí una copia cada 7 días al abrirse (conserva las 5 últimas)."
        else "Activa en: " + (f.lastPathSegment?.substringAfter(':') ?: f.toString())
    }

    private fun create(name: String, req: Int) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip").putExtra(Intent.EXTRA_TITLE, name), req)
    }

    private fun open(types: Array<String>, req: Int) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, types), req)
    }

    private fun startImport() = open(arrayOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "text/comma-separated-values",
        "text/plain", "application/vnd.ms-excel", "application/octet-stream"), REQ_IMPORT)

    /** Tarea en segundo plano con diálogo de espera. */
    @Suppress("DEPRECATION")
    private fun <T> work(msg: String, job: () -> T, done: (T) -> Unit) {
        val pd = ProgressDialog(this).apply { setMessage(Lang.tr(this@SettingsActivity, msg)); setCancelable(false); show() }
        Thread {
            val r = try { Result.success(job()) } catch (e: Throwable) { Result.failure(e) }
            runOnUiThread {
                try { pd.dismiss() } catch (_: Exception) {}
                if (isFinishing) return@runOnUiThread
                r.onSuccess(done).onFailure { e ->
                    Dlg(this).setTitle("No se pudo completar").setMessage(e.message ?: e.toString())
                        .setPositiveButton("Cerrar", null).show()
                }
                refresh()
            }
        }.start()
    }

    private fun autoNow() {
        if (Backup.autoFolder(this) == null) { Tst.makeText(this, "Primero elige la carpeta.", Toast.LENGTH_LONG).show(); return }
        work("Copiando…", { Backup.writeAuto(this) }) { n -> Tst.makeText(this, "Copia guardada: $n", Toast.LENGTH_LONG).show() }
    }

    private fun infoText(i: Backup.Info) = "${i.clients} clientes · ${i.groups} grupos · ${i.jumps} saltos" +
        (if (i.videos > 0) " · ${i.videos} videos" else "")

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT, REQ_EXPORT_VID -> work("Creando la copia…", { Backup.exportTo(this, uri, requestCode == REQ_EXPORT_VID) }) { i ->
                Dlg(this).setTitle("Copia creada").setMessage(infoText(i) +
                    "\n\nPara restaurarla: Ajustes y datos › Restaurar una copia, en este u otro teléfono.")
                    .setPositiveButton("Cerrar", null).show()
            }
            REQ_RESTORE -> work("Leyendo la copia…", { Backup.peek(this, uri) }) { i ->
                Dlg(this)
                    .setTitle("Restaurar copia" + (i.created?.takeIf { it.isNotEmpty() }?.let { " del " + Evaluations.pretty(it.take(10)) } ?: ""))
                    .setMessage(infoText(i) + "\n\nCombinar: agrega lo que no tengas (no borra nada).\nReemplazar: deja el teléfono igual que la copia.")
                    .setPositiveButton("Combinar") { _, _ -> restore(uri, false) }
                    .setNeutralButton("Reemplazar") { _, _ ->
                        Dlg(this).setTitle("¿Reemplazar todos los datos?")
                            .setMessage("Se borran los clientes y saltos actuales de este teléfono y se dejan los de la copia.")
                            .setPositiveButton("Reemplazar") { _, _ -> restore(uri, true) }
                            .setNegativeButton("Cancelar", null).show()
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
            REQ_TREE -> {
                Backup.setAutoFolder(this, uri)
                refresh()
                autoNow()
            }
            REQ_IMPORT -> work("Importando…", { DataIO.importClients(this, DataIO.readTable(this, uri)) }) { r ->
                val sb = StringBuilder("${r.created} clientes nuevos · ${r.updated} actualizados")
                if (r.groupsCreated > 0) sb.append(" · ${r.groupsCreated} grupos nuevos")
                if (r.skipped.isNotEmpty()) sb.append("\n\nNo importados:\n").append(r.skipped.take(15).joinToString("\n"))
                Dlg(this).setTitle("Importación terminada").setMessage(sb.toString())
                    .setPositiveButton("Ver clientes") { _, _ -> startActivity(Intent(this, ClientsActivity::class.java)) }
                    .setNegativeButton("Cerrar", null).show()
            }
        }
    }

    private fun restore(uri: Uri, replace: Boolean) {
        work("Restaurando…", { Backup.restore(this, uri, replace) }) { i ->
            Dlg(this).setTitle(if (replace) "Datos restaurados" else "Copia combinada")
                .setMessage((if (replace) "" else "Agregados: ") + infoText(i))
                .setPositiveButton("Cerrar", null).show()
        }
    }
}
