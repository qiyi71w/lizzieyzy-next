package featurecat.lizzie.enginegame;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EngineGameSnapshotTest {
  private static final EngineParticipantIdentity FIRST =
      new EngineParticipantIdentity("black-cmd", "");
  private static final EngineParticipantIdentity SECOND =
      new EngineParticipantIdentity("white-cmd", "");

  @Test
  void idleHasNoProductActivity() {
    EngineGameSnapshot idle = new EngineGameSnapshot.Idle();

    assertFalse(idle.starting());
    assertFalse(idle.playing());
    assertFalse(idle.betweenGames());
    assertFalse(idle.startingOrPlaying());
    assertFalse(idle.playingGenmove());
  }

  @Test
  void startingIsProductVisibleWithoutPlayingGenmove() {
    EngineGameSnapshot starting =
        new EngineGameSnapshot.BatchActive(summary(), new GameActivity.Starting());

    assertTrue(starting.starting());
    assertTrue(starting.startingOrPlaying());
    assertFalse(starting.playing());
    assertFalse(starting.betweenGames());
    assertFalse(starting.playingGenmove());
  }

  @Test
  void playingGenmoveIsOnlyThePlayingGenmoveVariant() {
    EngineGameSnapshot genmove =
        new EngineGameSnapshot.BatchActive(
            summary(),
            new GameActivity.Playing(
                new EngineGameView(FIRST, SECOND, EngineGamePlayMode.GENMOVE, 1),
                RunState.RUNNING));
    EngineGameSnapshot analysis =
        new EngineGameSnapshot.BatchActive(
            summary(),
            new GameActivity.Playing(
                new EngineGameView(FIRST, SECOND, EngineGamePlayMode.ANALYSIS, 1),
                RunState.PAUSED));

    assertTrue(genmove.playing());
    assertTrue(genmove.playingGenmove());
    assertTrue(genmove.startingOrPlaying());
    assertFalse(analysis.playingGenmove());
    assertTrue(analysis.playing());
  }

  @Test
  void betweenGamesIsNotStartingOrPlaying() {
    EngineGameSnapshot between =
        new EngineGameSnapshot.BatchActive(summary(), new GameActivity.BetweenGames());

    assertTrue(between.betweenGames());
    assertFalse(between.startingOrPlaying());
    assertFalse(between.playingGenmove());
  }

  private static BatchSummary summary() {
    return new BatchSummary(
        FIRST, SECOND, 1, 1, false, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, List.of());
  }
}
