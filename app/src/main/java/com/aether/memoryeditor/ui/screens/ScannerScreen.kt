package com.aether.memoryeditor.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aether.memoryeditor.data.model.DataType
import com.aether.memoryeditor.data.model.MemoryRegion
import com.aether.memoryeditor.data.model.SearchType
import com.aether.memoryeditor.ui.viewmodel.ScannerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScannerScreen(
    viewModel: ScannerViewModel,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()

    val selectedProcess by viewModel.selectedProcess.collectAsState()
    val searchValue by viewModel.searchValue.collectAsState()
    val selectedDataType by viewModel.selectedDataType.collectAsState()
    val selectedSearchType by viewModel.selectedSearchType.collectAsState()
    val selectedRegions by viewModel.selectedRegions.collectAsState()
    val alignedOnly by viewModel.alignedOnly.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val resultCount by viewModel.resultCount.collectAsState()

    var showProcessSelector by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory Scanner") },
                actions = {
                    if (isScanning) {
                        IconButton(onClick = { viewModel.cancelScan() }) {
                            Icon(Icons.Default.Clear, "Cancel")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Process Selection Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Target Process",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (selectedProcess != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedProcess!!.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "PID: ${selectedProcess!!.pid} | ${selectedProcess!!.formattedMemory}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { viewModel.clearProcessSelection() }) {
                                Icon(Icons.Default.Clear, "Clear")
                            }
                        }
                    } else {
                        Button(
                            onClick = { showProcessSelector = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Search, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select Process")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Search Configuration Card
            AnimatedVisibility(visible = selectedProcess != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Search Configuration",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Value Input
                        OutlinedTextField(
                            value = searchValue,
                            onValueChange = { viewModel.setSearchValue(it) },
                            label = { Text("Search Value") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Data Type Dropdown
                        DataTypeDropdown(
                            selectedType = selectedDataType,
                            onTypeSelected = { viewModel.setDataType(it) }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Search Type Dropdown
                        SearchTypeDropdown(
                            selectedType = selectedSearchType,
                            onTypeSelected = { viewModel.setSearchType(it) }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Memory Regions
                        Text(
                            text = "Memory Regions",
                            style = MaterialTheme.typography.labelMedium
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MemoryRegion.values().filter { it != MemoryRegion.NONE && it != MemoryRegion.ALL }.forEach { region ->
                                FilterChip(
                                    selected = region in selectedRegions,
                                    onClick = { viewModel.toggleRegion(region) },
                                    label = { Text(region.displayName) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Options
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = alignedOnly,
                                onCheckedChange = { viewModel.setAlignedOnly(it) }
                            )
                            Text("Aligned addresses only")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Progress
                        if (isScanning) {
                            LinearProgressIndicator(
                                progress = { scanProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Scanning: $scanProgress% | $resultCount results",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.startScan() },
                                enabled = !isScanning && searchValue.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("New Scan")
                            }

                            Button(
                                onClick = { viewModel.refineScan() },
                                enabled = !isScanning && resultCount > 0,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Next Scan")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showProcessSelector) {
        ProcessSelectorDialog(
            viewModel = viewModel,
            onDismiss = { showProcessSelector = false },
            onProcessSelected = { process ->
                viewModel.selectProcess(process)
                showProcessSelector = false
                scope.launch {
                    snackbarHostState.showSnackbar("Selected: ${process.displayName}")
                }
            }
        )
    }
}

@Composable
fun DataTypeDropdown(
    selectedType: DataType,
    onTypeSelected: (DataType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedType.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Data Type") },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.ArrowDropDown, null)
                }
            }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            DataType.values().forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.displayName) },
                    onClick = {
                        onTypeSelected(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun SearchTypeDropdown(
    selectedType: SearchType,
    onTypeSelected: (SearchType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedType.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Search Type") },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.ArrowDropDown, null)
                }
            }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            SearchType.values().forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.displayName) },
                    onClick = {
                        onTypeSelected(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessSelectorDialog(
    viewModel: ScannerViewModel,
    onDismiss: () -> Unit,
    onProcessSelected: (com.aether.memoryeditor.data.model.ProcessInfo) -> Unit
) {
    // Implementation would show a dialog with process list
    // For brevity, this is simplified
}
