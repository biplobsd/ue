package dev.updateengine.hyperos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.updateengine.hyperos.core.model.PreflightReport
import dev.updateengine.hyperos.core.model.RootStatus
import dev.updateengine.hyperos.core.model.ThemeMode
import dev.updateengine.hyperos.core.ui.glass.FloatingBottomBar
import dev.updateengine.hyperos.core.ui.glass.FloatingBottomBarClearance
import dev.updateengine.hyperos.core.ui.glass.FloatingBottomBarItem
import dev.updateengine.hyperos.core.ui.theme.HyperOsOtaTheme
import dev.updateengine.hyperos.feature.home.HomeScreen
import dev.updateengine.hyperos.feature.log.LogScreen
import dev.updateengine.hyperos.feature.settings.SettingsScreen
import dev.updateengine.hyperos.feature.updates.UpdatesScreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class Screen(val title: String, val icon: ImageVector) {
    HOME("Home", MiuixIcons.Home),
    UPDATES("Updates", MiuixIcons.Update),
    LOGS("Logs", MiuixIcons.Notes),
    SETTINGS("Settings", MiuixIcons.Settings)
}

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val themeMode by HyperOsOtaApp.instance.settingsStorage.themeMode
                .collectAsState(initial = ThemeMode.SYSTEM)

            HyperOsOtaTheme(themeMode = themeMode) {
                MainApp()
            }
        }
    }
}

@Composable
fun MainApp() {
    val app = HyperOsOtaApp.instance
    val scope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    var deviceCodename by remember { mutableStateOf("") }
    var currentOsVersion by remember { mutableStateOf("") }
    var activeSlot by remember { mutableStateOf("") }
    var rootStatus by remember { mutableStateOf(RootStatus()) }
    var preflightReport by remember { mutableStateOf(PreflightReport()) }

    fun refreshDeviceInfo() {
        scope.launch {
            val dev = app.systemProps.getDevice()
            val os = app.systemProps.getIncremental()
            val slot = app.systemProps.getSlotSuffix()
            val root = app.rootProvider.probe()
            val report = app.preflightChecker.runChecks(
                target = app.pipelineRunner.progress.value.target
            )

            deviceCodename = dev
            currentOsVersion = os
            activeSlot = slot
            rootStatus = root
            preflightReport = report
        }
    }

    // Shared by the catalogue cards and the local .zip importer: both persist the target first, so
    // the preflight report is always re-evaluated against whichever ROM is now selected.
    fun applyTargetSelection() {
        val target = app.pipelineRunner.progress.value.target
        scope.launch {
            preflightReport = app.preflightChecker.runChecks(target = target)
        }
        currentScreen = Screen.HOME
    }

    LaunchedEffect(Unit) {
        refreshDeviceInfo()
    }

    // The glass capsule samples whatever is drawn beneath it, so the pages have to sit in their own
    // graphics layer and the bar is drawn as a sibling above it. Pages leave room for the capsule
    // themselves, which keeps their content scrolling underneath instead of stopping short of it.
    // Only the top and side insets are taken here: the bottom one belongs to the bar.
    val backdrop = rememberLayerBackdrop()
    val snackbarHostState = remember { SnackbarHostState() }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(MiuixTheme.colorScheme.background)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                    )
                )
        ) {
            when (currentScreen) {
                Screen.HOME -> HomeScreen(
                    deviceCodename = deviceCodename,
                    currentOsVersion = currentOsVersion,
                    activeSlot = activeSlot,
                    isRootGranted = rootStatus.isRootGranted,
                    rootDetails = rootStatus.details,
                    preflightReport = preflightReport,
                    onNavigateToUpdates = { currentScreen = Screen.UPDATES },
                    onTargetReady = { applyTargetSelection() }
                )
                Screen.UPDATES -> UpdatesScreen(
                    deviceCodename = deviceCodename,
                    currentOsVersion = currentOsVersion,
                    onRomSelected = { rom ->
                        app.pipelineRunner.setTarget(rom)
                        applyTargetSelection()
                    },
                    onTargetReady = { applyTargetSelection() }
                )
                Screen.LOGS -> LogScreen(snackbarHostState = snackbarHostState)
                Screen.SETTINGS -> SettingsScreen()
            }
        }

        FloatingBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                    )
                )
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth(),
            selectedIndex = { currentScreen.ordinal },
            onSelected = { index -> currentScreen = Screen.entries[index] },
            backdrop = backdrop,
            tabsCount = Screen.entries.size,
            isBackdropBlurEnabled = true,
            isLiquidGlassEnabled = true
        ) {
            for (screen in Screen.entries) {
                FloatingBottomBarItem(onClick = { currentScreen = screen }) {
                    Icon(
                        imageVector = screen.icon,
                        contentDescription = screen.title,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = screen.title,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // Chrome, like the bar, so it sits above the content layer rather than inside the blur, and
        // clear of the capsule so a message is never hidden behind it.
        SnackbarHost(
            state = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                    )
                )
                .padding(start = 12.dp, end = 12.dp, bottom = FloatingBottomBarClearance)
        )
    }
}
