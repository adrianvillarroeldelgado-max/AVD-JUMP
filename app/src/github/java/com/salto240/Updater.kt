package com.salto240

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualización automática de la versión descargada desde GitHub.
 *
 * Cada versión publicada en GitHub Releases incluye "version.json" con su código de versión.
 * Al abrir la app (como máximo cada 3 horas) se consulta la última versión; si es más nueva,
 * se ofrece actualizar: se descarga el APK y Android pide confirmar la instalación.
 * Como todas las versiones están firmadas con la misma clave, se instala encima sin perder datos.
 * Solo se consulta GitHub; no se envía ningún dato del usuario.
 */
object Updater {

    /** La versión de Google Play no tiene actualizador (Play actualiza la app). */
    const val enabled = true

    data class Info(val code: Int, val name: String, val apkUrl: String, val notes: String)

    private const val PREFS = "salto240"
    private const val KEY_LAST = "updateCheckMs"
    private const val KEY_SKIP = "updateSkipCode"
    private const val EVERY_MS = 3L * 60 * 60 * 1000

    /** Versión que espera el permiso "instalar apps desconocidas" para continuar. */
    private var pending: Info? = null
    @Volatile private var busy = false

    private fun base() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download"

    /** Busca una versión nueva. [manual] = el usuario pulsó "Buscar actualizaciones". */
    fun check(a: Activity, manual: Boolean = false) {
        if (busy) return
        val p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Volvió de dar el permiso de instalación: seguir con la descarga.
        pending?.let { info ->
            if (canInstall(a)) { pending = null; download(a, info); return }
        }
        val now = System.currentTimeMillis()
        if (!manual && now - p.getLong(KEY_LAST, 0L) < EVERY_MS) return
        p.edit().putLong(KEY_LAST, now).apply()

        busy = true
        Thread {
            val info = try { fetch() } catch (_: Exception) { null }
            busy = false
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                when {
                    info == null -> if (manual) toast(a, "No se pudo comprobar. Revisa la conexión a internet.")
                    info.code <= BuildConfig.VERSION_CODE -> if (manual) toast(a, "Ya tienes la última versión (${BuildConfig.VERSION_NAME}).")
                    !manual && p.getInt(KEY_SKIP, -1) == info.code -> {}
                    else -> offer(a, info)
                }
            }
        }.start()
    }

    private fun fetch(): Info? {
        val c = (URL("${base()}/version.json").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            return Info(
                code = o.getInt("versionCode"),
                name = o.optString("versionName", "?"),
                apkUrl = o.optString("apk", "").ifBlank { "${base()}/AVD-jump.apk" },
                notes = o.optString("notes", "")
            )
        } finally {
            c.disconnect()
        }
    }

    private fun offer(a: Activity, info: Info) {
        val msg = buildString {
            append("Tienes la ${BuildConfig.VERSION_NAME}. ")
            append("La nueva versión se instala encima: no se pierden clientes ni saltos.")
            if (info.notes.isNotBlank()) append("\n\nNovedades:\n").append(info.notes.take(600))
        }
        AlertDialog.Builder(a)
            .setTitle("Nueva versión ${info.name}")
            .setMessage(msg)
            .setPositiveButton("Actualizar") { _, _ -> start(a, info) }
            .setNegativeButton("Más tarde", null)
            .setNeutralButton("Omitir esta") { _, _ ->
                a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SKIP, info.code).apply()
            }
            .show()
    }

    private fun canInstall(ctx: Context) =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    private fun start(a: Activity, info: Info) {
        if (canInstall(a)) { download(a, info); return }
        pending = info
        AlertDialog.Builder(a)
            .setTitle("Permitir actualizaciones")
            .setMessage("Para instalar la versión nueva, activa “Permitir de esta fuente” para AVD jump y vuelve a la app. Solo hay que hacerlo una vez.")
            .setPositiveButton("Abrir ajustes") { _, _ ->
                try {
                    a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
                } catch (_: Exception) {
                    toast(a, "Abre Ajustes › Apps › AVD jump › Instalar apps desconocidas.")
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> pending = null }
            .show()
    }

    private fun download(a: Activity, info: Info) {
        val status = a.text("Descargando 0 %", 15f).apply { setPadding(a.dp(24), a.dp(16), a.dp(24), a.dp(8)) }
        val dlg = AlertDialog.Builder(a)
            .setTitle("AVD jump ${info.name}")
            .setView(status)
            .setCancelable(false)
            .show()
        busy = true
        Thread {
            val f = File(a.cacheDir, "actualizacion.apk")
            var err: String? = null
            try {
                var url = info.apkUrl
                var c: HttpURLConnection
                var hops = 0
                while (true) {
                    c = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000; readTimeout = 20000; instanceFollowRedirects = true
                    }
                    val code = c.responseCode
                    if (code in 300..399 && hops < 5) {
                        url = c.getHeaderField("Location") ?: break; c.disconnect(); hops++; continue
                    }
                    break
                }
                if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; a.runOnUiThread { status.text = "Descargando $pct %" } }
                            }
                        }
                    }
                }
                c.disconnect()
                a.runOnUiThread { status.text = "Preparando la instalación…" }
                install(a.applicationContext, f)
            } catch (e: Exception) {
                err = e.message ?: "error desconocido"
            }
            busy = false
            a.runOnUiThread {
                try { dlg.dismiss() } catch (_: Exception) {}
                if (err != null) toast(a, "No se pudo descargar la actualización: $err")
            }
        }.start()
    }

    private fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            // Desde Android 12, si AVD jump ya instaló una versión antes, las siguientes pueden no pedir confirmación.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("avd-jump.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pend = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), flags)
            s.commit(pend.intentSender)
        }
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}

/** Recibe el estado de la instalación y abre la confirmación de Android cuando hace falta. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(it) } catch (_: Exception) {}
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Toast.makeText(ctx, "No se pudo instalar la actualización. $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
