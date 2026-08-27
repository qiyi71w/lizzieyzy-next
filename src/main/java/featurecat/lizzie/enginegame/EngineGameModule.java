package featurecat.lizzie.enginegame;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineGameInfo;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.DesktopTimeControl;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.util.Utils;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Owns product batch admission, first GamePlan, product transaction identity, and the public
 * Idle/Starting/Playing snapshot. Calls concrete {@link EngineManager} for lifecycle.
 */
public final class EngineGameModule implements EngineGameControl, EngineGameState {
  private final Object lock = new Object();
  private EngineGameSnapshot snapshot = new EngineGameSnapshot.Idle();
  private EngineGameBatchState batch;
  private EngineGamePlan plan;
  private EngineGameTransaction transaction;
  private StartObserver observer;
  private boolean observerCompleted;

  @Override
  public Acceptance accept(EngineGameBatchSpec spec, StartObserver observer) {
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(observer, "observer");
    EngineManager manager = Lizzie.engineManager;
    Rejection rejection = validateSpec(spec);
    if (rejection != null) {
      presentRejection(rejection);
      return new Acceptance.Rejected(rejection);
    }
    if (manager == null) {
      return new Acceptance.Rejected(new Rejection.OccupiedLifecycle());
    }
    int blackIndex = manager.resolveEngineGameParticipant(spec.first());
    int whiteIndex = manager.resolveEngineGameParticipant(spec.second());
    if (blackIndex < 0 || whiteIndex < 0 || blackIndex == whiteIndex) {
      presentRejection(new Rejection.InvalidParticipantCombination());
      return new Acceptance.Rejected(new Rejection.InvalidParticipantCombination());
    }
    synchronized (lock) {
      if (!(snapshot instanceof EngineGameSnapshot.Idle)) {
        return new Acceptance.Rejected(new Rejection.OccupiedLifecycle());
      }
    }
    EngineGameBatchState acceptedBatch = new EngineGameBatchState(spec);
    EngineGamePlan acceptedPlan = createFirstPlan(acceptedBatch, spec, blackIndex, whiteIndex);
    EngineGameTransaction acceptedTransaction = new EngineGameTransaction(acceptedPlan);
    EngineGameInfo gameInfo = EngineGameInfoFactory.from(acceptedPlan);
    synchronized (lock) {
      if (!(snapshot instanceof EngineGameSnapshot.Idle)) {
        return new Acceptance.Rejected(new Rejection.OccupiedLifecycle());
      }
      this.batch = acceptedBatch;
      this.plan = acceptedPlan;
      this.transaction = acceptedTransaction;
      this.observer = observer;
      this.observerCompleted = false;
      publishLocked(
          new EngineGameSnapshot.BatchActive(acceptedBatch.summary(), new GameActivity.Starting()));
    }
    boolean started = manager.startEngineGame(gameInfo);
    if (!started) {
      synchronized (lock) {
        this.observer = null;
        observerCompleted = false;
        clearAcceptedLocked();
        publishLocked(new EngineGameSnapshot.Idle());
      }
      return new Acceptance.Rejected(ownerRejection(manager, gameInfo));
    }
    return new Acceptance.Accepted();

  }

  @Override
  public void stop() {
    StartObserver pending = null;
    boolean starting;
    EngineManager manager;
    synchronized (lock) {
      if (snapshot instanceof EngineGameSnapshot.Idle) {
        return;
      }
      starting =
          snapshot instanceof EngineGameSnapshot.BatchActive active
              && active.activity() instanceof GameActivity.Starting;
      if (starting && observer != null && !observerCompleted) {
        pending = observer;
        observerCompleted = true;
        observer = null;
      }
      clearAcceptedLocked();
      publishLocked(new EngineGameSnapshot.Idle());
      manager = Lizzie.engineManager;
    }
    if (pending != null) {
      pending.startFailed(new StartFailure.CancelledByUser());
    }
    if (manager != null) {
      if (starting) {
        manager.clearEngineGame();
      } else {
        manager.stopEngineGame(-1, true);
      }
    }
  }

  @Override
  public void pause() {
    EngineGameTransaction pausedTransaction = null;
    synchronized (lock) {
      if (snapshot instanceof EngineGameSnapshot.BatchActive active
          && active.activity() instanceof GameActivity.Playing playing
          && playing.runState() == RunState.RUNNING) {
        if (transaction != null) {
          transaction.setPaused(true);
        }
        publishLocked(
            new EngineGameSnapshot.BatchActive(
                active.batch(), new GameActivity.Playing(playing.view(), RunState.PAUSED)));
        pausedTransaction = transaction;
      }
    }
    if (pausedTransaction != null) {
      BottomToolbar toolbar = LizzieFrame.toolbar;
      if (toolbar != null) {
        toolbar.isPkStop = true;
      }
      EngineManager.pauseEngineGame(ownerOf(pausedTransaction));
    }
  }

  @Override
  public void resume() {
    EngineGameTransaction resumedTransaction = null;
    synchronized (lock) {
      if (snapshot instanceof EngineGameSnapshot.BatchActive active
          && active.activity() instanceof GameActivity.Playing playing
          && playing.runState() == RunState.PAUSED) {
        if (transaction != null
            && transaction.plan().playMode() == EngineGamePlayMode.GENMOVE
            && !transaction.genmovePauseSettled()) {
          return;
        }
        if (transaction != null) {
          transaction.setPaused(false);
        }
        publishLocked(
            new EngineGameSnapshot.BatchActive(
                active.batch(), new GameActivity.Playing(playing.view(), RunState.RUNNING)));
        resumedTransaction = transaction;
      }
    }
    if (resumedTransaction != null) {
      BottomToolbar toolbar = LizzieFrame.toolbar;
      if (toolbar != null) {
        toolbar.isPkStop = false;
      }
      EngineManager.resumeEngineGame(ownerOf(resumedTransaction));
    }
  }

  @Override
  public void reviseBatchLimit(int gameCount) {
    synchronized (lock) {
      if (batch == null) {
        return;
      }
      batch.setBatchLimit(gameCount);
      if (snapshot instanceof EngineGameSnapshot.BatchActive active) {
        publishLocked(new EngineGameSnapshot.BatchActive(batch.summary(), active.activity()));
      }
    }
    if (EngineManager.engineGameInfo != null) {
      EngineManager.engineGameInfo.batchNumber = gameCount;
    }
  }

  @Override
  public EngineGameSnapshot current() {
    synchronized (lock) {
      return snapshot;
    }
  }

  public EngineGamePlan firstPlan() {
    synchronized (lock) {
      return plan;
    }
  }

  public EngineGameTransaction transaction() {
    synchronized (lock) {
      return transaction;
    }
  }

  /**
   * Routes a normal per-game outcome. Duplicate or stale owner tokens are inert. This is not
   * {@link #stop()}.
   */
  public boolean complete(GameOutcome outcome, Object ownerToken, int participantIndex) {
    Objects.requireNonNull(outcome, "outcome");
    EngineGameTransaction product;
    synchronized (lock) {
      product = transaction;
      if (product != null) {
        if (ownerToken != null
            && product.lifecycle() != null
            && !product.lifecycle().sameOwner(ownerToken)) {
          return false;
        }
        if (!product.claimComplete()) {
          return false;
        }
      }
    }
    int index = participantIndex;
    if (index < 0 && outcome instanceof GameOutcome.Resign resign && product != null) {
      ParticipantBinding binding =
          resign.side() == EngineGameSide.BLACK
              ? product.blackBinding()
              : product.whiteBinding();
      if (binding != null) {
        index = binding.catalogIndex();
      }
    }
    return EngineManager.stopEngineGameIfCurrent(ownerToken, index, false);
  }

  private static Object ownerOf(EngineGameTransaction product) {
    if (product == null || product.lifecycle() == null) {
      return null;
    }
    return product.lifecycle().ownerToken();
  }


  public void onOwnerPlaying() {
    StartObserver pending = null;
    synchronized (lock) {
      if (batch == null || plan == null) {
        return;
      }
      publishLocked(
          new EngineGameSnapshot.BatchActive(
              batch.summary(), new GameActivity.Playing(plan.view(), RunState.RUNNING)));
      if (observer != null && !observerCompleted) {
        observerCompleted = true;
        pending = observer;
        observer = null;
      }
    }
    if (pending != null) {
      pending.playing();
    }
  }

  public void onOwnerStartFailed(Throwable cause) {
    StartObserver pending = null;
    synchronized (lock) {
      boolean starting =
          snapshot instanceof EngineGameSnapshot.BatchActive active
              && active.activity() instanceof GameActivity.Starting;
      if (!starting) {
        return;
      }
      if (observer != null && !observerCompleted) {
        observerCompleted = true;
        pending = observer;
        observer = null;
      }
      clearAcceptedLocked();
      publishLocked(new EngineGameSnapshot.Idle());
    }
    if (pending != null) {
      pending.startFailed(startFailureFrom(cause));
    }
  }

  public void resetForTest() {
    synchronized (lock) {
      observer = null;
      observerCompleted = false;
      clearAcceptedLocked();
      snapshot = new EngineGameSnapshot.Idle();
    }
  }


  private void clearAcceptedLocked() {
    batch = null;
    plan = null;
    transaction = null;
  }

  private void publishLocked(EngineGameSnapshot next) {
    snapshot = next;
  }

  private static Rejection validateSpec(EngineGameBatchSpec spec) {
    if (spec.first().equals(spec.second())) {
      return new Rejection.InvalidParticipantCombination();
    }
    if (spec.playMode() == EngineGamePlayMode.ANALYSIS) {
      if (invalidAnalysisLimits(spec.firstLimits()) || invalidAnalysisLimits(spec.secondLimits())) {
        return new Rejection.InvalidAnalysisLimits();
      }
    }
    return null;
  }

  private static boolean invalidAnalysisLimits(EngineGameSideLimits limits) {
    return limits.timeSeconds() <= 0 && limits.visits() <= 0 && limits.firstMoveVisits() <= 0;
  }

  private static Rejection ownerRejection(EngineManager manager, EngineGameInfo gameInfo) {
    if (DesktopTimeControl.rejectsEngineGame(
        manager.engineList,
        gameInfo.blackEngineIndex,
        gameInfo.whiteEngineIndex,
        gameInfo.blackTimeMode,
        gameInfo.whiteTimeMode)) {
      return new Rejection.UnsupportedMode();
    }
    return new Rejection.OccupiedLifecycle();
  }

  private static void presentRejection(Rejection rejection) {
    if (rejection instanceof Rejection.InvalidParticipantCombination) {
      showEngineMessage("EngineManager.engineGameSameEngine");
    } else if (rejection instanceof Rejection.InvalidAnalysisLimits) {
      showEngineMessage("EngineManager.engineGameBlackSettingWrong");
    }
  }

  private static void showEngineMessage(String key) {
    if (Lizzie.resourceBundle == null) {
      return;
    }
    Utils.showMsg(Lizzie.resourceBundle.getString(key));
  }

  private static EngineGamePlan createFirstPlan(
      EngineGameBatchState batch, EngineGameBatchSpec spec, int blackIndex, int whiteIndex) {
    List<EngineGameMove> openingMoves = List.of();
    int openingIndex = -1;
    boolean continueGame = false;
    if (spec.opening() instanceof EngineGameOpeningPlan.ContinuePosition continuePosition) {
      openingMoves = continuePosition.moves();
      continueGame = true;
    } else if (spec.opening() instanceof EngineGameOpeningPlan.SgfCatalog catalog
        && !catalog.openings().isEmpty()) {
      if (catalog.strategy() == SgfOpeningStrategy.RANDOM) {
        openingIndex = new Random().nextInt(catalog.openings().size());
      } else {
        openingIndex = batch.sequentialOpeningCursor();
        if (openingIndex < 0 || openingIndex >= catalog.openings().size()) {
          openingIndex = 0;
        }
        batch.setSequentialOpeningCursor(openingIndex + 1);
      }
      openingMoves = catalog.openings().get(openingIndex);
    }
    return new EngineGamePlan(
        spec.first(),
        spec.second(),
        blackIndex,
        whiteIndex,
        spec.playMode(),
        spec.firstLimits(),
        spec.secondLimits(),
        spec.komi(),
        spec.handicap(),
        openingMoves,
        openingIndex,
        continueGame,
        spec.exchangeColors(),
        batch.gameOrdinal(),
        spec.batch(),
        batch.batchLimit(),

        spec.maxMoveLimitEnabled(),
        spec.maxMoves(),
        spec.output());
  }

  private static StartFailure startFailureFrom(Throwable cause) {
    String detail = cause == null || cause.getMessage() == null ? "" : cause.getMessage();
    if (detail.contains("deadline expired")) {
      return new StartFailure.Timeout();
    }
    return new StartFailure.ParticipantStartupFailed();
  }
}
