package featurecat.lizzie.enginegame;

import java.util.Objects;

/**
 * Exact per-side binding for one product transaction. Frozen rules come from the GamePlan; writers
 * must not reread toolbar, Config, Board, or current catalog slots.
 */
public final class ParticipantBinding {
  private final EngineGameTransaction transaction;
  private final EngineGameSide side;
  private final int catalogIndex;
  private final EngineGameSideLimits limits;
  private final EngineGamePlayMode playMode;
  private final int maxMoves;

  ParticipantBinding(
      EngineGameTransaction transaction,
      EngineGameSide side,
      int catalogIndex,
      EngineGameSideLimits limits,
      EngineGamePlayMode playMode,
      int maxMoves) {
    this.transaction = transaction;
    this.side = Objects.requireNonNull(side, "side");
    this.catalogIndex = catalogIndex;
    this.limits = Objects.requireNonNull(limits, "limits");
    this.playMode = Objects.requireNonNull(playMode, "playMode");
    this.maxMoves = maxMoves;
  }


  public static ParticipantBinding of(
      EngineGameTransaction transaction,
      EngineGameSide side,
      int catalogIndex,
      EngineGameSideLimits limits,
      EngineGamePlayMode playMode,
      int maxMoves) {
    return new ParticipantBinding(
        transaction, side, catalogIndex, limits, playMode, maxMoves);
  }
  public EngineGameTransaction transaction() {
    return transaction;
  }

  public EngineGameSide side() {
    return side;
  }

  public boolean isBlack() {
    return side == EngineGameSide.BLACK;
  }

  public int catalogIndex() {
    return catalogIndex;
  }

  public EngineGameSideLimits limits() {
    return limits;
  }

  public EngineGamePlayMode playMode() {
    return playMode;
  }

  public int maxGameMoves(int boardWidth, int boardHeight) {
    if (maxMoves > 0) {
      return maxMoves;
    }
    return boardWidth * boardHeight * 2;
  }
}
