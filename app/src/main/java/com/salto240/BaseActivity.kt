package com.salto240

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * Base de todas las pantallas. Desde Android 15 (y obligatorio al apuntar a Android 16)
 * las apps dibujan detrás de las barras del sistema: aquí se agrega a la vista raíz el
 * margen de la barra de estado, la de navegación, el recorte de cámara y el teclado.
 */
open class BaseActivity : Activity() {

    /** true en pantallas oscuras (cámara): iconos claros en las barras. */
    protected open val darkSystemBars = false

    override fun setContentView(view: View) {
        super.setContentView(view)
        fitSystemBars(view)
    }

    @Suppress("DEPRECATION")
    private fun fitSystemBars(view: View) {
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        val l = view.paddingLeft
        val t = view.paddingTop
        val r = view.paddingRight
        val b = view.paddingBottom
        view.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            v.setPadding(l + i.left, t + i.top, r + i.right, b + i.bottom)
            insets
        }
        view.requestApplyInsets()
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (darkSystemBars) 0 else light, light)
    }
}
