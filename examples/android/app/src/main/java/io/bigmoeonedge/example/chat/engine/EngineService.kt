package io.bigmoeonedge.example.chat.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.bigmoeonedge.example.AppSettings
import io.bigmoeonedge.example.BuildConfig
import io.bigmoeonedge.example.ModelManager
import io.bigmoeonedge.example.R
import io.bigmoeonedge.example.RunService
import io.bigmoeonedge.example.chat.ChatSettings
import io.bigmoeonedge.example.chat.data.ChatDb
import io.bigmoeonedge.example.chat.data.ConversationEntity
import java.io.File

/**
 * Hosts the engine for the chat in its own Android process (`:engine`). The `bmoe-cli` child
 * then lands in this process's memory cgroup instead of the UI's, which is what stops the kernel
 * from throttling the UI thread when the model fills memory. All job logic is in [EngineRunner];
 * this class is the Android side: foreground notification, wake lock, settings, battery.
 */
class EngineService : Service(), EngineHost {
    private lateinit var runner: EngineRunner
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var lastStartId = 0

    @Volatile private var lastText = "Starting…"

    private val wakeRefresh = object : Runnable {
        override fun run() {
            wake?.takeIf { it.isHeld }?.acquire(WAKE_MS)
            main.postDelayed(this, WAKE_REFRESH_MS)
        }
    }

    private val batteryKick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = runner.kick()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // A previous engine process may have died with its child still alive.
        ProcessEngineBackend.killOrphan(File(filesDir, "engine.pid"))
        runner = EngineRunner(ChatDb.get(this), this)
        runner.start()
        registerReceiver(
            batteryKick,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_BATTERY_OKAY)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Required within seconds of startForegroundService, whatever the action turns out to be.
        enterForeground(lastText)
        when (intent?.action) {
            ACTION_KICK -> runner.kick()
            ACTION_CANCEL -> runner.cancel(intent.getLongExtra(EXTRA_MESSAGE_ID, -1))
            ACTION_STOP_ACTIVE -> runner.cancelActive()
            ACTION_UNLOAD -> runner.unload()
            ACTION_SUSPEND -> runner.suspend()
            ACTION_RESUME -> runner.resume()
            else -> runner.kick()
        }
        // Sticky: if the system kills this process mid-reply it brings the service back, and recovery
        // (onCreate) re-queues what was running.
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryKick) }
        main.removeCallbacksAndMessages(null)
        runner.shutdown()
        holdWake(false)
        super.onDestroy()
    }

    // ── EngineHost ──

    override suspend fun jobConfig(conv: ConversationEntity): JobConfig {
        // The UI process may have saved settings since this process first read the files.
        ChatSettings.refreshFromDisk(this)
        val chat = ChatSettings.load(this)
        val base = AppSettings.load(this)
        val model = File(conv.modelPath)
        val s = EngineConfig.resolve(base, chat, model.length())
        val cli = ModelManager.cliPath(this)
        val argv = EngineConfig.argv(s, chat, cli, conv.modelPath)
        val native = applicationInfo.nativeLibraryDir
        val env = HashMap<String, String>()
        env["LD_LIBRARY_PATH"] = "$native:/system/lib64:/vendor/lib64"
        env["ADSP_LIBRARY_PATH"] = native
        if (argv.contains("--prefill-device")) {
            env["GGML_HEXAGON_OPPOLL"] = "1"
            env["GGML_HEXAGON_HOSTBUF"] = "1"
        }
        val fake = BuildConfig.DEBUG && chat.fakeEngine
        return JobConfig(
            argv = argv,
            sig = (if (fake) "fake|" else "") + EngineConfig.signature(s, chat, conv.modelPath),
            env = env,
            workDir = model.parentFile,
            // The reply and the history share the context; leave room for the history.
            nPredict = minOf(chat.nPredict, s.sessionCtx / 2).coerceAtLeast(64),
            ctx = s.sessionCtx,
            fake = fake,
            modelName = model.nameWithoutExtension.take(40),
        )
    }

    override fun createBackend(cfg: JobConfig): EngineBackend =
        if (cfg.fake) FakeEngineBackend() else ProcessEngineBackend(File(filesDir, "engine.pid"))

    override suspend fun clearOtherEngines(selfPid: Int) {
        var others = ProcessEngineBackend.otherCliPids(selfPid)
        if (others.isEmpty()) return
        // The lab screen's service is in the main process; ask it to close its session properly.
        runCatching {
            startService(Intent(this, RunService::class.java).setAction(RunService.ACTION_SHUTDOWN))
        }
        val deadline = System.currentTimeMillis() + OTHER_ENGINE_WAIT_MS
        while (others.isNotEmpty() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(250)
            others = ProcessEngineBackend.otherCliPids(selfPid)
        }
        // Same uid, so we may. Better than two models fighting for the same memory.
        others.forEach { android.os.Process.killProcess(it) }
    }

    override fun foregroundText(text: String) {
        lastText = text
        // The system drops notification updates that come too fast; keep the newest text.
        main.removeCallbacks(postText)
        val wait = NOTIF_MIN_GAP_MS - (System.currentTimeMillis() - lastPosted)
        if (wait <= 0) main.post(postText) else main.postDelayed(postText, wait)
    }

    private var lastPosted = 0L
    private val postText = Runnable {
        lastPosted = System.currentTimeMillis()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(lastText))
    }

    override fun holdWake(on: Boolean) {
        main.post {
            if (on) {
                if (wake?.isHeld != true) {
                    wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bmoechat:engine")
                        .apply { setReferenceCounted(false); acquire(WAKE_MS) }
                    main.postDelayed(wakeRefresh, WAKE_REFRESH_MS)
                }
            } else {
                main.removeCallbacks(wakeRefresh)
                wake?.takeIf { it.isHeld }?.release()
                wake = null
            }
        }
    }

    override fun pauseReason(): String? {
        ChatSettings.refreshFromDisk(this)
        if (!ChatSettings.load(this).pauseOnLowBattery) return null
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        return if (pct < LOW_BATTERY_PCT && !plugged) "Paused — low battery" else null
    }

    override fun thermalStatus(): Int =
        runCatching { (getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus }.getOrDefault(0)

    override fun keepLoadedMinutes(): Int {
        ChatSettings.refreshFromDisk(this)
        return ChatSettings.load(this).keepLoadedMinutes
    }

    override fun replyFinished(messageId: Long, conversationId: Long, status: String) {
        // Reply notifications arrive with the notification work; nothing to do for the queue itself.
    }

    override fun idle(modelLoaded: Boolean) {
        if (modelLoaded) return
        // stopSelf(startId) is a no-op when a newer start command has arrived since.
        val id = lastStartId
        main.post {
            if (id == lastStartId) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(id)
            }
        }
    }

    // ── notification ──

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ENGINE, "Model activity", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun enterForeground(text: String) {
        val n = build(text)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun build(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP_ACTIVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ENGINE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_KICK = "io.bigmoeonedge.example.chat.KICK"
        const val ACTION_CANCEL = "io.bigmoeonedge.example.chat.CANCEL"
        const val ACTION_STOP_ACTIVE = "io.bigmoeonedge.example.chat.STOP_ACTIVE"
        const val ACTION_UNLOAD = "io.bigmoeonedge.example.chat.UNLOAD"
        const val ACTION_SUSPEND = "io.bigmoeonedge.example.chat.SUSPEND"
        const val ACTION_RESUME = "io.bigmoeonedge.example.chat.RESUME"
        const val EXTRA_MESSAGE_ID = "messageId"

        const val CHANNEL_ENGINE = "engine"
        const val NOTIF_ID = 1001

        // A reply at 0.5 tok/s can run most of an hour; the upstream service's 30 minutes would cut it.
        private const val WAKE_MS = 60 * 60 * 1000L
        private const val WAKE_REFRESH_MS = 10 * 60 * 1000L
        private const val NOTIF_MIN_GAP_MS = 1500L
        private const val OTHER_ENGINE_WAIT_MS = 10_000L
        private const val LOW_BATTERY_PCT = 15
    }
}
