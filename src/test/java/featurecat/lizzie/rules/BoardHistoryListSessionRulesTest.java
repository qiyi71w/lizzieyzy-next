package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.KataGoRules;
import org.junit.jupiter.api.Test;

class BoardHistoryListSessionRulesTest {
  @Test
  void publishesImmutableTargetsWithDistinctKindsAndMonotonicRevisions() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(9, 9));

    BoardHistoryList.SessionRulesTarget initial = history.captureSessionRules();
    assertEquals(BoardHistoryList.SessionRulesKind.UNSPECIFIED, initial.kind());
    assertEquals(BoardHistoryList.SessionRulesSource.NONE, initial.source());
    assertEquals(0L, initial.revision());
    assertFalse(initial.parsedRules().isPresent());
    assertNull(initial.rawDeclaration());

    BoardHistoryList.SessionRulesTarget missing = history.publishExternalRules(null);
    assertEquals(BoardHistoryList.SessionRulesKind.UNSPECIFIED, missing.kind());
    assertEquals(BoardHistoryList.SessionRulesSource.EXTERNAL_IMPORT, missing.source());
    assertTrue(missing.revision() > initial.revision());
    assertFalse(missing.parsedRules().isPresent());
    assertNull(missing.rawDeclaration());

    BoardHistoryList.SessionRulesTarget invalid = history.publishExternalRules(" ");
    assertEquals(BoardHistoryList.SessionRulesKind.INVALID, invalid.kind());
    assertEquals(BoardHistoryList.SessionRulesSource.EXTERNAL_IMPORT, invalid.source());
    assertEquals(" ", invalid.rawDeclaration());
    assertFalse(invalid.parsedRules().isPresent());
    assertTrue(invalid.revision() > missing.revision());

    BoardHistoryList.SessionRulesTarget valid = history.publishExternalRules("Japanese");
    assertEquals(BoardHistoryList.SessionRulesKind.VALID, valid.kind());
    assertEquals(BoardHistoryList.SessionRulesSource.EXTERNAL_IMPORT, valid.source());
    assertEquals("Japanese", valid.rawDeclaration());
    assertTrue(valid.parsedRules().isPresent());
    assertEquals(KataGoRules.Summary.JAPANESE, valid.parsedRules().orElseThrow().summary());
    assertTrue(valid.revision() > invalid.revision());

    KataGoRules manualRules = KataGoRules.parse("Chinese").orElseThrow();
    BoardHistoryList.SessionRulesTarget manual = history.publishManualRules(manualRules);
    assertEquals(BoardHistoryList.SessionRulesKind.VALID, manual.kind());
    assertEquals(BoardHistoryList.SessionRulesSource.MANUAL_SELECTION, manual.source());
    assertTrue(manual.parsedRules().isPresent());
    assertSame(manualRules, manual.parsedRules().orElseThrow());
    assertTrue(manual.revision() > valid.revision());

    history.add(BoardData.empty(9, 9));
    history.previous();
    history.clear();
    assertSame(manual, history.captureSessionRules());

    assertEquals(BoardHistoryList.SessionRulesKind.UNSPECIFIED, initial.kind());
    assertEquals(BoardHistoryList.SessionRulesSource.NONE, initial.source());
    assertEquals(0L, initial.revision());
    assertFalse(initial.parsedRules().isPresent());
    assertNull(initial.rawDeclaration());
  }

  @Test
  void manualPublicationRequiresTheExactCapturedTarget() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(9, 9));
    BoardHistoryList.SessionRulesTarget imported = history.publishExternalRules("Japanese");
    KataGoRules chinese = KataGoRules.parse("Chinese").orElseThrow();
    BoardHistoryList.ManualRulesIntent first = history.beginManualRulesIntent();

    BoardHistoryList.SessionRulesTarget manual =
        history.publishManualRulesIfCurrent(first, chinese).orElseThrow();

    assertEquals(imported.revision() + 1L, manual.revision());
    assertTrue(history.publishManualRulesIfCurrent(first, chinese).isEmpty());
    assertSame(manual, history.captureSessionRules());

    BoardHistoryList.ManualRulesIntent superseded = history.beginManualRulesIntent();
    BoardHistoryList.ManualRulesIntent latest = history.beginManualRulesIntent();
    assertTrue(history.publishManualRulesIfCurrent(superseded, chinese).isEmpty());
    assertTrue(history.isLatestManualRulesIntent(latest));
    assertSame(manual, history.captureSessionRules());
  }

  @Test
  void analysisOverrideIsBoundToTargetAndEngineSet() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(9, 9));
    BoardHistoryList.SessionRulesTarget target = history.publishExternalRules("Japanese");
    Object primary = new Object();
    Object mirror = new Object();

    history.authorizeAnalysisWithCurrentRules(target, primary, 7L, mirror);
    assertTrue(history.permitsAnalysisWithCurrentRules(target, primary, 7L, mirror));
    assertFalse(history.permitsAnalysisWithCurrentRules(target, primary, 8L, mirror));
    assertFalse(history.permitsAnalysisWithCurrentRules(target, primary, 7L, new Object()));
    history.revokeAnalysisOverride(target);
    assertFalse(history.permitsAnalysisWithCurrentRules(target, primary, 7L, mirror));
    history.authorizeAnalysisWithCurrentRules(target, primary, 7L, mirror);

    BoardHistoryList.SessionRulesTarget replacement = history.publishExternalRules("Chinese");
    assertFalse(history.permitsAnalysisWithCurrentRules(target, primary, 7L, mirror));
    assertFalse(history.permitsAnalysisWithCurrentRules(replacement, primary, 7L, mirror));
  }
}
