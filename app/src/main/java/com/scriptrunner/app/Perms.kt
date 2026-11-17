package com.scriptrunner.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

object Perms {
    const val RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    /** Permissions still to request. Notifications are asked only once. */
    fun missing(a: Activity): Array<String> {
        val l = mutableListOf<String>()
        if (a.checkSelfPermission(RUN_COMMAND) != PackageManager.PERMISSION_GRANTED) l.add(RUN_COMMAND)
        if (Build.VERSION.SDK_INT >= 33 &&
            a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = a.getSharedPreferences("p", 0)
            if (!prefs.getBoolean("asked_notif", false)) {
                prefs.edit().putBoolean("asked_notif", true).apply()
                l.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        return l.toTypedArray()
    }
}
