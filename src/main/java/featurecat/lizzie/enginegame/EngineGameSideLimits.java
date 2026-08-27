package featurecat.lizzie.enginegame;

import java.util.Objects;

public record EngineGameSideLimits(
    EngineGameTimeMode timeMode,
    int timeSeconds,
    String advancedTimeCommand,
    int visits,
    int firstMoveVisits,
    EngineGameResignPolicy resign) {
  public EngineGameSideLimits {
    timeMode = timeMode == null ? EngineGameTimeMode.FIXED : timeMode;
    advancedTimeCommand = advancedTimeCommand == null ? "" : advancedTimeCommand;
    resign = Objects.requireNonNull(resign, "resign");
  }
}
