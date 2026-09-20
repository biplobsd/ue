package dev.updateengine.hyperos.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.updateengine.hyperos.HyperOsOtaApp
import dev.updateengine.hyperos.core.model.ThemeMode
import dev.updateengine.hyperos.core.ui.glass.FloatingBottomBarClearance
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val app = HyperOsOtaApp.instance
    val scope = rememberCoroutineScope()

    val isAutoPurge by app.settingsStorage.isAutoPurgeZip.collectAsState(initial = true)
    val themeMode by app.settingsStorage.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val minMarginGb by app.settingsStorage.minStorageMarginGb.collectAsState(initial = 16)

    var showThemeMenu by remember { mutableStateOf(false) }
    var showMarginDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Engine Settings & Safety",
            color = MiuixTheme.colorScheme.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Configure appearance, safety checks and cleanup behavior",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Appearance Card — opens the system/dark/light dropdown
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(14.dp),
            onClick = { showThemeMenu = true }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Appearance",
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Follow the system, or always stay dark or light",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Text(
                    text = themeMode.label,
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.width(6.dp))

                DropdownArrowEndAction(
                    actionColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )

                // A Miuix list popup anchors to its own layout parent and aligns inside that rect.
                // It is declared in the row's trailing slot and aligned End, which is what Miuix's
                // own WindowDropdownPopup does — anywhere else it anchors to that container and
                // drifts away from the control that opened it.
                WindowListPopup(
                    show = showThemeMenu,
                    alignment = PopupPositionProvider.Align.End,
                    onDismissRequest = { showThemeMenu = false }
                ) {
                    ListPopupColumn {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            DropdownImpl(
                                text = mode.label,
                                optionSize = ThemeMode.entries.size,
                                isSelected = mode == themeMode,
                                index = index,
                                onSelectedIndexChange = { selected ->
                                    showThemeMenu = false
                                    scope.launch {
                                        app.settingsStorage.setThemeMode(ThemeMode.entries[selected])
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Auto Purge Zip Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(14.dp),
            onClick = {
                scope.launch { app.settingsStorage.setAutoPurgeZip(!isAutoPurge) }
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Purge Downloaded Zip",
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Automatically delete the 7.7 GB .zip file from storage after payload.bin is extracted and verified, freeing critical space before Virtual A/B snapshots allocate.",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Switch(
                    checked = isAutoPurge,
                    onCheckedChange = { checked ->
                        scope.launch { app.settingsStorage.setAutoPurgeZip(checked) }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Storage Safety Margin Card — drives the preflight "/data free space" blocker
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(14.dp),
            onClick = { showMarginDialog = true }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Storage Safety Margin",
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Free space /data must have before a flash is allowed. Virtual A/B snapshots need room on top of the payload.",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Text(
                    text = "$minMarginGb GB",
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "HARDWARE INTEGRATION FACTS",
            color = MiuixTheme.colorScheme.disabledOnSurface,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "• update_engine: native streaming via --follow (avoids obsolete --status flag)\n• Root keeper: /data/adb/ksu/bin/ksud boot-patch -u -f\n• Snapshot merge: snapshotctl dump + update_engine_client --merge\n• Partition safety: SHA-256 boot integrity verification + fastboot emergency guide",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        // Room for the floating capsule so the last line can be scrolled clear of it.
        Spacer(modifier = Modifier.height(FloatingBottomBarClearance))
    }

    if (showMarginDialog) {
        // Edited as a draft so scrolling the wheel does not write to DataStore on every tick.
        var draftMarginGb by remember { mutableIntStateOf(minMarginGb) }

        WindowDialog(
            show = true,
            title = "Storage Safety Margin",
            summary = "Minimum free space in /data before update_engine is allowed to flash. The 7.7 GB payload is purged after verification, but snapshot allocation still needs room.",
            onDismissRequest = { showMarginDialog = false }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                NumberPicker(
                    value = draftMarginGb,
                    onValueChange = { draftMarginGb = it },
                    modifier = Modifier.fillMaxWidth(),
                    range = 16..64,
                    label = { "$it GB" }
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "Cancel",
                        onClick = { showMarginDialog = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors()
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = {
                            scope.launch { app.settingsStorage.setMinStorageMarginGb(draftMarginGb) }
                            showMarginDialog = false
                        },
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(
                            text = "Save",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
