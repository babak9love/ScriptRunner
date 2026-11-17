package com.scriptrunner.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the stdout of the "cat ~/scripts.txt" command that Termux ran for us. */
class ScriptListReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val b = intent.getBundleExtra("result")
        val stdout = b?.getString("stdout") ?: ""
        val stderr = b?.getString("stderr") ?: ""
        val errmsg = b?.getString("errmsg") ?: ""
        val ok = stdout.isNotBlank()
        val text = if (ok) stdout else errmsg.ifBlank { stderr }.ifBlank { "No output from Termux" }
        TermuxHelper.deliverListResult(ok, text)
    }
}
