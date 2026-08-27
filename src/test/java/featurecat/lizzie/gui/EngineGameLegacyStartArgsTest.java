package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.enginegame.EngineGameBatchSpec;
import featurecat.lizzie.enginegame.EngineGameBatchSpecFactory;
import featurecat.lizzie.enginegame.EngineGameParsedStart;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineGameLegacyStartArgsTest {
  @Test
  void liveStartResolvesStableIdentityAfterCatalogReorder() {
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            EngineGameParsedStart.builder()
                .first(new EngineParticipantIdentity("cmd-a", "alpha"))
                .second(new EngineParticipantIdentity("cmd-b", "beta"))
                .genmove(true)
                .timeLimitEnabled(true)
                .firstTimeSeconds(5)
                .secondTimeSeconds(7)
                .exchangeColors(true)
                .batch(true)
                .batchLimit(4)
                .batchName("pk")
                .build());

    List<EngineData> original = List.of(engine("alpha", "cmd-a"), engine("beta", "cmd-b"));
    EngineGameLegacyStartArgs originalArgs = EngineGameLegacyStartArgs.from(spec, original);
    assertEquals(0, originalArgs.engineBlack());
    assertEquals(1, originalArgs.engineWhite());

    List<EngineData> reordered = List.of(engine("beta", "cmd-b"), engine("alpha", "cmd-a"));
    EngineGameLegacyStartArgs reorderedArgs = EngineGameLegacyStartArgs.from(spec, reordered);
    assertEquals(1, reorderedArgs.engineBlack(), "first identity must follow cmd-a off index 0");
    assertEquals(0, reorderedArgs.engineWhite(), "second identity must follow cmd-b off index 1");
    assertEquals(5, reorderedArgs.timeBlack());
    assertEquals(7, reorderedArgs.timeWhite());
    assertTrue(reorderedArgs.genmove());
    assertTrue(reorderedArgs.exchange());
    assertTrue(reorderedArgs.batchGame());
    assertEquals(4, reorderedArgs.batchGameNumber());
    assertEquals("pk", reorderedArgs.batchGameName());
    assertEquals(EngineGamePlayMode.GENMOVE, spec.playMode());
  }

  @Test
  void continueOpeningUnpacksToContinueGameFlag() {
    EngineGameBatchSpec spec =
        EngineGameBatchSpecFactory.from(
            EngineGameParsedStart.builder()
                .first(new EngineParticipantIdentity("cmd-a", "alpha"))
                .second(new EngineParticipantIdentity("cmd-b", "beta"))
                .continueGame(true)
                .build());
    EngineGameLegacyStartArgs args =
        EngineGameLegacyStartArgs.from(
            spec, List.of(engine("alpha", "cmd-a"), engine("beta", "cmd-b")));
    assertTrue(args.continueGame());
    assertFalse(args.genmove());
  }

  private static EngineData engine(String name, String commands) {
    EngineData engine = new EngineData();
    engine.name = name;
    engine.commands = commands;
    return engine;
  }
}
