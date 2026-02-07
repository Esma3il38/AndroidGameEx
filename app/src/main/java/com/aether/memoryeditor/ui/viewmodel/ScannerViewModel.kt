package com.aether.memoryeditor.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aether.memoryeditor.data.model.DataType
import com.aether.memoryeditor.data.model.MemoryRegion
import com.aether.memoryeditor.data.model.ProcessInfo
import com.aether.memoryeditor.data.model.ScanResult
import com.aether.memoryeditor.data.model.SearchType
import com.aether.memoryeditor.data.repository.ProcessRepository
import com.aether.memoryeditor.data.repository.ScanRepository
import com.aether.memoryeditor.native.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScannerViewModel(
    private val scanRepository: ScanRepository,
    private val processRepository: ProcessRepository
) : ViewModel() {

    // Process selection
    val selectedProcess: StateFlow<ProcessInfo?> = processRepository.selectedProcess
    val availableProcesses: StateFlow<List<ProcessInfo>> = processRepository.processes
    val isLoadingProcesses: StateFlow<Boolean> = processRepository.isLoading

    // Scan state
    val isScanning: StateFlow<Boolean> = scanRepository.isScanning
    val scanProgress: StateFlow<Int> = scanRepository.progress
    val resultCount: StateFlow<Long> = scanRepository.resultCount

    private val _searchValue = MutableStateFlow("")
    val searchValue: StateFlow<String> = _searchValue.asStateFlow()

    private val _selectedDataType = MutableStateFlow(DataType.DWORD)
    val selectedDataType: StateFlow<DataType> = _selectedDataType.asStateFlow()

    private val _selectedSearchType = MutableStateFlow(SearchType.EXACT)
    val selectedSearchType: StateFlow<SearchType> = _selectedSearchType.asStateFlow()

    private val _selectedRegions = MutableStateFlow(setOf(
        MemoryRegion.C_HEAP,
        MemoryRegion.C_ALLOC,
        MemoryRegion.ANONYMOUS
    ))
    val selectedRegions: StateFlow<Set<MemoryRegion>> = _selectedRegions.asStateFlow()

    private val _alignedOnly = MutableStateFlow(false)
    val alignedOnly: StateFlow<Boolean> = _alignedOnly.asStateFlow()

    // Results
    private val _results = MutableStateFlow<List<ScanResult>>(emptyList())
    val results: StateFlow<List<ScanResult>> = _results.asStateFlow()

    val savedAddresses: StateFlow<List<ScanResult>> = scanRepository.savedAddresses

    // Edit dialog
    private val _showEditDialog = MutableStateFlow(false)
    val showEditDialog: StateFlow<Boolean> = _showEditDialog.asStateFlow()

    private val _editingResult = MutableStateFlow<ScanResult?>(null)
    val editingResult: StateFlow<ScanResult?> = _editingResult.asStateFlow()

    init {
        viewModelScope.launch {
            refreshProcesses()
        }
    }

    fun refreshProcesses() {
        viewModelScope.launch {
            processRepository.refreshProcesses()
        }
    }

    fun searchProcesses(query: String) {
        viewModelScope.launch {
            processRepository.searchProcesses(query)
        }
    }

    fun selectProcess(process: ProcessInfo) {
        processRepository.selectProcess(process)
        // Attach to the process
        NativeBridge().attachProcess(process.pid)
    }

    fun clearProcessSelection() {
        processRepository.clearSelection()
    }

    fun setSearchValue(value: String) {
        _searchValue.value = value
    }

    fun setDataType(type: DataType) {
        _selectedDataType.value = type
    }

    fun setSearchType(type: SearchType) {
        _selectedSearchType.value = type
    }

    fun toggleRegion(region: MemoryRegion) {
        val current = _selectedRegions.value.toMutableSet()
        if (region in current) {
            current.remove(region)
        } else {
            current.add(region)
        }
        _selectedRegions.value = current
    }

    fun setAlignedOnly(aligned: Boolean) {
        _alignedOnly.value = aligned
    }

    fun startScan() {
        if (_searchValue.value.isBlank()) return

        scanRepository.startScan(
            value = _searchValue.value,
            dataType = _selectedDataType.value,
            searchType = _selectedSearchType.value,
            regions = _selectedRegions.value,
            alignedOnly = _alignedOnly.value
        )

        // Start polling for results
        startResultsPolling()
    }

    fun refineScan() {
        scanRepository.refineScan(
            searchType = _selectedSearchType.value,
            value = _searchValue.value.takeIf { it.isNotBlank() },
            dataType = _selectedDataType.value
        )
        startResultsPolling()
    }

    fun cancelScan() {
        scanRepository.cancelScan()
    }

    fun clearResults() {
        scanRepository.clearResults()
        _results.value = emptyList()
    }

    fun loadMoreResults(offset: Long = 0, limit: Long = 1000) {
        scanRepository.loadResults(offset, limit)
        // _results is updated via Flow in init or observed from repository
        _results.value = scanRepository.results.value
    }

    fun modifyValue(address: Long, newValue: ByteArray, type: DataType): Boolean {
        return scanRepository.modifyValue(address, newValue, type)
    }

    fun freezeValue(address: Long, value: ByteArray, type: DataType) {
        scanRepository.freezeValue(address, value, type)
    }

    fun unfreezeValue(address: Long) {
        scanRepository.unfreezeValue(address)
    }

    fun unfreezeAll() {
        scanRepository.unfreezeAll()
    }

    fun saveResult(result: ScanResult) {
        scanRepository.saveAddress(result)
    }

    fun removeSavedAddress(address: Long) {
        scanRepository.removeSavedAddress(address)
    }

    fun showEditDialog(result: ScanResult) {
        _editingResult.value = result
        _showEditDialog.value = true
    }

    fun hideEditDialog() {
        _showEditDialog.value = false
        _editingResult.value = null
    }

    private fun startResultsPolling() {
        viewModelScope.launch {
            while (isScanning.value) {
                loadMoreResults()
                kotlinx.coroutines.delay(500)
            }
            // Final load when scan completes
            loadMoreResults()
        }
    }
}
