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
import io.bigmoeonedge.example.chat.AppForeground
import io.bigmoeonedge.example.chat.ChatSettings
import io.bigmoeonedge.example.chat.data.ChatDb
import io.bigmoeonedge.example.chat.data.ConversationEntity
import io.bigmoeonedge.example.chat.data.ChatRepository
import io.bigmoeonedge.example.chat.data.MessageStatus
import io.bigmoeonedge.example.chat.notify.ReplyNotifier
import io.bigmoeonedge.example.chat.notify.isThreadVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.bigmoeonedge.example.chat.data.ScanRunEntity
import io.bigmoeonedge.example.chat.data.ScanRunStatus
import io.bigmoeonedge.example.scan.AndroidSampler
import io.bigmoeonedge.example.scan.DeviceSampler
import io.bigmoeonedge.example.scan.FakeSampler
import io.bigmoeonedge.example.scan.FakeThermal
import io.bigmoeonedge.example.scan.ScanTiming
import io.bigmoeonedge.example.scan.SettingsJson
import java.io.File

/**
 * Hosts the engine for the chat in its own Android process (`:engine`). The `bmoe-cli` child
 * then lands in this process's memory cgroup instead of the UI's, which is what stops the kernel
 * from throttling the UI thread when the model fills memory. All job logic is in [EngineRunner];
 * this class is the Android side: foreground notification, wake lock, settings, battery.
 */
open class EngineService : Service(), EngineHost {
    /** True in the main-process subclass: it serves the models set to foreground mode, only while the app is in front. */
    protected open val foregroundMode: Boolean = false

    /**
     * One pid file per service. The two services start together, and a file they shared let the
     * newer one take the other's child for an orphan of a dead process and kill it at once.
     */
    private val pidFile: File get() = File(filesDir, if (foregroundMode) "engine-fg.pid" else "engine.pid")

    // Two services of one app: the same id would make one's notification replace and remove the other's.
    private val notifId: Int get() = if (foregroundMode) NOTIF_ID_FOREGROUND else NOTIF_ID

    private lateinit var runner: EngineRunner
    private lateinit var notifier: ReplyNotifier
    private lateinit var repo: ChatRepository
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    private val appListener = AppForeground.Listener { front ->
        if (front) runner.freeze(false) else runner.freeze(true)
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // A previous engine process may have died with its child still alive.
        ProcessEngineBackend.killOrphan(pidFile)
        val db = ChatDb.get(this)
        notifier = ReplyNotifier(this, db).also { it.ensureChannel() }
        runner = EngineRunner(db, this)
        repo = ChatRepository(db, { runner.kick() })
        runner.start()
        if (foregroundMode) AppForeground.add(appListener)
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
            ACTION_END_THINKING -> runner.endThinking(intent.getLongExtra(EXTRA_MESSAGE_ID, -1))
            ACTION_STOP_ACTIVE -> runner.cancelActive()
            ACTION_REPLY -> {
                val conv = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1)
                val text = ReplyNotifier.replyText(intent)?.toString()?.trim().orEmpty()
                if (conv >= 0 && text.isNotEmpty()) {
                    io.launch {
                        repo.send(conv, text)
                        // Without a new post the inline-reply spinner never stops.
                        notifier.post(conv, queuedNote = true)
                    }
                }
            }
            ACTION_MARK_READ -> {
                val conv = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1)
                notifier.cancel(conv)
                io.launch { repo.markRead(conv) }
            }
            ACTION_RETRY -> {
                val conv = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1)
                notifier.cancel(conv)
                io.launch { repo.retry(intent.getLongExtra(EXTRA_MESSAGE_ID, -1)) }
            }
            ACTION_SCAN_STOP -> runner.stopScan()
            ACTION_UNLOAD -> runner.unload()
            ACTION_SUSPEND -> runner.suspend()
            ACTION_RESUME -> runner.resume()
            else -> {}
        }
        // Every start ends in a kick: with nothing queued the loop finds it idle and lets the
        // service stop, which an action like Mark read would otherwise never do.
        runner.kick()
        // Sticky: if the system kills this process mid-reply it brings the service back, and recovery
        // (onCreate) re-queues what was running.
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryKick) }
        if (foregroundMode) AppForeground.remove(appListener)
        slot?.let { runCatching { it.close() } }
        main.removeCallbacksAndMessages(null)
        runner.shutdown()
        io.coroutineContext[kotlinx.coroutines.Job]?.cancel()
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
        // A profile saved from a scan replaces the global engine settings for this model.
        val profile = ChatDb.get(this).scan().profile(conv.modelPath)
            ?.let { runCatching { SettingsJson.fromJson(it.settingsJson) }.getOrNull() }
        val s = EngineConfig.resolve(base, chat, model.length(), override = profile, foreground = foregroundMode)
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

    // ── foreground mode ──

    override fun handles(modelPath: String): Boolean {
        ChatSettings.refreshFromDisk(this)
        return ChatSettings.isForeground(this, modelPath) == foregroundMode
    }

    override val primary: Boolean get() = !foregroundMode

    private var slot: SlotLock? = null

    /** An exclusive lock on a file in filesDir: held by whichever process has a job, gone if it dies. */
    private class SlotLock(private val file: java.io.RandomAccessFile, private val lock: java.nio.channels.FileLock) : AutoCloseable {
        override fun close() {
            runCatching { lock.release() }
            runCatching { file.close() }
        }
    }

    override fun tryEngineSlot(): AutoCloseable? {
        val f = runCatching { java.io.RandomAccessFile(File(filesDir, "engine.slot"), "rw") }.getOrNull() ?: return AutoCloseable {}
        val l = try {
            f.channel.tryLock()
        } catch (_: java.nio.channels.OverlappingFileLockException) {
            null // this process holds it already (the other service class shares the file in one JVM only in tests)
        } catch (_: Throwable) {
            runCatching { f.close() }
            return AutoCloseable {} // locking unavailable: do not block the queue on it
        }
        if (l == null) {
            runCatching { f.close() }
            return null
        }
        return SlotLock(f, l).also { slot = it }
    }

    // ── the scan ──

    override fun isFake(): Boolean {
        ChatSettings.refreshFromDisk(this)
        return BuildConfig.DEBUG && ChatSettings.load(this).fakeEngine
    }

    private fun fastFake(): Boolean = isFake() && ChatSettings.load(this).fakeFast

    override fun scanTiming(): ScanTiming {
        if (!fastFake()) return ScanTiming()
        FakeThermal.timeScale = 40.0
        FakeThermal.speedScale = 40.0
        return ScanTiming(
            refIdleMs = 2_000, refExtendMs = 4_000, sampleMs = 200, gatePollMs = 300, gateMaxMs = 15_000, sustainedMs = 6_000,
        )
    }

    override fun deviceSampler(): DeviceSampler = if (isFake()) FakeSampler() else AndroidSampler(this)

    override suspend fun scanCurrentSettings(modelPath: String): AppSettings {
        ChatSettings.refreshFromDisk(this)
        return EngineConfig.resolve(AppSettings.load(this), ChatSettings.load(this), File(modelPath).length())
    }

    override fun scanJobConfig(modelPath: String, settings: AppSettings, csv: File?): JobConfig {
        val chat = ChatSettings.load(this)
        val argv = settings.sessionArgv(ModelManager.cliPath(this), modelPath, csv?.absolutePath)
        val native = applicationInfo.nativeLibraryDir
        val env = HashMap<String, String>()
        env["LD_LIBRARY_PATH"] = "$native:/system/lib64:/vendor/lib64"
        env["ADSP_LIBRARY_PATH"] = native
        if (argv.contains("--prefill-device")) {
            env["GGML_HEXAGON_OPPOLL"] = "1"
            env["GGML_HEXAGON_HOSTBUF"] = "1"
        }
        val model = File(modelPath)
        return JobConfig(
            argv, (if (isFake()) "fake|" else "") + settings.sessionSignature(modelPath), env, model.parentFile,
            settings.nPredict, settings.sessionCtx, BuildConfig.DEBUG && chat.fakeEngine, model.nameWithoutExtension.take(40),
        )
    }

    override fun scanCsv(runId: Long, cellId: Long): File? =
        File(filesDir, "scan/$runId").apply { mkdirs() }.let { File(it, "$cellId.csv") }

    override fun scanPrompt(): String = assets.open("scan_prompt.txt").bufferedReader().use { it.readText() }

    override fun ramBytes(): Long {
        val mi = android.app.ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        return mi.totalMem
    }

    override fun cpusetOf(pid: Int): Pair<String, Int> {
        if (pid <= 0) return "" to 0
        val cpuset = runCatching {
            File("/proc/$pid/cgroup").readLines().firstOrNull { ":cpuset:" in it }?.substringAfter(":cpuset:") ?: ""
        }.getOrDefault("")
        val list = runCatching {
            File("/proc/$pid/status").readLines().first { it.startsWith("Cpus_allowed_list:") }.substringAfter(":").trim()
        }.getOrDefault("")
        // "0-5" or "0-3,6" -> a count.
        val n = list.split(',').filter { it.isNotBlank() }.sumOf { part ->
            val ab = part.trim().split('-')
            if (ab.size == 2) (ab[1].toIntOrNull() ?: 0) - (ab[0].toIntOrNull() ?: 0) + 1 else 1
        }
        return cpuset to n
    }

    override fun scanPauseReason(): String? {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        return if (pct < SCAN_LOW_BATTERY_PCT && !plugged) "paused, low battery" else null
    }

    override fun scanFinished(run: ScanRunEntity) {
        notifier.postScanFinished(run)
    }

    override fun createBackend(cfg: JobConfig): EngineBackend =
        if (cfg.fake) FakeEngineBackend() else ProcessEngineBackend(pidFile)

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
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(notifId, build(lastText))
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
        if (foregroundMode && !AppForeground.isForeground) return EngineRunner.PAUSED_OUT_OF_APP
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

    override fun childMemoryMb(pid: Int): Int {
        if (pid <= 0) return -1
        return runCatching {
            val st = File("/proc/$pid/status").readLines()
            fun kb(key: String) = st.firstOrNull { it.startsWith(key) }?.filter { it.isDigit() }?.toLongOrNull() ?: 0L
            ((kb("RssAnon:") + kb("VmSwap:")) / 1024).toInt()
        }.getOrDefault(-1)
    }

    override fun keepLoadedMinutes(): Int {
        ChatSettings.refreshFromDisk(this)
        return ChatSettings.load(this).keepLoadedMinutes
    }

    override suspend fun replyFinished(messageId: Long, conversationId: Long, status: String) {
        if (status == MessageStatus.CANCELLED) return
        val visible = ChatDb.get(this).presence().get()?.visibleConversationId
        if (!isThreadVisible(this, visible, conversationId)) notifier.post(conversationId)
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
            ServiceCompat.startForeground(this, notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notifId, n)
        }
    }

    private fun build(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, javaClass).setAction(ACTION_STOP_ACTIVE),
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
        const val ACTION_END_THINKING = "io.bigmoeonedge.example.chat.END_THINKING"
        const val ACTION_STOP_ACTIVE = "io.bigmoeonedge.example.chat.STOP_ACTIVE"
        const val ACTION_UNLOAD = "io.bigmoeonedge.example.chat.UNLOAD"
        const val ACTION_SUSPEND = "io.bigmoeonedge.example.chat.SUSPEND"
        const val ACTION_RESUME = "io.bigmoeonedge.example.chat.RESUME"
        const val ACTION_REPLY = "io.bigmoeonedge.example.chat.REPLY"
        const val ACTION_MARK_READ = "io.bigmoeonedge.example.chat.MARK_READ"
        const val ACTION_SCAN_START = "io.bigmoeonedge.example.chat.SCAN_START"
        const val ACTION_SCAN_STOP = "io.bigmoeonedge.example.chat.SCAN_STOP"
        const val ACTION_RETRY = "io.bigmoeonedge.example.chat.RETRY"
        const val EXTRA_MESSAGE_ID = "messageId"
        const val EXTRA_CONVERSATION_ID = "conversationId"

        const val CHANNEL_ENGINE = "engine"
        const val NOTIF_ID = 1001
        const val NOTIF_ID_FOREGROUND = 1003

        // A reply at 0.5 tok/s can run most of an hour; the upstream service's 30 minutes would cut it.
        private const val WAKE_MS = 60 * 60 * 1000L
        private const val WAKE_REFRESH_MS = 10 * 60 * 1000L
        private const val NOTIF_MIN_GAP_MS = 2000L
        private const val OTHER_ENGINE_WAIT_MS = 10_000L
        private const val LOW_BATTERY_PCT = 15
        private const val SCAN_LOW_BATTERY_PCT = 20
    }
}
