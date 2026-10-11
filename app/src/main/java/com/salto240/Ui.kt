package com.salto240

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Paleta: azul pista + naranja de tartán. */
object Col {
    const val BG = 0xFFEEF1F5.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val INK = 0xFF152033.toInt()
    const val MUTED = 0xFF5B6678.toInt()
    const val LINE = 0xFFD6DCE5.toInt()
    const val ACCENT = 0xFFD9481F.toInt()
    const val TRACK = 0xFF1E4F8C.toInt()
    const val OK = 0xFF1D7A4A.toInt()
    const val WARN = 0xFFA86400.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun Context.vbox(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gapDp > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(1, dp(gapDp)); setColor(0x00000000) }
    }
}

fun Context.hbox(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (gapDp > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(dp(gapDp), 1); setColor(0x00000000) }
    }
}

fun Context.text(s: String, sizeSp: Float = 15f, color: Int = Col.INK, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

fun Context.caption(s: String): TextView = text(s.uppercase(), 11f, Col.MUTED, false).apply {
    letterSpacing = 0.08f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

fun Context.rounded(fill: Int, stroke: Int = 0, radiusDp: Int = 10): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dp(radiusDp).toFloat()
    if (stroke != 0) setStroke(dp(1), stroke)
}

fun Context.panel(): LinearLayout = vbox(10).apply {
    background = rounded(Col.SURFACE, Col.LINE)
    setPadding(dp(14), dp(14), dp(14), dp(14))
}

fun Context.button(label: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    isAllCaps = false
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    setTextColor(if (primary) 0xFFFFFFFF.toInt() else Col.INK)
    background = rounded(if (primary) Col.ACCENT else Col.SURFACE, if (primary) Col.ACCENT else Col.LINE, 8)
    stateListAnimator = null
    minHeight = dp(48)
    setPadding(dp(10), dp(8), dp(10), dp(8))
    setOnClickListener { onClick() }
}

fun Context.input(hint: String, numeric: Boolean = false): EditText = EditText(this).apply {
    this.hint = hint
    setTextColor(Col.INK)
    setHintTextColor(Col.MUTED)
    background = rounded(Col.BG, Col.LINE, 6)
    setPadding(dp(10), dp(10), dp(10), dp(10))
    setSingleLine()
    if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
}

/** Lee un número aceptando coma o punto decimal. */
fun EditText.number(): Double? = text.toString().trim().replace(',', '.').toDoubleOrNull()
