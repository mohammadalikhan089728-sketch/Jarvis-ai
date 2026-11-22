package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.HudScreen
import com.example.ui.MacrosScreen
import com.example.ui.PermissionsScreen
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.DeepHudNavy
import com.example.ui.theme.JarvisTheme
import com.example.ui.theme.ObsidianVoid

const val ROUTE_HUD = "hud"
const val ROUTE_MACROS = "macros"
const val ROUTE_PERMISSIONS = "permissions"
const val ROUTE_SETTINGS = "settings"

private data class JarvisNavItem(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val NAV_ITEMS = listOf(
    JarvisNavItem(ROUTE_HUD, R.string.nav_hud, Icons.Filled.Radar, Icons.Outlined.Radar),
    JarvisNavItem(ROUTE_MACROS, R.string.nav_macros, Icons.Filled.AutoFixHigh, Icons.Outlined.AutoFixHigh),
    JarvisNavItem(ROUTE_PERMISSIONS, R.string.nav_permissions, Icons.Filled.Security, Icons.Outlined.Security),
    JarvisNavItem(ROUTE_SETTINGS, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings)
)

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        handleLaunchIntent(intent)

        setContent {
            JarvisTheme {
                JarvisRootApp(
                    viewModel = viewModel,
                    onKeepScreenOnChanged = { keepOn ->
                        if (keepOn) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MicCoordinator.setMainActivityActive(true)
    }

    override fun onPause() {
        MicCoordinator.setMainActivityActive(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(launchIntent: Intent?) {
        if (launchIntent == null) return
        val action = launchIntent.action
        val initialCommand = launchIntent.getStringExtra(JarvisWakeWordService.EXTRA_INITIAL_COMMAND)?.trim().orEmpty()
        val shouldStartListening =
            launchIntent.getBooleanExtra(FloatingOrbService.EXTRA_START_LISTENING, false) ||
                launchIntent.getBooleanExtra(JarvisWakeWordService.EXTRA_WAKE_TRIGGERED, false) ||
                action == Intent.ACTION_ASSIST ||
                action == "android.intent.action.VOICE_ASSIST"

        if (initialCommand.isNotEmpty()) {
            viewModel.startLiveSession(initialCommand = initialCommand)
        } else if (shouldStartListening) {
            viewModel.startLiveSession()
        }
    }

    override fun onDestroy() {
        MicCoordinator.setMainActivityActive(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}

@Composable
private fun JarvisRootApp(
    viewModel: JarvisViewModel,
    onKeepScreenOnChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_HUD

    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val voiceRmsDb by viewModel.voiceRmsDb.collectAsStateWithLifecycle()
    val isLiveSessionActive by viewModel.isLiveSessionActive.collectAsStateWithLifecycle()
    val inactivityRemainingMs by viewModel.inactivityRemainingMs.collectAsStateWithLifecycle()
    val isAutoSleepStandby by viewModel.isAutoSleepStandby.collectAsStateWithLifecycle()
    val liveTranscript by viewModel.liveTranscript.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val history by viewModel.chatHistory.collectAsStateWithLifecycle()
    val macros by viewModel.macros.collectAsStateWithLifecycle()
    val scheduledCalls by viewModel.scheduledCalls.collectAsStateWithLifecycle()
    val userMemories by viewModel.userMemories.collectAsStateWithLifecycle()
    val permissions by viewModel.permissionsState.collectAsStateWithLifecycle()
    val keyVerification by viewModel.keyVerificationState.collectAsStateWithLifecycle()
    val screenSummary by viewModel.screenSummary.collectAsStateWithLifecycle()
    val isMultiStepRunning by viewModel.isMultiStepRunning.collectAsStateWithLifecycle()
    val multiStepStatus by viewModel.multiStepStatus.collectAsStateWithLifecycle()
    val activeVoiceLabel by viewModel.activeVoiceLabel.collectAsStateWithLifecycle()
    val lastVoiceStatus by viewModel.lastVoiceStatus.collectAsStateWithLifecycle()
    val wakeWordServiceRunning by viewModel.wakeWordServiceRunning.collectAsStateWithLifecycle()

    // Keep screen awake during active continuous live conversation and release on stop
    DisposableEffect(isLiveSessionActive) {
        onKeepScreenOnChanged(isLiveSessionActive)
        onDispose {
            onKeepScreenOnChanged(false)
        }
    }

    // Refresh permissions whenever returning from Android System Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissions()
        if (granted) {
            viewModel.startLiveSession()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
    }

    val phoneContactsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissions()
    }

    fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openDefaultAssistantSettings() {
        val primaryIntent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opened = runCatching { context.startActivity(primaryIntent); true }.getOrDefault(false)
        if (!opened) {
            val fallback = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openAppInfoSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openNotificationListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun runDiagnosticTapWithToast() {
        viewModel.runDiagnosticTapTest { success ->
            val message = if (success) {
                "JARVIS Screen Tap SUCCESS — canPerformGestures is ACTIVE"
            } else {
                "Enable JARVIS Accessibility Service first for touch gestures"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 720.dp

        Scaffold(
            containerColor = ObsidianVoid,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = DeepHudNavy,
                        tonalElevation = 0.dp
                    ) {
                        NAV_ITEMS.forEach { item ->
                            val selected = currentRoute == item.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(item.labelRes),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = ObsidianVoid,
                                    selectedTextColor = ArcCyan,
                                    indicatorColor = ArcCyan,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag("nav_${item.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = DeepHudNavy
                    ) {
                        NAV_ITEMS.forEach { item ->
                            val selected = currentRoute == item.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(item.labelRes),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = ObsidianVoid,
                                    selectedTextColor = ArcCyan,
                                    indicatorColor = ArcCyan,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag("nav_${item.route}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_HUD
                    ) {
                        composable(ROUTE_HUD) {
                            HudScreen(
                                phase = phase,
                                voiceRmsDb = voiceRmsDb,
                                isLiveSessionActive = isLiveSessionActive,
                                inactivityRemainingMs = inactivityRemainingMs,
                                isAutoSleepStandby = isAutoSleepStandby,
                                isMultiStepRunning = isMultiStepRunning,
                                multiStepStatus = multiStepStatus,
                                liveTranscript = liveTranscript,
                                statusBanner = statusBanner,
                                settings = settings,
                                permissions = permissions,
                                history = history,
                                onToggleVoice = {
                                    if (!permissions.microphoneGranted) {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        viewModel.toggleListening()
                                    }
                                },
                                onStopSpeech = viewModel::stopSpeaking,
                                onStopMultiStep = viewModel::stopMultiStepAgent,
                                onToggleAutoReply = viewModel::toggleAutoReplyMode,
                                onSubmitCommand = viewModel::submitCommand,
                                onClearHistory = viewModel::clearHistory,
                                onOpenSettings = { navigateTopLevel(ROUTE_SETTINGS) },
                                onOpenPermissions = { navigateTopLevel(ROUTE_PERMISSIONS) }
                            )
                        }

                        composable(ROUTE_MACROS) {
                            MacrosScreen(
                                macros = macros,
                                accessibilityActive = permissions.accessibilityActive,
                                onAddMacro = viewModel::addCustomMacro,
                                onDeleteMacro = viewModel::deleteCustomMacro,
                                onRunMacro = { macro -> viewModel.submitCommand(macro.triggerPhrase) },
                                onRunGestureTest = viewModel::runDirectGestureTest
                            )
                        }

                        composable(ROUTE_PERMISSIONS) {
                            PermissionsScreen(
                                permissions = permissions,
                                screenSummary = screenSummary,
                                onRequestMic = {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                onOpenDefaultAssistantSettings = ::openDefaultAssistantSettings,
                                onOpenAccessibilitySettings = ::openAccessibilitySettings,
                                onTestScreenTap = ::runDiagnosticTapWithToast,
                                onOpenNotificationListenerSettings = ::openNotificationListenerSettings,
                                onOpenAppInfoSettings = ::openAppInfoSettings,
                                onRequestOverlay = ::openOverlayPermissionSettings,
                                onRequestCamera = {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                onRequestPhoneAndContacts = {
                                    phoneContactsLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.CALL_PHONE,
                                            Manifest.permission.READ_CONTACTS
                                        )
                                    )
                                },
                                onRefreshPermissions = viewModel::refreshPermissions,
                                onScanScreenNow = viewModel::scanCurrentScreenNow
                            )
                        }

                        composable(ROUTE_SETTINGS) {
                            SettingsScreen(
                                settings = settings,
                                keyVerificationState = keyVerification,
                                activeVoiceLabel = activeVoiceLabel,
                                lastVoiceStatus = lastVoiceStatus,
                                overlayPermissionGranted = permissions.overlayGranted,
                                notificationListenerGranted = permissions.notificationListenerActive,
                                wakeWordServiceRunning = wakeWordServiceRunning,
                                scheduledCalls = scheduledCalls,
                                userMemories = userMemories,
                                onSaveSettings = viewModel::saveSettings,
                                onVerifyApiKey = viewModel::verifyAiCoreApiKey,
                                onResetVerification = viewModel::resetVerificationState,
                                onTestVoice = viewModel::testVoice,
                                onRequestOverlayPermission = ::openOverlayPermissionSettings,
                                onOpenNotificationListenerSettings = ::openNotificationListenerSettings,
                                onOpenDefaultAssistantSettings = ::openDefaultAssistantSettings,
                                onRequestPostNotificationsPermission = ::requestNotificationsIfNeeded,
                                onAddScheduledCall = viewModel::addScheduledCall,
                                onToggleScheduledCall = viewModel::toggleScheduledCallEnabled,
                                onDeleteScheduledCall = viewModel::deleteScheduledCall,
                                onAddUserMemory = viewModel::addUserMemory,
                                onDeleteUserMemory = viewModel::deleteUserMemory,
                                onTestIncomingCall = viewModel::triggerTestIncomingCall,
                                onTestScreenTap = ::runDiagnosticTapWithToast
                            )
                        }
                    }
                }
            }
        }
    }
}
