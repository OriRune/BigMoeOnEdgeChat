package io.bigmoeonedge.example.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bigmoeonedge.example.Hint
import io.bigmoeonedge.example.LabeledDropdown
import io.bigmoeonedge.example.ModelManager
import io.bigmoeonedge.example.SwitchRow
import io.bigmoeonedge.example.chat.ChatFormat
import io.bigmoeonedge.example.requestSharedStorageAccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(vm: NewChatViewModel, onBack: () -> Unit, onCreated: (Long) -> Unit, onLab: () -> Unit) {
    val ctx = LocalContext.current
    val st by vm.state.collectAsStateWithLifecycle()
    var system by rememberSaveable { mutableStateOf("") }
    var thinking by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New chat") },
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
            when {
                st.scanning -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Looking for models…", fontSize = 14.sp)
                }
                st.models.isEmpty() -> {
                    Text(ModelManager.pushHint(), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Row {
                        TextButton(onClick = { requestSharedStorageAccess(ctx); vm.refresh() }) { Text("Refresh") }
                        TextButton(onClick = onLab) { Text("Get models in Engine lab") }
                    }
                }
                else -> {
                    val idx = st.models.indexOfFirst { it.absolutePath == st.selected }.coerceAtLeast(0)
                    LabeledDropdown(
                        label = "Model",
                        options = st.models.map { ChatFormat.modelShortName(it.absolutePath) },
                        selected = idx,
                        onSelect = { vm.select(st.models[it].absolutePath) },
                    )
                    Hint("The model loads when the first reply starts, which can take from under a minute to several for a large one.")
                }
            }
            OutlinedTextField(
                value = system, onValueChange = { system = it },
                label = { Text("System prompt (optional)") }, minLines = 2, maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(
                "Thinking", "Let the model reason before it answers. Slower, and not every model can turn it off.",
                thinking,
            ) { thinking = it }
            Button(
                onClick = { vm.create(system, thinking, onCreated) },
                enabled = st.selected != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start chat") }
        }
    }
}
