package dev.updateengine.hyperos.feature.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.updateengine.hyperos.HyperOsOtaApp
import dev.updateengine.hyperos.core.common.RomCompatibility
import dev.updateengine.hyperos.core.model.OtaTarget
import dev.updateengine.hyperos.core.ui.components.TargetPickerDialog
import dev.updateengine.hyperos.core.ui.glass.FloatingBottomBarClearance
import dev.updateengine.hyperos.core.ui.theme.WarningAmber
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun UpdatesScreen(
    deviceCodename: String,
    currentOsVersion: String,
    onRomSelected: (OtaTarget) -> Unit,
    onTargetReady: () -> Unit,
    modifier: Modifier = Modifier
) {
    val app = HyperOsOtaApp.instance
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var roms by remember { mutableStateOf<List<OtaTarget>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedBranch by remember { mutableStateOf<String?>(null) }
    var showTargetPicker by remember { mutableStateOf(false) }

    fun loadRoms() {
        if (deviceCodename.isBlank()) return
        isLoading = true
        errorMessage = null
        scope.launch {
            val res = app.hyperDataClient.getRomsForDevice(deviceCodename)
            isLoading = false
            if (res.isSuccess) {
                roms = res.getOrDefault(emptyList())
            } else {
                errorMessage = res.exceptionOrNull()?.message ?: "Failed to load ROMs"
            }
        }
    }

    LaunchedEffect(deviceCodename) {
        loadRoms()
    }

    val deviceRegion = remember(currentOsVersion) { RomCompatibility.region(currentOsVersion) }

    // Only same-resign, forward-only builds are offered; the chip row then narrows by branch.
    val compatibleRoms = remember(roms, currentOsVersion) {
        RomCompatibility.selectableRoms(roms, currentOsVersion)
    }

    val branches = remember(compatibleRoms) {
        compatibleRoms.map { it.branch }.distinct()
    }

    val activeBranch = selectedBranch?.takeIf { it in branches } ?: branches.firstOrNull()

    val filteredRoms = remember(compatibleRoms, activeBranch) {
        if (activeBranch == null) compatibleRoms else compatibleRoms.filter { it.branch == activeBranch }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Official ROM Catalogue",
                    color = MiuixTheme.colorScheme.onBackground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = buildString {
                        append("Device: $deviceCodename")
                        if (currentOsVersion.isNotBlank()) append(" • Installed: $currentOsVersion")
                    },
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp
                )
                Text(
                    text = when {
                        deviceRegion != null && currentOsVersion.isNotBlank() ->
                            "Resign: ${RomCompatibility.regionLabel(deviceRegion)} • newer builds only"
                        deviceRegion != null ->
                            "Resign: ${RomCompatibility.regionLabel(deviceRegion)}"
                        else ->
                            "Resign undetected • showing all regions, cross-region builds may bootloop"
                    },
                    color = if (deviceRegion != null) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        WarningAmber
                    },
                    fontSize = 12.sp
                )
            }
            TextButton(
                text = "Refresh",
                onClick = { loadRoms() },
                colors = ButtonDefaults.textButtonColors()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Build source dropdown: the official catalogue listed below, or a zip already on the device.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { showTargetPicker = true },
            cornerRadius = 12.dp,
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Select Target ROM",
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when {
                            deviceRegion != null && currentOsVersion.isNotBlank() ->
                                "Official updates • ${RomCompatibility.regionLabel(deviceRegion)} • newer builds only"
                            deviceRegion != null ->
                                "Official updates • ${RomCompatibility.regionLabel(deviceRegion)}"
                            else ->
                                "Official updates • resign undetected"
                        },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp
                    )
                }
                Icon(
                    imageVector = MiuixIcons.ExpandMore,
                    contentDescription = "Choose build source",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Branch Selector Chips (Horizontally Scrollable)
        if (branches.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(branches) { b ->
                    val isSelected = b == activeBranch
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.surfaceContainer
                                }
                            )
                            .border(
                                1.dp,
                                if (isSelected) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.outline
                                },
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { selectedBranch = b }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = b,
                            color = if (isSelected) {
                                MiuixTheme.colorScheme.onPrimary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceContainer
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (errorMessage != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = errorMessage!!,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { loadRoms() },
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text("Retry")
                    }
                }
            }
        } else if (filteredRoms.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (deviceRegion != null && currentOsVersion.isNotBlank()) {
                        "No newer ${RomCompatibility.regionLabel(deviceRegion)} builds available for $deviceCodename"
                    } else {
                        "No Full Recovery ROMs found for $deviceCodename"
                    },
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = FloatingBottomBarClearance)
            ) {
                items(filteredRoms) { rom ->
                    RomItemCard(
                        rom = rom,
                        onSelect = {
                            app.pipelineRunner.setTarget(rom)
                            onRomSelected(rom)
                        }
                    )
                }
            }
        }
    }

    TargetPickerDialog(
        show = showTargetPicker,
        deviceCodename = deviceCodename,
        onDismiss = { showTargetPicker = false },
        onBrowseUpdates = { showTargetPicker = false },
        onTargetReady = {
            showTargetPicker = false
            onTargetReady()
        }
    )
}

@Composable
private fun RomItemCard(
    rom: OtaTarget,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        insideMargin = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rom.osVersion,
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Android ${rom.androidVersion} • ${RomCompatibility.regionLabel(RomCompatibility.region(rom.osVersion))} • Released: ${rom.releaseDate}",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = onSelect,
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(
                    text = "Select",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = rom.recoveryFilename,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}
