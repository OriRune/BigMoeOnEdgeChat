package io.bigmoeonedge.example.scan

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.bigmoeonedge.example.ModelManager
import io.bigmoeonedge.example.chat.ChatServices
import io.bigmoeonedge.example.chat.data.ScanCellEntity
import io.bigmoeonedge.example.chat.data.ScanRunEntity
import io.bigmoeonedge.example.chat.data.ScanRunStatus
import io.bigmoeonedge.example.chat.data.EngineStatusEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ScanViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repo = ChatServices.scan(app)
    private val chatRepo = ChatServices.repository(app)

    val models = MutableStateFlow<List<File>>(emptyList())
    val selected = MutableStateFlow<Set<String>>(emptySet())
    val sustained = MutableStateFlow(true)
    val lossy = MutableStateFlow(false)
    val sustainedMinutes = MutableStateFlow(12)

    val ramBytes: Long = run {
        val mi = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.totalMem
    }

    val runs: StateFlow<List<ScanRunEntity>> = repo.observeRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val engine: StateFlow<EngineStatusEntity?> = chatRepo.observeEngineStatus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The cells of the scan that is running now, for the live view. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val liveCells: StateFlow<List<ScanCellEntity>> = runs
        .map { r -> r.firstOrNull { it.status == ScanRunStatus.RUNNING }?.id }
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeCells(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { models.value = withContext(Dispatchers.IO) { ModelManager.listMoeModels(app) } }
    }

    fun toggle(path: String) {
        selected.value = selected.value.let { if (path in it) it - path else it + path }
    }

    fun estimate(): IntRange? {
        val sel = models.value.filter { it.absolutePath in selected.value }
        if (sel.isEmpty()) return null
        var lo = 0
        var hi = 0
        for (f in sel) {
            val r = ScanPlanner.estimateMinutes(f.length(), ramBytes, lossy.value, sustained.value, sustainedMinutes.value)
            lo += r.first
            hi += r.last
        }
        return lo..hi
    }

    fun start() {
        val order = models.value.map { it.absolutePath }.filter { it in selected.value }
        if (order.isEmpty()) return
        viewModelScope.launch { repo.start(order, sustained.value, lossy.value, sustainedMinutes.value) }
    }

    fun stop() {
        viewModelScope.launch {
            repo.stopAll()
            ChatServices.client(app).scanStop()
        }
    }

    fun resume(id: Long) {
        viewModelScope.launch { repo.resume(id) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }
}

class ScanResultsViewModel(private val app: Application, val runId: Long) : AndroidViewModel(app) {
    private val repo = ChatServices.scan(app)

    val run: StateFlow<ScanRunEntity?> = repo.observeRun(runId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val cells: StateFlow<List<ScanCellEntity>> = repo.observeCells(runId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val profileSource: StateFlow<String?> = run.flatMapLatest { r ->
        if (r == null) flowOf(null) else repo.observeProfile(r.modelPath).map { it?.let { p -> "${p.source}:${p.runId}" } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun useRecommended() {
        viewModelScope.launch { repo.useRecommended(runId) }
    }

    fun useCell(cell: ScanCellEntity) {
        viewModelScope.launch { repo.useCell(runId, cell) }
    }

    fun resetProfile() {
        val r = run.value ?: return
        viewModelScope.launch { repo.resetProfile(r.modelPath) }
    }

    fun resume() {
        viewModelScope.launch { repo.resume(runId) }
    }

    /** Writes the cells and the samples to the cache and returns content:// URIs for the share sheet. */
    suspend fun exportCsv(): List<android.net.Uri> = withContext(Dispatchers.IO) {
        val r = run.value ?: return@withContext emptyList()
        val cs = cells.value
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        val a = File(dir, "scan-${r.id}-cells.csv").apply { writeText(ScanCsv.cells(r, cs)) }
        val b = File(dir, "scan-${r.id}-samples.csv").apply { writeText(ScanCsv.samples(cs)) }
        listOf(a, b).map { androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", it) }
    }
}
