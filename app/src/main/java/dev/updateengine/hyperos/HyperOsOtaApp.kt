package dev.updateengine.hyperos

import android.app.Application
import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.datastore.PipelineStorage
import dev.updateengine.hyperos.core.datastore.SettingsStorage
import dev.updateengine.hyperos.core.network.HyperDataApiClient
import dev.updateengine.hyperos.core.network.LocalPackageImporter
import dev.updateengine.hyperos.core.network.RemotePackageInspector
import dev.updateengine.hyperos.core.network.RomDownloader
import dev.updateengine.hyperos.core.notifications.NotificationChannels
import dev.updateengine.hyperos.core.notifications.OtaNotifier
import dev.updateengine.hyperos.core.ota.ChecksumVerifier
import dev.updateengine.hyperos.core.ota.CleanupUseCase
import dev.updateengine.hyperos.core.ota.EmergencyRescueWriter
import dev.updateengine.hyperos.core.ota.ModuleSafety
import dev.updateengine.hyperos.core.ota.PayloadStager
import dev.updateengine.hyperos.core.ota.PipelineRunner
import dev.updateengine.hyperos.core.ota.PostBootVerifier
import dev.updateengine.hyperos.core.ota.PreflightChecker
import dev.updateengine.hyperos.core.ota.RootPatchKeeper
import dev.updateengine.hyperos.core.ota.SnapshotMergeMonitor
import dev.updateengine.hyperos.core.ota.UpdateEngineController
import dev.updateengine.hyperos.core.root.KernelSuNextProvider
import dev.updateengine.hyperos.core.root.RootProvider
import dev.updateengine.hyperos.core.root.RootShell
import dev.updateengine.hyperos.core.root.SuRootShell
import dev.updateengine.hyperos.core.root.SystemProps

class HyperOsOtaApp : Application() {

    lateinit var dispatchers: AppDispatchers
    lateinit var rootShell: RootShell
    lateinit var systemProps: SystemProps
    lateinit var rootProvider: RootProvider
    lateinit var hyperDataClient: HyperDataApiClient
    lateinit var romDownloader: RomDownloader
    lateinit var packageInspector: RemotePackageInspector
    lateinit var localPackageImporter: LocalPackageImporter
    lateinit var preflightChecker: PreflightChecker
    lateinit var payloadStager: PayloadStager
    lateinit var checksumVerifier: ChecksumVerifier
    lateinit var updateEngineController: UpdateEngineController
    lateinit var rootPatchKeeper: RootPatchKeeper
    lateinit var moduleSafety: ModuleSafety
    lateinit var emergencyRescueWriter: EmergencyRescueWriter
    lateinit var snapshotMergeMonitor: SnapshotMergeMonitor
    lateinit var postBootVerifier: PostBootVerifier
    lateinit var cleanupUseCase: CleanupUseCase
    lateinit var pipelineStorage: PipelineStorage
    lateinit var settingsStorage: SettingsStorage
    lateinit var notifier: OtaNotifier
    lateinit var pipelineRunner: PipelineRunner

    override fun onCreate() {
        super.onCreate()
        instance = this

        NotificationChannels.createChannels(this)

        dispatchers = AppDispatchers()
        rootShell = SuRootShell(dispatchers)
        systemProps = SystemProps(rootShell, dispatchers)
        rootProvider = KernelSuNextProvider(rootShell, dispatchers)

        // The preflight checker reads the storage margin the user set, so the settings store has to
        // exist before the engine components are built.
        pipelineStorage = PipelineStorage(this)
        settingsStorage = SettingsStorage(this)

        hyperDataClient = HyperDataApiClient(dispatchers = dispatchers)
        romDownloader = RomDownloader(dispatchers = dispatchers)
        packageInspector = RemotePackageInspector(rootShell, dispatchers)
        localPackageImporter = LocalPackageImporter(this, packageInspector, dispatchers)

        preflightChecker = PreflightChecker(rootProvider, systemProps, settingsStorage, dispatchers)
        payloadStager = PayloadStager(rootShell, dispatchers)
        checksumVerifier = ChecksumVerifier(rootShell, dispatchers)
        updateEngineController = UpdateEngineController(rootShell, dispatchers)
        rootPatchKeeper = RootPatchKeeper(rootProvider, dispatchers)
        moduleSafety = ModuleSafety(rootProvider, dispatchers)
        emergencyRescueWriter = EmergencyRescueWriter(rootShell, dispatchers)
        snapshotMergeMonitor = SnapshotMergeMonitor(rootShell, dispatchers)
        postBootVerifier = PostBootVerifier(rootProvider, systemProps, dispatchers)
        cleanupUseCase = CleanupUseCase(rootShell, dispatchers)

        notifier = OtaNotifier(this)

        pipelineRunner = PipelineRunner(
            context = this,
            rootProvider = rootProvider,
            systemProps = systemProps,
            preflightChecker = preflightChecker,
            payloadStager = payloadStager,
            checksumVerifier = checksumVerifier,
            updateEngineController = updateEngineController,
            rootPatchKeeper = rootPatchKeeper,
            moduleSafety = moduleSafety,
            emergencyRescueWriter = emergencyRescueWriter,
            snapshotMergeMonitor = snapshotMergeMonitor,
            postBootVerifier = postBootVerifier,
            cleanupUseCase = cleanupUseCase,
            romDownloader = romDownloader,
            packageInspector = packageInspector,
            storage = pipelineStorage,
            settingsStorage = settingsStorage,
            notifier = notifier,
            dispatchers = dispatchers
        )
    }

    companion object {
        lateinit var instance: HyperOsOtaApp
            private set
    }
}
