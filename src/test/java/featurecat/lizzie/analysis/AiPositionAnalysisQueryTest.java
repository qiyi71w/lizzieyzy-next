package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class AiPositionAnalysisQueryTest {

  @Test
  void requestCarriesBoardRulesKomiOwnershipBudgetAndBlackPerspective() {
    AiPositionRequestContext context =
        new AiPositionRequestContext(
            "node-a", 1L, "[stones]", false, 13, 13, "chinese", 7.5, null, 1L);

    JSONObject request = AiPositionAnalysisQuery.build("ai-position-3", context, null, 51);

    assertEquals("ai-position-3", request.getString("id"));
    assertEquals(51, request.getInt("maxVisits"));
    assertTrue(request.getBoolean("includeOwnership"));
    assertFalse(request.optBoolean("includeMovesOwnership", true));
    assertEquals(13, request.getInt("boardXSize"));
    assertEquals(13, request.getInt("boardYSize"));
    assertEquals(7.5, request.getDouble("komi"), 1e-9);
    assertEquals("chinese", request.get("rules"));
    JSONObject override = request.getJSONObject("overrideSettings");
    assertEquals("BLACK", override.getString("reportAnalysisWinratesAs"));
    assertTrue(request.getDouble("reportDuringSearchEvery") > 0.0);
  }

  @Test
  void jsonRulesPayloadStripsEqualsPrefix() {
    AiPositionRequestContext context =
        new AiPositionRequestContext(
            "node-a",
            1L,
            "[stones]",
            true,
            19,
            19,
            "= {\"scoring\":\"AREA\",\"ko\":\"POSITIONAL\"}",
            6.5,
            null,
            1L);

    JSONObject request = AiPositionAnalysisQuery.build("ai-position-1", context, null, 2);

    JSONObject rules = request.getJSONObject("rules");
    assertEquals("AREA", rules.getString("scoring"));
    assertEquals("POSITIONAL", rules.getString("ko"));
    assertEquals(6.5, request.getDouble("komi"), 1e-9);
  }
}
