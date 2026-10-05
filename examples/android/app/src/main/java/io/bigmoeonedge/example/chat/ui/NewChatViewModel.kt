package io.bigmoeonedge.example.chat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.bigmoeonedge.example.ModelManager
import io.bigmoeonedge.example.chat.ChatServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class NewChatViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repo = ChatServices.repository(app)

    data class State(
        val scanning: Boolean = true,
        val models: List<File> = emptyList(),
        val selected: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        refresh()
    }

    /** Header probing reads files: off the main thread. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(scanning = true)
            val models = withContext(Dispatchers.IO) { ModelManager.listMoeModels(app) }
            val last = repo.lastUsedModel()
            val keep = _state.value.selected
            val pick = listOfNotNull(keep, last).firstOrNull { p -> models.any { it.absolutePath == p } }
                ?: models.firstOrNull()?.absolutePath
            _state.value = State(scanning = false, models = models, selected = pick)
        }
    }

    fun select(path: String) {
        _state.value = _state.value.copy(selected = path)
    }

    fun create(system: String, thinking: Boolean, onCreated: (Long) -> Unit) {
        val model = _state.value.selected ?: return
        viewModelScope.launch { onCreated(repo.createConversation(model, system.trim(), thinking)) }
    }
}
