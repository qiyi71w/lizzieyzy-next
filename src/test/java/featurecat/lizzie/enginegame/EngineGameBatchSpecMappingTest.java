package featurecat.lizzie.enginegame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineGameBatchSpecMappingTest {
  private static final EngineParticipantIdentity FIRST =
      new EngineParticipantIdentity("cmd-a", "alpha");
  private static final EngineParticipantIdentity SECOND =
      new EngineParticipantIdentity("cmd-b", "beta");
  private static final EngineGameMove BLACK_33 = new EngineGameMove(3, 3, 1, true, false);
  private static final EngineGameMove WHITE_15 = new EngineGameMove(15, 3, 2, false, false);

  @Test
  void analysisDialogInputMapsPlayModeLimitsKomiAndEmptyBoard() {
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            analysisStart()
                .timeLimitEnabled(true)
                .firstTimeSeconds(5)
                .secondTimeSeconds(6)
                .visitLimitEnabled(true)
                .firstVisits(100)
                .secondVisits(200)
                .firstMoveVisitLimitEnabled(true)
                .firstOpeningVisits(10)
                .secondOpeningVisits(20)
                .firstResign(new EngineGameResignPolicy(4, 3, 8.5))
                .secondResign(new EngineGameResignPolicy(5, 2, 12.0))
                .komi(6.5)
                .handicap(2)
                .exchangeColors(true)
                .batch(true)
                .batchLimit(8)
                .maxMoveLimitEnabled(true)
                .maxMoves(361)
                .autosave(true)
                .saveWinrateImage(true)
                .batchName("alpha_vs_beta")
                .build());

    assertEquals(FIRST, spec.first());
    assertEquals(SECOND, spec.second());
    assertEquals(EngineGamePlayMode.ANALYSIS, spec.playMode());
    assertEquals(EngineGameTimeMode.FIXED, spec.firstLimits().timeMode());
    assertEquals(5, spec.firstLimits().timeSeconds());
    assertEquals(6, spec.secondLimits().timeSeconds());
    assertEquals(100, spec.firstLimits().visits());
    assertEquals(200, spec.secondLimits().visits());
    assertEquals(10, spec.firstLimits().firstMoveVisits());
    assertEquals(20, spec.secondLimits().firstMoveVisits());
    assertEquals(new EngineGameResignPolicy(4, 3, 8.5), spec.firstLimits().resign());
    assertEquals(new EngineGameResignPolicy(5, 2, 12.0), spec.secondLimits().resign());
    assertEquals(6.5, spec.komi());
    assertEquals(2, spec.handicap());
    assertInstanceOf(EngineGameOpeningPlan.EmptyBoard.class, spec.opening());
    assertTrue(spec.exchangeColors());
    assertTrue(spec.batch());
    assertEquals(8, spec.initialBatchLimit());
    assertTrue(spec.maxMoveLimitEnabled());
    assertEquals(361, spec.maxMoves());
    assertEquals(new EngineGameOutputChoices(true, true, "alpha_vs_beta"), spec.output());
  }

  @Test
  void genmoveDialogInputMapsPlayModeAndTimeModesWithoutDisabledVisits() {
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            analysisStart()
                .genmove(true)
                .firstTimeMode(EngineGameTimeMode.RAW_ADVANCED)
                .secondTimeMode(EngineGameTimeMode.ENGINE_OWNED)
                .firstAdvancedTimeCommand("time_settings 120 2 1")
                .secondAdvancedTimeCommand("time_settings 60 1 1")
                .timeLimitEnabled(true)
                .firstTimeSeconds(30)
                .secondTimeSeconds(40)
                .visitLimitEnabled(false)
                .firstVisits(999)
                .secondVisits(999)
                .firstMoveVisitLimitEnabled(false)
                .firstOpeningVisits(888)
                .secondOpeningVisits(888)
                .build());

    assertEquals(EngineGamePlayMode.GENMOVE, spec.playMode());
    assertEquals(EngineGameTimeMode.RAW_ADVANCED, spec.firstLimits().timeMode());
    assertEquals(EngineGameTimeMode.ENGINE_OWNED, spec.secondLimits().timeMode());
    assertEquals("time_settings 120 2 1", spec.firstLimits().advancedTimeCommand());
    assertEquals("time_settings 60 1 1", spec.secondLimits().advancedTimeCommand());
    assertEquals(30, spec.firstLimits().timeSeconds());
    assertEquals(40, spec.secondLimits().timeSeconds());
    assertEquals(-1, spec.firstLimits().visits());
    assertEquals(-1, spec.secondLimits().visits());
    assertEquals(-1, spec.firstLimits().firstMoveVisits());
    assertEquals(-1, spec.secondLimits().firstMoveVisits());
    assertInstanceOf(EngineGameOpeningPlan.EmptyBoard.class, spec.opening());
  }

  @Test
  void emptyBoardOpeningWhenNeitherContinueNorSgfStartIsSelected() {
    EngineGameBatchSpec spec = EngineGameBatchSpecFactory.from(analysisStart().build());
    assertInstanceOf(EngineGameOpeningPlan.EmptyBoard.class, spec.opening());
  }

  @Test
  void continuePositionCapturesMovesAndIgnoresLaterMutationAndSgfFlag() {
    List<EngineGameMove> moves = new ArrayList<>();
    moves.add(BLACK_33);
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            analysisStart()
                .continueGame(true)
                .continueMoves(moves)
                .sgfOpening(true)
                .sgfOpenings(List.of(List.of(WHITE_15)))
                .sgfRandom(true)
                .build());

    moves.clear();
    EngineGameOpeningPlan.ContinuePosition opening =
        assertInstanceOf(EngineGameOpeningPlan.ContinuePosition.class, spec.opening());
    assertEquals(List.of(BLACK_33), opening.moves());
  }

  @Test
  void sgfCatalogMapsSequentialStrategy() {
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            analysisStart()
                .sgfOpening(true)
                .sgfOpenings(List.of(List.of(BLACK_33), List.of(WHITE_15)))
                .sgfRandom(false)
                .build());

    EngineGameOpeningPlan.SgfCatalog catalog =
        assertInstanceOf(EngineGameOpeningPlan.SgfCatalog.class, spec.opening());
    assertEquals(SgfOpeningStrategy.SEQUENTIAL, catalog.strategy());
    assertEquals(List.of(List.of(BLACK_33), List.of(WHITE_15)), catalog.openings());
  }

  @Test
  void sgfCatalogMapsRandomStrategyAndCopiesCatalog() {
    List<EngineGameMove> firstOpening = new ArrayList<>();
    firstOpening.add(BLACK_33);
    List<List<EngineGameMove>> catalog = new ArrayList<>();
    catalog.add(firstOpening);

    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            analysisStart().sgfOpening(true).sgfOpenings(catalog).sgfRandom(true).build());

    firstOpening.clear();
    catalog.clear();
    EngineGameOpeningPlan.SgfCatalog mapped =
        assertInstanceOf(EngineGameOpeningPlan.SgfCatalog.class, spec.opening());
    assertEquals(SgfOpeningStrategy.RANDOM, mapped.strategy());
    assertEquals(List.of(List.of(BLACK_33)), mapped.openings());
  }

  @Test
  void batchSpecHoldsStableIdentityNotCatalogIndex() {
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            EngineGameParsedStart.builder()
                .first(new EngineParticipantIdentity("cmd-a", "alpha"))
                .second(new EngineParticipantIdentity("cmd-b", "beta"))
                .build());

    assertEquals("cmd-a", spec.first().commands());
    assertEquals("alpha", spec.first().name());
    assertEquals("cmd-b", spec.second().commands());
    assertEquals("beta", spec.second().name());
    assertFalse(spec.first().commands().chars().allMatch(Character::isDigit));
  }

  @Test
  void disabledTimeLimitMapsToSentinelRegardlessOfParsedFieldText() {
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            analysisStart()
                .timeLimitEnabled(false)
                .firstTimeSeconds(15)
                .secondTimeSeconds(20)
                .build());
    assertEquals(-1, spec.firstLimits().timeSeconds());
    assertEquals(-1, spec.secondLimits().timeSeconds());
  }

  private static EngineGameParsedStart.Builder analysisStart() {
    return EngineGameParsedStart.builder().first(FIRST).second(SECOND);
  }
}
