package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiPositionSearchUpdateTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "info move Q16 visits 10 winrate 0.40 scoreLead -5.0 pv Q16"
            + " rootInfo visits 800 winrate 0.120000 scoreLead -71.4"
            + " ownership 0.25 -0.50 0.10",
        "info move Q16 visits 10 winrate 0.40 scoreLead -5.0 pv Q16"
            + " ownership 0.25 -0.50 0.10"
            + " rootInfo visits 800 winrate 0.120000 scoreLead -71.4",
        "rootInfo visits 800 winrate 0.120000 scoreLead -71.4 ownership 0.25 -0.50 0.10",
        "ownership 0.25 -0.50 0.10 rootInfo visits 800 winrate 0.120000 scoreLead -71.4",
        "move Q16 visits 10 winrate 0.40 scoreLead -5.0 pv Q16"
            + " rootInfo scoreLead -71.4 visits 800 winrate 0.120000"
            + " ownership 0.25 -0.50 0.10"
      })
  void parsesRootResultRegardlessOfFieldOrder(String line) {
    AiPositionSearchUpdate update = requireUpdate(line);

    assertEquals(800, update.visits());
    assertEquals(0.12, update.sideToMoveWinrate(), 1e-9);
    assertEquals(-71.4, update.sideToMoveScoreLead(), 1e-9);
    assertArrayEquals(new double[] {0.25, -0.50, 0.10}, update.sideToMoveOwnership(), 1e-9);
  }

  @Test
  void ignoresUnknownRootInfoFieldsAndScoreMeanAlias() {
    AiPositionSearchUpdate update =
        requireUpdate(
            "rootInfo foo 1 visits 12 utility 0.3 scoreMean -71.4 extra 9 winrate 0.2"
                + " ownership 0.1 -0.2");

    assertEquals(12, update.visits());
    assertEquals(0.2, update.sideToMoveWinrate(), 1e-9);
    assertEquals(-71.4, update.sideToMoveScoreLead(), 1e-9);
    assertArrayEquals(new double[] {0.1, -0.2}, update.sideToMoveOwnership(), 1e-9);
  }

  @Test
  void doesNotTreatMovesOwnershipAsSearchedOwnership() {
    Optional<AiPositionSearchUpdate> update =
        AiPositionSearchUpdate.parse(
            "info move Q16 visits 10 winrate 0.4 scoreLead -5.0 pv Q16"
                + " movesOwnership 0.9 -0.9 rootInfo visits 8 winrate 0.5 scoreLead 1.0");

    assertTrue(update.isEmpty());
  }

  @Test
  void rejectsLineMissingRootInfoOrOwnership() {
    assertTrue(
        AiPositionSearchUpdate.parse(
                "info move Q16 visits 10 winrate 0.4 scoreLead -71.4 pv Q16 ownership 0.1")
            .isEmpty());
    assertTrue(
        AiPositionSearchUpdate.parse("rootInfo visits 800 winrate 0.12 scoreLead -71.4").isEmpty());
  }

  @Test
  void movePayloadStripsRootInfoAndOwnershipForOrdinaryMoveParse() {
    String payload =
        AiPositionSearchUpdate.movePayload(
            "info move Q16 visits 10 winrate 0.40 scoreLead -5.0 pv Q16"
                + " rootInfo visits 800 winrate 0.12 scoreLead -71.4"
                + " ownership 0.25 -0.50");
    assertEquals("move Q16 visits 10 winrate 0.40 scoreLead -5.0 pv Q16", payload);
  }

  @Test
  void parsesAnalysisEngineJsonRootAndOwnership() {
    AiPositionSearchUpdate update =
        requireJson(
            "{\"id\":\"ai-position-1\",\"isDuringSearch\":true,"
                + "\"rootInfo\":{\"visits\":800,\"winrate\":0.12,\"scoreLead\":-71.4},"
                + "\"ownership\":[0.25,-0.50,0.10]}");
    assertEquals(800, update.visits());
    assertEquals(0.12, update.sideToMoveWinrate(), 1e-9);
    assertEquals(-71.4, update.sideToMoveScoreLead(), 1e-9);
    assertArrayEquals(new double[] {0.25, -0.50, 0.10}, update.sideToMoveOwnership(), 1e-9);
  }

  @Test
  void analysisJsonUsesScoreMeanAliasAndIgnoresMoveInfos() {
    AiPositionSearchUpdate update =
        requireJson(
            "{\"moveInfos\":[{\"move\":\"Q16\",\"visits\":10,\"winrate\":0.4,\"scoreLead\":-13.0}],"
                + "\"rootInfo\":{\"visits\":800,\"winrate\":0.12,\"scoreMean\":-71.4},"
                + "\"ownership\":[1,1,1,1,1,-1,-1,-1,-1,-1]}");
    assertEquals(-71.4, update.sideToMoveScoreLead(), 1e-9);
    assertEquals(800, update.visits());
  }

  @Test
  void rejectsAnalysisJsonMissingRootOrOwnership() {
    assertTrue(
        AiPositionSearchUpdate.parseAnalysisJson(
                "{\"rootInfo\":{\"visits\":800,\"winrate\":0.12,\"scoreLead\":-71.4}}")
            .isEmpty());
    assertTrue(
        AiPositionSearchUpdate.parseAnalysisJson("{\"ownership\":[0.1,-0.2]}").isEmpty());
    assertTrue(AiPositionSearchUpdate.parseAnalysisJson("not-json").isEmpty());
  }



  private static AiPositionSearchUpdate requireUpdate(String line) {
    Optional<AiPositionSearchUpdate> parsed = AiPositionSearchUpdate.parse(line);
    assertTrue(parsed.isPresent(), line);
    return parsed.get();
  }

  private static AiPositionSearchUpdate requireJson(String json) {
    Optional<AiPositionSearchUpdate> parsed = AiPositionSearchUpdate.parseAnalysisJson(json);
    assertTrue(parsed.isPresent(), json);
    return parsed.get();
  }
}
