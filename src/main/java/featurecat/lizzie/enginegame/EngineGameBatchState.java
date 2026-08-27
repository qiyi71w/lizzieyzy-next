package featurecat.lizzie.enginegame;

import java.util.Objects;

/** Internal mutable batch shell owned only by {@link EngineGameModule}. */
final class EngineGameBatchState {
  private final EngineGameBatchSpec spec;
  private int batchLimit;
  private int gameOrdinal;
  private int sequentialOpeningCursor;

  EngineGameBatchState(EngineGameBatchSpec spec) {
    this.spec = Objects.requireNonNull(spec, "spec");
    this.batchLimit = Math.max(1, spec.initialBatchLimit());
    this.gameOrdinal = 1;
    this.sequentialOpeningCursor = 0;
  }

  EngineGameBatchSpec spec() {
    return spec;
  }

  int batchLimit() {
    return batchLimit;
  }

  void setBatchLimit(int batchLimit) {
    this.batchLimit = batchLimit;
  }

  int gameOrdinal() {
    return gameOrdinal;
  }

  int sequentialOpeningCursor() {
    return sequentialOpeningCursor;
  }

  void setSequentialOpeningCursor(int sequentialOpeningCursor) {
    this.sequentialOpeningCursor = sequentialOpeningCursor;
  }

  BatchSummary summary() {
    return new BatchSummary(
        spec.first(), spec.second(), gameOrdinal, batchLimit, spec.exchangeColors());
  }
}
