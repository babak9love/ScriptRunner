package com.scriptrunner.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.View

val DEFAULT_BAR: Int = Color.parseColor("#0A0E14")

fun parseColorOrNull(s: String?): Int? {
    if (s.isNullOrBlank()) return null
    return try {
        Color.parseColor(s.trim())
    } catch (_: Exception) {
        null
    }
}

fun isLightColor(c: Int): Boolean {
    val lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
    return lum > 0.6
}

/**
 * [status bar, navigation bar, background] colours for a script.
 * An explicit "#RRGGBB" in scripts.txt wins; otherwise the colour measured from the
 * script's page (remembered from the last run); otherwise the default dark colour.
 */
fun resolveColors(ctx: Context, s: Script?, sampled: Int? = null): IntArray {
    val auto = sampled ?: s?.let { TermuxHelper.getSampledColor(ctx, it.path) } ?: DEFAULT_BAR
    return intArrayOf(
        parseColorOrNull(s?.statusBar) ?: auto,
        parseColorOrNull(s?.navBar) ?: auto,
        parseColorOrNull(s?.background) ?: auto
    )
}

/** Paints the status / navigation bars; icons switch to dark on light colours. */
fun Activity.applyBarColors(status: Int, nav: Int) {
    window.statusBarColor = status
    window.navigationBarColor = nav
    @Suppress("DEPRECATION")
    var f = window.decorView.systemUiVisibility
    f = if (isLightColor(status)) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
    f = if (isLightColor(nav)) f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    else f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = f
}
