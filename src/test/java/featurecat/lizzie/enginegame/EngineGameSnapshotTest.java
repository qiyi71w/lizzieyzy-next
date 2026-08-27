package featurecat.lizzie.enginegame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    assertFalse(idle.paused());
    assertFalse(idle.playingGenmove());
    assertNull(idle.view());
  }

  @Test
  void startingIsProductVisibleWithoutPlaying() {
    EngineGameSnapshot starting =
        new EngineGameSnapshot.BatchActive(
            summary(), new GameActivity.Starting(view(false, EngineGamePlayMode.ANALYSIS)));

    assertTrue(starting.starting());
    assertTrue(starting.startingOrPlaying());
    assertFalse(starting.playing());
    assertFalse(starting.betweenGames());
    assertFalse(starting.paused());
    assertFalse(starting.playingGenmove());
    assertEquals(FIRST, starting.view().black());
  }

  @Test
  void playingGenmoveAndPauseAreOnlyPlayingVariants() {
    EngineGameSnapshot genmove =
        new EngineGameSnapshot.BatchActive(
            summary(),
            new GameActivity.Playing(view(false, EngineGamePlayMode.GENMOVE), RunState.RUNNING));
    EngineGameSnapshot pausedAnalysis =
        new EngineGameSnapshot.BatchActive(
            summary(),
            new GameActivity.Playing(view(false, EngineGamePlayMode.ANALYSIS), RunState.PAUSED));

    assertTrue(genmove.playing());
    assertTrue(genmove.playingGenmove());
    assertTrue(genmove.startingOrPlaying());
    assertFalse(genmove.paused());
    assertFalse(pausedAnalysis.playingGenmove());
    assertTrue(pausedAnalysis.playing());
    assertTrue(pausedAnalysis.paused());
  }

  @Test
  void betweenGamesIsNotStartingOrPlaying() {
    EngineGameSnapshot between =
        new EngineGameSnapshot.BatchActive(summary(), new GameActivity.BetweenGames());

    assertTrue(between.betweenGames());
    assertFalse(between.startingOrPlaying());
    assertFalse(between.playing());
    assertFalse(between.paused());
    assertFalse(between.playingGenmove());
    assertNull(between.view());
  }

  private static EngineGameView view(boolean exchanged, EngineGamePlayMode mode) {
    return new EngineGameView(
        exchanged ? SECOND : FIRST,
        exchanged ? FIRST : SECOND,
        mode,
        1,
        exchanged ? 1 : 0,
        exchanged ? 0 : 1,
        0,
        1,
        true,
        -1);
  }

  private static BatchSummary summary() {
    return new BatchSummary(
        FIRST, SECOND, 1, 2, true, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, List.of());
  }
}
