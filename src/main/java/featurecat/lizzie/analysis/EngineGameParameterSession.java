package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineGameParticipantCommands.Leg;
import featurecat.lizzie.analysis.EngineManager.EngineGameOwnerTransaction;
import featurecat.lizzie.enginegame.EngineGameOpeningParameters;
import featurecat.lizzie.enginegame.EngineGameParameterRequest;
import featurecat.lizzie.enginegame.EngineGameParameterRevision;
import featurecat.lizzie.enginegame.EngineGameParameterState;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.rules.Board;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Breadth ({@code analysisWideRootNoise}) of one analysis-mode engine-game batch.
 *
 * <p>Keeps apart the batch's frozen opening settings, the current game's requested targets, the
 * values each participant process confirmed by readback, and the value each participant process had
 * before the batch first changed it. Each game applies the opening settings before analysis starts;
 * in-game changes stay with that game. When the batch ends, only the exact captured processes are
 * restored; a replacement process is never written with another process's record. {@link Leelaz}
 * keeps command queuing, numbered responses, timeouts and output arbitration.
 */
public final class EngineGameParameterSession {
  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.ENGINE);
  private static final String WRN = "analysisWideRootNoise";

  private final EngineGameOpeningParameters opening;

  /** The game board whose analysis cache a confirmed change invalidates; may be null in tests. */
  private final Board board;

  private final AtomicBoolean restoreClaimed = new AtomicBoolean();
  private final CompletableFuture<Void> restored = new CompletableFuture<>();

  /** Guarded by this. */
  private final List<Original> originals = new ArrayList<>();

  /** Guarded by this. */
  private EngineGameParameterSession predecessor;

  /** Guarded by this; reads of {@link Game#blocked} are lock-free. */
  private volatile Game game;

  private record Original(String label, Leelaz engine, Object incarnation, double wrn) {}

  /** One game's parameter state. Arrays are indexed by position: 0 = Engine 1, 1 = Engine 2. */
  private static final class Game {
    private final EngineGameOwnerTransaction owner;
    private final boolean[] supported = new boolean[2];
    private final Double[] confirmed = new Double[2];
    private final Double[] target = new Double[2];
    private boolean pending;
    private boolean workerRunning;
    private String failure;
    private volatile boolean blocked;

    private Game(EngineGameOwnerTransaction owner) {
      this.owner = owner;
    }

    private void refreshBlocked() {
      blocked = pending || failure != null;
    }
  }

  private EngineGameParameterSession(
      EngineGameOpeningParameters opening, Board board, EngineGameParameterSession predecessor) {
    this.opening = Objects.requireNonNull(opening, "opening");
    this.board = board;
    this.predecessor = predecessor;
  }

  /**
   * Session for a new batch, or null for genmove batches, which keep their existing behavior. An
   * unfinished predecessor is restored before the new batch captures anything.
   */
  static EngineGameParameterSession forBatch(
      featurecat.lizzie.enginegame.EngineGamePlan plan,
      Board board,
      EngineGameParameterSession predecessor) {
    if (plan.genmove()) {
      if (predecessor != null) predecessor.restoreAsync();
      return null;
    }
    return new EngineGameParameterSession(plan.openingParameters(), board, predecessor);
  }

  /**
   * Restores the batch's processes once a retired game leaves the batch idle. The stop path calls
   * this before releasing the participants; retirement completion and the successor continuation
   * are fallbacks for failed, cancelled or between-games endings and must not block their caller.
   * While a successor or a newer batch is active, the newer game's opening restores this batch
   * first.
   */
  static void restoreIfBatchEnded(EngineGameOwnerTransaction transaction, boolean async) {
    EngineGameParameterSession session = transaction.parameters;
    if (session == null
        || (Lizzie.engineGame != null
            && !(Lizzie.engineGame.current() instanceof EngineGameSnapshot.Idle))) {
      return;
    }
    if (async) {
      session.restoreAsync();
    } else {
      session.restoreOnce();
    }
  }

  // ---- Product-facing reads and requests -------------------------------------------------

  public static EngineGameParameterState state(Object ownerToken) {
    if (!(ownerToken instanceof EngineGameOwnerTransaction owner) || owner.parameters == null) {
      return null;
    }
    return owner.parameters.stateFor(owner);
  }

  /** Records one apply request as the game's latest target; only the update worker writes. */
  public static EngineGameParameterRevision revise(
      Object ownerToken, EngineGameParameterRequest request, Runnable onChange) {
    if (!(ownerToken instanceof EngineGameOwnerTransaction owner)
        || owner.parameters == null
        || request == null) {
      return EngineGameParameterRevision.NOT_PLAYING;
    }
    return owner.parameters.reviseFor(owner, request, onChange);
  }

  static boolean blocksSearch(EngineGameOwnerTransaction owner) {
    EngineGameParameterSession session = owner.parameters;
    Game current = session == null ? null : session.game;
    return current != null && current.owner == owner && current.blocked;
  }

  private synchronized EngineGameParameterState stateFor(EngineGameOwnerTransaction owner) {
    Game current = game;
    if (current == null || current.owner != owner) {
      return null;
    }
    return new EngineGameParameterState(
        position(current, 0), position(current, 1), current.pending, current.failure);
  }

  private static EngineGameParameterState.Position position(Game current, int p) {
    Double target = current.target[p];
    boolean unmet = target != null && !same(target, current.confirmed[p]);
    return new EngineGameParameterState.Position(
        current.supported[p], current.confirmed[p], unmet ? target : null);
  }

  private EngineGameParameterRevision reviseFor(
      EngineGameOwnerTransaction owner, EngineGameParameterRequest request, Runnable onChange) {
    // Stage restriction: PDA overrides are not migrated to this panel yet. Reject before any write.
    if (request.changesPda()) {
      return EngineGameParameterRevision.UNSUPPORTED;
    }
    for (int p = 0; p < 2; p++) {
      Double value = request.wrn(p == 0);
      if (value != null && !EngineGameOpeningParameters.validWrn(value)) {
        return EngineGameParameterRevision.INVALID;
      }
    }
    Game current;
    boolean start;
    synchronized (this) {
      current = game;
      if (current == null
          || current.owner != owner
          || owner.phase() != EngineManager.EngineGamePhase.ACTIVE
          || owner.isGenmove()
          || !EngineManager.isCurrentEngineGameTransaction(owner)) {
        return EngineGameParameterRevision.NOT_PLAYING;
      }
      Double[] next = current.target.clone();
      for (int p = 0; p < 2; p++) {
        Double value = request.wrn(p == 0);
        if (value == null) continue;
        if (!current.supported[p]) return EngineGameParameterRevision.UNSUPPORTED;
        next[p] = value;
      }
      if (!current.pending && current.failure == null && !unmet(next, current.confirmed)) {
        return EngineGameParameterRevision.UNCHANGED;
      }
      System.arraycopy(next, 0, current.target, 0, 2);
      current.pending = true;
      current.refreshBlocked();
      start = !current.workerRunning;
      current.workerRunning = true;
    }
    onChange.run();
    if (start
        && !owner.searchUpdates.submit(
            "engine-game-parameters-" + owner.epoch(), () -> runUpdate(current, onChange))) {
      fail(current, "Could not schedule the engine-game parameter update", onChange);
    }
    return EngineGameParameterRevision.ACCEPTED;
  }

  // ---- Opening -------------------------------------------------------------------------

  /**
   * Applies the batch's opening settings to both participants before the game's first analysis. The
   * first time a participant process appears in this batch, its actual value (after normal startup
   * and auto-load) is captured as the value to restore when the batch ends. Throws when a
   * participant cannot be captured, set or confirmed; the game must not start then.
   */
  void applyOpening(EngineGameOwnerTransaction owner) throws InterruptedException {
    finishPredecessor(owner.manager.engineGameStartupTimeoutMillis());
    owner.parameters = this;
    Game next = new Game(owner);
    List<Leg> captures = new ArrayList<>();
    double[] captured = new double[2];
    boolean[] capturing = new boolean[2];
    for (int p = 0; p < 2; p++) {
      Leelaz engine = engine(owner, p);
      next.supported[p] = engine.isKatago;
      if (!next.supported[p]) {
        if (opening.wrn(p == 0) != 0) {
          throw new IllegalStateException(label(p) + " does not support " + WRN);
        }
        continue;
      }
      if (original(engine, incarnation(owner, p)) == null) {
        int position = p;
        capturing[p] = true;
        captures.add(
            new Leg(
                label(p),
                engine,
                incarnation(owner, p),
                "kata-get-param " + WRN,
                payload -> {
                  Double value = parse(payload);
                  if (value == null) return "unreadable " + WRN + " '" + payload + "'";
                  captured[position] = value;
                  return null;
                }));
      }
    }
    // Every participant is checked and captured before the first write.
    EngineGameParticipantCommands.confirmAll(owner, captures);
    synchronized (this) {
      for (int p = 0; p < 2; p++) {
        if (capturing[p]) {
          originals.add(
              new Original(label(p), engine(owner, p), incarnation(owner, p), captured[p]));
        }
      }
    }
    Double[] values = new Double[2];
    for (int p = 0; p < 2; p++) {
      if (next.supported[p]) values[p] = opening.wrn(p == 0);
    }
    applyValues(owner, values, null);
    synchronized (this) {
      for (int p = 0; p < 2; p++) {
        next.confirmed[p] = values[p];
        next.target[p] = values[p];
      }
      next.refreshBlocked();
      game = next;
    }
  }

  // ---- In-game update ------------------------------------------------------------------

  private void runUpdate(Game current, Runnable onChange) {
    EngineGameOwnerTransaction owner = current.owner;
    try {
      // Stop both searches and settle earlier position commands. A move that already passed its
      // commit gate may finish; uncommitted results lost their right to play when pending was set.
      EngineGameParticipantCommands.confirmAll(
          owner,
          List.of(
              new Leg(label(0), engine(owner, 0), incarnation(owner, 0), null, null),
              new Leg(label(1), engine(owner, 1), incarnation(owner, 1), null, null)));
      long deadline =
          System.nanoTime()
              + TimeUnit.MILLISECONDS.toNanos(owner.manager.engineGameStartupTimeoutMillis());
      while (EngineManager.isCurrentEngineGameTransaction(owner)
          && owner.operationsInFlight.get() > 1) {
        if (System.nanoTime() >= deadline) {
          throw new IllegalStateException("Timed out waiting for the current engine-game turn");
        }
        Thread.sleep(10L);
      }
      if (!EngineManager.isCurrentEngineGameTransaction(owner)) return;
      owner.mutationLock.lock();
      try {
        owner.searchRevision++;
      } finally {
        owner.mutationLock.unlock();
      }
      // The update consumes the current move's budget instead of starting a new one.
      long[] ponderStarts = {owner.blackEngine.startPonderTime, owner.whiteEngine.startPonderTime};
      long[] moveStarts = {owner.blackEngine.pkMoveStartTime, owner.whiteEngine.pkMoveStartTime};
      while (true) {
        Double[] target;
        Double[] write = new Double[2];
        synchronized (this) {
          target = current.target.clone();
          for (int p = 0; p < 2; p++) {
            if (target[p] != null && !same(target[p], current.confirmed[p])) {
              write[p] = target[p];
              // A timed-out setter may still apply later; the value is unknown until confirmed.
              current.confirmed[p] = null;
            }
          }
        }
        applyValues(owner, write, current);
        boolean again;
        boolean resume = false;
        owner.mutationLock.lock();
        try {
          if (!EngineManager.isCurrentEngineGameTransaction(owner)) return;
          synchronized (this) {
            again = unmet(current.target, current.confirmed);
            if (!again) {
              current.pending = false;
              current.failure = null;
              current.workerRunning = false;
              current.refreshBlocked();
            }
          }
          if (!again) {
            Board board = this.board;
            if (board != null) {
              synchronized (board) {
                board.clearBestMovesAfter(board.getHistory().getStart());
              }
            }
            resume = !owner.paused && (owner.product == null || !owner.product.paused());
          }
        } finally {
          owner.mutationLock.unlock();
        }
        onChange.run();
        if (!again) {
          if (resume && EngineManager.isCurrentEngineGameTransaction(owner)) {
            EngineManager.continueEngineGame(owner);
            owner.blackEngine.startPonderTime = ponderStarts[0];
            owner.whiteEngine.startPonderTime = ponderStarts[1];
            owner.blackEngine.pkMoveStartTime = moveStarts[0];
            owner.whiteEngine.pkMoveStartTime = moveStarts[1];
          }
          return;
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      fail(current, "Engine-game parameter update interrupted", onChange);
    } catch (RuntimeException failure) {
      fail(current, failure.getMessage(), onChange);
    }
  }

  /**
   * Sets and reads back each non-null value. With a game, every confirmed position is published as
   * it is verified so a partial failure still reports which participant holds which value.
   */
  private void applyValues(EngineGameOwnerTransaction owner, Double[] values, Game current)
      throws InterruptedException {
    List<Leg> sets = new ArrayList<>();
    List<Integer> positions = new ArrayList<>();
    for (int p = 0; p < 2; p++) {
      if (values[p] == null) continue;
      positions.add(p);
      sets.add(
          new Leg(
              label(p),
              engine(owner, p),
              incarnation(owner, p),
              "kata-set-param " + WRN + " " + plain(values[p]),
              null));
    }
    if (sets.isEmpty()) return;
    String[] setFailures = EngineGameParticipantCommands.confirm(owner, sets, false);
    List<Leg> reads = new ArrayList<>();
    List<Integer> readPositions = new ArrayList<>();
    for (int i = 0; i < sets.size(); i++) {
      if (setFailures[i] != null) continue;
      int p = positions.get(i);
      readPositions.add(p);
      reads.add(verifyLeg(label(p), engine(owner, p), incarnation(owner, p), values[p]));
    }
    String[] readFailures = EngineGameParticipantCommands.confirm(owner, reads, false);
    StringBuilder failure = new StringBuilder();
    for (String detail : setFailures) append(failure, detail);
    for (int i = 0; i < reads.size(); i++) {
      int p = readPositions.get(i);
      if (readFailures[i] != null) {
        append(failure, readFailures[i]);
        continue;
      }
      engine(owner, p).wrn = values[p];
      if (current != null) {
        synchronized (this) {
          current.confirmed[p] = values[p];
        }
      }
    }
    if (failure.length() > 0) {
      throw new IllegalStateException(failure.toString());
    }
  }

  /** A failed update keeps the game paused; only a later successful apply resolves it. */
  private void fail(Game current, String detail, Runnable onChange) {
    EngineGameOwnerTransaction owner = current.owner;
    synchronized (this) {
      current.pending = false;
      current.workerRunning = false;
      current.failure = detail == null ? "Engine-game parameter update failed" : detail;
      current.refreshBlocked();
    }
    LOG.warn("Engine-game parameter update failed: {}", current.failure);
    if (EngineManager.isCurrentEngineGameTransaction(owner)) {
      EngineManager.pauseEngineGame(owner);
    }
    onChange.run();
  }

  // ---- Batch restore -------------------------------------------------------------------

  /** Restores the batch's captured processes once. Safe to call from every batch-ending path. */
  void restoreOnce() {
    if (!restoreClaimed.compareAndSet(false, true)) {
      return;
    }
    try {
      restoreOriginals();
    } finally {
      restored.complete(null);
    }
  }

  void restoreAsync() {
    if (restoreClaimed.get()) {
      return;
    }
    Thread worker = new Thread(this::restoreOnce, "engine-game-parameter-restore");
    worker.setDaemon(true);
    worker.start();
  }

  private void restoreOriginals() {
    List<Original> records;
    synchronized (this) {
      records = List.copyOf(originals);
      game = null;
    }
    List<String> failures = new ArrayList<>();
    for (Original original : records) {
      if (!original.engine().isCurrentLiveEngineIncarnation(original.incarnation())) {
        // The captured process is gone; its replacement keeps its own startup state and is never
        // written with this record. Its batch value could not be restored, so say so.
        String gone =
            original.label() + ": the engine process changed before " + WRN + " could be restored";
        failures.add(gone);
        LOG.warn("Engine-game parameter restore failed: {}", gone);
        continue;
      }
      String detail;
      try {
        detail =
            first(
                EngineGameParticipantCommands.confirm(
                    null,
                    List.of(
                        new Leg(
                            original.label(),
                            original.engine(),
                            original.incarnation(),
                            "kata-set-param " + WRN + " " + plain(original.wrn()),
                            null)),
                    true));
        if (detail == null) {
          detail =
              first(
                  EngineGameParticipantCommands.confirm(
                      null,
                      List.of(
                          verifyLeg(
                              original.label(),
                              original.engine(),
                              original.incarnation(),
                              original.wrn())),
                      true));
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        detail = original.label() + ": restore interrupted";
      }
      if (detail == null) {
        original.engine().wrn = original.wrn();
        continue;
      }
      // The process may hold a game value: stop that exact process so it is not reused as if
      // restored. A replacement process is untouched.
      failures.add(detail);
      LOG.warn("Engine-game parameter restore failed: {}", detail);
      original.engine().forceQuitIfCurrentIncarnation(original.incarnation());
    }
    if (!failures.isEmpty() && Lizzie.engineGame != null) {
      Lizzie.engineGame.reportParameterRestoreFailure(String.join("; ", failures));
    }
  }

  private void finishPredecessor(long timeoutMillis) throws InterruptedException {
    EngineGameParameterSession previous;
    synchronized (this) {
      previous = predecessor;
      predecessor = null;
    }
    if (previous == null) {
      return;
    }
    previous.restoreOnce();
    try {
      previous.restored.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (ExecutionException | TimeoutException unfinished) {
      throw new IllegalStateException("The previous engine-game batch is still restoring");
    }
  }

  // ---- Helpers -------------------------------------------------------------------------

  private synchronized Original original(Leelaz engine, Object incarnation) {
    for (Original original : originals) {
      if (original.engine() == engine && original.incarnation() == incarnation) return original;
    }
    return null;
  }

  private static Leg verifyLeg(String label, Leelaz engine, Object incarnation, double expected) {
    return new Leg(
        label,
        engine,
        incarnation,
        "kata-get-param " + WRN,
        payload -> {
          Double actual = parse(payload);
          return actual != null && same(actual, expected)
              ? null
              : "readback '" + payload + "' does not match " + plain(expected);
        });
  }

  private static Leelaz engine(EngineGameOwnerTransaction owner, int position) {
    return black(owner, position) ? owner.blackEngine : owner.whiteEngine;
  }

  private static Object incarnation(EngineGameOwnerTransaction owner, int position) {
    return black(owner, position) ? owner.blackIncarnation : owner.whiteIncarnation;
  }

  private static boolean black(EngineGameOwnerTransaction owner, int position) {
    return (position == 0) == owner.plan.firstIsBlack();
  }

  private static String label(int position) {
    return position == 0 ? "Engine 1" : "Engine 2";
  }

  private static boolean unmet(Double[] target, Double[] confirmed) {
    for (int p = 0; p < 2; p++) {
      if (target[p] != null && !same(target[p], confirmed[p])) return true;
    }
    return false;
  }

  private static boolean same(Double expected, Double actual) {
    return actual != null && Math.abs(expected - actual) <= 1e-9;
  }

  private static Double parse(String payload) {
    try {
      double value = Double.parseDouble(payload.trim());
      return Double.isFinite(value) ? value : null;
    } catch (NumberFormatException | NullPointerException invalid) {
      return null;
    }
  }

  private static String plain(double value) {
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  private static String first(String[] failures) {
    for (String failure : failures) {
      if (failure != null) return failure;
    }
    return null;
  }

  private static void append(StringBuilder failure, String detail) {
    if (detail == null) return;
    if (failure.length() > 0) failure.append("; ");
    failure.append(detail);
  }
}
