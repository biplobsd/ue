package dev.updateengine.hyperos.core.ota

import android.content.Context
import dev.updateengine.hyperos.core.background.OtaApplyService
import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.common.PathSafety
import dev.updateengine.hyperos.core.common.RomCompatibility
import dev.updateengine.hyperos.core.datastore.PipelineStorage
import dev.updateengine.hyperos.core.datastore.SettingsStorage
import dev.updateengine.hyperos.core.model.FailureInfo
import dev.updateengine.hyperos.core.model.OtaProgress
import dev.updateengine.hyperos.core.model.OtaTarget
import dev.updateengine.hyperos.core.model.Phase
import dev.updateengine.hyperos.core.model.PreFlashChecklist
import dev.updateengine.hyperos.core.model.ProgressDetail
import dev.updateengine.hyperos.core.network.RemotePackageInspector
import dev.updateengine.hyperos.core.network.RomDownloader
import dev.updateengine.hyperos.core.notifications.OtaNotifier
import dev.updateengine.hyperos.core.root.RootProvider
import dev.updateengine.hyperos.core.root.SystemProps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class PipelineRunner(
    private val context: Context,
    private val rootProvider: RootProvider,
    private val systemProps: SystemProps,
    private val preflightChecker: PreflightChecker,
    private val payloadStager: PayloadStager,
    private val checksumVerifier: ChecksumVerifier,
    private val updateEngineController: UpdateEngineController,
    private val rootPatchKeeper: RootPatchKeeper,
    private val moduleSafety: ModuleSafety,
    private val emergencyRescueWriter: EmergencyRescueWriter,
    private val snapshotMergeMonitor: SnapshotMergeMonitor,
    private val postBootVerifier: PostBootVerifier,
    private val cleanupUseCase: CleanupUseCase,
    private val romDownloader: RomDownloader,
    private val packageInspector: RemotePackageInspector,
    private val storage: PipelineStorage,
    private val settingsStorage: SettingsStorage,
    private val notifier: OtaNotifier,
    private val dispatchers: AppDispatchers = AppDispatchers(),
    private val scope: CoroutineScope = CoroutineScope(dispatchers.default)
) {

    private val _progress = MutableStateFlow(OtaProgress())
    val progress: StateFlow<OtaProgress> = _progress.asStateFlow()

    private val _logLines = MutableSharedFlow<String>(replay = 500, extraBufferCapacity = 500)
    val logLines: SharedFlow<String> = _logLines.asSharedFlow()

    private var activeStockBackupPath: String = ""
    private var activeTargetSlot: String = ""
    private var disabledModuleIds: List<String> = emptyList()

    private val chainBBusy = AtomicBoolean(false)
    private val chainCBusy = AtomicBoolean(false)
    private var chainAJob: Job? = null

    private val logFile by lazy { File(context.filesDir, "pipeline.log") }

    /** Last phase written to DataStore: progress ticks must not turn into hundreds of disk commits. */
    private var persistedPhase: Phase? = null

    /** Identity of the last terminal alert posted, so a retried phase does not spam the user. */
    private var lastAlertKey: String? = null

    private companion object {
        /** Automatic merge monitoring attempts before the phase stays resumable. */
        const val MERGE_ATTEMPTS = 4

        /** /data headroom kept free while payload.bin is extracted next to the downloaded zip. */
        const val EXTRACTION_HEADROOM_BYTES = 1024L * 1024 * 1024
    }

    init {
        scope.launch {
            val savedPhase = storage.currentPhase.first()
            var savedTarget = storage.target.first()
            activeStockBackupPath = storage.stockBackupPath.first() ?: ""
            activeTargetSlot = storage.pendingSlot.first() ?: ""
            disabledModuleIds = storage.disabledModules.first()

            val miuiInc = systemProps.getMiuiIncremental()
            val currentIncremental = if (miuiInc.isNotBlank()) miuiInc else systemProps.getIncremental()
            val isTargetAlreadyInstalled = savedTarget != null && currentIncremental.isNotBlank() && (
                savedTarget.osVersion.equals(currentIncremental, ignoreCase = true) ||
                (savedTarget.postBuildIncremental.isNotBlank() && savedTarget.postBuildIncremental.equals(currentIncremental, ignoreCase = true))
            )

            if (isTargetAlreadyInstalled || savedPhase == Phase.DONE) {
                storage.saveTarget(null)
                savedTarget = null
            }

            if (savedPhase != Phase.IDLE) {
                _progress.value = OtaProgress(
                    phase = savedPhase,
                    target = savedTarget,
                    statusText = "Resumed state: ${savedPhase.name}"
                )
                when (savedPhase) {
                    Phase.FLASHING -> resumeFlashing()
                    Phase.MODULES_DISABLING, Phase.ROOT_PATCHING, Phase.VERIFYING_PATCH ->
                        resumeAfterFlash(savedPhase)
                    Phase.AWAITING_BOOT -> {
                        val expectedSlot = activeTargetSlot.ifBlank { storage.pendingSlot.first() ?: "" }
                        val currentSlot = systemProps.getSlotSuffix()
                        if (expectedSlot.isNotBlank() && currentSlot.equals(expectedSlot, ignoreCase = true)) {
                            startChainC()
                        } else {
                            _progress.value = _progress.value.copy(
                                statusText = "Waiting for reboot into slot $expectedSlot..."
                            )
                        }
                    }
                    Phase.POSTBOOT_VERIFY, Phase.MERGING -> startChainC()
                    Phase.CLEANUP -> finishCleanup()
                    else -> {}
                }
            } else {
                _progress.value = _progress.value.copy(target = savedTarget)
            }
        }
    }

    fun setTarget(target: OtaTarget?) {
        scope.launch {
            val miuiInc = systemProps.getMiuiIncremental()
            val currentIncremental = if (miuiInc.isNotBlank()) miuiInc else systemProps.getIncremental()
            val isAlreadyInstalled = target != null && currentIncremental.isNotBlank() && (
                target.osVersion.equals(currentIncremental, ignoreCase = true) ||
                (target.postBuildIncremental.isNotBlank() && target.postBuildIncremental.equals(currentIncremental, ignoreCase = true))
            )
            val effectiveTarget = if (isAlreadyInstalled) null else target
            _progress.value = _progress.value.copy(target = effectiveTarget)
            storage.saveTarget(effectiveTarget)
        }
    }

    fun clearTarget() {
        setTarget(null)
    }

    fun clearLogs() {
        try {
            if (logFile.exists()) {
                logFile.writeText("")
            }
        } catch (_: Exception) {}
    }

    private fun log(line: String) {
        _logLines.tryEmit(line)
        try {
            logFile.appendText("$line\n")
        } catch (_: Exception) {}
    }

    private fun updateState(
        phase: Phase,
        detail: ProgressDetail = ProgressDetail.Indeterminate,
        statusText: String = "",
        failure: FailureInfo? = null,
        target: OtaTarget? = _progress.value.target
    ) {
        val newProgress = OtaProgress(
            phase = phase,
            detail = detail,
            target = target,
            startedAt = if (_progress.value.startedAt == 0L) System.currentTimeMillis() else _progress.value.startedAt,
            updatedAt = System.currentTimeMillis(),
            statusText = statusText,
            failure = failure
        )
        _progress.value = newProgress
        if (persistedPhase != phase) {
            persistedPhase = phase
            scope.launch {
                storage.savePhase(phase)
            }
        }
        if (newProgress.showsProgressNotification) {
            notifier.updateProgressNotification(newProgress)
        } else {
            notifier.cancelProgressNotification()
        }
        notifyTerminalState(newProgress)
    }

    /**
     * Posts a high-importance alert for the states the user has to act on. The progress notification
     * lives on a low-importance channel and is removed when the foreground service stops, so a
     * terminal "do not reboot this device" message would otherwise be easy to miss.
     */
    private fun notifyTerminalState(progress: OtaProgress) {
        val (key, title, message) = when (progress.phase) {
            Phase.ROOT_PATCH_FAILED -> Triple(
                "root_patch:${progress.failure?.title}:${progress.failure?.message}",
                "HyperOS OTA: DO NOT REBOOT",
                buildString {
                    append(progress.failure?.message ?: "Root patch verification failed.")
                    progress.failure?.actionableResolution?.let { append("\n").append(it) }
                }
            )
            Phase.FAILED -> Triple(
                "failed:${progress.failure?.title}:${progress.failure?.message}",
                "HyperOS OTA: ${progress.failure?.title ?: "Update stopped"}",
                buildString {
                    append(progress.failure?.message ?: progress.statusText)
                    progress.failure?.actionableResolution?.let { append("\n").append(it) }
                }
            )
            Phase.DONE -> Triple(
                "done",
                "HyperOS OTA: update complete",
                "Re-enable your KernelSU modules one by one and check that each one still works on the new build."
            )
            else -> return
        }

        if (key == lastAlertKey) return
        lastAlertKey = key
        try {
            notifier.showTerminalNotification(title, message)
        } catch (e: Exception) {
            log("[Notifier] Could not post the terminal alert: ${e.message}")
        }
    }

    private fun startApplyService(
        statusText: String = "Applying system update via update_engine...",
        phase: Phase = Phase.FLASHING
    ) {
        try {
            OtaApplyService.start(context, statusText, phase)
        } catch (e: Exception) {
            log("[Service] Could not start foreground service: ${e.message}")
        }
    }

    /** Size of a file as the root shell sees it; the app itself may be denied the path. */
    private suspend fun rootFileSize(path: String): Long {
        val res = rootProvider.shell.exec("stat -c %s ${PathSafety.quote(path)} 2>/dev/null", timeoutMs = 5000)
        return res.stdout.trim().toLongOrNull() ?: 0L
    }

    private fun stopApplyService() {
        try {
            OtaApplyService.stop(context)
        } catch (e: Exception) {
            log("[Service] Could not stop foreground service: ${e.message}")
        }
    }

    // ==========================================
    // CHAIN A: AUTOMATIC & NON-DESTRUCTIVE
    // ==========================================

    fun startChainA(target: OtaTarget, destZipFile: File) {
        chainAJob?.cancel()
        chainAJob = scope.launch(dispatchers.io) {
            var stagingServiceStarted = false
            try {
                // The file name ends up inside root shell commands, so it is validated before it is
                // used anywhere near `su`.
                if (!PathSafety.isSafeFileName(target.recoveryFilename)) {
                    val failure = FailureInfo(
                        phase = Phase.FAILED,
                        title = "Unsafe Package File Name",
                        message = "The package name '${target.recoveryFilename}' contains characters that are not allowed in a recovery package name.",
                        actionableResolution = "Use the official package for your device, or rename the file to plain letters, digits, dots, dashes and underscores."
                    )
                    updateState(Phase.FAILED, statusText = "Package name rejected", failure = failure)
                    log("[Chain A] Rejected unsafe package name: ${target.recoveryFilename}")
                    return@launch
                }

                updateState(Phase.CHECKING, statusText = "Checking target package...", target = target)
                log("[Chain A] Starting verification for ${target.osVersion}")

                // Staging (download, extract, 7.7 GB hash) can run for a long time, so it runs under the
                // same foreground service as flashing. Without it the process can be frozen while the
                // screen is off and the download stalls silently.
                startApplyService("Verifying and staging the update package...", Phase.CHECKING)
                stagingServiceStarted = true

                // 1. Preflight checks
                updateState(Phase.PREFLIGHT, statusText = "Running preflight safety checks...")
                val preflight = preflightChecker.runChecks(
                    target = target,
                    isFullOta = true,
                    postSdkLevel = target.postSdkLevel,
                    postTimestamp = target.postTimestamp
                )

                if (preflight.hasBlockers) {
                    val blocker = preflight.checks.first { it.outcome.name == "FAIL" && it.severity.name == "BLOCKER" }
                    val failure = FailureInfo(
                        phase = Phase.PREFLIGHT,
                        title = blocker.title,
                        message = blocker.detail,
                        actionableResolution = blocker.remediation ?: "Resolve preflight blocker."
                    )
                    updateState(Phase.FAILED, statusText = "Preflight check failed", failure = failure)
                    log("[Preflight Error] ${blocker.title}: ${blocker.detail}")
                    return@launch
                }

                // 2. Acquire the package: a user-picked zip is used where it already lives (the app
                //    process may not be able to stat it under scoped storage, so root checks it), a
                //    catalogue entry is downloaded into the staging directory.
                val isUserSuppliedPackage = target.localZipPath.isNotBlank() &&
                    destZipFile.absolutePath == target.localZipPath
                val stagingRoot = context.getExternalFilesDir("ota")?.absolutePath
                val zipIsAppOwned = stagingRoot != null && destZipFile.absolutePath.startsWith(stagingRoot)

                val zipExists = if (isUserSuppliedPackage) {
                    rootFileSize(destZipFile.absolutePath) > 0L
                } else {
                    destZipFile.exists() && destZipFile.length() > 0
                }

                // Check if a payload matching this target is already staged in /data/ota_package
                val stagedPayloadSize = rootFileSize("/data/ota_package/payload.bin")
                val propsRes = rootProvider.shell.exec("cat /data/ota_package/payload_properties.txt 2>/dev/null", timeoutMs = 5000)
                val stagedProps = if (propsRes.isSuccess && propsRes.stdout.isNotBlank()) {
                    RemotePackageInspector.parseProperties(propsRes.stdout)
                } else emptyMap()
                val stagedFileSize = stagedProps["FILE_SIZE"]?.toLongOrNull() ?: 0L
                val stagedFileHash = stagedProps["FILE_HASH"].orEmpty()
                val stagedMetadataHash = stagedProps["METADATA_HASH"].orEmpty()
                val stagedMetadataSize = stagedProps["METADATA_SIZE"]?.toLongOrNull() ?: 0L

                val isPayloadAlreadyStaged = !zipExists &&
                    stagedPayloadSize > 0L &&
                    stagedFileSize > 0L &&
                    stagedPayloadSize == stagedFileSize &&
                    (target.fileSize == 0L || target.fileSize == stagedFileSize) &&
                    (target.fileHash.isBlank() || target.fileHash.equals(stagedFileHash, ignoreCase = true))

                val inspected: RemotePackageInspector.InspectionResult
                if (isPayloadAlreadyStaged) {
                    log("[Chain A] Found matching payload already staged in /data/ota_package/ ($stagedPayloadSize bytes). Skipping download & extraction.")
                    inspected = RemotePackageInspector.InspectionResult(
                        isFullOta = true,
                        preDevice = target.device,
                        postBuildIncremental = target.postBuildIncremental,
                        preBuildIncremental = null,
                        postSdkLevel = target.postSdkLevel,
                        postTimestamp = target.postTimestamp,
                        fileHash = stagedFileHash,
                        fileSize = stagedFileSize,
                        metadataHash = stagedMetadataHash,
                        metadataSize = stagedMetadataSize
                    )
                    setTarget(
                        target.copy(
                            fileHash = stagedFileHash,
                            fileSize = stagedFileSize,
                            metadataHash = stagedMetadataHash,
                            metadataSize = stagedMetadataSize
                        )
                    )
                    // Ensure metadata is staged with the signature included for update_engine --allocate
                    val metaRes = payloadStager.stageMetadataOnly(stagedMetadataSize)
                    if (metaRes.isFailure) {
                        val failure = FailureInfo(
                            phase = Phase.EXTRACTING,
                            title = "Metadata Staging Failed",
                            message = metaRes.exceptionOrNull()?.message ?: "Failed to stage metadata",
                            actionableResolution = "Check root permissions on /data/ota_package."
                        )
                        updateState(Phase.FAILED, statusText = "Metadata staging failed", failure = failure)
                        return@launch
                    }
                } else {
                    if (!zipExists) {
                        if (target.downloadUrls.isEmpty()) {
                            val failure = FailureInfo(
                                phase = Phase.PREFLIGHT,
                                title = "Package Not Found",
                                message = "The package you imported is no longer at ${destZipFile.absolutePath}.",
                                actionableResolution = "Pick the .zip again from storage, or choose an official build from the Updates tab."
                            )
                            updateState(Phase.FAILED, statusText = "Imported package missing", failure = failure)
                            log("[Chain A] Imported package missing at ${destZipFile.absolutePath}")
                            return@launch
                        }

                        updateState(Phase.DOWNLOADING, statusText = "Downloading official recovery zip...")
                        log("[Downloader] Downloading to ${destZipFile.absolutePath}...")

                        romDownloader.download(target.downloadUrls, destZipFile).collect { bytes ->
                            updateState(
                                Phase.DOWNLOADING,
                                detail = bytes,
                                statusText = "Downloading package..."
                            )
                        }
                    } else {
                        log("[Chain A] Using package at ${destZipFile.absolutePath}${if (isUserSuppliedPackage) " (in place, not copied)" else ""}")
                    }

                    // Inspect local package metadata. For an in-place package the app may not be allowed to
                    // open the path itself, so the root shell reads the entries instead.
                    val inspectRes = if (isUserSuppliedPackage) {
                        packageInspector.inspectZipInPlace(destZipFile.absolutePath)
                    } else {
                        packageInspector.inspectLocalZip(destZipFile)
                    }
                    if (inspectRes.isFailure) {
                        // A package the engine downloaded itself and cannot read is disposable. Without
                        // removing it the next attempt would find "the zip is already there", skip the
                        // download and fail on the same unusable file forever, because nothing else in the
                        // app ever deletes a staged package. A package the user picked is never touched.
                        val discarded = zipIsAppOwned && destZipFile.delete()
                        if (discarded) {
                            log("[Chain A] Discarded the unusable downloaded package ${destZipFile.name}.")
                        }
                        val failure = FailureInfo(
                            phase = Phase.EXTRACTING,
                            title = "Package Metadata Error",
                            message = "Could not parse metadata inside zip: ${inspectRes.exceptionOrNull()?.message}",
                            actionableResolution = if (discarded) {
                                "Tap 'Start Update' again to download the package afresh."
                            } else {
                                "Delete this file from storage (or pick a different package) and start the update again."
                            }
                        )
                        updateState(Phase.FAILED, statusText = "Package inspect failed", failure = failure)
                        return@launch
                    }

                    val localInspected = inspectRes.getOrThrow()
                    if (!localInspected.isFullOta) {
                        val failure = FailureInfo(
                            phase = Phase.PREFLIGHT,
                            title = "Incremental Package Rejected",
                            message = "Target is an Incremental OTA package, not a Full Recovery package.",
                            actionableResolution = "Only Full Recovery packages can be installed via this engine."
                        )
                        updateState(Phase.FAILED, statusText = "Incremental package rejected", failure = failure)
                        return@launch
                    }

                    // Validate the real package metadata against this device (anti-downgrade, codename)
                    // and persist the inspected values so post-boot verification can use them.
                    val compatFailure = preflightChecker.checkPackageAgainstDevice(
                        preDevice = localInspected.preDevice,
                        postSdkLevel = localInspected.postSdkLevel,
                        postTimestamp = localInspected.postTimestamp,
                        targetRegion = RomCompatibility.region(target.osVersion)
                    )
                    if (compatFailure != null) {
                        val failure = FailureInfo(
                            phase = Phase.PREFLIGHT,
                            title = compatFailure.title,
                            message = compatFailure.detail,
                            actionableResolution = compatFailure.remediation ?: "Use a compatible package."
                        )
                        updateState(Phase.FAILED, statusText = "Package rejected", failure = failure)
                        log("[Package Check] ${compatFailure.title}: ${compatFailure.detail}")
                        return@launch
                    }

                    setTarget(
                        target.copy(
                            postSdkLevel = localInspected.postSdkLevel,
                            postTimestamp = localInspected.postTimestamp,
                            postBuildIncremental = localInspected.postBuildIncremental,
                            fileHash = localInspected.fileHash,
                            fileSize = localInspected.fileSize,
                            metadataHash = localInspected.metadataHash,
                            metadataSize = localInspected.metadataSize
                        )
                    )

                    // 3. Extract to /data/ota_package.
                    //    The zip stays on /data while payload.bin is written next to it, so the space for
                    //    the payload has to be checked against the real package size and not just against
                    //    the configured margin.
                    if (localInspected.fileSize > 0) {
                        val freeBytes = preflightChecker.freeSpaceOnData()
                        val neededBytes = localInspected.fileSize + EXTRACTION_HEADROOM_BYTES
                        if (freeBytes in 1 until neededBytes) {
                            val neededGb = neededBytes / (1024.0 * 1024 * 1024)
                            val freeGb = freeBytes / (1024.0 * 1024 * 1024)
                            val failure = FailureInfo(
                                phase = Phase.EXTRACTING,
                                title = "Not Enough Space To Stage The Payload",
                                message = "Extracting payload.bin needs %.1f GB free in /data, but only %.1f GB are available.".format(neededGb, freeGb),
                                actionableResolution = "Free up storage on the device and start the update again."
                            )
                            updateState(Phase.FAILED, statusText = "Insufficient storage", failure = failure)
                            log("[Stager] Aborted: needs ${"%.1f".format(neededGb)} GB free, ${"%.1f".format(freeGb)} GB available.")
                            return@launch
                        }
                    }

                    updateState(Phase.EXTRACTING, statusText = "Extracting payload to /data/ota_package with SELinux context...")
                    log("[Stager] Extracting payload.bin and manifest...")

                    val stageRes = payloadStager.stage(destZipFile, localInspected.metadataSize)
                    if (stageRes.isFailure) {
                        val failure = FailureInfo(
                            phase = Phase.EXTRACTING,
                            title = "Extraction Failed",
                            message = stageRes.exceptionOrNull()?.message ?: "Unknown extraction error",
                            actionableResolution = "Check free space on /data and root permissions."
                        )
                        updateState(Phase.FAILED, statusText = "Extraction failed", failure = failure)
                        return@launch
                    }

                    inspected = localInspected
                }

                // 4. Verify Hashes & Space Allocation
                updateState(Phase.VERIFYING, statusText = "Verifying hashes and COW space allocation...")
                log("[Verifier] Verifying METADATA_HASH, FILE_HASH, and COW space pre-allocation...")

                val autoPurge = settingsStorage.isAutoPurgeZip.first()
                val verifyRes = checksumVerifier.verifyStagedPayload(
                    expectedFileSize = inspected.fileSize,
                    expectedFileHash = inspected.fileHash,
                    metadataSize = inspected.metadataSize,
                    expectedMetadataHash = inspected.metadataHash,
                    downloadedZipFile = destZipFile,
                    // Only the engine's own download is disposable. A package the user picked is read in
                    // place and is never deleted, so the snapshot allocation has to fit next to it.
                    autoPurgeZip = autoPurge && zipIsAppOwned
                )

                when (verifyRes) {
                    is ChecksumVerifier.VerificationResult.Failure -> {
                        val failure = FailureInfo(
                            phase = Phase.VERIFYING,
                            title = "Verification Failed",
                            message = verifyRes.reason,
                            actionableResolution = "Payload hashes do not match. Do not flash this package."
                        )
                        updateState(Phase.FAILED, statusText = "Verification failed", failure = failure)
                        log("[Verifier Error] ${verifyRes.reason}")
                        return@launch
                    }
                    is ChecksumVerifier.VerificationResult.Success -> {
                        log("[Verifier] All checksums verified successfully.")
                        verifyRes.notes.forEach { note -> log("[Verifier] $note") }
                    }
                }

                // Reached ARMED safely!
                updateState(Phase.ARMED, statusText = "Armed. Ready for Gate 1 pre-flash confirmation.")
                log("[Chain A Complete] Non-destructive boundary reached safely. Device is ARMED.")
            } catch (e: CancellationException) {
                log("[Chain A] Operation cancelled by user.")
                throw e
            } catch (e: Exception) {
                log("[Chain A Exception] ${e.message}")
                val failure = FailureInfo(
                    phase = Phase.FAILED,
                    title = "Operation Failed",
                    message = e.message ?: "Unexpected error",
                    actionableResolution = "Check logs and retry."
                )
                updateState(Phase.FAILED, statusText = "Error: ${e.message}", failure = failure)
            } finally {
                // Chain A is over (armed or failed); Chain B starts its own service instance.
                if (stagingServiceStarted) stopApplyService()
            }
        }
    }

    // ==========================================
    // CHAIN B: FLASHING & ROOT PRESERVATION
    // ==========================================

    fun startChainB(checklist: PreFlashChecklist) {
        require(checklist.isAllChecked) { "Gate 1 mandatory checklist must be 100% checked." }

        if (!chainBBusy.compareAndSet(false, true)) {
            log("[Chain B] Already running; ignoring duplicate start.")
            return
        }

        scope.launch(dispatchers.io) {
            log("[Chain B] Gate 1 passed with all 5 safety confirmations. Re-checking device state before flashing...")

            // Battery level and snapshot state can change between Chain A and Gate 1, so the volatile
            // conditions the user confirmed are re-validated before update_engine is allowed to write.
            val readinessFailure = preflightChecker.recheckFlashReadiness()
            if (readinessFailure != null) {
                val failure = FailureInfo(
                    phase = Phase.FAILED,
                    title = readinessFailure.title,
                    message = readinessFailure.detail,
                    actionableResolution = readinessFailure.remediation ?: "Resolve the issue, then start the update again."
                )
                updateState(Phase.FAILED, statusText = "Pre-flash re-check failed", failure = failure)
                log("[Chain B] Blocked before flashing: ${readinessFailure.title} — ${readinessFailure.detail}")
                chainBBusy.set(false)
                return@launch
            }

            startApplyService()

            try {
                // Remember which slot this run has to land in before anything is written, so a resumed
                // or interrupted run can still verify the boot afterwards.
                val startStatus = rootProvider.probe()
                if (startStatus.inactiveSlot.isNotBlank()) {
                    activeTargetSlot = startStatus.inactiveSlot
                    storage.savePendingReboot(startStatus.inactiveSlot, activeStockBackupPath)
                }

                // Modules are disabled *before* update_engine is allowed to write: from the moment the
                // new slot has been written a reboot (even a manual one, from the power menu) can land
                // in it, and a module that is still enabled would then inject into Zygote and can crash
                // snapuserd, which leaves the display unlit. Nothing has been written yet at this point,
                // so a failure here leaves the device untouched and the modules can be re-enabled.
                if (!disableModulesForFirstBoot(payloadApplied = false)) {
                    return@launch
                }

                updateState(Phase.FLASHING, detail = ProgressDetail.Percent(0f, "Starting update_engine..."), statusText = "Flashing inactive slot...")
                log("[update_engine] Launching apply.sh with streaming --follow...")

                val success = streamEngineEvents(updateEngineController.applyPayload())
                if (!success || _progress.value.phase == Phase.FAILED) {
                    return@launch
                }

                runRootPatchAndArm()
            } catch (e: Exception) {
                log("[Chain B Exception] ${e.message}")
                val failure = FailureInfo(
                    phase = Phase.FAILED,
                    title = "System Update Exception",
                    message = e.message ?: "Unexpected error during flashing",
                    actionableResolution = "Check logs. Revert slot switch if necessary.",
                    canRollbackSlot = true
                )
                updateState(Phase.FAILED, statusText = "Exception during update", failure = failure)
            } finally {
                stopApplyService()
                chainBBusy.set(false)
            }
        }
    }

    /**
     * Disables every KernelSU module so nothing can inject into Zygote or the display compositor on
     * the first boot into the new slot. Returns true only when every module was verified disabled and
     * the global flag is set.
     *
     * [payloadApplied] describes the device state for the failure path and for the phase that is
     * persisted: before the payload is written an aborted update leaves the current slot untouched and
     * the run stays on the non-destructive boundary ([Phase.ARMED], which is never auto-resumed),
     * while afterwards the slot switch is already armed and the phase is [Phase.MODULES_DISABLING].
     */
    private suspend fun disableModulesForFirstBoot(payloadApplied: Boolean): Boolean {
        updateState(
            if (payloadApplied) Phase.MODULES_DISABLING else Phase.ARMED,
            statusText = "Disabling KernelSU modules for the first boot into the new slot..."
        )
        log("[ModuleSafety] Disabling modules with 'ksud module disable' so nothing can inject before the merge...")

        val moduleReport = moduleSafety.disableAllModulesBeforeReboot()

        // Record the modules that were enabled before this ran even when the run did not fully
        // succeed: an abort has to re-enable every module that did get a disable flag, not only a
        // completely disabled set. A retry reports an empty list (everything is already disabled), so
        // the previously persisted record has to survive that too.
        val knownDisabled = if (moduleReport.previouslyEnabled.isNotEmpty()) {
            moduleReport.previouslyEnabled
        } else {
            disabledModuleIds.ifEmpty { storage.disabledModules.first() }
        }
        disabledModuleIds = knownDisabled
        storage.saveDisabledModules(knownDisabled)

        if (!moduleReport.isComplete) {
            val failure = FailureInfo(
                phase = if (payloadApplied) Phase.MODULES_DISABLING else Phase.FLASHING,
                title = "KernelSU Modules Could Not Be Disabled",
                message = buildString {
                    append("These modules could not be confirmed disabled: ${moduleReport.failed.joinToString(", ")}. ")
                    if (payloadApplied) {
                        append("Rebooting now risks a module injecting into Zygote and crashing snapuserd on first boot.")
                    } else {
                        append("Nothing has been written to the inactive slot yet; the device is still running its current build.")
                    }
                },
                actionableResolution = if (payloadApplied) {
                    "DO NOT REBOOT. Tap 'Retry Patch' to disable them again, or 'Revert Slot Switch' to stay on your current working slot."
                } else {
                    "Tap 'Revert Slot Switch' to re-enable the modules that were disabled, then start the update again."
                },
                canRollbackSlot = true
            )
            updateState(Phase.FAILED, statusText = "Module disabling failed", failure = failure)
            log("[ModuleSafety CRITICAL] Failed: ${moduleReport.failed.joinToString(", ")}")
            return false
        }

        log(
            "[ModuleSafety] ${moduleReport.disabled.size} module(s) disabled" +
                (if (moduleReport.ksudUsed) " via ksud (preinit modules.rc regenerated)" else " by direct flag, ksud unavailable") +
                (if (moduleReport.pendingUpdatesParked) "; pending module update parked" else "") + "."
        )
        return true
    }

    /**
     * Re-runs only root patching and the post-flash arming steps. Never re-applies the payload.
     */
    fun retryRootPatch() {
        if (!chainBBusy.compareAndSet(false, true)) {
            log("[Retry] Another operation is already running; ignoring retry request.")
            return
        }

        scope.launch(dispatchers.io) {
            log("[Retry] Re-running root patch and post-flash arming (payload is NOT re-applied)...")
            try {
                // Never patch the inactive slot unless update_engine confirms a payload was
                // applied to it. Patching an un-written slot would arm a reboot into stock.
                val appliedStatus = updateEngineController.probeCurrentStatus()?.first.orEmpty()
                if (appliedStatus != "UPDATED_NEED_REBOOT") {
                    val failure = FailureInfo(
                        phase = Phase.FAILED,
                        title = "Payload Not Applied",
                        message = "update_engine reports '${appliedStatus.ifBlank { "unknown" }}', not UPDATED_NEED_REBOOT. There is no flashed payload to preserve root on.",
                        actionableResolution = "Tap 'Start Update' to flash the payload first, or 'Revert Slot Switch' to cancel the pending session.",
                        canRollbackSlot = true
                    )
                    updateState(Phase.FAILED, statusText = "No applied payload to patch", failure = failure)
                    log("[Retry] Blocked: no applied payload (status: ${appliedStatus.ifBlank { "unknown" }}).")
                    return@launch
                }
                runRootPatchAndArm()
            } finally {
                chainBBusy.set(false)
            }
        }
    }

    private suspend fun streamEngineEvents(
        events: Flow<UpdateEngineController.EngineEvent>
    ): Boolean {
        var success = false
        events.collect { event ->
            when (event) {
                is UpdateEngineController.EngineEvent.LogLine -> log(event.line)
                is UpdateEngineController.EngineEvent.Progress -> {
                    updateState(
                        Phase.FLASHING,
                        detail = ProgressDetail.Percent(event.progressFraction, "${(event.progressFraction * 100).toInt()}%"),
                        statusText = "Flashing: ${event.statusName}"
                    )
                }
                is UpdateEngineController.EngineEvent.Completed -> {
                    if (event.isSuccess) {
                        success = true
                    } else {
                        val failure = FailureInfo(
                            phase = Phase.FLASHING,
                            title = "Flashing Failed",
                            message = event.message,
                            actionableResolution = "Slot switch has not been finalized. Revert the slot switch or retry the update.",
                            canRollbackSlot = true
                        )
                        updateState(Phase.FAILED, statusText = "Flash error", failure = failure)
                    }
                }
            }
        }

        if (!success && _progress.value.phase == Phase.FLASHING) {
            val failure = FailureInfo(
                phase = Phase.FLASHING,
                title = "Flashing Unconfirmed",
                message = "update_engine finished without confirming a successful payload application.",
                actionableResolution = "Re-attach to update_engine or revert the slot switch before rebooting.",
                canRollbackSlot = true
            )
            updateState(Phase.FAILED, statusText = "Flash not confirmed", failure = failure)
        }

        return success
    }

    /**
     * Module disabling -> root patch -> cryptographic verification -> rescue guide -> READY_TO_REBOOT.
     *
     * Modules are disabled *before* the inactive slot is patched: from the moment update_engine has
     * written the new slot, a reboot could land in it, and an injection module that is still enabled
     * would crash snapuserd and leave the screen unlit. A patch that was already verified is never
     * applied twice, because a second `ksud boot-patch` would patch an already patched image.
     */
    private suspend fun runRootPatchAndArm() {
        try {
            val alreadyPatched = activeStockBackupPath.isNotBlank()

            // MODULES_DISABLING: proven armed before Gate 2 can unlock. This is idempotent, so it also
            // covers a resumed run that was interrupted before the modules were disabled.
            if (!disableModulesForFirstBoot(payloadApplied = true)) {
                return
            }

            if (!alreadyPatched) {
                updateState(Phase.ROOT_PATCHING, statusText = "Preserving root: Running ksud boot-patch on inactive slot...")
                log("[RootPatch] Applying KernelSU Next to inactive slot (/data/adb/ksu/bin/ksud boot-patch -u -f)...")

                val patchRes = rootPatchKeeper.patchAndVerify(
                    onVerifyStep = {
                        updateState(
                            Phase.VERIFYING_PATCH,
                            statusText = "Verifying the patched boot partition against the stock backup..."
                        )
                    }
                )
                if (!patchRes.isSuccess) {
                    log("[RootPatch CRITICAL FAILURE] ${patchRes.errorMessage}")
                    val failure = FailureInfo(
                        phase = Phase.ROOT_PATCH_FAILED,
                        title = "Root Patch Verification Failed",
                        message = patchRes.errorMessage ?: "Inactive slot was not modified with root.",
                        actionableResolution = "DO NOT REBOOT. Tap 'Retry Patch' or 'Revert Slot Switch' to stay safely on your current working slot.",
                        canRollbackSlot = true
                    )
                    updateState(Phase.ROOT_PATCH_FAILED, statusText = "Root patch verification failed", failure = failure)
                    return
                }

                activeStockBackupPath = patchRes.stockBackupPath
                activeTargetSlot = patchRes.targetSlot.ifBlank { patchRes.targetPartition.takeLast(2) }
                storage.savePendingReboot(activeTargetSlot, activeStockBackupPath)
                patchRes.warning?.let { log("[RootPatch] WARNING: $it") }
                log("[RootPatch Success] Root patch verified on ${patchRes.targetPartition}. Stock backup: $activeStockBackupPath")
            } else {
                log("[Resume] Root patch already verified previously; keeping ${activeStockBackupPath.ifBlank { "unknown backup" }}")
            }

            // EMERGENCY RESCUE GUIDE
            val rootStatus = rootProvider.probe()
            val rescueRes = emergencyRescueWriter.writeRescueGuide(
                deviceCodename = systemProps.getDevice(),
                currentSlot = rootStatus.currentSlot,
                updatedSlot = activeTargetSlot,
                stockBackupPath = activeStockBackupPath,
                targetPartition = "${rootStatus.defaultPartition}${activeTargetSlot}"
            )
            if (rescueRes.isFailure) {
                val failure = FailureInfo(
                    phase = Phase.MODULES_DISABLING,
                    title = "Emergency Rescue File Not Written",
                    message = rescueRes.exceptionOrNull()?.message
                        ?: "Could not write /sdcard/Download/ota_emergency_recovery.txt.",
                    actionableResolution = "DO NOT REBOOT without the rescue guide. Tap 'Retry Patch' to try again, or 'Revert Slot Switch' to abort safely.",
                    canRollbackSlot = true
                )
                updateState(Phase.FAILED, statusText = "Emergency rescue guide missing", failure = failure)
                log("[EmergencyRescue CRITICAL] ${rescueRes.exceptionOrNull()?.message}")
                return
            }
            log("[EmergencyRescue] Fastboot rescue guide generated at ${rescueRes.getOrNull()?.absolutePath}")

            // Reached READY_TO_REBOOT (Gate 2)
            updateState(Phase.READY_TO_REBOOT, statusText = "Ready for Gate 2 reboot confirmation.")
            log("[Chain B Complete] Modules disabled, root verified on the inactive slot, rescue guide written. Ready to reboot.")
        } catch (e: Exception) {
            log("[Chain B Post-Flash Exception] ${e.message}")
            val failure = FailureInfo(
                phase = Phase.FAILED,
                title = "Post-Flash Operation Failed",
                message = e.message ?: "Unexpected error after flashing",
                actionableResolution = "Check logs. Revert slot switch if necessary.",
                canRollbackSlot = true
            )
            updateState(Phase.FAILED, statusText = "Exception after flashing", failure = failure)
        }
    }

    // ==========================================
    // RESUME AFTER APP RESTART
    // ==========================================

    private fun resumeFlashing() {
        if (!chainBBusy.compareAndSet(false, true)) {
            log("[Resume] Another operation is already running; skipping auto-resume.")
            return
        }

        scope.launch(dispatchers.io) {
            try {
                log("[Resume] App restarted while flashing. Re-attaching to update_engine (--follow)...")
                startApplyService()
                updateState(Phase.FLASHING, statusText = "Re-attaching to update_engine (--follow)...")

                val immediate = updateEngineController.probeCurrentStatus()
                val statusName = immediate?.first ?: ""
                log("[Resume] update_engine status: ${statusName.ifBlank { "unknown" }}")

                when {
                    statusName == "UPDATED_NEED_REBOOT" -> {
                        log("[Resume] Payload already applied. Continuing with root patch...")
                        runRootPatchAndArm()
                    }
                    statusName.isBlank() || statusName == "IDLE" -> {
                        val failure = FailureInfo(
                            phase = Phase.FAILED,
                            title = "Update Interrupted",
                            message = "The app was killed during flashing and update_engine reports no applied update (status: ${statusName.ifBlank { "unknown" }}).",
                            actionableResolution = "Tap 'Start Update' to verify and flash again, or 'Revert Slot Switch' to cancel the pending session."
                        )
                        updateState(Phase.FAILED, statusText = "Update interrupted", failure = failure)
                    }
                    else -> {
                        log("[Resume] Update still in progress on the daemon. Following until final state...")
                        val success = streamEngineEvents(updateEngineController.followStatus())
                        if (success && _progress.value.phase != Phase.FAILED) {
                            runRootPatchAndArm()
                        }
                    }
                }
            } catch (e: Exception) {
                log("[Resume Exception] ${e.message}")
                val failure = FailureInfo(
                    phase = Phase.FAILED,
                    title = "Resume Failed",
                    message = e.message ?: "Unexpected error while resuming the update",
                    actionableResolution = "Check the logs, then retry or revert the slot switch.",
                    canRollbackSlot = true
                )
                updateState(Phase.FAILED, statusText = "Resume failed", failure = failure)
            } finally {
                stopApplyService()
                chainBBusy.set(false)
            }
        }
    }

    private fun resumeAfterFlash(savedPhase: Phase) {
        if (!chainBBusy.compareAndSet(false, true)) {
            log("[Resume] Another operation is already running; skipping auto-resume.")
            return
        }

        scope.launch(dispatchers.io) {
            try {
                // These phases only exist after a payload was applied, so the daemon must still be
                // waiting for the reboot. If it is not, the device was rebooted (or the session was
                // reset) while the app was gone: patching the inactive slot now would root the wrong
                // partition and report a readiness that does not exist, so it is refused and the user
                // decides between finishing the post-boot chain and starting over.
                val status = updateEngineController.probeCurrentStatus()?.first.orEmpty()
                if (status != "UPDATED_NEED_REBOOT") {
                    val failure = FailureInfo(
                        // Classified as a post-boot problem on purpose: Safe Mode must stay armed and
                        // the update session must not be reset while the snapshots of a boot into the
                        // new slot may still be merging.
                        phase = Phase.POSTBOOT_VERIFY,
                        title = "Update Session No Longer Pending",
                        message = "The app was interrupted in ${savedPhase.name} and update_engine now " +
                            "reports '${status.ifBlank { "unknown" }}', so no payload is waiting to be " +
                            "rooted. Every KernelSU module stays disabled until this is resolved.",
                        actionableResolution = "If the device already rebooted into the new build, tap " +
                            "'Resume' to verify the boot and finish the snapshot merge. If it is still on " +
                            "the old build, the update has to be started again, and the modules can be " +
                            "re-enabled from this screen only after the device state is confirmed.",
                        canRetry = true,
                        canRollbackSlot = false
                    )
                    updateState(Phase.FAILED, statusText = "Update session no longer pending", failure = failure)
                    log("[Resume] Refusing to patch: update_engine reports '${status.ifBlank { "unknown" }}'.")
                    return@launch
                }

                // runRootPatchAndArm() decides from the persisted stock backup whether the inactive
                // slot still needs patching, so a resumed run never patches twice.
                log("[Resume] Continuing after ${savedPhase.name}...")
                runRootPatchAndArm()
            } finally {
                chainBBusy.set(false)
            }
        }
    }

    // ==========================================
    // GATE 2 & CHAIN C: REBOOT & POST-BOOT MERGE
    // ==========================================

    fun executeGate2Reboot() {
        scope.launch(dispatchers.io) {
            if (_progress.value.phase != Phase.READY_TO_REBOOT) {
                log("[Gate 2] Ignored: pipeline is not ready for reboot.")
                return@launch
            }

            log("[Gate 2] Reboot initiated by user. Persisting AWAITING_BOOT state...")
            updateState(Phase.AWAITING_BOOT, statusText = "Rebooting device...")
            storage.savePhase(Phase.AWAITING_BOOT)

            val rebooted = rootProvider.reboot()
            if (!rebooted) {
                log("[Gate 2] WARNING: reboot command did not report success. Reboot manually if the device stays on.")
            }
        }
    }

    fun startChainC() {
        val currentPhase = _progress.value.phase
        if (currentPhase != Phase.AWAITING_BOOT && currentPhase != Phase.POSTBOOT_VERIFY && currentPhase != Phase.MERGING) {
            log("[Chain C] Ignored: pipeline is in ${currentPhase.name}, not a post-boot state.")
            return
        }

        if (!chainCBusy.compareAndSet(false, true)) {
            log("[Chain C] Already running; ignoring duplicate start.")
            return
        }

        scope.launch(dispatchers.io) {
            try {
                log("[Chain C] Verifying post-boot system state...")
                updateState(Phase.POSTBOOT_VERIFY, statusText = "Verifying active slot and root status...")

                val target = _progress.value.target
                val postCheck = postBootVerifier.verify(
                    expectedSlot = activeTargetSlot.ifBlank { storage.pendingSlot.first() ?: "" },
                    expectedIncremental = target?.postBuildIncremental,
                    expectedBuildDateUtc = target?.postTimestamp ?: 0L
                )

                postCheck.warnings.forEach { warning -> log("[PostBoot Warning] $warning") }

                if (!postCheck.isSuccess) {
                    val failure = FailureInfo(
                        phase = Phase.POSTBOOT_VERIFY,
                        title = "Post-Boot Verification Issue",
                        message = postCheck.detail,
                        actionableResolution = "Safe Mode stays armed until the update is confirmed. Inspect the slots and the KernelSU app, then resume verification."
                    )
                    updateState(Phase.FAILED, statusText = "Post-boot verification failed", failure = failure)
                    log("[PostBoot Error] ${postCheck.detail}")
                    return@launch
                }

                log("[PostBoot Verified] ${postCheck.detail}")

                // MERGING: never offer Safe-Mode clear until the merge is confirmed.
                updateState(Phase.MERGING, statusText = "Monitoring background Virtual A/B snapshot merge...")
                log("[SnapshotMerge] Waiting for update_engine to confirm the snapshot merge...")

                var evidence = SnapshotMergeMonitor.MergeEvidence(
                    mergeConfirmed = false,
                    snapshotsIdle = false,
                    detail = "Not started"
                )
                var attempts = 0
                while (!evidence.complete && attempts < MERGE_ATTEMPTS) {
                    attempts++
                    evidence = snapshotMergeMonitor.awaitMergeComplete { text ->
                        log("[SnapshotMerge] $text")
                        updateState(Phase.MERGING, statusText = text)
                    }
                    if (!evidence.complete) {
                        log("[SnapshotMerge] Attempt $attempts/$MERGE_ATTEMPTS did not confirm the merge: ${evidence.detail}")
                    }
                }

                if (!evidence.complete) {
                    // Stay in a resumable MERGING state instead of a dead end: Safe Mode must remain
                    // armed until update_engine confirms, but the user needs a way to keep monitoring.
                    val message = if (evidence.snapshotsIdle) {
                        "${evidence.detail} Safe Mode stays armed. You can resume monitoring, or remove Safe Mode " +
                            "manually only if you are certain the merge has finished."
                    } else {
                        "${evidence.detail} The merge is still running. Keep the device idle and charging, then resume monitoring."
                    }
                    val failure = FailureInfo(
                        phase = Phase.MERGING,
                        title = "Snapshot Merge Not Confirmed",
                        message = message,
                        actionableResolution = "Tap 'Resume Merge Monitoring'. Do NOT re-enable modules before the merge is confirmed.",
                        canRetry = true,
                        canRollbackSlot = false
                    )
                    updateState(Phase.MERGING, statusText = "Snapshot merge not confirmed yet", failure = failure)
                    log("[SnapshotMerge CRITICAL] $message")
                    return@launch
                }

                // SAFE_MODE_CLEAR
                updateState(Phase.SAFE_MODE_CLEAR, statusText = "Snapshot merge complete. Safe Mode active.")
                log("[Merge Complete] Virtual A/B snapshots merged and confirmed by update_engine. Safe Mode is still active.")
            } finally {
                chainCBusy.set(false)
            }
        }
    }

    /**
     * Re-runs post-boot verification and merge monitoring from a resumable post-boot state, for
     * example after the app was killed while a merge was still running.
     */
    fun retryChainC() {
        val phase = _progress.value.phase
        if (phase != Phase.MERGING && phase != Phase.POSTBOOT_VERIFY && phase != Phase.AWAITING_BOOT && phase != Phase.FAILED) {
            log("[Chain C] Cannot resume from ${phase.name}.")
            return
        }

        log("[Chain C] Resuming post-boot verification and merge monitoring...")
        updateState(Phase.POSTBOOT_VERIFY, statusText = "Re-checking post-boot state...")
        startChainC()
    }

    /**
     * Removes the global flag after the merge was confirmed, or — only when the user explicitly
     * accepted the risk from the advanced dialog — when update_engine could not confirm the merge.
     *
     * The individual modules deliberately stay disabled: they are re-enabled by the user in KernelSU
     * Next, one by one, so an incompatible hook cannot crash the first boot or the merge.
     */
    fun confirmRemoveSafeMode(forced: Boolean = false) {
        scope.launch(dispatchers.io) {
            if (forced) {
                log("[SafeMode] ADVANCED OVERRIDE: clearing the flag without an update_engine merge confirmation.")
            }
            log("[SafeMode] User confirmed. Clearing the global flag (modules stay disabled until re-enabled in KernelSU Next)...")
            log(
                "[SafeMode] Note: if a pending module update was parked before the first boot, it is waiting at " +
                    "/data/adb/modules_update.ota_parked; move it back to /data/adb/modules_update to let KernelSU install it."
            )

            val cleared = moduleSafety.clearGlobalFlagAfterMerge()
            if (!cleared) {
                log("[SafeMode] WARNING: /data/adb/disable could not be removed.")
            }

            updateState(Phase.CLEANUP, statusText = "Reclaiming /data/ota_package temporary space...")
            cleanupUseCase.cleanupPayload()

            storage.clear()
            updateState(
                Phase.DONE,
                statusText = "Update complete. Modules are still disabled — re-enable them one by one in KernelSU Next.",
                target = null
            )
            log("[DONE] Update finished. Modules remain disabled on purpose; re-enable them one by one in KernelSU Next.")
        }
    }

    /**
     * Finishes a cleanup that was interrupted (the phase is persisted, so a process death between the
     * safe-mode removal and the final state would otherwise leave the pipeline with no action at all).
     */
    fun finishCleanup() {
        scope.launch(dispatchers.io) {
            log("[Cleanup] Finishing the storage cleanup and closing the run...")
            updateState(Phase.CLEANUP, statusText = "Reclaiming /data/ota_package temporary space...")
            cleanupUseCase.cleanupPayload()
            notifier.cancelProgressNotification()
            storage.clear()
            updateState(
                Phase.DONE,
                statusText = "Update complete. Modules are still disabled — re-enable them one by one in KernelSU Next.",
                target = null
            )
            log("[DONE] Update finished. Modules remain disabled on purpose; re-enable them one by one in KernelSU Next.")
        }
    }

    /**
     * Reverts the pending slot switch and verifies that update_engine actually returned to IDLE.
     * Because the device keeps running its current build, the modules that were disabled for the
     * first boot are re-enabled again.
     */
    fun revertSlotSwitch() {
        scope.launch(dispatchers.io) {
            log("[Rollback] Reverting slot switch via update_engine_client --reset_status...")

            val resetOk = updateEngineController.resetStatus()
            val statusName = updateEngineController.probeCurrentStatus()?.first ?: ""
            val confirmed = resetOk && (statusName == "IDLE" || statusName.isBlank())

            if (!confirmed) {
                val failure = FailureInfo(
                    phase = Phase.ROOT_PATCH_FAILED,
                    title = "Slot Revert Not Confirmed",
                    message = "update_engine still reports '${statusName.ifBlank { "unknown" }}' after reset_status. The pending slot switch may still be armed.",
                    actionableResolution = "Tap 'Revert Slot Switch' again. If it keeps failing, use the emergency fastboot guide and do not reboot blindly.",
                    canRollbackSlot = false
                )
                updateState(Phase.ROOT_PATCH_FAILED, statusText = "Slot revert not confirmed", failure = failure)
                log("[Rollback] WARNING: slot revert not confirmed (exitOk=$resetOk, status=$statusName).")
                return@launch
            }

            val restoreMessage = restoreModulesForAbort()
            storage.clear()
            updateState(Phase.IDLE, statusText = "Update aborted. Device remains on current working slot. $restoreMessage")
            log("[Rollback] Slot switch canceled and verified (update_engine IDLE). Device is safe. $restoreMessage")
        }
    }

    /**
     * Safety valve for Gate 2: aborts before rebooting, reverts the slot switch, restores the modules
     * the user had enabled and clears the global flag.
     */
    fun abortBeforeReboot() {
        scope.launch(dispatchers.io) {
            log("[Abort] Aborting before reboot: reverting slot switch and restoring modules...")

            val resetOk = updateEngineController.resetStatus()
            val statusName = updateEngineController.probeCurrentStatus()?.first ?: ""

            val confirmed = resetOk && (statusName == "IDLE" || statusName.isBlank())
            if (!confirmed) {
                val failure = FailureInfo(
                    phase = Phase.ROOT_PATCH_FAILED,
                    title = "Abort Incomplete",
                    message = "The slot switch could not be reverted (update_engine reports '${statusName.ifBlank { "unknown" }}'). Modules stay disabled and Safe Mode is kept armed to protect the first boot.",
                    actionableResolution = "Tap 'Revert Slot Switch' again before rebooting the device.",
                    canRollbackSlot = false
                )
                updateState(Phase.ROOT_PATCH_FAILED, statusText = "Abort not confirmed", failure = failure)
                log("[Abort] WARNING: slot revert not confirmed (exitOk=$resetOk, status=$statusName). Modules stay disabled.")
                return@launch
            }

            val restoreMessage = restoreModulesForAbort()
            notifier.cancelProgressNotification()
            storage.clear()
            updateState(Phase.IDLE, statusText = "Update aborted before reboot. Slot switch reverted. $restoreMessage", target = null)
            log("[Abort] Device remains on the current working slot. $restoreMessage")
        }
    }

    /**
     * Re-enables the modules that were disabled for the first boot, and reports honestly when that did
     * not work, instead of claiming the user's setup is back.
     */
    private suspend fun restoreModulesForAbort(): String {
        val ids = disabledModuleIds.ifEmpty { storage.disabledModules.first() }
        val report = moduleSafety.restoreModulesAfterAbort(ids)

        return when {
            ids.isEmpty() -> {
                disabledModuleIds = emptyList()
                storage.saveDisabledModules(emptyList())
                "No modules had to be re-enabled."
            }
            report.isComplete -> {
                disabledModuleIds = emptyList()
                storage.saveDisabledModules(emptyList())
                "Re-enabled ${report.restored.size} module(s)."
            }
            else -> {
                // Keep the record: the user can retry, and the next run must still know what to restore.
                disabledModuleIds = report.failed
                storage.saveDisabledModules(report.failed)
                "WARNING: these modules could not be re-enabled: ${report.failed.joinToString(", ")}. " +
                    "Enable them manually in KernelSU Next."
            }
        }
    }

    fun resetToIdle() {
        val jobToCancel = chainAJob
        chainAJob = null
        jobToCancel?.cancel()
        stopApplyService()

        scope.launch(dispatchers.io) {
            jobToCancel?.join()
            val phaseBeforeReset = _progress.value.phase
            log("[Reset] Pipeline reset to IDLE state.")

            // Chain A's --allocate has already prepared Virtual A/B snapshots and reserved COW space.
            // Releasing it here keeps an abandoned run from leaving that state (and the reservation)
            // behind. It is only safe before the payload was applied and before a merge is running.
            val mayReleaseSession = phaseBeforeReset in listOf(
                Phase.CHECKING,
                Phase.PREFLIGHT,
                Phase.DOWNLOADING,
                Phase.EXTRACTING,
                Phase.VERIFYING,
                Phase.ARMED,
                Phase.FAILED
            )
            if (mayReleaseSession) {
                log("[Reset] Releasing the prepared update session (update_engine_client --reset_status)...")
                updateEngineController.resetStatus()
            }

            // The staged payload is only removed in states that have neither an applied payload nor a
            // running daemon session. Leaving ~7 GB in /data/ota_package behind would otherwise eat
            // into the free space the next run's preflight check depends on.
            val failureBeforeReset = _progress.value.failure
            val mayCleanStaging = phaseBeforeReset in listOf(
                Phase.CHECKING,
                Phase.PREFLIGHT,
                Phase.DOWNLOADING,
                Phase.EXTRACTING,
                Phase.VERIFYING,
                Phase.ARMED,
                Phase.DONE,
                Phase.CLEANUP
            ) || (
                phaseBeforeReset == Phase.FAILED &&
                    failureBeforeReset?.canRollbackSlot != true &&
                    failureBeforeReset?.phase != Phase.POSTBOOT_VERIFY &&
                    failureBeforeReset?.phase != Phase.MERGING
            )
            if (mayCleanStaging) {
                log("[Reset] Removing the staged payload from /data/ota_package...")
                cleanupUseCase.cleanupPayload()
                try {
                    context.getExternalFilesDir("ota")?.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            file.delete()
                            log("[Reset] Removed downloaded package file: ${file.name}")
                        }
                    }
                } catch (e: Exception) {
                    log("[Reset] Failed to clean external ota files: ${e.message}")
                }
            }

            // Gate 1's first action is disabling every KernelSU module, so a run abandoned from ARMED
            // (or from a pre-flash failure) can leave them all switched off. Clearing the record without
            // putting them back would leave the user with dead modules and nothing left that remembers
            // which ones were on. Restoring is only safe while nothing has been written to the inactive
            // slot, and a completed update is deliberately excluded: modules stay disabled after a
            // success so they can be re-enabled and tested one by one in KernelSU Next.
            val mayRestoreModules = phaseBeforeReset == Phase.ARMED ||
                (
                    phaseBeforeReset == Phase.FAILED &&
                        failureBeforeReset?.canRollbackSlot != true &&
                        failureBeforeReset?.phase != Phase.POSTBOOT_VERIFY &&
                        failureBeforeReset?.phase != Phase.MERGING
                    )

            if (mayRestoreModules) {
                log("[Reset] ${restoreModulesForAbort()}")
            } else if (disabledModuleIds.isNotEmpty() || storage.disabledModules.first().isNotEmpty()) {
                log(
                    "[Reset] Modules stay disabled: the inactive slot may already carry the payload. " +
                        "Re-enable them one by one in KernelSU Next only after the update is finished."
                )
            }

            notifier.cancelProgressNotification()
            storage.clear()
            updateState(Phase.IDLE, statusText = "Ready", target = null)
        }
    }
}
