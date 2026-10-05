package io.bigmoeonedge.example.scan

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bigmoeonedge.example.Hint
import io.bigmoeonedge.example.chat.ChatFormat
import io.bigmoeonedge.example.chat.data.CellKind
import io.bigmoeonedge.example.chat.data.CellStatus
import io.bigmoeonedge.example.chat.data.ScanCellEntity
import io.bigmoeonedge.example.chat.data.ScanRunStatus
import kotlinx.coroutines.launch

/** What is questionable about a cell, in a few words. Empty when nothing is. */
fun cellWarnings(c: ScanCellEntity): List<String> = buildList {
    if (c.status == CellStatus.FAILED) add("failed")
    if (c.status == CellStatus.THERMAL_ABORT) add("stopped: too hot")
    if (c.gateGaveUp) add("did not fully cool")
    if (c.throttledFrac > ScanPlanner.CONTAMINATED_FRAC) add("%.0f%% throttled".format(c.throttledFrac * 100))
    if (c.ioMode.startsWith("buffered")) add("buffered I/O")
    if (c.charging) add("charging")
    if (c.screenOn) add("screen on")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultsScreen(vm: ScanResultsViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val run by vm.run.collectAsStateWithLifecycle()
    val cells by vm.cells.collectAsStateWithLifecycle()
    val profile by vm.profileSource.collectAsStateWithLifecycle()
    val r = run

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(r?.let { ChatFormat.modelShortName(it.modelPath) } ?: "Scan") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (r == null) return@Scaffold
        val burst = cells.filter { it.kind == CellKind.BURST && it.stage != "H" && it.status != CellStatus.PENDING }
            .sortedByDescending { it.decodeMedianTokS }
        val sustained = cells.filter { it.kind == CellKind.SUSTAINED }.sortedByDescending { it.sustainedTokS }
        val confirm = cells.filter { it.kind == CellKind.CONFIRM }
        val lossy = cells.filter { it.stage == "H" || (it.stage == "CUR" && it.lossy) }
        val base = cells.firstOrNull { it.stage == "BASE" && it.status == CellStatus.DONE }

        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (r.startedWarm) {
                Text("The phone was already warm when this scan started, so every figure is lower than it could be.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
            when (r.status) {
                ScanRunStatus.RUNNING -> Text(r.detail.ifEmpty { "Running…" }, fontSize = 14.sp)
                ScanRunStatus.STOPPED, ScanRunStatus.FAILED -> {
                    Text(r.verdict.ifEmpty { "Stopped." }, fontSize = 14.sp)
                    Button(onClick = vm::resume) { Text("Resume") }
                }
                else -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Recommended: ${r.recommendedLabel.ifEmpty { "—" }}", fontSize = 16.sp)
                            Text(r.verdict, fontSize = 13.sp)
                            Row2(
                                { Button(onClick = vm::useRecommended, enabled = r.recommendedJson.isNotEmpty()) { Text("Use for chats with this model") } },
                                { OutlinedButton(onClick = vm::resetProfile, enabled = profile != null) { Text("Reset") } },
                            )
                            if (profile != null) Hint("Chats with this model use ${if (profile!!.startsWith("SCAN_CELL")) "a chosen cell" else "a scan result"} (run ${profile!!.substringAfter(':')}).")
                        }
                    }
                }
            }
            OutlinedButton(onClick = { scope.launch { share(ctx, vm.exportCsv()) } }) { Text("Export CSV") }

            Section("Burst (cold start, 256 tokens)")
            for (c in burst) CellRow(c, onUse = null)
            if (sustained.isNotEmpty()) {
                Section("Sustained (${r.sustainedMinutes} min; the recommendation is chosen here)")
                for (c in sustained) CellRow(c, onUse = null, sustainedView = true)
            }
            if (confirm.isNotEmpty()) {
                Section("Confirmation (alternating)")
                for (c in confirm) CellRow(c, onUse = null)
            }
            if (lossy.isNotEmpty()) {
                Section("Lossy settings (change the answer; never applied automatically)")
                Hint("Each row shows how much the text differs from the lossless baseline.")
                for (c in lossy) CellRow(c, onUse = { vm.useCell(c) }, compareTo = base?.outputText)
            }
        }
    }
}

@Composable
private fun Row2(a: @Composable () -> Unit, b: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        a()
        b()
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun CellRow(c: ScanCellEntity, onUse: (() -> Unit)?, sustainedView: Boolean = false, compareTo: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        val head = if (sustainedView) fmt(c.sustainedTokS) else fmt(c.decodeMedianTokS)
        Text("${c.label}${if (c.attempt > 0) " (re-run)" else ""}  —  $head tok/s", fontSize = 14.sp)
        val parts = mutableListOf<String>()
        if (sustainedView) parts += "first minute ${fmt(c.firstMinuteTokS)}"
        else if (c.coolMedianTokS > 0) parts += "cool ${fmt(c.coolMedianTokS)}"
        if (c.timeToThrottleS >= 0) parts += "throttled after ${c.timeToThrottleS.toInt()} s"
        if (c.prefillTps > 0) parts += "prefill ${fmt(c.prefillTps)}"
        if (c.loadS >= 0) parts += "load ${c.loadS.toInt()} s"
        if (c.cacheHitPct >= 0) parts += "hit ${c.cacheHitPct.toInt()}%"
        if (c.peakAnonMb > 0) parts += "%.1f GB".format(c.peakAnonMb / 1024.0)
        if (c.throttledFrac > 0) parts += "hot %.0f%%".format(c.throttledFrac * 100)
        if (compareTo != null && c.outputText.isNotEmpty()) {
            val d = OutputCompare.firstDivergence(compareTo, c.outputText)
            parts += "text %.0f%% alike".format(OutputCompare.similarity(compareTo, c.outputText) * 100) +
                (if (d >= 0) ", differs from character $d" else "")
        }
        Text(parts.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val w = cellWarnings(c)
        if (w.isNotEmpty()) Text("⚠ " + w.joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        if (onUse != null && c.status == CellStatus.DONE) TextButton(onClick = onUse) { Text("Use these settings") }
    }
}

private fun fmt(v: Double) = if (v <= 0) "—" else "%.2f".format(v)

private fun share(ctx: Context, uris: List<android.net.Uri>) {
    if (uris.isEmpty()) return
    val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/csv"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(Intent.createChooser(i, "Share scan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
