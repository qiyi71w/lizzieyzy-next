package featurecat.lizzie.enginegame;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authoritative product identity for one engine-game transaction. */
public final class EngineGameTransaction {
  private final EngineGamePlan plan;
  private final AtomicBoolean completed = new AtomicBoolean();
  private volatile LifecycleBinding lifecycle;
  private volatile ParticipantBinding blackBinding;
  private volatile ParticipantBinding whiteBinding;
  private volatile boolean paused;
  private volatile boolean genmovePauseSettled;
  private volatile EngineGameSide pendingGenmoveSide;

  EngineGameTransaction(EngineGamePlan plan) {
    this.plan = Objects.requireNonNull(plan, "plan");
  }

  public EngineGamePlan plan() {
    return plan;
  }

  public LifecycleBinding lifecycle() {
    return lifecycle;
  }

  public ParticipantBinding blackBinding() {
    return blackBinding;
  }

  public ParticipantBinding whiteBinding() {
    return whiteBinding;
  }

  public boolean paused() {
    return paused;
  }

  public boolean genmovePauseSettled() {
    return genmovePauseSettled;
  }

  public EngineGameSide pendingGenmoveSide() {
    return pendingGenmoveSide;
  }

  public void attach(
      LifecycleBinding lifecycle,
      ParticipantBinding blackBinding,
      ParticipantBinding whiteBinding) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.blackBinding = Objects.requireNonNull(blackBinding, "blackBinding");
    this.whiteBinding = Objects.requireNonNull(whiteBinding, "whiteBinding");
  }

  public void setPaused(boolean paused) {
    this.paused = paused;
    if (paused && plan.playMode() == EngineGamePlayMode.GENMOVE) {
      genmovePauseSettled = false;
    }
    if (!paused) {
      genmovePauseSettled = false;
    }
  }

  public void recordPendingGenmoveSide(EngineGameSide side) {
    this.pendingGenmoveSide = Objects.requireNonNull(side, "side");
    this.genmovePauseSettled = true;
  }

  public EngineGameSide takePendingGenmoveSide() {
    EngineGameSide side = pendingGenmoveSide;
    pendingGenmoveSide = null;
    genmovePauseSettled = false;
    return side;
  }

  public boolean claimComplete() {
    return completed.compareAndSet(false, true);
  }

  public boolean alreadyCompleted() {
    return completed.get();
  }
}
