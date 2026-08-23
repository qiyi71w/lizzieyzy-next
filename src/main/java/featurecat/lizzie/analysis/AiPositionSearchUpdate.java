package featurecat.lizzie.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One matching KataGo search update: searched rootInfo plus searched ownership from the same line.
 */
public final class AiPositionSearchUpdate {
  private final int visits;
  private final double sideToMoveWinrate;
  private final double sideToMoveScoreLead;
  private final double[] sideToMoveOwnership;

  private AiPositionSearchUpdate(
      int visits,
      double sideToMoveWinrate,
      double sideToMoveScoreLead,
      double[] sideToMoveOwnership) {
    this.visits = visits;
    this.sideToMoveWinrate = sideToMoveWinrate;
    this.sideToMoveScoreLead = sideToMoveScoreLead;
    this.sideToMoveOwnership = sideToMoveOwnership;
  }

  public int visits() {
    return visits;
  }

  public double sideToMoveWinrate() {
    return sideToMoveWinrate;
  }

  public double sideToMoveScoreLead() {
    return sideToMoveScoreLead;
  }

  public double[] sideToMoveOwnership() {
    return sideToMoveOwnership.clone();
  }

  public static Optional<AiPositionSearchUpdate> parse(String line) {
    if (line == null) {
      return Optional.empty();
    }
    String trimmed = line.trim();
    if (trimmed.isEmpty()) {
      return Optional.empty();
    }
    String[] tokens = trimmed.split("\\s+");
    int visits = -1;
    double winrate = Double.NaN;
    double scoreLead = Double.NaN;
    double[] ownership = null;
    int index = 0;
    while (index < tokens.length) {
      String token = tokens[index];
      if (isInfoSection(token)) {
        index = skipUntilSection(tokens, index + 1);
        continue;
      }
      if (isRootInfoSection(token)) {
        index++;
        while (index < tokens.length && !isSectionHeader(tokens[index])) {
          String key = tokens[index];
          if (index + 1 >= tokens.length || isSectionHeader(tokens[index + 1])) {
            index++;
            break;
          }
          String value = tokens[index + 1];
          if ("visits".equals(key)) {
            visits = parseInt(value, visits);
          } else if ("winrate".equals(key)) {
            winrate = parseDouble(value, winrate);
          } else if ("scoreLead".equals(key) || "scoreMean".equals(key)) {
            scoreLead = parseDouble(value, scoreLead);
          }
          index += 2;
        }
        continue;
      }
      if (isOwnershipSection(token)) {
        List<Double> values = new ArrayList<Double>();
        index++;
        while (index < tokens.length && !isSectionHeader(tokens[index])) {
          Double value = parseDoubleOrNull(tokens[index]);
          if (value == null) {
            break;
          }
          values.add(value);
          index++;
        }
        if (!values.isEmpty()) {
          ownership = new double[values.size()];
          for (int i = 0; i < values.size(); i++) {
            ownership[i] = values.get(i);
          }
        }
        continue;
      }
      index++;
    }
    if (visits <= 0
        || !Double.isFinite(winrate)
        || !Double.isFinite(scoreLead)
        || ownership == null
        || ownership.length == 0) {
      return Optional.empty();
    }
    return Optional.of(new AiPositionSearchUpdate(visits, winrate, scoreLead, ownership));
  }

  public static Optional<AiPositionSearchUpdate> parseAnalysisJson(String json) {
    if (json == null) {
      return Optional.empty();
    }
    String trimmed = json.trim();
    if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
      return Optional.empty();
    }
    try {
      JSONObject result = new JSONObject(trimmed);
      JSONObject rootInfo = result.optJSONObject("rootInfo");
      JSONArray ownershipJson = result.optJSONArray("ownership");
      if (rootInfo == null || ownershipJson == null || ownershipJson.length() == 0) {
        return Optional.empty();
      }
      int visits = rootInfo.optInt("visits", -1);
      double winrate = rootInfo.optDouble("winrate", Double.NaN);
      double scoreLead = rootInfo.has("scoreLead")
          ? rootInfo.optDouble("scoreLead", Double.NaN)
          : rootInfo.optDouble("scoreMean", Double.NaN);
      if (visits <= 0 || !Double.isFinite(winrate) || !Double.isFinite(scoreLead)) {
        return Optional.empty();
      }
      double[] ownership = new double[ownershipJson.length()];
      for (int i = 0; i < ownershipJson.length(); i++) {
        ownership[i] = ownershipJson.getDouble(i);
      }
      return Optional.of(new AiPositionSearchUpdate(visits, winrate, scoreLead, ownership));
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  static String movePayload(String line) {
    if (line == null || line.isEmpty()) {
      return line;
    }
    String[] tokens = line.trim().split("\\s+");
    if (tokens.length == 0) {
      return line;
    }
    StringBuilder payload = new StringBuilder();
    int index = 0;
    boolean firstInfo = true;
    while (index < tokens.length) {
      if (isRootInfoSection(tokens[index])) {
        index = skipUntilSection(tokens, index + 1);
        continue;
      }
      if (isOwnershipSection(tokens[index])) {
        index = skipUntilSection(tokens, index + 1);
        continue;
      }
      if (isInfoSection(tokens[index])) {
        if (!firstInfo) {
          payload.append(" info");
        }
        firstInfo = false;
        index++;
        continue;
      }
      if (payload.length() > 0) {
        payload.append(' ');
      }
      payload.append(tokens[index]);
      index++;
    }
    return payload.toString();
  }

  private static boolean isSectionHeader(String token) {
    return isInfoSection(token) || isRootInfoSection(token) || isOwnershipSection(token);
  }

  private static boolean isInfoSection(String token) {
    return "info".equals(token);
  }

  private static boolean isRootInfoSection(String token) {
    return "rootInfo".equals(token);
  }

  private static boolean isOwnershipSection(String token) {
    return "ownership".equals(token);
  }

  private static int skipUntilSection(String[] tokens, int start) {
    int index = start;
    while (index < tokens.length && !isSectionHeader(tokens[index])) {
      index++;
    }
    return index;
  }

  private static int parseInt(String token, int fallback) {
    try {
      return Integer.parseInt(token);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static double parseDouble(String token, double fallback) {
    Double value = parseDoubleOrNull(token);
    return value == null ? fallback : value;
  }

  private static Double parseDoubleOrNull(String token) {
    try {
      double value = Double.parseDouble(token);
      return Double.isFinite(value) ? value : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }
}
