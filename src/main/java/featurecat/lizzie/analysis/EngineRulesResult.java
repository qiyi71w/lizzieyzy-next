package featurecat.lizzie.analysis;

/**
 * Per-instance completion of a rules set or readback. Requested value, last successful observation,
 * and the current operation result are distinct. A failed query does not present the last known
 * observation as the current success.
 */
public final class EngineRulesResult {
  public enum Status {
    IDLE,
    PENDING,
    CONFIRMED,
    UNCONFIRMED,
    SET_FAILED,
    QUERY_FAILED,
    CAPABILITY_FAILED
  }

  public enum Reason {
    NONE,
    SET_REJECTED,
    SET_TIMEOUT,
    QUERY_REJECTED,
    QUERY_TIMEOUT,
    INVALID_READBACK,
    QUERY_UNSUPPORTED,
    SET_UNSUPPORTED,
    LIST_COMMANDS_TIMEOUT,
    LIST_COMMANDS_FAILED,
    OCCUPIED,
    SEND_FAILED
  }

  private static final EngineRulesResult IDLE =
      new EngineRulesResult(
          0L, Status.IDLE, Reason.NONE, null, null, false, false, false, -1, -1);

  private final long generation;
  private final Status status;
  private final Reason reason;
  private final KataGoRules requested;
  private final KataGoRules observed;
  private final boolean lastKnownStale;
  private final boolean canSet;
  private final boolean canQuery;
  private final int setCommandId;
  private final int queryCommandId;

  private EngineRulesResult(
      long generation,
      Status status,
      Reason reason,
      KataGoRules requested,
      KataGoRules observed,
      boolean lastKnownStale,
      boolean canSet,
      boolean canQuery,
      int setCommandId,
      int queryCommandId) {
    this.generation = generation;
    this.status = status;
    this.reason = reason;
    this.requested = requested;
    this.observed = observed;
    this.lastKnownStale = lastKnownStale;
    this.canSet = canSet;
    this.canQuery = canQuery;
    this.setCommandId = setCommandId;
    this.queryCommandId = queryCommandId;
  }

  public static EngineRulesResult idle() {
    return IDLE;
  }

  public static EngineRulesResult pending(
      long generation,
      KataGoRules requested,
      KataGoRules lastKnown,
      boolean canSet,
      boolean canQuery) {
    return new EngineRulesResult(
        generation,
        Status.PENDING,
        Reason.NONE,
        requested,
        lastKnown,
        lastKnown != null,
        canSet,
        canQuery,
        -1,
        -1);
  }

  public long generation() {
    return generation;
  }

  public Status status() {
    return status;
  }

  public Reason reason() {
    return reason;
  }

  public KataGoRules requested() {
    return requested;
  }

  public KataGoRules observed() {
    return observed;
  }

  public boolean lastKnownStale() {
    return lastKnownStale;
  }

  public boolean canSet() {
    return canSet;
  }

  public boolean canQuery() {
    return canQuery;
  }

  public int setCommandId() {
    return setCommandId;
  }

  public int queryCommandId() {
    return queryCommandId;
  }

  public boolean isSettled() {
    return status != Status.IDLE && status != Status.PENDING;
  }

  public boolean isConfirmed() {
    return status == Status.CONFIRMED;
  }

  public boolean isUnconfirmed() {
    return status == Status.UNCONFIRMED;
  }

  public boolean isFailed() {
    return status == Status.SET_FAILED
        || status == Status.QUERY_FAILED
        || status == Status.CAPABILITY_FAILED;
  }

  public EngineRulesResult withCommandIds(int setId, int queryId) {
    return new EngineRulesResult(
        generation,
        status,
        reason,
        requested,
        observed,
        lastKnownStale,
        canSet,
        canQuery,
        setId,
        queryId);
  }

  public EngineRulesResult confirmed(KataGoRules observedRules) {
    return new EngineRulesResult(
        generation,
        Status.CONFIRMED,
        Reason.NONE,
        requested,
        observedRules,
        false,
        canSet,
        canQuery,
        setCommandId,
        queryCommandId);
  }

  public EngineRulesResult unconfirmed(Reason unconfirmedReason) {
    return new EngineRulesResult(
        generation,
        Status.UNCONFIRMED,
        unconfirmedReason,
        requested,
        observed,
        observed != null,
        canSet,
        canQuery,
        setCommandId,
        queryCommandId);
  }

  public EngineRulesResult failed(Status failedStatus, Reason failedReason) {
    return new EngineRulesResult(
        generation,
        failedStatus,
        failedReason,
        requested,
        observed,
        observed != null,
        canSet,
        canQuery,
        setCommandId,
        queryCommandId);
  }
}
