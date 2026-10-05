package io.bigmoeonedge.example.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.bigmoeonedge.example.BuildConfig
import io.bigmoeonedge.example.Hint
import io.bigmoeonedge.example.IntSetting
import io.bigmoeonedge.example.LabeledDropdown
import io.bigmoeonedge.example.SwitchRow
import io.bigmoeonedge.example.chat.ChatSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    current: ChatSettings,
    onChange: (ChatSettings) -> Unit,
    onEngineSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IntSetting("Reply length (tokens)", ChatSettings.NPREDICT_CHOICES, current.nPredict) {
                onChange(current.copy(nPredict = it))
            }
            IntSetting(
                "Context (tokens)", ChatSettings.CTX_CHOICES, current.sessionCtx,
                format = { if (it == ChatSettings.CTX_AUTO) "Automatic" else it.toString() },
            ) { onChange(current.copy(sessionCtx = it)) }
            Hint(
                "The conversation the model can read, replies included. A bigger context takes memory from the " +
                    "expert cache. Automatic is 4096, or 2048 for a model too big for the background memory budget.",
            )

            val temps = floatArrayOf(0f, 0.2f, 0.4f, 0.7f, 1.0f, 1.3f)
            LabeledDropdown(
                "Temperature", temps.map { if (it == 0f) "0 (greedy)" else it.toString() },
                temps.indexOfFirst { it == current.temperature }.let { if (it < 0) 3 else it },
            ) { onChange(current.copy(temperature = temps[it])) }
            val tops = floatArrayOf(0.5f, 0.8f, 0.9f, 0.95f, 1.0f)
            LabeledDropdown(
                "Top-p", tops.map { it.toString() }, tops.indexOfFirst { it == current.topP }.let { if (it < 0) 2 else it },
            ) { onChange(current.copy(topP = tops[it])) }
            IntSetting("Top-k", intArrayOf(10, 20, 40, 80, 100), current.topK) { onChange(current.copy(topK = it)) }
            Hint("Sampling changes the model session, so a change reloads the model on the next reply.")

            IntSetting(
                "Keep the model loaded", ChatSettings.KEEP_CHOICES, current.keepLoadedMinutes,
                format = {
                    when (it) {
                        0 -> "Unload right away"
                        -1 -> "Never unload"
                        else -> "$it min"
                    }
                },
            ) { onChange(current.copy(keepLoadedMinutes = it)) }
            SwitchRow(
                "Pause on low battery", "Queued replies wait below 15% unless the phone is charging.",
                current.pauseOnLowBattery,
            ) { onChange(current.copy(pauseOnLowBattery = it)) }
            if (BuildConfig.DEBUG) {
                SwitchRow(
                    "Fake engine", "Debug builds only: answers with canned text, for the emulator.",
                    current.fakeEngine,
                ) { onChange(current.copy(fakeEngine = it)) }
            }
            OutlinedButton(onClick = onEngineSettings, modifier = Modifier.fillMaxWidth()) { Text("Engine settings") }
        }
    }
}
