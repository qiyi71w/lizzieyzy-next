package featurecat.lizzie.enginegame;

import featurecat.lizzie.Lizzie;
import java.util.List;

public final class EngineGameSnapshotFixtures {
  private static final EngineParticipantIdentity FIRST =
      new EngineParticipantIdentity("first-cmd", "First");
  private static final EngineParticipantIdentity SECOND =
      new EngineParticipantIdentity("second-cmd", "Second");

  private EngineGameSnapshotFixtures() {}

  public static EngineGameSnapshot playingAnalysis() {
    return playing(EngineGamePlayMode.ANALYSIS, RunState.RUNNING);
  }

  public static EngineGameSnapshot playing(EngineGamePlayMode mode, RunState runState) {
    return new EngineGameSnapshot.BatchActive(
        summary(),
        new GameActivity.Playing(
            new EngineGameView(FIRST, SECOND, mode, 1, 0, 1, 0, 1, true, -1), runState));
  }

  public static EngineGameSnapshot starting() {
    return new EngineGameSnapshot.BatchActive(
        summary(),
        new GameActivity.Starting(
            new EngineGameView(
                FIRST, SECOND, EngineGamePlayMode.ANALYSIS, 1, 0, 1, 0, 1, true, -1)));
  }

  public static void publishPlaying() {
    Lizzie.engineGame.publishSnapshotForTest(playingAnalysis());
  }

  public static void publishIdle() {
    Lizzie.engineGame.publishSnapshotForTest(new EngineGameSnapshot.Idle());
  }

  private static BatchSummary summary() {
    return new BatchSummary(
        FIRST, SECOND, 1, 1, false, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, List.of());
  }
}
