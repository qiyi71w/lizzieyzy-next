package featurecat.lizzie.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Selects a plausible HumanSL move without retaining state between calls. */
final class HumanLikeMoveSelector {
  static final int MAX_CANDIDATES = 12;
  static final double CANDIDATE_POLICY_MASS = 0.95;

  private static final double MIN_RELATIVE_POLICY = 0.01;
  private static final double DEEP_VERIFICATION_THRESHOLD_RATIO = 0.55;
  private static final int OPENING_END_MOVE = 60;
  private static final int MIDDLEGAME_END_MOVE = 160;
  private static final double OPENING_TEMPERATURE = 1.25;
  private static final double MIDDLEGAME_TEMPERATURE = 1.05;
  private static final double ENDGAME_TEMPERATURE = 0.90;

  private HumanLikeMoveSelector() {}

  static String select(
      List<Candidate> legalMoves,
      JSONArray moveInfos,
      int moveNumber,
      String profile,
      double randomValue) {
    List<Candidate> pool = candidatePool(legalMoves);
    if (pool.isEmpty()) {
      return null;
    }

    QualityTable quality = QualityTable.from(moveInfos);
    if (!quality.hasSearchResult()) {
      return null;
    }
    if (quality.kind == QualityKind.NONE) {
      return quality.bestMove;
    }

    double maxQualityLoss = maxQualityLoss(profile, quality.kind);
    double temperature = temperatureForMove(moveNumber);
    ArrayList<WeightedMove> weighted = new ArrayList<WeightedMove>(pool.size());
    for (Candidate candidate : pool) {
      double qualityFactor = quality.factor(candidate.move, maxQualityLoss);
      if (qualityFactor <= 0.0) {
        continue;
      }
      double policyWeight = Math.pow(candidate.probability, 1.0 / temperature);
      double weight = policyWeight * qualityFactor;
      if (Double.isFinite(weight) && weight > 0.0) {
        weighted.add(new WeightedMove(candidate.move, weight));
      }
    }

    if (weighted.isEmpty()) {
      return quality.bestMove;
    }
    return sampleWeighted(weighted, randomValue);
  }

  /** Returns true when shallow results are too incomplete or volatile for a safe final choice. */
  static boolean needsDeepVerification(
      List<Candidate> legalMoves, JSONArray moveInfos, String profile) {
    List<Candidate> pool = candidatePool(legalMoves);
    if (pool.isEmpty()) {
      return false;
    }
    QualityTable quality = QualityTable.from(moveInfos);
    if (quality.kind == QualityKind.NONE || !quality.hasSearchResult()) {
      return true;
    }
    double threshold = maxQualityLoss(profile, quality.kind) * DEEP_VERIFICATION_THRESHOLD_RATIO;
    for (Candidate candidate : pool) {
      Double loss = quality.loss(candidate.move);
      if (loss == null || loss.doubleValue() > threshold) {
        return true;
      }
    }
    return false;
  }

  static List<Candidate> candidatePool(List<Candidate> legalMoves) {
    ArrayList<Candidate> sorted = new ArrayList<Candidate>();
    if (legalMoves != null) {
      for (Candidate candidate : legalMoves) {
        if (candidate != null
            && candidate.move != null
            && !candidate.move.trim().isEmpty()
            && Double.isFinite(candidate.probability)
            && candidate.probability > 0.0) {
          sorted.add(candidate);
        }
      }
    }
    sorted.sort(Comparator.comparingDouble((Candidate move) -> move.probability).reversed());
    if (sorted.isEmpty()) {
      return sorted;
    }

    double total = 0.0;
    for (Candidate candidate : sorted) {
      total += candidate.probability;
    }
    if (!(total > 0.0) || !Double.isFinite(total)) {
      return List.of(sorted.get(0));
    }

    double topProbability = sorted.get(0).probability;
    double cumulative = 0.0;
    ArrayList<Candidate> pool = new ArrayList<Candidate>(MAX_CANDIDATES);
    for (Candidate candidate : sorted) {
      if (pool.size() >= MAX_CANDIDATES) {
        break;
      }
      if (!pool.isEmpty() && candidate.probability < topProbability * MIN_RELATIVE_POLICY) {
        break;
      }
      pool.add(candidate);
      cumulative += candidate.probability;
      if (cumulative / total >= CANDIDATE_POLICY_MASS) {
        break;
      }
    }
    return pool;
  }

  static double temperatureForMove(int moveNumber) {
    if (moveNumber <= OPENING_END_MOVE) {
      return OPENING_TEMPERATURE;
    }
    if (moveNumber <= MIDDLEGAME_END_MOVE) {
      return MIDDLEGAME_TEMPERATURE;
    }
    return ENDGAME_TEMPERATURE;
  }

  private static String sampleWeighted(List<WeightedMove> moves, double randomValue) {
    double total = 0.0;
    for (WeightedMove move : moves) {
      total += move.weight;
    }
    if (!(total > 0.0) || !Double.isFinite(total)) {
      return moves.get(0).move;
    }

    double boundedRandom = Math.max(0.0, Math.min(0.999999999999, randomValue));
    double target = boundedRandom * total;
    double cumulative = 0.0;
    for (WeightedMove move : moves) {
      cumulative += move.weight;
      if (target < cumulative) {
        return move.move;
      }
    }
    return moves.get(moves.size() - 1).move;
  }

  private static double maxQualityLoss(String profile, QualityKind kind) {
    StrengthBand band = strengthBand(profile);
    switch (kind) {
      case UTILITY:
        switch (band) {
          case ELITE:
            return 0.32;
          case HIGH_DAN:
            return 0.45;
          case DAN:
            return 0.65;
          case STRONG_KYU:
            return 0.85;
          case KYU:
            return 1.15;
          case DEVELOPING:
          default:
            return 1.80;
        }
      case SCORE:
        switch (band) {
          case ELITE:
            return 3.0;
          case HIGH_DAN:
            return 4.0;
          case DAN:
            return 5.5;
          case STRONG_KYU:
            return 7.0;
          case KYU:
            return 10.0;
          case DEVELOPING:
          default:
            return 15.0;
        }
      case WINRATE:
        switch (band) {
          case ELITE:
            return 0.10;
          case HIGH_DAN:
            return 0.14;
          case DAN:
            return 0.20;
          case STRONG_KYU:
            return 0.25;
          case KYU:
            return 0.34;
          case DEVELOPING:
          default:
            return 0.48;
        }
      case NONE:
      default:
        return 1.0;
    }
  }

  private static StrengthBand strengthBand(String profile) {
    if (profile == null) {
      return StrengthBand.KYU;
    }
    String normalized = profile.trim().toLowerCase(Locale.ROOT);
    if (normalized.startsWith("proyear_")) {
      return StrengthBand.ELITE;
    }
    int rank = parseRankNumber(normalized);
    if (normalized.endsWith("d")) {
      if (rank >= 7) {
        return StrengthBand.ELITE;
      }
      if (rank >= 4) {
        return StrengthBand.HIGH_DAN;
      }
      return StrengthBand.DAN;
    }
    if (rank <= 5) {
      return StrengthBand.STRONG_KYU;
    }
    if (rank <= 10) {
      return StrengthBand.KYU;
    }
    return StrengthBand.DEVELOPING;
  }

  private static int parseRankNumber(String profile) {
    int underscore = profile.lastIndexOf('_');
    int suffix = profile.length() - 1;
    if (underscore < 0 || suffix <= underscore) {
      return 10;
    }
    try {
      return Integer.parseInt(profile.substring(underscore + 1, suffix));
    } catch (NumberFormatException ignored) {
      return 10;
    }
  }

  static final class Candidate {
    final String move;
    final double probability;

    Candidate(String move, double probability) {
      this.move = move;
      this.probability = probability;
    }
  }

  private static final class WeightedMove {
    private final String move;
    private final double weight;

    private WeightedMove(String move, double weight) {
      this.move = move;
      this.weight = weight;
    }
  }

  private enum QualityKind {
    UTILITY,
    SCORE,
    WINRATE,
    NONE
  }

  private enum StrengthBand {
    ELITE,
    HIGH_DAN,
    DAN,
    STRONG_KYU,
    KYU,
    DEVELOPING
  }

  private static final class QualityTable {
    private final QualityKind kind;
    private final Map<String, Double> values;
    private final double best;
    private final String bestMove;

    private QualityTable(
        QualityKind kind, Map<String, Double> values, double best, String bestMove) {
      this.kind = kind;
      this.values = values;
      this.best = best;
      this.bestMove = bestMove;
    }

    private double factor(String move, double maxLoss) {
      Double value = values.get(normalizeMove(move));
      if (value == null) {
        return 0.0;
      }
      double loss = Math.max(0.0, best - value.doubleValue());
      if (loss > maxLoss) {
        return 0.0;
      }
      return Math.exp(-loss / Math.max(1.0e-9, maxLoss));
    }

    private Double loss(String move) {
      Double value = values.get(normalizeMove(move));
      return value == null ? null : Double.valueOf(Math.max(0.0, best - value.doubleValue()));
    }

    private boolean hasSearchResult() {
      return bestMove != null && !bestMove.isEmpty();
    }

    private static QualityTable from(JSONArray moveInfos) {
      QualityTable utility = collect(moveInfos, QualityKind.UTILITY);
      if (!utility.values.isEmpty()) {
        return utility;
      }
      QualityTable score = collect(moveInfos, QualityKind.SCORE);
      if (!score.values.isEmpty()) {
        return score;
      }
      QualityTable winrate = collect(moveInfos, QualityKind.WINRATE);
      if (!winrate.values.isEmpty()) {
        return winrate;
      }
      return new QualityTable(QualityKind.NONE, Map.of(), 0.0, firstSearchMove(moveInfos));
    }

    private static QualityTable collect(JSONArray moveInfos, QualityKind kind) {
      HashMap<String, Double> values = new HashMap<String, Double>();
      double best = Double.NEGATIVE_INFINITY;
      String bestMove = null;
      if (moveInfos != null) {
        for (int i = 0; i < moveInfos.length(); i++) {
          JSONObject moveInfo = moveInfos.optJSONObject(i);
          if (moveInfo == null) {
            continue;
          }
          String move = normalizeMove(moveInfo.optString("move", ""));
          Double value = qualityValue(moveInfo, kind);
          if (move.isEmpty() || value == null || !Double.isFinite(value.doubleValue())) {
            continue;
          }
          values.put(move, value);
          if (bestMove == null || value.doubleValue() > best) {
            best = value.doubleValue();
            bestMove = move;
          }
        }
      }
      return new QualityTable(kind, values, best, bestMove);
    }

    private static String firstSearchMove(JSONArray moveInfos) {
      if (moveInfos == null) {
        return null;
      }
      JSONObject fallback = null;
      for (int i = 0; i < moveInfos.length(); i++) {
        JSONObject moveInfo = moveInfos.optJSONObject(i);
        if (moveInfo == null) {
          continue;
        }
        String move = normalizeMove(moveInfo.optString("move", ""));
        if (move.isEmpty()) {
          continue;
        }
        if (fallback == null) {
          fallback = moveInfo;
        }
        if (moveInfo.optInt("order", i) == 0) {
          return move;
        }
      }
      return fallback == null ? null : normalizeMove(fallback.optString("move", ""));
    }

    private static Double qualityValue(JSONObject moveInfo, QualityKind kind) {
      switch (kind) {
        case UTILITY:
          return number(moveInfo, "utility");
        case SCORE:
          Double scoreLead = number(moveInfo, "scoreLead");
          return scoreLead != null ? scoreLead : number(moveInfo, "scoreMean");
        case WINRATE:
          Double winrate = number(moveInfo, "winrate");
          if (winrate != null && winrate.doubleValue() > 1.0) {
            return winrate.doubleValue() / 100.0;
          }
          return winrate;
        case NONE:
        default:
          return null;
      }
    }

    private static Double number(JSONObject object, String key) {
      if (!object.has(key) || !(object.opt(key) instanceof Number)) {
        return null;
      }
      return ((Number) object.opt(key)).doubleValue();
    }
  }

  private static String normalizeMove(String move) {
    if (move == null) {
      return "";
    }
    String normalized = move.trim();
    return "pass".equalsIgnoreCase(normalized) ? "pass" : normalized.toUpperCase(Locale.ROOT);
  }
}
