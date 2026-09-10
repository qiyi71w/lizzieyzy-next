package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Applies one captured history rules target to one captured engine instance. */
public final class SessionRulesSynchronizer {
  private static final long OPERATION_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30);

  public enum Failure {
    NONE,
    INVALID_DECLARATION,
    ENGINE_UNAVAILABLE,
    CAPABILITY_DISCOVERY_FAILED,
    OPERATION_REJECTED,
    OPERATION_FAILED,
    UNCONFIRMED,
    TARGET_MISMATCH,
    INTERRUPTED
  }

  public static final class Result {
    private final boolean satisfied;
    private final Failure failure;
    private final EngineRulesResult operationResult;
    private final KataGoRules observed;

    private Result(
        boolean satisfied,
        Failure failure,
        EngineRulesResult operationResult,
        KataGoRules observed) {
      this.satisfied = satisfied;
      this.failure = failure;
      this.operationResult = operationResult;
      this.observed = observed;
    }

    public boolean satisfied() {
      return satisfied;
    }

    public Failure failure() {
      return failure;
    }

    public EngineRulesResult operationResult() {
      return operationResult;
    }

    public KataGoRules observed() {
      return observed;
    }
  }

  private SessionRulesSynchronizer() {}

  public static Result capabilityDiscoveryFailed() {
    return failure(Failure.CAPABILITY_DISCOVERY_FAILED, null, null);
  }

  public static Result synchronize(BoardHistoryList.SessionRulesTarget target, Leelaz engine) {
    return synchronize(target, engine, null);
  }

  static boolean permitsOrdinaryAnalysis(Leelaz engine) {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return true;
    }
    BoardHistoryList history = Lizzie.board.getHistory();
    BoardHistoryList.SessionRulesTarget target = history.captureSessionRules();
    Leelaz primary = Lizzie.leelaz;
    Leelaz mirror = primary == null ? null : primary.activeComparisonEngine();
    return permitsOrdinaryAnalysis(target, history, primary, mirror, engine);
  }

  static boolean withOrdinaryAnalysisAdmission(
      Leelaz engine, Leelaz mirroredEngine, Supplier<Boolean> admission) {
    Board board = Lizzie.board;
    if (board == null || board.getHistory() == null) {
      return admission.get();
    }
    BoardHistoryList history = board.getHistory();
    synchronized (history) {
      if (Lizzie.board != board || board.getHistory() != history) {
        return false;
      }
      BoardHistoryList.SessionRulesTarget target = history.captureSessionRules();
      Leelaz primary = Lizzie.leelaz;
      Leelaz mirror = primary == null ? null : primary.activeComparisonEngine();
      if (!permitsOrdinaryAnalysis(target, history, primary, mirror, engine)
          || (mirroredEngine != null
              && !permitsOrdinaryAnalysis(
                  target, history, primary, mirror, mirroredEngine))) {
        return false;
      }
      return admission.get();
    }
  }

  static boolean permitsOrdinaryAnalysis(
      BoardHistoryList.SessionRulesTarget target,
      BoardHistoryList history,
      Leelaz primary,
      Leelaz mirror,
      Leelaz engine) {
    if (target.kind() == BoardHistoryList.SessionRulesKind.UNSPECIFIED) {
      return true;
    }
    if (engine != primary && engine != mirror) {
      return true;
    }
    if (history.permitsAnalysisWithCurrentRules(
        target, primary, Lizzie.capturePrimaryEngineGeneration(primary), mirror)) {
      return true;
    }
    if (target.kind() != BoardHistoryList.SessionRulesKind.VALID
        || target.parsedRules().isEmpty()) {
      return false;
    }
    KataGoRules requested = target.parsedRules().orElseThrow();
    return hasConfirmedRules(primary, requested)
        && (mirror == null || hasConfirmedRules(mirror, requested));
  }

  private static boolean hasConfirmedRules(Leelaz engine, KataGoRules requested) {
    EngineRulesResult current = engine == null ? null : engine.engineRulesResult();
    return current != null
        && current.isConfirmed()
        && current.observed() != null
        && current.observed().semanticallyEquals(requested);
  }

  static Result synchronize(
      BoardHistoryList.SessionRulesTarget target,
      Leelaz engine,
      Leelaz.ExactSnapshotRestoreAdmission restoreAdmission) {
    Objects.requireNonNull(target, "target");
    if (target.kind() == BoardHistoryList.SessionRulesKind.UNSPECIFIED) {
      return success(null, null);
    }
    if (target.kind() != BoardHistoryList.SessionRulesKind.VALID
        || target.parsedRules().isEmpty()) {
      return failure(Failure.INVALID_DECLARATION, null, null);
    }
    if (engine == null || !engine.isStarted() || !engine.isRulesCapabilityDiscoveryComplete()) {
      return failure(Failure.ENGINE_UNAVAILABLE, null, null);
    }
    KataGoRules requested = target.parsedRules().orElseThrow();
    EngineRulesResult current = engine.engineRulesResult();
    if (current != null
        && current.isConfirmed()
        && current.observed() != null
        && current.observed().semanticallyEquals(requested)) {
      return success(current, current.observed());
    }

    Leelaz.EngineRulesOperation[] operation = new Leelaz.EngineRulesOperation[1];
    Runnable start = () -> operation[0] = engine.applyEngineRulesOperation(requested);
    try {
      if (restoreAdmission == null) {
        start.run();
      } else {
        engine.requireExactSnapshotRestoreAdmission(restoreAdmission);
        engine.withExactSnapshotRestoreAdmission(restoreAdmission, start);
      }
    } catch (RuntimeException failure) {
      return failure(Failure.OPERATION_REJECTED, null, null);
    }
    if (operation[0] == null || !operation[0].accepted()) {
      EngineRulesResult result = operation[0] == null ? null : operation[0].result();
      return failure(Failure.OPERATION_REJECTED, result, observed(result));
    }

    EngineRulesResult result;
    try {
      result = operation[0].await(OPERATION_TIMEOUT_MILLIS + TimeUnit.SECONDS.toMillis(1));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return failure(
          Failure.INTERRUPTED, operation[0].snapshot(), observed(operation[0].snapshot()));
    }
    if (result == null) {
      return failure(
          Failure.OPERATION_FAILED, operation[0].snapshot(), observed(operation[0].snapshot()));
    }
    if (!result.isConfirmed() || result.observed() == null) {
      return failure(
          result.status() == EngineRulesResult.Status.UNCONFIRMED
              ? Failure.UNCONFIRMED
              : Failure.OPERATION_FAILED,
          result,
          observed(result));
    }
    if (!result.observed().semanticallyEquals(requested)) {
      return failure(Failure.TARGET_MISMATCH, result, result.observed());
    }
    return success(result, result.observed());
  }

  private static KataGoRules observed(EngineRulesResult result) {
    return result == null ? null : result.observed();
  }

  private static Result success(EngineRulesResult result, KataGoRules observed) {
    return new Result(true, Failure.NONE, result, observed);
  }

  private static Result failure(Failure failure, EngineRulesResult result, KataGoRules observed) {
    return new Result(false, failure, result, observed);
  }
}
