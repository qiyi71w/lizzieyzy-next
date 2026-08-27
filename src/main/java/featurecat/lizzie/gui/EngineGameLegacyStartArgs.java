package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.enginegame.EngineGameBatchSpec;
import featurecat.lizzie.enginegame.EngineGameOpeningPlan;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import java.util.List;
import java.util.Objects;

/** Unpacks {@link EngineGameBatchSpec} into the current 16-scalar {@code startEngineGame} call. */
public record EngineGameLegacyStartArgs(
    int engineBlack,
    int engineWhite,
    int timeBlack,
    int timeWhite,
    int playoutsBlack,
    int playoutsWhite,
    int firstPlayoutsBlack,
    int firstPlayoutsWhite,
    boolean batchGame,
    int batchGameNumber,
    String batchGameName,
    boolean continueGame,
    boolean genmove,
    boolean exchange,
    boolean checkGameMaxMove,
    int maxGameMoves) {

  public static EngineGameLegacyStartArgs from(
      EngineGameBatchSpec spec, List<EngineData> engines) {
    Objects.requireNonNull(spec, "spec");
    EngineParticipantIdentity first = spec.first();
    EngineParticipantIdentity second = spec.second();
    return new EngineGameLegacyStartArgs(
        EnginePkIdentity.resolveIndex(engines, first.commands(), first.name()),
        EnginePkIdentity.resolveIndex(engines, second.commands(), second.name()),
        spec.firstLimits().timeSeconds(),
        spec.secondLimits().timeSeconds(),
        spec.firstLimits().visits(),
        spec.secondLimits().visits(),
        spec.firstLimits().firstMoveVisits(),
        spec.secondLimits().firstMoveVisits(),
        spec.batch(),
        spec.initialBatchLimit(),
        spec.output().batchName(),
        spec.opening() instanceof EngineGameOpeningPlan.ContinuePosition,
        spec.playMode() == EngineGamePlayMode.GENMOVE,
        spec.exchangeColors(),
        spec.maxMoveLimitEnabled(),
        spec.maxMoves());
  }

  public boolean submit(EngineManager manager) {
    return manager.startEngineGame(
        engineBlack,
        engineWhite,
        timeBlack,
        timeWhite,
        playoutsBlack,
        playoutsWhite,
        firstPlayoutsBlack,
        firstPlayoutsWhite,
        batchGame,
        batchGameNumber,
        batchGameName,
        continueGame,
        genmove,
        exchange,
        checkGameMaxMove,
        maxGameMoves);
  }
}
