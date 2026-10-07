package io.bigmoeonedge.example.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import io.bigmoeonedge.example.Hint
import io.bigmoeonedge.example.IntSetting
import io.bigmoeonedge.example.SwitchRow
import io.bigmoeonedge.example.chat.ChatFormat
import io.bigmoeonedge.example.chat.data.CellLite
import io.bigmoeonedge.example.chat.data.ScanPhase
import io.bigmoeonedge.example.chat.data.ScanRunEntity
import io.bigmoeonedge.example.chat.data.ScanRunStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(vm: ScanViewModel, onBack: () -> Unit, onOpenRun: (Long) -> Unit) {
    val models by vm.models.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val sustained by vm.sustained.collectAsStateWithLifecycle()
    val lossy by vm.lossy.collectAsStateWithLifecycle()
    val minutes by vm.sustainedMinutes.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val running = runs.firstOrNull { it.status == ScanRunStatus.RUNNING }
    val outlines by vm.outlines.collectAsStateWithLifecycle()
    val currentCells by vm.currentCells.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (running != null) {
                LiveCard(
                    run = running, outline = outlines[running.id], current = currentCells[running.id],
                    thermal = engine?.thermalStatus, onStop = vm::stop, onRestart = { vm.restart(running.id) },
                )
            } else {
                Text("Find the fastest settings for a model", fontSize = 18.sp)
                Text(
                    "Runs the model under many engine settings, one at a time, waiting for the phone to cool before each, " +
                        "then re-checks the best on a long run once the phone is hot. Chats then use the result.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (models.isEmpty()) Hint("No MoE models found. Add one from the Engine lab.")
                for (f in models) {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.toggle(f.absolutePath) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = f.absolutePath in selected, onCheckedChange = { vm.toggle(f.absolutePath) })
                        Column {
                            Text(ChatFormat.modelShortName(f.absolutePath), fontSize = 15.sp)
                            Hint("%.1f GB".format(f.length() / 1073741824.0))
                        }
                    }
                }
                Hint("Several models run back to back, in the order listed.")
                SwitchRow("Include sustained test", "Chooses the recommendation on what a long reply gets once the phone is hot.", sustained) {
                    vm.sustained.value = it
                }
                if (sustained) {
                    IntSetting("Sustained test length (minutes)", intArrayOf(4, 8, 12, 20), minutes) { vm.sustainedMinutes.value = it }
                }
                SwitchRow(
                    "Include lossy settings",
                    "Also tries dropping or substituting experts and a lower top-k. These change the answer, so they are only " +
                        "measured, never applied on their own, and shown apart.",
                    lossy,
                ) { vm.lossy.value = it }
                vm.estimate()?.let { r ->
                    Text("About ${fmtMin(r.first)} to ${fmtMin(r.last)} in all.", fontSize = 14.sp)
                }
                Text(
                    "Start with the phone cool and unplugged, then lock it once the scan starts. Charging heats the phone " +
                        "and skews the results.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = vm::start, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Start scan") }
            }

            if (runs.isNotEmpty()) {
                HorizontalDivider()
                Text("Earlier scans", fontSize = 16.sp)
                for (r in runs) {
                    if (r.status == ScanRunStatus.RUNNING) continue
                    EarlierRow(r, outlines[r.id]) { onOpenRun(r.id) }
                }
            }
        }
    }
}

/** Seconds without a heartbeat after which a running scan is called quiet, and after which it is called stalled. */
private const val QUIET_MS = 30_000L
private const val STALLED_MS = 120_000L

@Composable
private fun LiveCard(
    run: ScanRunEntity, outline: Outline?, current: CellLite?, thermal: Int?, onStop: () -> Unit, onRestart: () -> Unit,
) {
    val now by rememberNow()
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val age = (now - run.heartbeatAt).coerceAtLeast(0)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Scan running · ${ChatFormat.modelShortName(run.modelPath)}", fontSize = 16.sp)
            if (outline != null) {
                val extra = if (outline.extraIfWin > 0) " (+${outline.extraIfWin} more if a setting wins)" else ""
                Text("Cell ${outline.currentOrdinal} of about ${outline.totalCells}$extra", fontSize = 14.sp)
                outline.currentStage?.let { st ->
                    Text(
                        "Stage ${st.stage} · ${st.title}" + (current?.label?.takeIf { it != st.title }?.let { " · $it" } ?: ""),
                        fontSize = 13.sp, color = sub,
                    )
                }
                LinearProgressIndicator(progress = { outline.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    outline.remainingMs?.let { "${ScanProgress.duration(it)} left" } ?: "Estimating the time left…",
                    fontSize = 13.sp,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            PhaseRow(run, now)

            // What it is doing now: the detail line without the repeated prefix and ETA.
            val doing = run.detail.substringBefore(" · ETA ").replace(Regex("^Scan · [^·]*· "), "").ifEmpty { "Starting…" }
            if (run.phase != ScanPhase.REFERENCE && run.phase != ScanPhase.GENERATING) Text(doing, fontSize = 12.sp, color = sub)
            if (run.phase == ScanPhase.GENERATING) GenerationBar(run, now)

            when {
                age >= STALLED_MS -> {
                    Text(
                        "No progress for ${ScanProgress.clock(age)}. The scan may have stopped.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onRestart) { Text("Restart scan") }
                }
                age >= QUIET_MS -> Hint("Last update ${ScanProgress.clock(age)} ago")
                else -> Text("Active · updated ${age / 1000} s ago", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            thermal?.let { Hint("Thermal status $it") }
            if (outline != null) {
                HorizontalDivider()
                Checklist(outline)
            }
            Hint("Lock the phone if you like: the scan keeps running with the screen off and posts a notification at the end.")
            OutlinedButton(onClick = onStop) { Text("Stop") }
        }
    }
}

private val PHASES = listOf(
    ScanPhase.COOLING to "Cooling", ScanPhase.LOADING to "Loading", ScanPhase.GENERATING to "Generating",
)

@Composable
private fun PhaseRow(run: ScanRunEntity, now: Long) {
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    if (run.phase == ScanPhase.REFERENCE) {
        Text("Letting the phone settle · ${ScanProgress.clock(now - run.phaseSince)}", fontSize = 13.sp)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((i, p) in PHASES.withIndex()) {
            val active = run.phase == p.first
            Text(
                (if (active) "● " else "○ ") + p.second + (if (active) " ${ScanProgress.clock(now - run.phaseSince)}" else ""),
                fontSize = 13.sp,
                color = if (active) MaterialTheme.colorScheme.primary else sub,
                fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Bold else null,
            )
            if (i < PHASES.lastIndex) Text("›", fontSize = 13.sp, color = sub)
        }
    }
}

@Composable
private fun GenerationBar(run: ScanRunEntity, now: Long) {
    val frac = when {
        run.liveTarget > 0 -> run.liveTokens.toFloat() / run.liveTarget
        run.phaseTotalMs > 0 -> (now - run.phaseSince).toFloat() / run.phaseTotalMs
        else -> 0f
    }.coerceIn(0f, 1f)
    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiary)
    Text(
        "%d tokens · %.2f tok/s now".format(run.liveTokens, run.liveTokS) +
            (if (run.liveTarget > 0) " · target ${run.liveTarget}" else " · sustained test, ${ScanProgress.clock(run.phaseTotalMs)} long"),
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Checklist(outline: Outline) {
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val active = outline.currentStage?.stage
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (st in outline.stages) {
            val mark = when {
                st.cells.isEmpty() -> "–"
                st.isDone -> "✓"
                st.stage == active -> "▶"
                else -> "○"
            }
            Text(
                "$mark  ${st.stage} · ${st.title}: ${ScanProgress.stageLine(st, active)}",
                fontSize = 12.sp, color = if (st.stage == active) MaterialTheme.colorScheme.onSurface else sub,
            )
        }
    }
}

@Composable
private fun EarlierRow(r: ScanRunEntity, o: Outline?, onClick: () -> Unit) {
    val fmt = remember { DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault()) }
    val whenText = Instant.ofEpochMilli(r.startedAt).atZone(ZoneId.systemDefault()).format(fmt)
    val took = r.finishedAt?.let { " · " + ScanProgress.duration((it - r.startedAt).coerceAtLeast(0)).removePrefix("about ") } ?: ""
    val cells = o?.let { " · ${it.measuredCells} cells" } ?: ""
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(ChatFormat.modelShortName(r.modelPath), fontSize = 15.sp)
        Text("$whenText$took$cells", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val outcome = when (r.status) {
            ScanRunStatus.DONE -> r.recommendedLabel.ifEmpty { "Done" } + if (r.confirmed) " · confirmed" else ""
            ScanRunStatus.STOPPED ->
                "Stopped" + (o?.let { " at cell ${it.currentOrdinal} of about ${it.totalCells}" } ?: "") + " · tap to resume"
            else -> "Failed" + (o?.let { " at cell ${it.currentOrdinal} of about ${it.totalCells}" } ?: "") +
                (r.verdict.takeIf { it.isNotEmpty() }?.let { ": ${it.take(90)}" } ?: "")
        }
        Text(
            outcome, fontSize = 13.sp,
            color = if (r.status == ScanRunStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The current time, refreshed every second, for "for 2:31" and "updated 4 s ago". */
@Composable
fun rememberNow(periodMs: Long = 1_000): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(periodMs)
    }
}

private fun fmtMin(m: Int) = if (m >= 90) "%.1f h".format(m / 60.0) else "$m min"
