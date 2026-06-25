package com.aether.memoryeditor.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aether.memoryeditor.data.repository.SettingsRepository
import com.aether.memoryeditor.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel
) {
    val theme by viewModel.theme.collectAsState()
    val autoFreeze by viewModel.autoFreeze.collectAsState()
    val showHex by viewModel.showHexValues.collectAsState()
    val stealthMode by viewModel.stealthMode.collectAsState()
    val hideOverlay by viewModel.hideOverlay.collectAsState()
    val threadCount by viewModel.threadCount.collectAsState()
    val bufferSize by viewModel.bufferSizeMb.collectAsState()
    val overlayOpacity by viewModel.overlayOpacity.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Appearance Section
            SettingsSection(title = "Appearance") {
                SettingsDropdownItem(
                    title = "Theme",
                    subtitle = theme.name.lowercase().replaceFirstChar { it.uppercase() },
                    icon = Icons.Default.Info, // Replaced missing DarkMode
                    onClick = { /* Show theme selector */ }
                )

                SettingsSwitchItem(
                    title = "Show Hex Values",
                    subtitle = "Display values in hexadecimal format",
                    checked = showHex,
                    onCheckedChange = { viewModel.setShowHexValues(it) }
                )
            }

            // Scanner Section
            SettingsSection(title = "Scanner") {
                SettingsSliderItem(
                    title = "Thread Count",
                    subtitle = "Number of threads for parallel scanning",
                    value = threadCount.toFloat(),
                    valueRange = 1f..16f,
                    steps = 15,
                    onValueChange = { viewModel.setThreadCount(it.toInt()) }
                )

                SettingsSliderItem(
                    title = "Buffer Size",
                    subtitle = "Memory buffer size in MB",
                    value = bufferSize.toFloat(),
                    valueRange = 1f..256f,
                    steps = 255,
                    onValueChange = { viewModel.setBufferSizeMb(it.toInt()) }
                )

                SettingsSwitchItem(
                    title = "Auto-freeze on Edit",
                    subtitle = "Automatically freeze values after editing",
                    checked = autoFreeze,
                    onCheckedChange = { viewModel.setAutoFreeze(it) }
                )
            }

            // Overlay Section
            SettingsSection(title = "Overlay") {
                SettingsSliderItem(
                    title = "Overlay Opacity",
                    subtitle = "Transparency of the floating overlay",
                    value = overlayOpacity,
                    valueRange = 0.1f..1f,
                    onValueChange = { viewModel.setOverlayOpacity(it) }
                )

                SettingsSwitchItem(
                    title = "Hide Overlay Icon",
                    subtitle = "Start overlay in hidden mode",
                    checked = hideOverlay,
                    onCheckedChange = { viewModel.setHideOverlay(it) }
                )
            }

            // Stealth Section
            SettingsSection(title = "Stealth") {
                SettingsSwitchItem(
                    title = "Stealth Mode",
                    subtitle = "Enable anti-detection features",
                    checked = stealthMode,
                    onCheckedChange = { viewModel.setStealthMode(it) },
                    icon = Icons.Default.Info // Replaced missing Security
                )
            }

            // About Section
            SettingsSection(title = "About") {
                SettingsItem(
                    title = "Version",
                    subtitle = "1.0.0-Alpha (Android 15)",
                    icon = Icons.Default.Info
                )

                SettingsItem(
                    title = "Credits",
                    subtitle = "Aether Memory Editor Team",
                    icon = Icons.Default.Info,
                    onClick = { /* Show credits */ }
                )
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (onClick != null) {
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun SettingsDropdownItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    SettingsItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = onClick
    )
}

@Composable
fun SettingsSliderItem(
    title: String,
    subtitle: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${value.toInt()}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
