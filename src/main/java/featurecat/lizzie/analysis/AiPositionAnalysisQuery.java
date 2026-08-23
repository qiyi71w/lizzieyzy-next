package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.BoardHistoryNode;
import java.util.ArrayList;
import org.json.JSONObject;

/** Builds one Analysis Engine query for a managed AI形势 request. */
public final class AiPositionAnalysisQuery {
  static final double REPORT_DURING_SEARCH_SECONDS = 0.3;

  private AiPositionAnalysisQuery() {}

  public static JSONObject build(
      String id, AiPositionRequestContext context, BoardHistoryNode node, int maxVisits) {
    JSONObject request = new JSONObject();
    request.put("id", id);
    request.put("maxVisits", Math.max(1, maxVisits));
    request.put("includeOwnership", true);
    request.put("includeMovesOwnership", false);
    request.put("includePVVisits", false);
    request.put("boardXSize", context.boardWidth());
    request.put("boardYSize", context.boardHeight());
    request.put("komi", context.komi());
    request.put("rules", rulesPayload(context.rules()));
    request.put("reportDuringSearchEvery", REPORT_DURING_SEARCH_SECONDS);
    JSONObject overrideSettings = new JSONObject();
    overrideSettings.put("reportAnalysisWinratesAs", "BLACK");
    request.put("overrideSettings", overrideSettings);
    if (node != null) {
      BoardHistoryNode snapshotAnchor = AnalysisEngine.findSnapshotAnchor(node);
      BoardHistoryNode initialStateAnchor = AnalysisEngine.resolveInitialStateAnchor(snapshotAnchor);
      ArrayList<String[]> initialStoneList = AnalysisEngine.collectInitialStones(initialStateAnchor);
      if (!initialStoneList.isEmpty()) {
        request.put("initialStones", initialStoneList);
      }
      String initialPlayer = AnalysisEngine.collectInitialPlayer(initialStateAnchor);
      if (initialPlayer != null) {
        request.put("initialPlayer", initialPlayer);
      }
      ArrayList<String[]> moveList = AnalysisEngine.collectHistoryActions(node, snapshotAnchor);
      request.put("moves", moveList);
      ArrayList<Integer> moveTurns = new ArrayList<Integer>();
      moveTurns.add(moveList.size());
      request.put("analyzeTurns", moveTurns);
    } else {
      request.put("moves", new ArrayList<String[]>());
      ArrayList<Integer> moveTurns = new ArrayList<Integer>();
      moveTurns.add(0);
      request.put("analyzeTurns", moveTurns);
    }
    return request;
  }

  static Object rulesPayload(String rules) {
    if (rules == null || rules.isEmpty()) {
      return "tromp-taylor";
    }
    String trimmed = rules.trim();
    if (trimmed.startsWith("=")) {
      trimmed = trimmed.substring(1).trim();
    }
    if (trimmed.isEmpty()) {
      return "tromp-taylor";
    }
    if (trimmed.startsWith("{")) {
      return new JSONObject(trimmed);
    }
    return trimmed;
  }
}
