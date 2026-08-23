package featurecat.lizzie.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable black-perspective AI形势 result bound to one request context. */
public final class AiPositionSnapshot {
  private final AiPositionRequestContext context;
  private final long generation;
  private final long sequence;
  private final double blackScoreLead;
  private final double blackWinrate;
  private final int visits;
  private final List<Double> ownership;

  private AiPositionSnapshot(
      AiPositionRequestContext context,
      long generation,
      long sequence,
      double blackScoreLead,
      double blackWinrate,
      int visits,
      List<Double> ownership) {
    this.context = context;
    this.generation = generation;
    this.sequence = sequence;
    this.blackScoreLead = blackScoreLead;
    this.blackWinrate = blackWinrate;
    this.visits = visits;
    this.ownership = ownership;
  }

  static AiPositionSnapshot from(
      AiPositionRequestContext context, long generation, AiPositionSearchUpdate update) {
    return from(context, generation, 0L, update);
  }

  static AiPositionSnapshot from(
      AiPositionRequestContext context,
      long generation,
      long sequence,
      AiPositionSearchUpdate update) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(update, "update");
    boolean blackToPlay = context.blackToPlay();
    double lead = blackToPlay ? update.sideToMoveScoreLead() : -update.sideToMoveScoreLead();
    double winratePercent =
        (blackToPlay ? update.sideToMoveWinrate() : 1.0 - update.sideToMoveWinrate()) * 100.0;
    double[] rawOwnership = update.sideToMoveOwnership();
    List<Double> ownership = new ArrayList<Double>(rawOwnership.length);
    for (int i = 0; i < rawOwnership.length; i++) {
      ownership.add(blackToPlay ? rawOwnership[i] : -rawOwnership[i]);
    }
    return new AiPositionSnapshot(
        context,
        generation,
        sequence,
        lead,
        Math.max(0.0, Math.min(100.0, winratePercent)),
        update.visits(),
        Collections.unmodifiableList(ownership));
  }

  static AiPositionSnapshot fromBlackPerspective(
      AiPositionRequestContext context,
      long generation,
      long sequence,
      AiPositionSearchUpdate update) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(update, "update");
    double[] rawOwnership = update.sideToMoveOwnership();
    List<Double> ownership = new ArrayList<Double>(rawOwnership.length);
    for (int i = 0; i < rawOwnership.length; i++) {
      ownership.add(rawOwnership[i]);
    }
    return new AiPositionSnapshot(
        context,
        generation,
        sequence,
        update.sideToMoveScoreLead(),
        Math.max(0.0, Math.min(100.0, update.sideToMoveWinrate() * 100.0)),
        update.visits(),
        Collections.unmodifiableList(ownership));
  }

  public AiPositionRequestContext context() {
    return context;
  }

  public long generation() {
    return generation;
  }

  public long sequence() {
    return sequence;
  }

  public double blackScoreLead() {
    return blackScoreLead;
  }

  public double blackWinrate() {
    return blackWinrate;
  }

  public int visits() {
    return visits;
  }

  public String rules() {
    return context.rules();
  }

  public double komi() {
    return context.komi();
  }

  public List<Double> ownership() {
    return ownership;
  }
}
