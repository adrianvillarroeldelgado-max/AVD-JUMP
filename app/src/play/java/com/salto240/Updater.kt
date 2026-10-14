package com.salto240

import android.app.Activity

/** Versión de Google Play: las actualizaciones las instala Play Store, así que aquí no se hace nada. */
object Updater {
    const val enabled = false

    @Suppress("UNUSED_PARAMETER")
    fun check(a: Activity, manual: Boolean = false) {}
}
