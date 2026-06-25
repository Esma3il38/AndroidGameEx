package com.aether.memoryeditor.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aether.memoryeditor.data.model.DataType
import com.aether.memoryeditor.data.model.MemoryRegion
import com.aether.memoryeditor.data.repository.SettingsRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val theme: StateFlow<SettingsRepository.Theme> = settingsRepository.theme
    val defaultDataType: StateFlow<DataType> = settingsRepository.defaultDataType
    val defaultRegions: StateFlow<Set<MemoryRegion>> = settingsRepository.defaultRegions
    val threadCount: StateFlow<Int> = settingsRepository.threadCount
    val bufferSizeMb: StateFlow<Int> = settingsRepository.bufferSizeMb
    val autoFreeze: StateFlow<Boolean> = settingsRepository.autoFreeze
    val showHexValues: StateFlow<Boolean> = settingsRepository.showHexValues
    val stealthMode: StateFlow<Boolean> = settingsRepository.stealthMode
    val hideOverlay: StateFlow<Boolean> = settingsRepository.hideOverlay
    val overlayOpacity: StateFlow<Float> = settingsRepository.overlayOpacity
    val overlaySize: StateFlow<Float> = settingsRepository.overlaySize

    fun setTheme(theme: SettingsRepository.Theme) {
        viewModelScope.launch {
            settingsRepository.setTheme(theme)
        }
    }

    fun setDefaultDataType(type: DataType) {
        viewModelScope.launch {
            settingsRepository.setDefaultDataType(type)
        }
    }

    fun setDefaultRegions(regions: Set<MemoryRegion>) {
        viewModelScope.launch {
            settingsRepository.setDefaultRegions(regions)
        }
    }

    fun setThreadCount(count: Int) {
        viewModelScope.launch {
            settingsRepository.setThreadCount(count)
        }
    }

    fun setBufferSizeMb(size: Int) {
        viewModelScope.launch {
            settingsRepository.setBufferSizeMb(size)
        }
    }

    fun setAutoFreeze(autoFreeze: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoFreeze(autoFreeze)
        }
    }

    fun setShowHexValues(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowHexValues(show)
        }
    }

    fun setStealthMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setStealthMode(enabled)
        }
    }

    fun setHideOverlay(hide: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHideOverlay(hide)
        }
    }

    fun setOverlayOpacity(opacity: Float) {
        viewModelScope.launch {
            settingsRepository.setOverlayOpacity(opacity)
        }
    }

    fun setOverlaySize(size: Float) {
        viewModelScope.launch {
            settingsRepository.setOverlaySize(size)
        }
    }
}
