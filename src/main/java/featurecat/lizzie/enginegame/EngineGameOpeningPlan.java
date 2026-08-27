package featurecat.lizzie.enginegame;

import java.util.List;
import java.util.Objects;

public sealed interface EngineGameOpeningPlan {
  record EmptyBoard() implements EngineGameOpeningPlan {}

  record ContinuePosition(List<EngineGameMove> moves) implements EngineGameOpeningPlan {
    public ContinuePosition {
      moves = copyMoves(moves);
    }
  }

  record SgfCatalog(List<List<EngineGameMove>> openings, SgfOpeningStrategy strategy)
      implements EngineGameOpeningPlan {
    public SgfCatalog {
      strategy = Objects.requireNonNull(strategy, "strategy");
      openings = copyCatalog(openings);
    }
  }

  static List<EngineGameMove> copyMoves(List<EngineGameMove> moves) {
    if (moves == null || moves.isEmpty()) {
      return List.of();
    }
    return moves.stream().filter(Objects::nonNull).toList();
  }

  static List<List<EngineGameMove>> copyCatalog(List<List<EngineGameMove>> openings) {
    if (openings == null || openings.isEmpty()) {
      return List.of();
    }
    return openings.stream().map(EngineGameOpeningPlan::copyMoves).toList();
  }
}
