package com.aether.memoryeditor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aether.memoryeditor.data.repository.ScriptRepository
import com.aether.memoryeditor.ui.viewmodel.ScriptViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(
    viewModel: ScriptViewModel,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()

    val scripts by viewModel.scripts.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val consoleOutput by viewModel.consoleOutput.collectAsState()
    val showEditor by viewModel.showEditor.collectAsState()
    val selectedScript by viewModel.selectedScript.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lua Scripts") },
                actions = {
                    if (isRunning) {
                        IconButton(onClick = { viewModel.stopScript() }) {
                            Icon(Icons.Default.Close, "Stop")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.createNewScript() }) {
                Icon(Icons.Default.Add, "New Script")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Scripts") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Console") }
                )
            }

            when (selectedTab) {
                0 -> ScriptsList(
                    scripts = scripts,
                    onEdit = { viewModel.editScript(it) },
                    onRun = {
                        viewModel.runScript(it)
                        scope.launch {
                            snackbarHostState.showSnackbar("Running: ${it.name}")
                        }
                    },
                    onDelete = { viewModel.deleteScript(it.id) }
                )
                1 -> ConsoleView(
                    output = consoleOutput,
                    onClear = { viewModel.clearConsole() }
                )
            }
        }
    }

    if (showEditor) {
        ScriptEditorDialog(
            viewModel = viewModel,
            onDismiss = { viewModel.closeEditor() }
        )
    }
}

@Composable
fun ScriptsList(
    scripts: List<ScriptRepository.ScriptInfo>,
    onEdit: (ScriptRepository.ScriptInfo) -> Unit,
    onRun: (ScriptRepository.ScriptInfo) -> Unit,
    onDelete: (ScriptRepository.ScriptInfo) -> Unit
) {
    if (scripts.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "No Scripts",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Create a new script to get started",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(scripts) { script ->
                ScriptItem(
                    script = script,
                    onEdit = { onEdit(script) },
                    onRun = { onRun(script) },
                    onDelete = { onDelete(script) }
                )
            }
        }
    }
}

@Composable
fun ScriptItem(
    script: ScriptRepository.ScriptInfo,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Modified: ${dateFormat.format(Date(script.lastModified))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onRun) {
                Icon(Icons.Default.PlayArrow, "Run")
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, "Edit")
            }
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, "More")
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    onDelete()
                    showMenu = false
                }
            )
        }
    }
}

@Composable
fun ConsoleView(
    output: List<String>,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Console Output",
                style = MaterialTheme.typography.titleSmall
            )
            TextButton(onClick = onClear) {
                Text("Clear")
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    output.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ScriptEditorDialog(
    viewModel: ScriptViewModel,
    onDismiss: () -> Unit
) {
    val scriptName by viewModel.scriptName.collectAsState()
    val scriptContent by viewModel.scriptContent.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            OutlinedTextField(
                value = scriptName,
                onValueChange = { viewModel.setScriptName(it) },
                label = { Text("Script Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(modifier = Modifier.height(400.dp)) {
                OutlinedTextField(
                    value = scriptContent,
                    onValueChange = { viewModel.setScriptContent(it) },
                    label = { Text("Script Content") },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = androidx.compose.ui.unit.sp(12)
                    )
                )
            }
        },
        confirmButton = {
            Row {
                Button(
                    onClick = { viewModel.runCurrentScript() },
                    enabled = !isRunning
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Run")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    viewModel.saveScript()
                    onDismiss()
                }) {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
