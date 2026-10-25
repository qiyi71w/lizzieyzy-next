package featurecat.lizzie.gui;

import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.KataGoExperimentalBackendInstaller;
import featurecat.lizzie.util.KataGoExperimentalBackendInstaller.Backend;
import featurecat.lizzie.util.KataGoExperimentalBackendInstaller.Status;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper.NvidiaRuntimeStatus;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtInstallStatus;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRepairContext;
import featurecat.lizzie.util.NvidiaGpuDetector.DetectionResult;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Filesystem-backed runtime observations, collected once per setup refresh in a worker. */
record KataGoSetupRuntimeState(
    NvidiaRuntimeStatus nvidia,
    TensorRtInstallStatus tensorRt,
    Map<Backend, Status> experimental,
    long cacheBytes,
    boolean inputsReady,
    boolean windows,
    String benchmarkUnavailableReason,
    boolean appleTuningAvailable) {
  record Target(SetupSnapshot snapshot, DetectionResult gpu, TensorRtRepairContext repair) {}

  static KataGoSetupRuntimeState inspect(Target target) throws IOException {
    SetupSnapshot snapshot = target.snapshot();
    boolean windows = supportsExperimentalBackends(System.getProperty("os.name", ""));
    Map<Backend, Status> experimental = new EnumMap<>(Backend.class);
    for (Backend backend : Backend.values()) {
      experimental.put(
          backend,
          windows
              ? KataGoExperimentalBackendInstaller.inspect(snapshot, backend)
              : new Status(backend, null, false, false));
    }
    return new KataGoSetupRuntimeState(
        KataGoRuntimeHelper.inspectNvidiaRuntime(snapshot),
        KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, target.gpu(), target.repair()),
        Map.copyOf(experimental),
        KataGoRuntimeHelper.tensorRtDownloadCacheBytes(),
        snapshot != null && snapshot.hasConfigs() && snapshot.hasWeight(),
        windows,
        KataGoRuntimeHelper.benchmarkUnavailableReason(snapshot),
        KataGoRuntimeHelper.isExperimentalAppleSiliconTuningAvailable(snapshot));
  }

  static boolean supportsExperimentalBackends(String osName) {
    String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    return os.contains("win") && !os.contains("darwin");
  }

  boolean canInstallNvidiaRuntime() {
    return nvidia != null && nvidia.applicable && !nvidia.ready;
  }

  boolean canRepairTensorRt() {
    return tensorRt != null
        && tensorRt.gpuDetectionComplete
        && tensorRt.hardwareEligible
        && tensorRt.repairable
        && (!tensorRt.runtimeReady
            || !tensorRt.companionReady
            || !tensorRt.enginePresent
            || !tensorRt.engineCurrent);
  }

  boolean canActivateTensorRt() {
    return tensorRt != null
        && tensorRt.gpuDetectionComplete
        && tensorRt.hardwareEligible
        && tensorRt.activatable
        && !tensorRt.profileActive;
  }

  boolean canSwitchBackToCuda() {
    return inputsReady && tensorRt != null && tensorRt.applicable && tensorRt.active;
  }

  boolean canInstallExperimentalBackend(Backend backend) {
    Status status = experimental.get(backend);
    return windows && inputsReady && status != null && !status.active();
  }
}
