package featurecat.lizzie.logging;

import featurecat.lizzie.Config;
import featurecat.lizzie.util.Utils;
import featurecat.lizzie.util.katago.tuning.KataGoCommandSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured engine-startup facts for {@link EngineObservation}. Values are log tokens only: no
 * full command lines and no absolute paths.
 *
 * <p>Bundled vs user-configured comes from {@link Config#isBundledKataGoCommand}. Backend detection
 * reuses the same NVIDIA path markers, OpenCL marker file, and {@code
 * lizzieyzy-next-engine-backend.txt} conventions as {@code KataGoRuntimeHelper}.
 */
public final class EngineBootstrapFacts {
  public static final String UNKNOWN = "unknown";
  public static final String SOURCE_BUNDLED = "bundled";
  public static final String SOURCE_USER_CONFIGURED = "user-configured";

  private static final int TOKEN_MAX_LENGTH = 96;
  private static final String ENGINE_BACKEND_MARKER_NAME = "lizzieyzy-next-engine-backend.txt";
  private static final String NVIDIA_ENGINE_DIR = "windows-x64-nvidia";
  private static final String NVIDIA50_CUDA_ENGINE_DIR = "windows-x64-nvidia50-cuda";
  private static final String NVIDIA_TRT_ENGINE_DIR = "windows-x64-nvidia-tensorrt";
  private static final String NVIDIA50_TRT_ENGINE_DIR = "windows-x64-nvidia50-trt";
  private static final String NVIDIA_BACKEND = "nvidia";
  private static final String NVIDIA50_CUDA_BACKEND = "nvidia50-cuda";
  private static final String NVIDIA_TRT_BACKEND = "nvidia-tensorrt";
  private static final String NVIDIA50_TRT_BACKEND = "nvidia50-trt";
  private static final String OPENCL_BACKEND = "opencl";
  private static final String HUMAN_SL_CUDA_COMPANION_NAME = "katago-human-sl-cuda.exe";
  private static final Pattern ONNX_PROVIDER =
      Pattern.compile("(?i)(?:^|[,\\s])onnxProvider=([^,\\s\"]+)");
  private static final Pattern SAFE_TOKEN = Pattern.compile("[^A-Za-z0-9._+-]+");

  private final String engineType;
  private final String version;
  private final String purpose;
  private final String source;
  private final String backend;
  private final String onnxProvider;
  private final String model;
  private final String config;

  private EngineBootstrapFacts(
      String engineType,
      String version,
      String purpose,
      String source,
      String backend,
      String onnxProvider,
      String model,
      String config) {
    this.engineType = engineType;
    this.version = version;
    this.purpose = purpose;
    this.source = source;
    this.backend = backend;
    this.onnxProvider = onnxProvider;
    this.model = model;
    this.config = config;
  }

  public static EngineBootstrapFacts unknown(String purpose) {
    return new EngineBootstrapFacts(
        UNKNOWN,
        UNKNOWN,
        safePurpose(purpose),
        SOURCE_USER_CONFIGURED,
        UNKNOWN,
        UNKNOWN,
        UNKNOWN,
        UNKNOWN);
  }

  public static EngineBootstrapFacts of(
      String engineType,
      String version,
      String purpose,
      String source,
      String backend,
      String onnxProvider,
      String model,
      String config) {
    return new EngineBootstrapFacts(
        safeToken(engineType),
        safeToken(version),
        safePurpose(purpose),
        safeToken(source),
        safeToken(backend),
        safeToken(onnxProvider),
        safeToken(model),
        safeToken(config));
  }

  public static EngineBootstrapFacts fromCommand(String command, String purpose) {
    try {
      return parseCommand(command, purpose);
    } catch (RuntimeException ignored) {
      return unknown(purpose);
    } catch (Error error) {
      if (error instanceof VirtualMachineError) {
        throw error;
      }
      return unknown(purpose);
    }
  }

  public String engineType() {
    return engineType;
  }

  public String version() {
    return version;
  }

  public String purpose() {
    return purpose;
  }

  public String source() {
    return source;
  }

  public String backend() {
    return backend;
  }

  public String onnxProvider() {
    return onnxProvider;
  }

  public String model() {
    return model;
  }

  public String config() {
    return config;
  }

  String formatLogLine(String stageFragment) {
    StringBuilder line = new StringBuilder("engine event=bootstrap");
    appendField(line, "engineType", engineType);
    appendField(line, "version", version);
    appendField(line, "purpose", purpose);
    appendField(line, "source", source);
    appendField(line, "backend", backend);
    appendField(line, "onnxProvider", onnxProvider);
    appendField(line, "model", model);
    appendField(line, "config", config);
    if (stageFragment != null && !stageFragment.isEmpty()) {
      line.append(stageFragment);
    }
    return line.toString();
  }

  private static EngineBootstrapFacts parseCommand(String command, String purpose) {
    List<String> parts = Utils.splitCommand(command == null ? "" : command);
    boolean bundled = Config.isBundledKataGoCommand(command);
    String source = bundled ? SOURCE_BUNDLED : SOURCE_USER_CONFIGURED;
    Path executable = executablePath(parts);
    String onnxProvider = resolveOnnxProvider(parts, command, executable);
    String backend = resolveBackend(executable, onnxProvider);
    return new EngineBootstrapFacts(
        inferEngineType(parts, bundled),
        UNKNOWN,
        safePurpose(purpose),
        source,
        backend,
        onnxProvider,
        fileIdentity(optionValue(parts, "-model", "--model", "-weights", "--weights")),
        fileIdentity(optionValue(parts, "-config", "--config")));
  }

  private static Path executablePath(List<String> parts) {
    if (parts == null || parts.isEmpty() || parts.get(0) == null || parts.get(0).trim().isEmpty()) {
      return null;
    }
    try {
      return Path.of(parts.get(0).trim());
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static String inferEngineType(List<String> parts, boolean bundled) {
    if (bundled) {
      return "katago";
    }
    if (parts == null || parts.isEmpty()) {
      return UNKNOWN;
    }
    String executable = fileIdentity(parts.get(0)).toLowerCase(Locale.ROOT);
    if (executable.contains("katago")) {
      return "katago";
    }
    if (executable.contains("leelaz") || executable.contains("leela")) {
      return "leela";
    }
    return UNKNOWN;
  }

  private static String resolveBackend(Path executable, String onnxProvider) {
    String nvidia = resolveNvidiaBackendFromPath(executable);
    if (nvidia != null) {
      return nvidia;
    }
    String marker = readBackendMarker(executable);
    if (OPENCL_BACKEND.equals(marker)) {
      return OPENCL_BACKEND;
    }
    if (isOnnxExecutionProvider(marker)) {
      return "onnx";
    }
    if (!UNKNOWN.equals(marker)) {
      return marker;
    }
    if (isOnnxExecutionProvider(onnxProvider)) {
      return "onnx";
    }
    return UNKNOWN;
  }

  /**
   * Mirrors {@code KataGoRuntimeHelper.resolveNvidiaBackend} path and marker rules without loading
   * that class from logging tests.
   */
  private static String resolveNvidiaBackendFromPath(Path enginePath) {
    if (enginePath == null) {
      return null;
    }
    Path fileName = enginePath.getFileName();
    if (fileName != null && HUMAN_SL_CUDA_COMPANION_NAME.equalsIgnoreCase(fileName.toString())) {
      return NVIDIA50_CUDA_BACKEND;
    }
    String normalized = enginePath.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    if (normalized.contains("/" + NVIDIA_TRT_ENGINE_DIR + "/")
        || normalized.contains("/" + NVIDIA50_TRT_ENGINE_DIR + "/")) {
      return NVIDIA_TRT_BACKEND;
    }
    if (normalized.contains("/" + NVIDIA50_CUDA_ENGINE_DIR + "/")) {
      return NVIDIA50_CUDA_BACKEND;
    }
    if (normalized.contains("/" + NVIDIA_ENGINE_DIR + "/")) {
      return NVIDIA_BACKEND;
    }
    String marker = readBackendMarker(enginePath);
    if (NVIDIA_TRT_BACKEND.equals(marker) || NVIDIA50_TRT_BACKEND.equals(marker)) {
      return NVIDIA_TRT_BACKEND;
    }
    if (NVIDIA50_CUDA_BACKEND.equals(marker)) {
      return NVIDIA50_CUDA_BACKEND;
    }
    if (NVIDIA_BACKEND.equals(marker)) {
      return NVIDIA_BACKEND;
    }
    if (marker.startsWith("nvidia")) {
      return marker;
    }
    return null;
  }

  private static String resolveOnnxProvider(List<String> parts, String command, Path executable) {
    String fromOverride = overrideOnnxProvider(parts);
    if (!UNKNOWN.equals(fromOverride)) {
      return fromOverride;
    }
    Matcher matcher = ONNX_PROVIDER.matcher(command == null ? "" : command);
    if (matcher.find()) {
      return safeToken(matcher.group(1));
    }
    String marker = readBackendMarker(executable);
    if (isOnnxExecutionProvider(marker)) {
      return marker;
    }
    return UNKNOWN;
  }

  private static String overrideOnnxProvider(List<String> parts) {
    if (parts == null || parts.isEmpty()) {
      return UNKNOWN;
    }
    try {
      Map<String, String> overrides = KataGoCommandSpec.parse(parts).effectiveOverrides();
      for (Map.Entry<String, String> entry : overrides.entrySet()) {
        if (entry.getKey() != null && "onnxprovider".equalsIgnoreCase(entry.getKey().trim())) {
          return safeToken(entry.getValue());
        }
      }
    } catch (RuntimeException ignored) {
    }
    return UNKNOWN;
  }

  private static String readBackendMarker(Path executable) {
    if (executable == null) {
      return UNKNOWN;
    }
    try {
      Path engineDir = executable.toAbsolutePath().normalize().getParent();
      if (engineDir == null) {
        return UNKNOWN;
      }
      Path markerPath = engineDir.resolve(ENGINE_BACKEND_MARKER_NAME);
      if (!Files.isRegularFile(markerPath)) {
        return UNKNOWN;
      }
      String marker =
          Files.readString(markerPath, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
      return marker.isEmpty() ? UNKNOWN : safeToken(marker);
    } catch (Exception ignored) {
      return UNKNOWN;
    }
  }

  private static boolean isOnnxExecutionProvider(String value) {
    if (value == null || UNKNOWN.equals(value)) {
      return false;
    }
    String normalized = value.toLowerCase(Locale.ROOT);
    return "directml".equals(normalized)
        || "openvino".equals(normalized)
        || "openvino-npu".equals(normalized);
  }

  private static String optionValue(List<String> parts, String... flags) {
    if (parts == null || flags == null) {
      return null;
    }
    for (int i = 0; i < parts.size(); i++) {
      String token = parts.get(i);
      if (token == null) {
        continue;
      }
      for (String flag : flags) {
        if (flag.equals(token)) {
          return i + 1 < parts.size() ? parts.get(i + 1) : null;
        }
        String prefix = flag + "=";
        if (token.startsWith(prefix)) {
          return token.substring(prefix.length());
        }
      }
    }
    return null;
  }

  static String fileIdentity(String raw) {
    if (raw == null) {
      return UNKNOWN;
    }
    String trimmed = stripQuotes(raw.trim());
    if (trimmed.isEmpty()) {
      return UNKNOWN;
    }
    String normalized = trimmed.replace('\\', '/');
    int separator = normalized.lastIndexOf('/');
    String fileName = separator >= 0 ? normalized.substring(separator + 1) : normalized;
    return safeToken(fileName);
  }

  static String safeToken(String raw) {
    if (raw == null) {
      return UNKNOWN;
    }
    String trimmed = stripQuotes(raw.trim());
    if (trimmed.isEmpty()) {
      return UNKNOWN;
    }
    String collapsed = SAFE_TOKEN.matcher(trimmed).replaceAll("_");
    while (collapsed.startsWith("_")) {
      collapsed = collapsed.substring(1);
    }
    while (collapsed.endsWith("_")) {
      collapsed = collapsed.substring(0, collapsed.length() - 1);
    }
    if (collapsed.isEmpty() || ".".equals(collapsed) || "..".equals(collapsed)) {
      return UNKNOWN;
    }
    if (collapsed.length() > TOKEN_MAX_LENGTH) {
      collapsed = collapsed.substring(0, TOKEN_MAX_LENGTH);
    }
    return collapsed;
  }

  private static String safePurpose(String purpose) {
    String token = safeToken(purpose);
    return UNKNOWN.equals(token) ? UNKNOWN : token;
  }

  private static String stripQuotes(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if (first == last && (first == '"' || first == '\'')) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }

  private static void appendField(StringBuilder line, String name, String value) {
    line.append(' ').append(name).append('=').append(value == null ? UNKNOWN : value);
  }
}
