package featurecat.lizzie.util;

import java.io.IOException;
import java.util.Map;

/**
 * The CUDA 12.8 and cuDNN 9.8 packages TensorRT acceleration downloads, pinned by version and
 * SHA-256 as scripts/katago_cuda_dependencies.json pins them for the build. NVIDIA's redistrib JSON
 * only says where to find them; a digest read from it at install time would trust whatever it said
 * that day.
 */
final class NvidiaRuntimePins {
  private static final Map<String, String[]> PINS =
      Map.of(
          "cuda_cudart",
          new String[] {
            "12.8.57", "2c7aa62a195d79229d4381c8bd0174a30502cf3d8124c6e94ee50a7fc8a1e9f4"
          },
          "libcublas",
          new String[] {
            "12.8.3.14", "a2f990cf61f0086d942632f2455727240baa9378c2e9fa2bdda56ef81f6cf8ad"
          },
          "libnvjitlink",
          new String[] {
            "12.8.61", "8258afaadbd9744d87070525067c497041dd52c6b22d11ba7297421fc8f6f39d"
          },
          "cuda_nvrtc",
          new String[] {
            KataGoRuntimeHelper.CUDA_12_8_NVRTC_VERSION, KataGoRuntimeHelper.CUDA_12_8_NVRTC_SHA256
          },
          "cudnn",
          new String[] {
            "9.8.0.87", "d8a23705e3884b137b7e05449fb2b61bfa524e7cfc3fda80743d633f423c6ce4"
          });

  private NvidiaRuntimePins() {}

  /** Fails unless NVIDIA's metadata for {@code key} names the pinned version and digest. */
  static void require(String key, String version, String sha256) throws IOException {
    String[] pin = PINS.get(key);
    if (pin == null || !pin[0].equals(version) || !pin[1].equalsIgnoreCase(sha256)) {
      throw new IOException(
          "Unexpected NVIDIA "
              + key
              + " metadata; expected version "
              + (pin == null ? "?" : pin[0])
              + " and SHA-256 "
              + (pin == null ? "?" : pin[1]));
    }
  }
}
