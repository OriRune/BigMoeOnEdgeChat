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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bigmoeonedge.example.Hint
import io.bigmoeonedge.example.IntSetting
import io.bigmoeonedge.example.SwitchRow
import io.bigmoeonedge.example.chat.ChatFormat
import io.bigmoeonedge.example.chat.data.CellStatus
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
    val cells by vm.liveCells.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val running = runs.firstOrNull { it.status == ScanRunStatus.RUNNING }

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
                val done = cells.count { it.status == CellStatus.DONE }
                val current = cells.lastOrNull { it.status == CellStatus.RUNNING }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Scan running · ${ChatFormat.modelShortName(running.modelPath)}", fontSize = 16.sp)
                        Text(running.detail.ifEmpty { "Starting…" }, fontSize = 13.sp)
                        current?.let { Text("Now: ${it.stage} · ${it.label}", fontSize = 13.sp) }
                        val st = engine
                        if (st != null) {
                            Hint("Thermal status ${st.thermalStatus} · $done cells finished")
                        }
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Hint("Lock the phone if you like: the scan keeps running with the screen off and posts a notification at the end.")
                        Button(onClick = vm::stop) { Text("Stop") }
                    }
                }
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
                    Column(Modifier.fillMaxWidth().clickable { onOpenRun(r.id) }.padding(vertical = 6.dp)) {
                        Text(ChatFormat.modelShortName(r.modelPath), fontSize = 15.sp)
                        Text(
                            when (r.status) {
                                ScanRunStatus.RUNNING -> "Running"
                                ScanRunStatus.DONE -> r.recommendedLabel.ifEmpty { "Done" } + if (r.confirmed) " · confirmed" else ""
                                ScanRunStatus.STOPPED -> "Stopped · tap to resume"
                                else -> "Failed"
                            },
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun fmtMin(m: Int) = if (m >= 90) "%.1f h".format(m / 60.0) else "$m min"
