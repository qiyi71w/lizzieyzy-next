package featurecat.lizzie.logging;

import featurecat.lizzie.analysis.ReadBoardLoggingProtocol;
import featurecat.lizzie.analysis.SyncDiagnosticsExportSanitizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public final class ExportSanitizer {
  public static final String VERSION = "export-2";

  private static final Pattern NICKNAME_PARAMETER =
      Pattern.compile("(?i)\\b(?:nickname|player|userName|username)\\b(?:\\s*[=:]\\s*)([^\\s,;]+)");
  private static final Pattern SAFE_JSON_FIELD_NAME =
      Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}");
  private static final String REDACTED_FIELD_PREFIX = "redacted-field-";

  private final PersistenceSanitizer persistence = new PersistenceSanitizer();
  private final SyncDiagnosticsExportSanitizer shareTime = new SyncDiagnosticsExportSanitizer();
  private final Map<String, String> typedAliases = new LinkedHashMap<>();
  private final Map<String, Integer> typedCounts = new LinkedHashMap<>();

  public String sanitize(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    String trimmed = text.trim();
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      try {
        return sanitizeJsonObject(new JSONObject(trimmed)).toString();
      } catch (JSONException ignored) {
        // Untagged text still goes through the shared field sanitizer.
      }
    }
    return sanitizeText(text);
  }

  public String sanitizeText(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    String safe = shareTime.text(persistence.sanitize(text));
    safe = aliasNicknameParameters(safe);
    return applyKnownAliases(safe);
  }

  public JSONObject sanitizeJsonObject(JSONObject value) {
    Object sanitized = sanitizeJsonValue(value);
    return sanitized instanceof JSONObject object ? object : new JSONObject();
  }

  /**
   * Sanitizes any already-parsed JSON value. Root strings use text redaction, numbers, booleans,
   * and null retain their JSON value, and a root value tagged secret becomes redacted text.
   */
  public Object sanitizeJsonValue(Object value) {
    Object sanitized = sanitizeJsonValue(value, null);
    return sanitized == OMIT ? "<redacted>" : sanitized;
  }

  /** Parses one complete JSON value, rejects trailing content, and returns valid sanitized JSON. */
  public String sanitizeJson(String text) {
    return renderJsonValue(sanitizeJsonValue(parseJsonValueStrict(text)));
  }

  static Object parseJsonValueStrict(String text) {
    if (text == null || text.trim().isEmpty()) {
      throw new JSONException("JSON text is required");
    }
    JSONTokener tokener = new JSONTokener(text);
    Object parsed = tokener.nextValue();
    if (tokener.nextClean() != 0) {
      throw new JSONException("Unexpected trailing JSON content");
    }
    return parsed;
  }

  static String renderJsonValue(Object value) {
    return JSONObject.valueToString(value);
  }

  public SyncDiagnosticsExportSanitizer shareTime() {
    return shareTime;
  }

  public Map<String, String> aliases() {
    Map<String, String> merged = new LinkedHashMap<>(shareTime.aliases());
    merged.putAll(typedAliases);
    return merged;
  }

  public String alias(String kind, String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    String existing = typedAliases.get(value);
    if (existing != null) {
      return existing;
    }
    int index = typedCounts.getOrDefault(kind, 0) + 1;
    typedCounts.put(kind, index);
    String alias = kind + "#" + index;
    typedAliases.put(value, alias);
    return alias;
  }

  private Object sanitizeJsonValue(Object value, String key) {
    if (key != null && isSecretKey(key)) {
      return "<redacted>";
    }
    if (value instanceof JSONObject object) {
      if (isTagged(object)) {
        return sanitizeTagged(object);
      }
      JSONObject sanitized = new JSONObject();
      Map<String, String> fieldNames = sanitizedFieldNames(object);
      for (String child : sortedFieldNames(object)) {
        String sanitizedField = fieldNames.get(child);
        // Only a field name that survived sanitization may classify its value. Preserve the raw
        // key solely for secret-looking fields so they remain fail-closed even though their name
        // is replaced in the exported object.
        String valueClassificationKey =
            sanitizedField.equals(child) || isSecretKey(child) ? child : null;
        Object rewritten = sanitizeJsonValue(object.get(child), valueClassificationKey);
        if (rewritten != OMIT) {
          sanitized.put(sanitizedField, rewritten);
        }
      }
      return sanitized;
    }
    if (value instanceof JSONArray array) {
      JSONArray sanitized = new JSONArray();
      for (int i = 0; i < array.length(); i++) {
        boolean secretArgument =
            "arguments".equals(key)
                && i > 0
                && array.opt(i - 1) instanceof String previous
                && isSecretArgumentFlag(previous);
        Object rewritten = secretArgument ? "<redacted>" : sanitizeJsonValue(array.get(i), key);
        if (rewritten != OMIT) {
          sanitized.put(rewritten);
        }
      }
      return sanitized;
    }
    if (value instanceof String text) {
      return sanitizeUntaggedString(key, text);
    }
    return value;
  }

  private Map<String, String> sanitizedFieldNames(JSONObject object) {
    List<String> fields = sortedFieldNames(object);
    Map<String, String> rewritten = new LinkedHashMap<>();
    Set<String> occupied = new HashSet<>();
    for (String field : fields) {
      if (isSafeJsonFieldName(field)) {
        rewritten.put(field, field);
        occupied.add(field);
      }
    }
    int redactedIndex = 1;
    for (String field : fields) {
      if (rewritten.containsKey(field)) {
        continue;
      }
      String placeholder;
      do {
        placeholder = REDACTED_FIELD_PREFIX + redactedIndex++;
      } while (occupied.contains(placeholder));
      rewritten.put(field, placeholder);
      occupied.add(placeholder);
    }
    return rewritten;
  }

  private boolean isSafeJsonFieldName(String field) {
    if (field == null || !SAFE_JSON_FIELD_NAME.matcher(field).matches() || isSecretKey(field)) {
      return false;
    }
    return field.equals(sanitizeText(field));
  }

  private static List<String> sortedFieldNames(JSONObject object) {
    List<String> fields = new ArrayList<>(object.keySet());
    fields.sort(String::compareTo);
    return fields;
  }

  private Object sanitizeTagged(JSONObject tagged) {
    String privacy = tagged.optString("privacy", "");
    Object raw = tagged.opt("value");
    if (ReadBoardLoggingProtocol.PRIVACY_SECRET.equals(privacy)) {
      return OMIT;
    }
    Object sanitized;
    if (ReadBoardLoggingProtocol.PRIVACY_SAFE.equals(privacy)) {
      if (raw instanceof String text) {
        sanitized = sanitizeText(text);
      } else if (raw instanceof JSONObject || raw instanceof JSONArray) {
        sanitized = sanitizeJsonValue(raw, null);
        if (sanitized == OMIT) {
          return OMIT;
        }
      } else {
        sanitized = raw;
      }
    } else if (ReadBoardLoggingProtocol.PRIVACY_LOCAL_PATH.equals(privacy)
        || ReadBoardLoggingProtocol.PRIVACY_LOCAL_URL.equals(privacy)
        || ReadBoardLoggingProtocol.PRIVACY_USER_TEXT.equals(privacy)
        || ReadBoardLoggingProtocol.PRIVACY_SESSION_ID.equals(privacy)) {
      if (!(raw instanceof String text)) {
        return OMIT;
      }
      sanitized = aliasTaggedString(privacy, text);
    } else {
      // The helper owns the schema tag, so an unknown value must not silently downgrade to safe.
      return OMIT;
    }
    JSONObject rewritten = new JSONObject();
    rewritten.put("privacy", privacy);
    rewritten.put("value", sanitized);
    return rewritten;
  }

  private String aliasTaggedString(String privacy, String text) {
    if (ReadBoardLoggingProtocol.PRIVACY_LOCAL_PATH.equals(privacy)) {
      return alias("path", text);
    }
    if (ReadBoardLoggingProtocol.PRIVACY_LOCAL_URL.equals(privacy)) {
      return alias("url", text);
    }
    if (ReadBoardLoggingProtocol.PRIVACY_USER_TEXT.equals(privacy)) {
      return alias("nickname", text);
    }
    if (ReadBoardLoggingProtocol.PRIVACY_SESSION_ID.equals(privacy)) {
      return alias("session", text);
    }
    return sanitizeText(text);
  }

  private String sanitizeUntaggedString(String key, String text) {
    if (key != null) {
      String normalized = key.toLowerCase(Locale.ROOT);
      if (isSecretKey(normalized)) {
        return "<redacted>";
      }
      if (normalized.contains("session")) {
        return alias("session", text);
      }
      if (normalized.contains("path")
          || normalized.contains("file")
          || normalized.contains("dir")) {
        return alias("path", text);
      }
      if (normalized.contains("url")) {
        return alias("url", text);
      }
      if (normalized.contains("nickname") || normalized.equals("player")) {
        return alias("nickname", text);
      }
    }
    return sanitizeText(text);
  }

  private String aliasNicknameParameters(String text) {
    Matcher matcher = NICKNAME_PARAMETER.matcher(text);
    StringBuffer rewritten = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(
          rewritten,
          Matcher.quoteReplacement(
              matcher.group().replace(matcher.group(1), alias("nickname", matcher.group(1)))));
    }
    matcher.appendTail(rewritten);
    return rewritten.toString();
  }

  private String applyKnownAliases(String text) {
    String rewritten = text;
    for (Map.Entry<String, String> alias : typedAliases.entrySet()) {
      if (!alias.getKey().isEmpty()) {
        rewritten = rewritten.replace(alias.getKey(), alias.getValue());
      }
    }
    return rewritten;
  }

  private static boolean isTagged(JSONObject object) {
    return object.has("value") && object.has("privacy");
  }

  private static boolean isSecretKey(String key) {
    if (key == null || key.isEmpty()) {
      return false;
    }
    String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    return normalized.contains("password")
        || normalized.contains("passwd")
        || normalized.contains("token")
        || normalized.contains("secret")
        || normalized.contains("cookie")
        || normalized.contains("authorization")
        || normalized.contains("credential")
        || normalized.contains("machinekey");
  }

  private static boolean isSecretArgumentFlag(String argument) {
    if (argument == null || argument.isEmpty()) {
      return false;
    }
    if (argument.indexOf('=') >= 0 || argument.indexOf(':') >= 0) {
      return false;
    }
    if (!argument.startsWith("-") && !argument.startsWith("/")) {
      return false;
    }
    int start = 0;
    while (start < argument.length()
        && (argument.charAt(start) == '-' || argument.charAt(start) == '/')) {
      start++;
    }
    if (start == 0 || start == argument.length()) {
      return false;
    }
    String name = argument.substring(start);
    if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
      return false;
    }
    return PersistenceSanitizer.isCredentialName(name)
        || isSecretKey(name)
        || isSecretKey(argument);
  }

  private static final Object OMIT = new Object();
}
