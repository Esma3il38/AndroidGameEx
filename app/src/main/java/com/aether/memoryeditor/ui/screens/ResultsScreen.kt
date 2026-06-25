package com.aether.memoryeditor.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aether.memoryeditor.data.model.DataType
import com.aether.memoryeditor.data.model.ScanResult
import com.aether.memoryeditor.ui.viewmodel.ScannerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    viewModel: ScannerViewModel,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()

    val results by viewModel.results.collectAsState()
    val savedAddresses by viewModel.savedAddresses.collectAsState()
    val resultCount by viewModel.resultCount.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    var showEditDialog by remember { mutableStateOf(false) }
    var editingResult by remember { mutableStateOf<ScanResult?>(null) }
    var newValue by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Results ($resultCount)") },
                actions = {
                    IconButton(onClick = { viewModel.loadMoreResults() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    IconButton(onClick = { viewModel.clearResults() }) {
                        Icon(Icons.Default.Delete, "Clear")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.loadMoreResults(0, 1000) }
            ) {
                Icon(Icons.Default.Refresh, "Load More")
            }
        }
    ) { padding ->
        if (results.isEmpty() && !isScanning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No Results",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Start a scan to find memory values",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(results, key = { it.address }) { result ->
                    ResultItem(
                        result = result,
                        isSaved = savedAddresses.any { it.address == result.address },
                        onEdit = {
                            editingResult = result
                            newValue = result.formattedValue
                            showEditDialog = true
                        },
                        onSave = { viewModel.saveResult(result) },
                        onFreeze = {
                            viewModel.freezeValue(result.address, result.valueSnapshot, result.detectedType)
                            scope.launch {
                                snackbarHostState.showSnackbar("Value frozen")
                            }
                        },
                        onCopyAddress = {
                            scope.launch {
                                snackbarHostState.showSnackbar("Address copied: ${result.formattedAddress}")
                            }
                        }
                    )
                }
            }
        }
    }

    if (showEditDialog && editingResult != null) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Value") },
            text = {
                Column {
                    Text(
                        text = "Address: ${editingResult!!.formattedAddress}",
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = { newValue = it },
                        label = { Text("New Value") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        editingResult?.let { result ->
                            val bytes = parseValueToBytes(newValue, result.detectedType)
                            if (viewModel.modifyValue(result.address, bytes, result.detectedType)) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Value modified")
                                }
                            }
                        }
                        showEditDialog = false
                    }
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResultItem(
    result: ScanResult,
    isSaved: Boolean,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onFreeze: () -> Unit,
    onCopyAddress: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = { },
                onLongClick = { showMenu = true }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Address
            Column(modifier = Modifier.weight(0.4f)) {
                Text(
                    text = result.formattedAddress,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = result.detectedType.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Value
            Text(
                text = result.formattedValue,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(0.4f)
            )

            // Actions
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.weight(0.2f)
            ) {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onSave, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Save",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Edit Value") },
                onClick = {
                    onEdit()
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text("Freeze Value") },
                onClick = {
                    onFreeze()
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text("Copy Address") },
                onClick = {
                    onCopyAddress()
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text(if (isSaved) "Remove from Saved" else "Save Address") },
                onClick = {
                    onSave()
                    showMenu = false
                }
            )
        }
    }
}

fun parseValueToBytes(value: String, type: DataType): ByteArray {
    return when (type) {
        DataType.BYTE -> byteArrayOf(value.toInt().toByte())
        DataType.WORD -> {
            val intValue = value.toInt()
            byteArrayOf(
                (intValue and 0xFF).toByte(),
                ((intValue shr 8) and 0xFF).toByte()
            )
        }
        DataType.DWORD -> {
            val intValue = value.toInt()
            byteArrayOf(
                (intValue and 0xFF).toByte(),
                ((intValue shr 8) and 0xFF).toByte(),
                ((intValue shr 16) and 0xFF).toByte(),
                ((intValue shr 24) and 0xFF).toByte()
            )
        }
        DataType.QWORD -> {
            val longValue = value.toLong()
            ByteArray(8) { i ->
                ((longValue shr (i * 8)) and 0xFF).toByte()
            }
        }
        DataType.FLOAT -> {
            java.nio.ByteBuffer.allocate(4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putFloat(value.toFloat())
                .array()
        }
        DataType.DOUBLE -> {
            java.nio.ByteBuffer.allocate(8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putDouble(value.toDouble())
                .array()
        }
        else -> value.toByteArray(Charsets.UTF_8)
    }
}
