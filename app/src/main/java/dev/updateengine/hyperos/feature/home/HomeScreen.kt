package dev.updateengine.hyperos.feature.home

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.updateengine.hyperos.HyperOsOtaApp
import dev.updateengine.hyperos.core.common.RomCompatibility
import dev.updateengine.hyperos.core.model.OtaTarget
import dev.updateengine.hyperos.core.model.Phase
import dev.updateengine.hyperos.core.model.PreflightReport
import dev.updateengine.hyperos.core.ui.components.CheckRow
import dev.updateengine.hyperos.core.ui.components.FailureCard
import dev.updateengine.hyperos.core.ui.components.Gate1ConfirmDialog
import dev.updateengine.hyperos.core.ui.components.Gate2RebootCard
import dev.updateengine.hyperos.core.ui.components.HeroCard
import dev.updateengine.hyperos.core.ui.components.PhaseRibbon
import dev.updateengine.hyperos.core.ui.components.RootPatchFailedCard
import dev.updateengine.hyperos.core.ui.components.SafeModeClearCard
import dev.updateengine.hyperos.core.ui.components.SafeModeOverrideCard
import dev.updateengine.hyperos.core.ui.components.SafeModeOverrideDialog
import dev.updateengine.hyperos.core.ui.components.TargetPickerDialog
import dev.updateengine.hyperos.core.ui.glass.FloatingBottomBarClearance
import java.io.File
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen(
    deviceCodename: String,
    currentOsVersion: String,
    activeSlot: String,
    isRootGranted: Boolean,
    rootDetails: String,
    preflightReport: PreflightReport,
    onNavigateToUpdates: () -> Unit,
    onTargetReady: () -> Unit,
    modifier: Modifier = Modifier
) {
    val app = HyperOsOtaApp.instance
    val progress by app.pipelineRunner.progress.collectAsState()

    var showGate1Dialog by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }
    var showSafeModeOverride by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Hero Card
        HeroCard(
            deviceCodename = deviceCodename,
            currentOsVersion = currentOsVersion,
            activeSlot = activeSlot,
            isRootGranted = isRootGranted,
            rootDetails = rootDetails
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Phase Status Ribbon
        PhaseRibbon(progress = progress)

        Spacer(modifier = Modifier.height(16.dp))

        // Contextual Cards according to state
        when (progress.phase) {
            Phase.ARMED -> {
                Button(
                    onClick = { showGate1Dialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(
                        text = "Prepare to Flash (Gate 1)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Phase.AWAITING_BOOT -> {
                Button(
                    onClick = { app.pipelineRunner.startChainC() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(
                        text = "Verify Post-Boot & Merge (Chain C)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Phase.READY_TO_REBOOT -> {
                Gate2RebootCard(
                    onRebootClick = { app.pipelineRunner.executeGate2Reboot() }
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    text = "Abort & Revert Slot (Stay on Current Slot)",
                    onClick = { app.pipelineRunner.abortBeforeReboot() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors()
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Phase.ROOT_PATCH_FAILED -> {
                RootPatchFailedCard(
                    title = progress.failure?.title ?: "Root Patch Verification Failed",
                    errorMessage = progress.failure?.message ?: "",
                    resolution = progress.failure?.actionableResolution,
                    onRetryClick = { app.pipelineRunner.retryRootPatch() },
                    onRevertSlotClick = { app.pipelineRunner.revertSlotSwitch() }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Phase.FAILED -> {
                val failure = progress.failure
                val canRollback = failure?.canRollbackSlot == true
                val isPostBootFailure = failure?.phase == Phase.POSTBOOT_VERIFY ||
                    failure?.phase == Phase.MERGING

                if (canRollback) {
                    // Retrying the root patch only makes sense once a payload was actually applied.
                    val payloadApplied = failure?.phase != Phase.FLASHING
                    RootPatchFailedCard(
                        title = failure?.title ?: "Safety Check Failed",
                        errorMessage = failure?.message ?: "",
                        resolution = failure?.actionableResolution,
                        onRetryClick = { app.pipelineRunner.retryRootPatch() },
                        onRevertSlotClick = { app.pipelineRunner.revertSlotSwitch() },
                        showRetryPatch = payloadApplied
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    if (isPostBootFailure) {
                        SafeModeOverrideCard(
                            title = failure?.title ?: "Post-Boot Verification Issue",
                            message = failure?.message
                                ?: "Modules stay disabled until the update is verified.",
                            resolution = failure?.actionableResolution,
                            showActions = true,
                            onResumeClick = { app.pipelineRunner.retryChainC() },
                            onForceClearSafeModeClick = { showSafeModeOverride = true }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Every other failure (preflight, download, extraction, verification) also has to
                    // show what went wrong; the status ribbon alone hides the cause.
                    FailureCard(
                        title = failure?.title ?: "Update Stopped",
                        message = failure?.message ?: progress.statusText,
                        resolution = failure?.actionableResolution,
                        phase = failure?.phase ?: progress.phase
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Starting a brand new update while a slot switch is pending would cancel the
                    // pending session, so only retry-patch or revert are offered above.
                    TargetActionRow(
                        target = progress.target,
                        currentOsVersion = currentOsVersion,
                        onSelectTarget = { showTargetPicker = true }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            Phase.MERGING -> {
                SafeModeOverrideCard(
                    title = progress.failure?.title ?: "Snapshot Merge In Progress",
                    message = progress.failure?.message
                        ?: "Modules stay disabled until update_engine confirms the Virtual A/B snapshots " +
                        "finished merging. Keep the device idle and charging.",
                    showActions = progress.failure != null,
                    onResumeClick = { app.pipelineRunner.retryChainC() },
                    onForceClearSafeModeClick = { showSafeModeOverride = true }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Phase.SAFE_MODE_CLEAR -> {
                SafeModeClearCard(
                    onClearSafeModeClick = { app.pipelineRunner.confirmRemoveSafeMode() }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Phase.VERIFYING_PATCH -> {
                // A verification that never finished (for example the process was killed while the
                // partition hashes were being compared) leaves the run in a state with no exit: the
                // payload is applied and the modules are disabled, so the only safe actions are
                // finishing the verification or reverting the slot switch.
                RootPatchFailedCard(
                    title = progress.failure?.title ?: "Root Patch Verification Interrupted",
                    errorMessage = progress.failure?.message
                        ?: "The root patch verification did not finish. The inactive slot must not be " +
                        "booted before the patched boot image is verified.",
                    resolution = progress.failure?.actionableResolution
                        ?: "Tap 'Retry Patch' to finish preserving root, or 'Revert Slot Switch' to stay " +
                        "safely on your current working slot.",
                    onRetryClick = { app.pipelineRunner.retryRootPatch() },
                    onRevertSlotClick = { app.pipelineRunner.revertSlotSwitch() }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Phase.CLEANUP -> {
                Button(
                    onClick = { app.pipelineRunner.finishCleanup() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(
                        text = "Finish Cleanup",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Phase.IDLE, Phase.DONE -> {
                TargetActionRow(
                    target = progress.target,
                    currentOsVersion = currentOsVersion,
                    onSelectTarget = { showTargetPicker = true }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            else -> {
                // Background operations running
            }
        }

        // Resetting the pipeline while a slot switch is pending or Safe Mode is armed would
        // silently abandon the update, so only offer it in genuinely safe phases. A failure that
        // happened after the reboot is excluded as well: resetting the update session while the
        // snapshots of the new slot may still be merging is not safe.
        val canReset = when (progress.phase) {
            Phase.CHECKING,
            Phase.PREFLIGHT,
            Phase.DOWNLOADING,
            Phase.EXTRACTING,
            Phase.VERIFYING,
            Phase.ARMED,
            Phase.CLEANUP,
            Phase.DONE -> true
            Phase.FAILED -> progress.failure?.canRollbackSlot != true &&
                progress.failure?.phase != Phase.POSTBOOT_VERIFY &&
                progress.failure?.phase != Phase.MERGING
            else -> false
        }

        if (canReset) {
            TextButton(
                text = "Reset to Idle",
                onClick = { app.pipelineRunner.resetToIdle() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors()
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Preflight Checks List
        if (preflightReport.checks.isNotEmpty()) {
            Text(
                text = "SYSTEM PREFLIGHT REQUIREMENTS",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            for (check in preflightReport.checks) {
                CheckRow(check = check)
            }
        }

        // Room for the floating capsule so the last check can be scrolled clear of it.
        Spacer(modifier = Modifier.height(FloatingBottomBarClearance))
    }

    if (showGate1Dialog) {
        Gate1ConfirmDialog(
            onDismiss = { showGate1Dialog = false },
            onConfirm = { checklist ->
                showGate1Dialog = false
                app.pipelineRunner.startChainB(checklist)
            }
        )
    }

    if (showSafeModeOverride) {
        SafeModeOverrideDialog(
            onDismiss = { showSafeModeOverride = false },
            onConfirm = {
                showSafeModeOverride = false
                app.pipelineRunner.confirmRemoveSafeMode(forced = true)
            }
        )
    }

    TargetPickerDialog(
        show = showTargetPicker,
        deviceCodename = deviceCodename,
        onDismiss = { showTargetPicker = false },
        onBrowseUpdates = {
            showTargetPicker = false
            onNavigateToUpdates()
        },
        onTargetReady = onTargetReady
    )
}

@Composable
private fun TargetActionRow(
    target: OtaTarget?,
    currentOsVersion: String,
    onSelectTarget: () -> Unit
) {
    val app = HyperOsOtaApp.instance
    val isSameAsInstalled = remember(target, currentOsVersion) {
        target != null && currentOsVersion.isNotBlank() && (
            target.osVersion.equals(currentOsVersion, ignoreCase = true) ||
            (target.postBuildIncremental.isNotBlank() && target.postBuildIncremental.equals(currentOsVersion, ignoreCase = true))
        )
    }

    LaunchedEffect(isSameAsInstalled) {
        if (isSameAsInstalled) {
            app.pipelineRunner.clearTarget()
        }
    }

    val effectiveTarget = if (isSameAsInstalled) null else target

    Column(modifier = Modifier.fillMaxWidth()) {
        if (effectiveTarget != null) {
            SelectedRomCard(target = effectiveTarget)
            Spacer(modifier = Modifier.height(12.dp))
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onSelectTarget,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(
                    text = if (effectiveTarget != null) "Change ROM" else "Select Target ROM",
                    fontWeight = FontWeight.Bold
                )
            }

            if (effectiveTarget != null) {
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(
                    text = "Start Update",
                    onClick = {
                        // A locally picked package is used where it already lives; catalogue packages
                        // download into the engine staging directory.
                        val dest = effectiveTarget.localZipPath
                            .takeIf { it.isNotBlank() }
                            ?.let { File(it) }
                            ?: File(app.getExternalFilesDir("ota"), effectiveTarget.recoveryFilename)
                        app.pipelineRunner.startChainA(effectiveTarget, dest)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors()
                )
            }
        }
    }
}

@Composable
private fun SelectedRomCard(target: OtaTarget) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(14.dp)
    ) {
        Text(
            text = "TARGET ROM",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = target.osVersion,
            color = MiuixTheme.colorScheme.primary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = buildString {
                append(target.branch)
                append(" • Resign: ${RomCompatibility.regionLabel(RomCompatibility.region(target.osVersion))}")
                if (target.androidVersion.isNotBlank()) append(" • Android ${target.androidVersion}")
            },
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = target.recoveryFilename,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}
