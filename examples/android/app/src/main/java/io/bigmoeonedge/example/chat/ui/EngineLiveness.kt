package io.bigmoeonedge.example.chat.ui

import android.app.ActivityManager
import android.content.Context

/** Whether the `:engine` process exists. The status row can say BUSY after the process died. */
object EngineLiveness {
    fun isAlive(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.any { it.processName.endsWith(":engine") } == true
    }
}
