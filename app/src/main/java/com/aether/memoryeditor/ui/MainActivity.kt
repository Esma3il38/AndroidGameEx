package com.aether.memoryeditor.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aether.memoryeditor.AetherApplication
import com.aether.memoryeditor.R
import com.aether.memoryeditor.ui.screens.ScannerScreen
import com.aether.memoryeditor.ui.screens.ResultsScreen
import com.aether.memoryeditor.ui.screens.ScriptsScreen
import com.aether.memoryeditor.ui.screens.SettingsScreen
import com.aether.memoryeditor.ui.theme.AetherTheme
import com.aether.memoryeditor.ui.viewmodel.ScannerViewModel
import com.aether.memoryeditor.ui.viewmodel.ScriptViewModel
import com.aether.memoryeditor.ui.viewmodel.SettingsViewModel
import com.aether.memoryeditor.service.OverlayService
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check and request overlay permission
        checkOverlayPermission()

        setContent {
            AetherTheme {
                MainScreen()
            }
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val navItems = listOf(
        Screen.Scanner,
        Screen.Results,
        Screen.Scripts,
        Screen.Settings
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                navItems.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Scanner.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Scanner.route) {
                val viewModel: ScannerViewModel = viewModel {
                    ScannerViewModel(
                        AetherApplication.instance.appModule.scanRepository,
                        AetherApplication.instance.appModule.processRepository
                    )
                }
                ScannerScreen(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState
                )
            }
            composable(Screen.Results.route) {
                val viewModel: ScannerViewModel = viewModel {
                    ScannerViewModel(
                        AetherApplication.instance.appModule.scanRepository,
                        AetherApplication.instance.appModule.processRepository
                    )
                }
                ResultsScreen(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState
                )
            }
            composable(Screen.Scripts.route) {
                val viewModel: ScriptViewModel = viewModel {
                    ScriptViewModel(
                        AetherApplication.instance.appModule.scriptRepository
                    )
                }
                ScriptsScreen(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState
                )
            }
            composable(Screen.Settings.route) {
                val viewModel: SettingsViewModel = viewModel {
                    SettingsViewModel(
                        AetherApplication.instance.appModule.settingsRepository
                    )
                }
                SettingsScreen(viewModel = viewModel)
            }
        }
    }
}

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Scanner : Screen("scanner", "Scanner", Icons.Default.Search)
    data object Results : Screen("results", "Results", Icons.Default.List)
    data object Scripts : Screen("scripts", "Scripts", Icons.Default.PlayArrow)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}
