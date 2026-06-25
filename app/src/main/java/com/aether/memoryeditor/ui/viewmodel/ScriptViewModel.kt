package com.aether.memoryeditor.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aether.memoryeditor.data.repository.ScriptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScriptViewModel(
    private val scriptRepository: ScriptRepository
) : ViewModel() {

    val scripts: StateFlow<List<ScriptRepository.ScriptInfo>> = scriptRepository.scripts
    val isRunning: StateFlow<Boolean> = scriptRepository.isRunning
    val consoleOutput: StateFlow<List<String>> = scriptRepository.consoleOutput

    private val _selectedScript = MutableStateFlow<ScriptRepository.ScriptInfo?>(null)
    val selectedScript: StateFlow<ScriptRepository.ScriptInfo?> = _selectedScript.asStateFlow()

    private val _scriptContent = MutableStateFlow("")
    val scriptContent: StateFlow<String> = _scriptContent.asStateFlow()

    private val _scriptName = MutableStateFlow("")
    val scriptName: StateFlow<String> = _scriptName.asStateFlow()

    private val _showEditor = MutableStateFlow(false)
    val showEditor: StateFlow<Boolean> = _showEditor.asStateFlow()

    fun createNewScript() {
        _selectedScript.value = null
        _scriptName.value = "New Script"
        _scriptContent.value = DEFAULT_SCRIPT_TEMPLATE
        _showEditor.value = true
    }

    fun editScript(script: ScriptRepository.ScriptInfo) {
        _selectedScript.value = script
        _scriptName.value = script.name
        _scriptContent.value = script.content
        _showEditor.value = true
    }

    fun closeEditor() {
        _showEditor.value = false
        _selectedScript.value = null
    }

    fun setScriptName(name: String) {
        _scriptName.value = name
    }

    fun setScriptContent(content: String) {
        _scriptContent.value = content
    }

    fun saveScript() {
        viewModelScope.launch {
            val existing = _selectedScript.value
            val script = if (existing != null) {
                existing.copy(
                    name = _scriptName.value,
                    content = _scriptContent.value,
                    lastModified = System.currentTimeMillis()
                )
            } else {
                scriptRepository.createScript(_scriptName.value, _scriptContent.value)
                return@launch
            }

            scriptRepository.saveScript(script)
            closeEditor()
        }
    }

    fun deleteScript(scriptId: Long) {
        scriptRepository.deleteScript(scriptId)
    }

    fun importScript(uri: Uri, name: String) {
        viewModelScope.launch {
            scriptRepository.importScript(uri, name)
        }
    }

    fun exportScript(script: ScriptRepository.ScriptInfo, uri: Uri) {
        viewModelScope.launch {
            scriptRepository.exportScript(script, uri)
        }
    }

    fun runScript(script: ScriptRepository.ScriptInfo) {
        scriptRepository.runScript(script)
    }

    fun runCurrentScript() {
        val script = _selectedScript.value ?: return
        val updated = script.copy(
            name = _scriptName.value,
            content = _scriptContent.value
        )
        scriptRepository.runScript(updated)
    }

    fun stopScript() {
        scriptRepository.stopScript()
    }

    fun clearConsole() {
        scriptRepository.clearConsole()
    }

    companion object {
        private const val DEFAULT_SCRIPT_TEMPLATE = """-- Aether Lua Script
-- This is a sample script to get you started

-- Log a message
log("Script started!")

-- Function to read memory
function readDword(address)
    local data = readMemory(address, 4)
    if data then
        -- Convert bytes to DWORD
        local value = 0
        for i = 0, 3 do
            value = value + (string.byte(data, i + 1) << (i * 8))
        end
        return value
    end
    return nil
end

-- Function to write memory
function writeDword(address, value)
    local data = string.char(
        value & 0xFF,
        (value >> 8) & 0xFF,
        (value >> 16) & 0xFF,
        (value >> 24) & 0xFF
    )
    return writeMemory(address, data)
end

-- Main logic
log("Script completed!")
"""
    }
}
