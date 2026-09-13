package com.foxislam.androidagent

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import com.foxislam.androidagent.agent.Approvals
import com.foxislam.androidagent.agent.Reasoning

/**
 * The settings the screens read, held as Compose state and written through to [SettingsStore]
 * as they change. The screens need their own copies to recompose from; the store is the
 * durable one
 */
@Stable
class SettingsState(private val store: SettingsStore) {
    private val _apiUrl = mutableStateOf(store.apiUrl)
    private val _apiKey = mutableStateOf(store.apiKey)
    private val _modelsRaw = mutableStateOf(store.modelsRaw)
    private val _selectedModel = mutableStateOf(store.selectedModel)
    private val _reasoning = mutableStateOf(store.reasoning)
    private val _approvalMode = mutableStateOf(store.approvalMode)
    private val _contextLimit = mutableStateOf(store.contextLimit)
    private val _fastModel = mutableStateOf(store.fastModel)

    val apiUrl: String get() = _apiUrl.value
    val apiKey: String get() = _apiKey.value
    val modelsRaw: String get() = _modelsRaw.value
    val selectedModel: String get() = _selectedModel.value
    val reasoning: Reasoning get() = _reasoning.value
    val approvalMode: Approvals.Mode get() = _approvalMode.value
    val contextLimit: Int get() = _contextLimit.value
    val fastModel: String get() = _fastModel.value
    /** Derived from [modelsRaw] and not from the store, so an edited list reaches the picker */
    val models: List<String> get() = SettingsStore.modelsFrom(modelsRaw)

    fun setApiUrl(value: String) { store.apiUrl = value; _apiUrl.value = value }

    fun resetApiUrl() { store.resetApiUrl(); _apiUrl.value = store.apiUrl }

    fun setApiKey(value: String) { store.apiKey = value; _apiKey.value = value }

    fun setModels(value: String) {
        store.modelsRaw = value
        _modelsRaw.value = value
        // The selection may no longer be in the list
        _selectedModel.value = store.selectedModel
    }

    fun setSelectedModel(value: String) { store.selectedModel = value; _selectedModel.value = value }

    fun setReasoning(value: Reasoning) { store.reasoning = value; _reasoning.value = value }

    fun setApprovalMode(value: Approvals.Mode) { store.approvalMode = value; _approvalMode.value = value }

    fun setContextLimit(value: Int) { store.contextLimit = value; _contextLimit.value = value }

    fun setFastModel(value: String) { store.fastModel = value; _fastModel.value = value }

    /** Re-reads the store after an import, which writes every one of these */
    fun reload() {
        _apiUrl.value = store.apiUrl
        _apiKey.value = store.apiKey
        _modelsRaw.value = store.modelsRaw
        _selectedModel.value = store.selectedModel
        _reasoning.value = store.reasoning
        _approvalMode.value = store.approvalMode
        _contextLimit.value = store.contextLimit
        _fastModel.value = store.fastModel
    }
}
