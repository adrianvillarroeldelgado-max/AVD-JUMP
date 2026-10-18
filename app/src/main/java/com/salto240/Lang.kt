package com.salto240

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Idiomas: español (original), inglés y portugués.
 *
 * Los textos de la app están escritos en español. Para inglés y portugués se usan diccionarios
 * (assets/i18n/en.tsv y pt.tsv: "español<TAB>traducción"). Cada texto se traduce entero si está
 * en el diccionario; si no, se traducen sus fragmentos conocidos (frases que llevan números o nombres).
 * Las pantallas se traducen solas al dibujarse (ver BaseActivity), igual que diálogos, avisos y listas.
 */
object Lang {
    private const val PREF = "lang"

    fun options(): List<Pair<String, String>> = listOf(
        "auto" to "Automático (idioma del teléfono)",
        "es" to "Español",
        "en" to "English",
        "pt" to "Português"
    )

    fun choice(ctx: Context): String = ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).getString(PREF, "auto") ?: "auto"

    fun set(ctx: Context, code: String) {
        ctx.getSharedPreferences("salto240", Context.MODE_PRIVATE).edit().putString(PREF, code).commit()
        cachedCode = null
        dict = null
        cache.evictAll()
    }

    @Volatile private var cachedCode: String? = null

    /** Idioma efectivo: es, en o pt. */
    fun code(ctx: Context): String {
        cachedCode?.let { return it }
        val c = when (val ch = choice(ctx)) {
            "auto" -> when (Locale.getDefault().language) { "en" -> "en"; "pt" -> "pt"; else -> "es" }
            else -> ch
        }
        cachedCode = c
        return c
    }

    fun locale(ctx: Context): Locale = when (code(ctx)) {
        "en" -> Locale.US
        "pt" -> Locale("pt", "BR")
        else -> Locale("es")
    }

    fun spanish(ctx: Context) = code(ctx) == "es"

    // ---------------------------------------------------------------- diccionario

    private class Dict(val exact: HashMap<String, String>, val parts: List<Pair<String, String>>, val rules: List<Pair<Regex, String>>)

    @Volatile private var dict: Dict? = null
    private val cache = LruCache<String, String>(1500)

    private fun load(ctx: Context): Dict? {
        dict?.let { return it }
        val code = code(ctx)
        if (code == "es") return null
        val exact = HashMap<String, String>(4000)
        val rules = ArrayList<Pair<Regex, String>>()
        try {
            ctx.assets.open("i18n/$code.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    if (raw.isBlank() || raw.startsWith("#")) continue
                    val i = raw.indexOf('\t')
                    if (i <= 0) continue
                    val k = raw.substring(0, i).replace("\\n", "\n")
                    val v = raw.substring(i + 1).replace("\\n", "\n")
                    if (k.startsWith("re:")) {
                        try { rules.add(Regex(k.substring(3)) to v) } catch (_: Exception) {}
                    } else exact[k] = v
                }
            }
        } catch (_: Exception) { return null }
        // Fragmentos: solo frases (≥ 2 palabras o ≥ 7 letras), de la más larga a la más corta.
        val parts = exact.entries.filter { (k, _) -> val t = k.trim(); t.length >= 7 || (t.contains(' ') && t.length >= 5) }
            .map { it.key to it.value }.sortedByDescending { it.first.length }
        return Dict(exact, parts, rules).also { dict = it }
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit()

    /** Traduce un texto escrito en español al idioma de la app. */
    fun tr(ctx: Context, s: CharSequence?): String {
        val src = s?.toString() ?: return ""
        if (src.isBlank()) return src
        val d = load(ctx) ?: return src
        cache.get(src)?.let { return it }
        val out = translate(d, src)
        cache.put(src, out)
        return out
    }

    private fun translate(d: Dict, src: String): String {
        d.exact[src]?.let { return it }
        // Por líneas: cada línea puede estar entera en el diccionario.
        if (src.contains('\n')) return src.split('\n').joinToString("\n") { if (it.isBlank()) it else translate(d, it) }
        val trimmed = src.trim()
        if (trimmed != src) d.exact[trimmed]?.let { return src.replace(trimmed, it) }
        var t = src
        for ((re, rep) in d.rules) t = re.replace(t, rep)
        if (t != src) d.exact[t]?.let { return it }
        // Fragmentos conocidos dentro del texto (respetando límites de palabra).
        for ((k, v) in d.parts) {
            var from = 0
            while (true) {
                val i = t.indexOf(k, from)
                if (i < 0) break
                val okL = i == 0 || !isWordChar(t[i - 1]) || !isWordChar(k.first())
                val end = i + k.length
                val okR = end >= t.length || !isWordChar(t[end]) || !isWordChar(k.last())
                if (okL && okR) { t = t.substring(0, i) + v + t.substring(end); from = i + v.length } else from = i + 1
            }
        }
        return t
    }

    // ---------------------------------------------------------------- vistas

    private const val TAG_KEY = 0x7f0a0b0c

    /** Traduce los textos visibles de una jerarquía de vistas (solo los que cambiaron). */
    fun translateTree(ctx: Context, v: View) {
        if (spanish(ctx)) return
        when (v) {
            is EditText -> {
                v.hint?.let { h -> val t = tr(ctx, h); if (t != h.toString()) v.hint = t }
            }
            is TextView -> {
                val cur = v.text
                if (cur != null && cur.isNotEmpty() && v.getTag(TAG_KEY) != cur.toString()) {
                    val t = tr(ctx, cur)
                    if (t != cur.toString()) v.text = t
                    v.setTag(TAG_KEY, t)
                }
                v.hint?.let { h -> val t = tr(ctx, h); if (t != h.toString()) v.hint = t }
            }
            is ViewGroup -> for (i in 0 until v.childCount) translateTree(ctx, v.getChildAt(i))
        }
    }
}

/** AlertDialog.Builder que traduce títulos, mensajes, botones y opciones. */
open class Dlg(private val c: Context) : AlertDialog.Builder(c) {
    private fun t(s: CharSequence?) = if (s == null) null else Lang.tr(c, s)
    private fun ta(a: Array<out CharSequence>?): Array<CharSequence>? = a?.map { Lang.tr(c, it) as CharSequence }?.toTypedArray()

    override fun setTitle(title: CharSequence?): AlertDialog.Builder = super.setTitle(t(title))
    override fun setMessage(message: CharSequence?): AlertDialog.Builder = super.setMessage(t(message))
    override fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setPositiveButton(t(text), listener)
    override fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setNegativeButton(t(text), listener)
    override fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setNeutralButton(t(text), listener)
    override fun setItems(items: Array<out CharSequence>?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setItems(ta(items), listener)
    override fun setMultiChoiceItems(items: Array<out CharSequence>?, checkedItems: BooleanArray?,
                                     listener: DialogInterface.OnMultiChoiceClickListener?): AlertDialog.Builder =
        super.setMultiChoiceItems(ta(items), checkedItems, listener)
    override fun setSingleChoiceItems(items: Array<out CharSequence>?, checkedItem: Int, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setSingleChoiceItems(ta(items), checkedItem, listener)
    override fun setView(view: View?): AlertDialog.Builder {
        view?.let { Lang.translateTree(c, it) }
        return super.setView(view)
    }
}

/** Toast traducido. */
object Tst {
    fun makeText(ctx: Context, s: CharSequence, d: Int): Toast = Toast.makeText(ctx, Lang.tr(ctx, s), d)
}

/** Adaptador de listas desplegables que traduce lo que muestra (el valor elegido no cambia). */
class TrAdapter<T>(ctx: Context, res: Int, items: List<T>) : ArrayAdapter<T>(ctx, res, items) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        super.getView(position, convertView, parent).also { Lang.translateTree(context, it) }
    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        super.getDropDownView(position, convertView, parent).also { Lang.translateTree(context, it) }
}
