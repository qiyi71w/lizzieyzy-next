package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SessionRulesSynchronizerTest {
  @Test
  void invalidDeclarationFailsWithoutTouchingEngine() throws IOException {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(9, 9));
    BoardHistoryList.SessionRulesTarget target = history.publishExternalRules("unknown-rules");
    Leelaz engine = new Leelaz("");
    engine.started = true;
    engine.isLoaded = true;
    AtomicInteger applies = new AtomicInteger();
    engine.installMatchRulesTestHook(
        new MatchRulesTestHook() {
          @Override
          public void query(Leelaz current) {
            throw new AssertionError("invalid declarations cannot query");
          }

          @Override
          public void apply(Leelaz current, KataGoRules requested) {
            applies.incrementAndGet();
          }
        });

    SessionRulesSynchronizer.Result result = SessionRulesSynchronizer.synchronize(target, engine);

    assertFalse(result.satisfied());
    assertEquals(SessionRulesSynchronizer.Failure.INVALID_DECLARATION, result.failure());
    assertEquals(0, applies.get());
  }

  @Test
  void startedEngineWithoutCompletedCapabilityDiscoveryIsTransientlyUnavailable()
      throws IOException {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(9, 9));
    BoardHistoryList.SessionRulesTarget target = history.publishExternalRules("chinese");
    Leelaz engine = new Leelaz("");
    engine.started = true;
    engine.isLoaded = false;

    SessionRulesSynchronizer.Result result = SessionRulesSynchronizer.synchronize(target, engine);

    assertFalse(result.satisfied());
    assertEquals(SessionRulesSynchronizer.Failure.ENGINE_UNAVAILABLE, result.failure());
  }

  @Test
  void freshMatchingObservationSkipsSetAndDifferentTargetConfirmsReadback() throws Exception {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(9, 9));
    Leelaz engine = new Leelaz("");
    engine.started = true;
    engine.isLoaded = true;
    AtomicInteger applies = new AtomicInteger();
    engine.installMatchRulesTestHook(
        new MatchRulesTestHook() {
          @Override
          public void query(Leelaz current) {
            current.confirmEngineRulesForTest(KataGoRules.parse("chinese").orElseThrow());
          }

          @Override
          public void apply(Leelaz current, KataGoRules requested) {
            applies.incrementAndGet();
            current.confirmEngineRulesForTest(requested);
          }
        });
    engine.queryEngineRulesOperation().await(1_000L);

    BoardHistoryList.SessionRulesTarget chinese = history.publishExternalRules("chinese");
    assertTrue(SessionRulesSynchronizer.synchronize(chinese, engine).satisfied());
    assertEquals(0, applies.get());

    BoardHistoryList.SessionRulesTarget japanese = history.publishExternalRules("japanese");
    SessionRulesSynchronizer.Result changed =
        SessionRulesSynchronizer.synchronize(japanese, engine);
    assertTrue(changed.satisfied());
    assertEquals(1, applies.get());
    assertTrue(changed.observed().semanticallyEquals(KataGoRules.parse("japanese").orElseThrow()));
  }

  @Test
  void startingEngineWithoutCompletedCapabilityDiscoveryIsRetried() throws IOException {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(9, 9));
    BoardHistoryList.SessionRulesTarget target = history.publishExternalRules("Japanese");
    Leelaz engine = new Leelaz("");
    engine.started = true;
    engine.isLoaded = false;

    SessionRulesSynchronizer.Result result = SessionRulesSynchronizer.synchronize(target, engine);

    assertFalse(result.satisfied());
    assertEquals(SessionRulesSynchronizer.Failure.ENGINE_UNAVAILABLE, result.failure());
  }

  @Test
  void ordinaryAnalysisRequiresConfirmedTargetOrExplicitOverride() throws Exception {
    Board previousBoard = Lizzie.board;
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Config previousConfig = Lizzie.config;
    try {
      Leelaz engine = new Leelaz("");
      engine.started = true;
      engine.isLoaded = true;
      Lizzie.leelaz = engine;
      Lizzie.board = new Board();
      Lizzie.leelaz2 = null;
      Lizzie.config = null;

      BoardHistoryList history = Lizzie.board.getHistory();
      BoardHistoryList.SessionRulesTarget japanese = history.publishExternalRules("japanese");
      assertFalse(SessionRulesSynchronizer.permitsOrdinaryAnalysis(engine));

      engine.installMatchRulesTestHook(
          new MatchRulesTestHook() {
            @Override
            public void query(Leelaz current) {
              current.confirmEngineRulesForTest(KataGoRules.parse("japanese").orElseThrow());
            }

            @Override
            public void apply(Leelaz current, KataGoRules requested) {
              throw new AssertionError("analysis gate test does not apply rules");
            }
          });
      engine.queryEngineRulesOperation().await(1_000L);
      assertTrue(SessionRulesSynchronizer.permitsOrdinaryAnalysis(engine));

      BoardHistoryList.SessionRulesTarget chinese = history.publishExternalRules("chinese");
      assertFalse(SessionRulesSynchronizer.permitsOrdinaryAnalysis(engine));
      history.authorizeAnalysisWithCurrentRules(
          chinese, engine, Lizzie.capturePrimaryEngineGeneration(engine), null);
      assertTrue(SessionRulesSynchronizer.permitsOrdinaryAnalysis(engine));
    } finally {
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void comparisonAnalysisWaitsForBothConfirmedEngines() throws Exception {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(9, 9));
    BoardHistoryList.SessionRulesTarget target = history.publishExternalRules("japanese");
    Leelaz primary = confirmedEngine("japanese");
    Leelaz mirror = new Leelaz("");
    mirror.started = true;
    mirror.isLoaded = true;

    assertFalse(
        SessionRulesSynchronizer.permitsOrdinaryAnalysis(
            target, history, primary, mirror, primary));
    confirmEngine(mirror, "japanese");
    assertTrue(
        SessionRulesSynchronizer.permitsOrdinaryAnalysis(
            target, history, primary, mirror, primary));
  }

  private static Leelaz confirmedEngine(String rules) throws Exception {
    Leelaz engine = new Leelaz("");
    engine.started = true;
    engine.isLoaded = true;
    confirmEngine(engine, rules);
    return engine;
  }

  private static void confirmEngine(Leelaz engine, String rules) throws Exception {
    engine.installMatchRulesTestHook(
        new MatchRulesTestHook() {
          @Override
          public void query(Leelaz current) {
            current.confirmEngineRulesForTest(KataGoRules.parse(rules).orElseThrow());
          }

          @Override
          public void apply(Leelaz current, KataGoRules requested) {
            throw new AssertionError("confirmation helper does not apply rules");
          }
        });
    engine.queryEngineRulesOperation().await(1_000L);
  }
}
