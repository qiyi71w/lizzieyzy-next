package featurecat.lizzie.analysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Immutable KataGo rules value used for parse, semantic comparison, display summary, and editing.
 * JSON key order is not identity. Display name and integer classification are not identity.
 */
public final class KataGoRules {
  public static final String KO = "ko";
  public static final String SCORING = "scoring";
  public static final String TAX = "tax";
  public static final String SUICIDE = "suicide";
  public static final String HAS_BUTTON = "hasButton";
  public static final String WHITE_HANDICAP_BONUS = "whiteHandicapBonus";
  public static final String FRIENDLY_PASS_OK = "friendlyPassOk";

  private static final List<String> REQUIRED_FIELDS =
      List.of(KO, SCORING, TAX, SUICIDE, HAS_BUTTON, WHITE_HANDICAP_BONUS, FRIENDLY_PASS_OK);

  public enum Summary {
    CHINESE,
    CHINESE_ANCIENT,
    JAPANESE,
    TROMP_TAYLOR,
    OTHER
  }

  private static final Map<String, KataGoRules> PRESETS = officialPresets();

  private final Map<String, Object> fields;

  private KataGoRules(Map<String, Object> fields) {
    this.fields = Collections.unmodifiableMap(new TreeMap<>(fields));
  }

  public static List<String> officialPresetNames() {
    return List.copyOf(PRESETS.keySet());
  }

  public static Optional<String> officialNameOf(KataGoRules rules) {
    if (rules == null) {
      return Optional.empty();
    }
    for (Map.Entry<String, KataGoRules> entry : PRESETS.entrySet()) {
      if (entry.getValue().semanticallyEquals(rules)) {
        return Optional.of(entry.getKey());
      }
    }
    return Optional.empty();
  }

  public static Optional<KataGoRules> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = stripGtpPrefix(raw.trim());
    if (trimmed.isEmpty()) {
      return Optional.empty();
    }
    if (trimmed.startsWith("{")) {
      try {
        return Optional.of(fromJson(new JSONObject(trimmed)));
      } catch (JSONException ignored) {
        return Optional.empty();
      }
    }
    return Optional.ofNullable(PRESETS.get(normalizePresetName(trimmed)));
  }

  public static KataGoRules fromJson(JSONObject json) {
    Objects.requireNonNull(json, "json");
    Map<String, Object> fields = new TreeMap<>();
    for (String key : json.keySet()) {
      fields.put(key, normalizeValue(json.get(key)));
    }
    return new KataGoRules(fields);
  }

  public boolean semanticallyEquals(KataGoRules other) {
    return other != null && fields.equals(other.fields);
  }

  public boolean hasRequiredFields() {
    for (String field : REQUIRED_FIELDS) {
      if (!fields.containsKey(field) || fields.get(field) == null) {
        return false;
      }
    }
    return true;
  }

  public boolean hasField(String name) {
    return fields.containsKey(name);
  }

  public String string(String name) {
    Object value = fields.get(name);
    return value == null ? "" : String.valueOf(value);
  }

  public boolean bool(String name) {
    Object value = fields.get(name);
    return value instanceof Boolean ? (Boolean) value : false;
  }

  public String fieldSummary() {
    StringBuilder text = new StringBuilder();
    for (String key : REQUIRED_FIELDS) {
      Object value = fields.get(key);
      if (value != null) {
        if (text.length() > 0) {
          text.append(", ");
        }
        text.append(key).append('=').append(value);
      }
    }
    for (Map.Entry<String, Object> entry : fields.entrySet()) {
      if (REQUIRED_FIELDS.contains(entry.getKey()) || entry.getValue() == null) {
        continue;
      }
      if (text.length() > 0) {
        text.append(", ");
      }
      text.append(entry.getKey()).append('=').append(entry.getValue());
    }
    return text.toString();
  }

  public KataGoRules overlayEditor(
      String scoring,
      String ko,
      boolean suicide,
      String tax,
      String whiteHandicapBonus,
      boolean hasButton) {
    Map<String, Object> next = new TreeMap<>(fields);
    next.put(SCORING, scoring);
    next.put(KO, ko);
    next.put(SUICIDE, suicide);
    next.put(TAX, tax);
    next.put(WHITE_HANDICAP_BONUS, whiteHandicapBonus);
    next.put(HAS_BUTTON, hasButton);
    return new KataGoRules(next);
  }

  public Summary summary() {
    if (matchesOfficial("tromp-taylor") || matchesLegacyTrompTaylor()) {
      return Summary.TROMP_TAYLOR;
    }
    if (matchesOfficial("stone-scoring") || matchesLegacyChineseAncient()) {
      return Summary.CHINESE_ANCIENT;
    }
    if (matchesOfficial("japanese")
        || matchesOfficial("korean")
        || matchesLegacyJapanese()) {
      return Summary.JAPANESE;
    }
    if (matchesOfficial("chinese")
        || matchesOfficial("chinese-ogs")
        || matchesOfficial("chinese-kgs")
        || matchesLegacyChinese()) {
      return Summary.CHINESE;
    }
    return Summary.OTHER;
  }

  /**
   * Existing integer classification used by Engine Game labels. Name identity is not rule identity.
   */
  public int legacyClassification() {
    String scoring = string(SCORING);
    String ko = string(KO);
    String tax = string(TAX);
    String bonus = string(WHITE_HANDICAP_BONUS);
    boolean suicide = bool(SUICIDE);
    boolean hasButton = bool(HAS_BUTTON);
    if (scoring.contentEquals("AREA")
        && ko.contentEquals("POSITIONAL")
        && suicide
        && tax.contentEquals("NONE")
        && bonus.contentEquals("N")
        && !hasButton) {
      return 4;
    }
    if (scoring.contentEquals("AREA") && tax.contentEquals("NONE") && !hasButton) {
      return 1;
    }
    if (scoring.contentEquals("AREA") && tax.contentEquals("ALL") && !hasButton) {
      return 2;
    }
    if (scoring.contentEquals("TERRITORY") && tax.contentEquals("SEKI")) {
      return 3;
    }
    if (scoring.contentEquals("AREA") || scoring.contentEquals("TERRITORY")) {
      return 5;
    }
    return -1;
  }

  public JSONObject toJson() {
    JSONObject json = new JSONObject();
    for (Map.Entry<String, Object> entry : fields.entrySet()) {
      json.put(entry.getKey(), toJsonValue(entry.getValue()));
    }
    return json;
  }

  public String toGtpArgument() {
    return toJson().toString();
  }

  public String toResponseLine() {
    return "= " + toGtpArgument();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof KataGoRules && semanticallyEquals((KataGoRules) other);
  }

  @Override
  public int hashCode() {
    return fields.hashCode();
  }

  @Override
  public String toString() {
    return toGtpArgument();
  }

  private boolean matchesOfficial(String name) {
    KataGoRules preset = PRESETS.get(name);
    return preset != null && semanticallyEquals(preset);
  }

  private boolean matchesLegacyTrompTaylor() {
    return string(SCORING).contentEquals("AREA")
        && string(KO).contentEquals("POSITIONAL")
        && bool(SUICIDE)
        && string(TAX).contentEquals("NONE")
        && string(WHITE_HANDICAP_BONUS).contentEquals("N")
        && !bool(HAS_BUTTON);
  }

  private boolean matchesLegacyChineseAncient() {
    return string(SCORING).contentEquals("AREA")
        && string(TAX).contentEquals("ALL")
        && !bool(HAS_BUTTON);
  }

  private boolean matchesLegacyJapanese() {
    return string(SCORING).contentEquals("TERRITORY") && string(TAX).contentEquals("SEKI");
  }

  private boolean matchesLegacyChinese() {
    return string(SCORING).contentEquals("AREA")
        && string(TAX).contentEquals("NONE")
        && !bool(HAS_BUTTON);
  }

  private static String stripGtpPrefix(String raw) {
    if (raw.isEmpty() || (raw.charAt(0) != '=' && raw.charAt(0) != '?')) {
      return raw;
    }
    int index = 1;
    while (index < raw.length() && Character.isDigit(raw.charAt(index))) {
      index++;
    }
    return raw.substring(index).trim();
  }

  private static String normalizePresetName(String name) {
    return name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private static Object normalizeValue(Object value) {
    if (value == null || value == JSONObject.NULL) {
      return null;
    }
    if (value instanceof JSONObject) {
      Map<String, Object> nested = new TreeMap<>();
      JSONObject json = (JSONObject) value;
      for (String key : json.keySet()) {
        nested.put(key, normalizeValue(json.get(key)));
      }
      return Collections.unmodifiableMap(nested);
    }
    if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      Object[] items = new Object[array.length()];
      for (int index = 0; index < array.length(); index++) {
        items[index] = normalizeValue(array.get(index));
      }
      return List.of(items);
    }
    if (value instanceof Boolean || value instanceof String) {
      return value;
    }
    if (value instanceof Number) {
      return value;
    }
    return String.valueOf(value);
  }

  private static Object toJsonValue(Object value) {
    if (value == null) {
      return JSONObject.NULL;
    }
    if (value instanceof Map) {
      JSONObject json = new JSONObject();
      @SuppressWarnings("unchecked")
      Map<String, Object> nested = (Map<String, Object>) value;
      for (Map.Entry<String, Object> entry : nested.entrySet()) {
        json.put(entry.getKey(), toJsonValue(entry.getValue()));
      }
      return json;
    }
    if (value instanceof List) {
      JSONArray array = new JSONArray();
      for (Object item : (List<?>) value) {
        array.put(toJsonValue(item));
      }
      return array;
    }
    return value;
  }

  private static Map<String, KataGoRules> officialPresets() {
    Map<String, KataGoRules> presets = new LinkedHashMap<>();
    presets.put(
        "tromp-taylor",
        preset("POSITIONAL", "AREA", "NONE", true, false, "0", false));
    presets.put("chinese", preset("SIMPLE", "AREA", "NONE", false, false, "N", true));
    presets.put("chinese-ogs", preset("POSITIONAL", "AREA", "NONE", false, false, "N", true));
    presets.put("chinese-kgs", preset("POSITIONAL", "AREA", "NONE", false, false, "N", true));
    presets.put("japanese", preset("SIMPLE", "TERRITORY", "SEKI", false, false, "0", true));
    presets.put("korean", preset("SIMPLE", "TERRITORY", "SEKI", false, false, "0", true));
    presets.put("stone-scoring", preset("SIMPLE", "AREA", "ALL", false, false, "0", true));
    presets.put("aga", preset("SITUATIONAL", "AREA", "NONE", false, false, "N-1", true));
    presets.put("bga", preset("SITUATIONAL", "AREA", "NONE", false, false, "N-1", true));
    presets.put("new-zealand", preset("SITUATIONAL", "AREA", "NONE", true, false, "0", true));
    presets.put("aga-button", preset("SITUATIONAL", "AREA", "NONE", false, true, "N-1", true));
    return Map.copyOf(presets);
  }

  private static KataGoRules preset(
      String ko,
      String scoring,
      String tax,
      boolean suicide,
      boolean hasButton,
      String whiteHandicapBonus,
      boolean friendlyPassOk) {
    Map<String, Object> fields = new TreeMap<>();
    fields.put(KO, ko);
    fields.put(SCORING, scoring);
    fields.put(TAX, tax);
    fields.put(SUICIDE, suicide);
    fields.put(HAS_BUTTON, hasButton);
    fields.put(WHITE_HANDICAP_BONUS, whiteHandicapBonus);
    fields.put(FRIENDLY_PASS_OK, friendlyPassOk);
    return new KataGoRules(fields);
  }
}
