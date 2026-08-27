package featurecat.lizzie.enginegame;

import java.util.Objects;

public record EngineGameView(
    EngineParticipantIdentity black,
    EngineParticipantIdentity white,
    EngineGamePlayMode playMode,
    int gameOrdinal,
    int blackIndex,
    int whiteIndex,
    int firstIndex,
    int secondIndex,
    boolean batch,
    int openingIndex) {
  public EngineGameView {
    black = Objects.requireNonNull(black, "black");
    white = Objects.requireNonNull(white, "white");
    playMode = Objects.requireNonNull(playMode, "playMode");
  }

  public boolean firstIsBlack() {
    return firstIndex == blackIndex;
  }

  public boolean genmove() {
    return playMode == EngineGamePlayMode.GENMOVE;
  }
}
