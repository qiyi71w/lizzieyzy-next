package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.ExportSanitizer;
import featurecat.lizzie.logging.ObservationText;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** One published revision. JSON is stored as text so callers cannot mutate retained evidence. */
public final class EngineStartupDiagnostic {
  private final String json;
  private final String attemptId;
  private final String engineId;
  private final long revision;
  private final int sizeBytes;

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
    revision = bounded.getLong("diagnosticRevision");
    sizeBytes = bytes(json);
  }

  public String attemptId() {
    return attemptId;
  }

  public String engineId() {
    return engineId;
  }

  public long revision() {
    return revision;
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
