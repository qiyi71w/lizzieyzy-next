package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.EngineBootstrapFacts;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.Utils;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Builds engine bootstrap facts using bundled/backend helpers already used at launch. */
final class EngineStartupBootstrap {
  private EngineStartupBootstrap() {}

  static EngineBootstrapFacts factsFor(String command, String purpose) {
    EngineBootstrapFacts parsed = EngineBootstrapFacts.fromCommand(command, purpose);
    try {
      List<String> parts = Utils.splitCommand(command == null ? "" : command);
      Path executable = KataGoRuntimeHelper.resolveCommandExecutable(parts);
      String nvidia = KataGoRuntimeHelper.resolveNvidiaBackend(executable);
      String backend = parsed.backend();
      String onnxProvider = parsed.onnxProvider();
      if (nvidia != null && !nvidia.isEmpty()) {
        backend = nvidia;
      } else if (KataGoRuntimeHelper.isBundledOpenClPath(executable)) {
        backend = "opencl";
      } else {
        String marker = KataGoRuntimeHelper.readEngineBackendMarker(executable);
        if (marker != null && !marker.trim().isEmpty()) {
          String normalized = marker.trim().toLowerCase(Locale.ROOT);
          if ("opencl".equals(normalized)) {
            backend = "opencl";
          } else if ("directml".equals(normalized)
              || "openvino".equals(normalized)
              || "openvino-npu".equals(normalized)) {
            backend = "onnx";
            if (EngineBootstrapFacts.UNKNOWN.equals(onnxProvider)) {
              onnxProvider = normalized;
            }
          }
        }
      }
      if (backend.equals(parsed.backend()) && onnxProvider.equals(parsed.onnxProvider())) {
        return parsed;
      }
      return EngineBootstrapFacts.of(
          parsed.engineType(),
          parsed.version(),
          parsed.purpose(),
          parsed.source(),
          backend,
          onnxProvider,
          parsed.model(),
          parsed.config());
    } catch (RuntimeException ignored) {
      return parsed;
    } catch (Error error) {
      if (error instanceof VirtualMachineError) {
        throw error;
      }
      return parsed;
    }
  }
}
