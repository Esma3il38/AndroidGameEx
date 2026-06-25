package com.aether.memoryeditor.data.repository

import com.aether.memoryeditor.data.model.DataType
import com.aether.memoryeditor.data.model.MemoryRegion
import com.aether.memoryeditor.data.model.ScanResult
import com.aether.memoryeditor.data.model.SearchType
import com.aether.memoryeditor.native.MemoryScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScanRepository(private val scanner: MemoryScanner) {

    private val _results = MutableStateFlow<List<ScanResult>>(emptyList())
    val results: StateFlow<List<ScanResult>> = _results.asStateFlow()

    private val _savedAddresses = MutableStateFlow<List<ScanResult>>(emptyList())
    val savedAddresses: StateFlow<List<ScanResult>> = _savedAddresses.asStateFlow()

    val isScanning: StateFlow<Boolean> = scanner.isScanning
    val progress: StateFlow<Int> = scanner.progress
    val resultCount: StateFlow<Long> = scanner.resultCount

    fun startScan(
        value: String,
        dataType: DataType,
        searchType: SearchType,
        regions: Set<MemoryRegion>,
        alignedOnly: Boolean = false,
        xorKey: Int? = null
    ) {
        scanner.startScan(value, dataType, searchType, regions, alignedOnly, xorKey)
    }

    fun refineScan(
        searchType: SearchType,
        value: String? = null,
        dataType: DataType = DataType.AUTO
    ) {
        scanner.refineScan(searchType, value, dataType)
    }

    fun loadResults(offset: Long = 0, limit: Long = 1000) {
        _results.value = scanner.getResults(offset, limit)
    }

    fun clearResults() {
        scanner.clearResults()
        _results.value = emptyList()
    }

    fun cancelScan() {
        scanner.cancelScan()
    }

    fun modifyValue(address: Long, value: ByteArray, type: DataType): Boolean {
        return scanner.modifyValue(address, value, type)
    }

    fun freezeValue(address: Long, value: ByteArray, type: DataType) {
        scanner.freezeValue(address, value, type)
    }

    fun unfreezeValue(address: Long) {
        scanner.unfreezeValue(address)
    }

    fun unfreezeAll() {
        scanner.unfreezeAll()
    }

    fun saveAddress(result: ScanResult) {
        val current = _savedAddresses.value.toMutableList()
        if (!current.any { it.address == result.address }) {
            current.add(result)
            _savedAddresses.value = current
        }
    }

    fun removeSavedAddress(address: Long) {
        _savedAddresses.value = _savedAddresses.value.filter { it.address != address }
    }

    fun updateSavedAddressDescription(address: Long, description: String) {
        _savedAddresses.value = _savedAddresses.value.map {
            if (it.address == address) it.copy(description = description) else it
        }
    }

    fun getSavedAddressesByCategory(category: String): List<ScanResult> {
        return _savedAddresses.value.filter { it.description.contains(category, ignoreCase = true) }
    }
}
