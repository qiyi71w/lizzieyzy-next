package featurecat.lizzie.enginegame;

import java.util.Objects;

/** Authoritative product identity for one engine-game transaction. */
public final class EngineGameTransaction {
  private final EngineGamePlan plan;

  EngineGameTransaction(EngineGamePlan plan) {
    this.plan = Objects.requireNonNull(plan, "plan");
  }

  public EngineGamePlan plan() {
    return plan;
  }
}
