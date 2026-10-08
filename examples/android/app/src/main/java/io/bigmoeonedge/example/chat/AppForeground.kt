package io.bigmoeonedge.example.chat

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether one of this process's activities is on screen. Foreground mode lives or dies by it: the main
 * process only has the larger memory budget while the app is in front. An activity change (rotation)
 * stops one activity and starts the next within a moment, so "left the app" is reported after a short
 * delay and cancelled by the next start.
 */
object AppForeground {
    fun interface Listener {
        fun onChanged(foreground: Boolean)
    }

    private const val LEAVE_DELAY_MS = 800L
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var started = 0

    @Volatile var isForeground = false
        private set

    private val leave = Runnable { set(false) }

    fun add(l: Listener) {
        listeners += l
    }

    fun remove(l: Listener) {
        listeners -= l
    }

    /** Call from every activity's onStart / onStop (main thread). */
    fun activityStarted() {
        started++
        main.removeCallbacks(leave)
        set(true)
    }

    fun activityStopped() {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) main.postDelayed(leave, LEAVE_DELAY_MS)
    }

    private fun set(v: Boolean) {
        if (isForeground == v) return
        isForeground = v
        for (l in listeners) l.onChanged(v)
    }
}
