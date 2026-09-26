package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.ExportSanitizer;
import featurecat.lizzie.logging.ObservationText;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable information for one failure window, its log entry and exported attachment. */
public final class EngineStartupDiagnostic {
  private final String json;
  private final String attemptId;
  private final String engineId;
  private final int sizeBytes;
  private static final int DEFAULT_MAX_BYTES = 256 * 1024;
  public static EngineStartupDiagnostic basic(List<String> commands, String command, String detail) {
    String resolvedCommand = "";
    if (command != null && !command.isBlank()) {
      resolvedCommand = command.trim();
    } else if (commands != null && !commands.isEmpty()) {
      resolvedCommand = String.join(" ", commands);
    }
    String resolvedDetail = detail == null ? "" : detail;
    boolean windows =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    JSONObject launch = new JSONObject();
    launch.put("engineId", "unknown");
    launch.put("launchPurpose", "unknown");
    launch.put("startedAt", "unavailable");
    launch.put("environmentState", "unavailable");
    launch.put("platform", windows ? "windows" : "non-windows");
    launch.put("configuredCommand", bounded(resolvedCommand, 16384));

    JSONObject sources = new JSONObject();
    sources.put(
        "basic",
        new JSONObject()
            .put("collectionState", "unavailable")
            .put("terminalReason", "")
            .put("checkedScope", "evidence-unavailable"));

    JSONObject value =
        new JSONObject()
            .put("attemptId", "failure-" + UUID.randomUUID())
            .put("engineId", "unknown")
            .put("launchPurpose", "unknown")
            .put("phase", "unknown")
            .put("originalError", bounded(resolvedDetail, 16384))
            .put("failedAt", "unavailable")
            .put("checkedAt", Instant.now().toString())
            .put("launch", launch)
            .put("exitCode", JSONObject.NULL)
            .put("exitHex", JSONObject.NULL)
            .put("statusName", "unavailable")
            .put("errorDomain", "unavailable")
            .put("exitObservation", "unavailable")
            .put("stdout", "")
            .put("stdoutOrigin", "unavailable")
            .put("stderr", "")
            .put("stdoutTruncated", false)
            .put("stderrTruncated", false)
            .put("sources", sources)
            .put("findings", new JSONArray())
            .put("collectionState", "unavailable")
            .put("outcome", "unavailable")
            .put("truncated", false);

    return new EngineStartupDiagnostic(value, DEFAULT_MAX_BYTES);
  }

  EngineStartupDiagnostic(JSONObject value, int maxBytes) {
    JSONObject bounded = new JSONObject(value.toString());
    // Identity and original status outrank optional detail when a policy limit is reached.
    for (String field : List.of("findings", "stdout", "stderr", "launch", "originalError")) {
      if (bytes(bounded.toString()) <= maxBytes) break;
      bounded.put("truncated", true);
      if (field.equals("launch")) {
        JSONObject launch = bounded.getJSONObject(field);
        bounded.put(
            "launch",
            new JSONObject()
                .put("environmentState", launch.getString("environmentState"))
                .put("startedAt", launch.getString("startedAt"))
                .put("platform", launch.getString("platform"))
                .put("truncated", true));
      } else if (field.equals("findings")) {
        bounded.put(field, new JSONArray());
      } else {
        bounded.put(field, "[truncated]");
      }
    }
    if (bytes(bounded.toString()) > maxBytes) {
      JSONObject sources = bounded.getJSONObject("sources");
      for (String key : sources.keySet())
        sources.getJSONObject(key).put("checkedScope", "[truncated]");
    }
    for (String field : List.of("phase", "launchPurpose")) {
      if (bytes(bounded.toString()) <= maxBytes) break;
      bounded.put(field, bounded(bounded.optString(field), 32));
    }
    json = bounded.toString();
    attemptId = bounded.getString("attemptId");
    engineId = bounded.getString("engineId");
    sizeBytes = bytes(json);
  }

  public String attemptId() {
    return attemptId;
  }

  public String engineId() {
    return engineId;
  }

  public JSONObject toJson() {
    return new JSONObject(json);
  }

  public int sizeBytes() {
    return sizeBytes;
  }

  public String shareText() {
    return new ExportSanitizer().sanitizeJsonObject(toJson()).toString(2);
  }

  public static String windowsStatus(int code) {
    return switch (code) {
      case 0xC0000135 -> "STATUS_DLL_NOT_FOUND";
      case 0xC000007B -> "STATUS_INVALID_IMAGE_FORMAT";
      case 0xC0000139 -> "STATUS_ENTRYPOINT_NOT_FOUND";
      case 0xC0000142 -> "STATUS_DLL_INIT_FAILED";
      case 0xC000001D -> "STATUS_ILLEGAL_INSTRUCTION";
      case 0xC0000005 -> "STATUS_ACCESS_VIOLATION";
      default -> "unknown";
    };
  }

  static int bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }

  static String bounded(String text, int bytes) {
    return ObservationText.boundedUtf8(text == null ? "" : text, bytes, Integer.MAX_VALUE);
  }
}
