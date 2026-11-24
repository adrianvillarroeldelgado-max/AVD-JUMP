package com.salto240

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * «Reportar medición»: empaqueta en un .zip lo necesario para revisar una medición que salió mal
 * (video, marcas automáticas y corregidas, confianza, datos del teléfono y un comentario) y abre
 * el menú de compartir (correo, WhatsApp, Drive). Así los fallos reales sirven para ajustar la detección.
 */
object Report {

    val PROBLEMS = listOf(
        "El despegue quedó mal marcado",
        "El aterrizaje quedó mal marcado",
        "La altura no coincide con la referencia",
        "Contó un salto que no hice",
        "No detectó el salto",
        "Otro problema"
    )

    fun autoJson(r: AutoDetect.Result?): JSONObject? {
        r ?: return null
        val o = JSONObject()
        r.takeoff?.let { o.put("takeoff", it) }
        r.landing?.let { o.put("landing", it) }
        r.contact?.let { o.put("contact", it) }
        if (r.events.isNotEmpty()) o.put("events", JSONArray(r.events))
        r.takeoffSub?.let { o.put("takeoffSub", it) }
        r.landingSub?.let { o.put("landingSub", it) }
        r.contactSub?.let { o.put("contactSub", it) }
        if (r.eventsSub.isNotEmpty()) o.put("eventsSub", JSONArray(r.eventsSub))
        r.preContact?.let { o.put("preContact", it) }
        o.put("quality", r.quality.toDouble())
        r.confidence?.let { o.put("confidence", it) }
        if (r.reasons.isNotEmpty()) o.put("reasons", JSONArray(r.reasons))
        r.error?.let { o.put("error", it) }
        return o
    }

    fun poseJson(samples: List<PoseJumpDetector.Sample>): JSONArray {
        val a = JSONArray()
        for (s in samples) a.put(JSONArray().put(s.t).put(s.foot ?: JSONObject.NULL).put(s.nose ?: JSONObject.NULL).put(s.hip ?: JSONObject.NULL))
        return a
    }

    private fun device(act: Activity): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("android", Build.VERSION.SDK_INT)
        .put("app", BuildConfig.VERSION_NAME)
        .put("lang", Lang.code(act))
        .apply { Diagnostics.last(act)?.let { put("diagnostic", it) } }

    /**
     * Pregunta qué falló y arma el reporte. [video] puede ser file:// o content:// (o null en la IA);
     * [data] lleva todo lo que se sepa de la medición.
     */
    fun ask(act: Activity, kind: String, video: String?, data: JSONObject) {
        val box = act.vbox(8).apply { setPadding(act.dp(20), act.dp(8), act.dp(20), 0) }
        box.addView(act.text("¿Qué salió mal? El reporte lleva el video y los datos de la medición para poder ajustar la detección.", 13f, Col.MUTED))
        val rg = RadioGroup(act)
        PROBLEMS.forEachIndexed { i, p -> rg.addView(RadioButton(act).apply { id = 1000 + i; text = p; setTextColor(Col.INK) }) }
        rg.check(1000)
        box.addView(rg, act.lp())
        val ref = act.input("Altura real o de la referencia (cm, opcional)", numeric = true)
        box.addView(ref, act.lp())
        val note = EditText(act).apply { hint = "Comentario (opcional)"; minLines = 2 }
        box.addView(note, act.lp())
        val withVideo = CheckBox(act).apply { text = "Incluir el video"; isChecked = video != null; isEnabled = video != null; setTextColor(Col.INK) }
        box.addView(withVideo, act.lp())
        Dlg(act).setTitle("Reportar medición").setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("Crear reporte") { _, _ ->
                val problem = PROBLEMS.getOrElse(rg.checkedRadioButtonId - 1000) { PROBLEMS.last() }
                data.put("kind", kind).put("problem", problem).put("comment", note.text.toString())
                ref.number()?.let { data.put("referenceCm", it) }
                data.put("device", device(act)).put("created", Store.today())
                val vid = if (withVideo.isChecked) video else null
                Tst.makeText(act, "Preparando el reporte…", Toast.LENGTH_SHORT).show()
                Thread {
                    val f = try { build(act, vid, data) } catch (e: Exception) { null }
                    act.runOnUiThread {
                        if (f == null) Tst.makeText(act, "No se pudo crear el reporte.", Toast.LENGTH_LONG).show()
                        else ShareFiles.share(act, f, "Reporte de medición AVD jump",
                            "Reporte de medición de AVD jump ${BuildConfig.VERSION_NAME} (${Build.MANUFACTURER} ${Build.MODEL}): $problem")
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun build(act: Activity, video: String?, data: JSONObject): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val f = ShareFiles.newFile(act, "AVD-jump-reporte-$stamp.zip")
        ZipOutputStream(f.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("reporte.json"))
            z.write(data.toString(2).toByteArray(Charsets.UTF_8))
            z.closeEntry()
            if (video != null) {
                val ext = if (video.endsWith(".mp4", true) || video.startsWith("file:")) "mp4" else "mp4"
                val inp = try { act.contentResolver.openInputStream(Uri.parse(video)) } catch (_: Exception) { null }
                inp?.use {
                    z.putNextEntry(ZipEntry("video.$ext"))
                    it.copyTo(z, 1 shl 16)
                    z.closeEntry()
                }
            }
        }
        return f
    }
}
