package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.NvidiaGpuDetector.CudaCompatibility;
import featurecat.lizzie.util.NvidiaGpuDetector.DetectionResult;
import featurecat.lizzie.util.NvidiaGpuDetector.GpuInfo;
import featurecat.lizzie.util.NvidiaGpuDetector.TensorRtRecommendation;
import java.lang.reflect.Constructor;
import java.util.List;
import org.junit.jupiter.api.Test;

class NvidiaGpuDetectorTest {
  @Test
  void driverPolicyUsesOfficialCuda128CompatibilityThresholds() {
    assertEquals(CudaCompatibility.SUPPORTED, NvidiaGpuDetector.cudaCompatibility("570.65"));
    assertEquals(CudaCompatibility.SUPPORTED, NvidiaGpuDetector.cudaCompatibility("576.80"));
    assertEquals(CudaCompatibility.PROBE_REQUIRED, NvidiaGpuDetector.cudaCompatibility("560.76"));
    assertEquals(CudaCompatibility.PROBE_REQUIRED, NvidiaGpuDetector.cudaCompatibility("528.33"));
    assertEquals(CudaCompatibility.UNSUPPORTED, NvidiaGpuDetector.cudaCompatibility("528.32"));
    assertEquals(CudaCompatibility.UNKNOWN, NvidiaGpuDetector.cudaCompatibility("N/A"));
  }

  @Test
  void tensorRtIsOptionalForRtx30AndEarlierButNotModernRtx() {
    assertEquals(
        TensorRtRecommendation.ALLOWED,
        NvidiaGpuDetector.recommend(gpu("NVIDIA GeForce RTX 3070", 8, 6)));
    assertEquals(
        TensorRtRecommendation.ALLOWED,
        NvidiaGpuDetector.recommend(gpu("NVIDIA GeForce RTX 2080", 7, 5)));
    assertEquals(
        TensorRtRecommendation.ALLOWED,
        NvidiaGpuDetector.recommend(gpu("NVIDIA GeForce GTX 1660", 7, 5)));
    assertEquals(
        TensorRtRecommendation.NOT_RECOMMENDED,
        NvidiaGpuDetector.recommend(gpu("NVIDIA GeForce RTX 4090", 8, 9)));
    assertEquals(
        TensorRtRecommendation.NOT_RECOMMENDED,
        NvidiaGpuDetector.recommend(gpu("NVIDIA GeForce RTX 5090", 12, 0)));
    assertEquals(
        TensorRtRecommendation.NOT_RECOMMENDED,
        NvidiaGpuDetector.recommend(gpu("NVIDIA GeForce GTX 1080", 6, 1)));
  }

  @Test
  void tensorRtHardwareEligibilityUsesMinimumInsteadOfRecommendation() {
    assertFalse(NvidiaGpuDetector.supportsTensorRtHardware(detection(gpu("GTX 1050", 6, 1))));
    assertTrue(NvidiaGpuDetector.supportsTensorRtHardware(detection(gpu("GTX 1660", 7, 5))));
    assertTrue(NvidiaGpuDetector.supportsTensorRtHardware(detection(gpu("RTX 2080", 7, 5))));
    assertTrue(NvidiaGpuDetector.supportsTensorRtHardware(detection(gpu("RTX 3070", 8, 6))));
    assertTrue(NvidiaGpuDetector.supportsTensorRtHardware(detection(gpu("RTX 4090", 8, 9))));
    assertTrue(NvidiaGpuDetector.supportsTensorRtHardware(detection(gpu("RTX 5090", 12, 0))));
    assertTrue(NvidiaGpuDetector.supportsTensorRtHardware(detection(gpu("NVIDIA GPU", 0, 0))));
  }

  private static DetectionResult detection(GpuInfo gpu) {
    try {
      Constructor<DetectionResult> constructor =
          DetectionResult.class.getDeclaredConstructor(
              boolean.class,
              List.class,
              GpuInfo.class,
              TensorRtRecommendation.class,
              String.class,
              String.class);
      constructor.setAccessible(true);
      return constructor.newInstance(
          true, List.of(gpu), gpu, NvidiaGpuDetector.recommend(gpu), "test", "test");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static GpuInfo gpu(String name, int major, int minor) {
    return new GpuInfo(name, major, minor, "570.65", 8192L, "test");
  }
}
