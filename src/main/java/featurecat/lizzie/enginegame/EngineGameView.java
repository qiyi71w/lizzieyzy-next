package featurecat.lizzie.enginegame;

import java.util.Objects;

public record EngineGameView(
    EngineParticipantIdentity black,
    EngineParticipantIdentity white,
    EngineGamePlayMode playMode,
    int gameOrdinal) {
  public EngineGameView {
    black = Objects.requireNonNull(black, "black");
    white = Objects.requireNonNull(white, "white");
    playMode = Objects.requireNonNull(playMode, "playMode");
  }
}
