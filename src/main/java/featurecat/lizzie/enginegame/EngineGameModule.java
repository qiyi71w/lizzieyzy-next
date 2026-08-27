package featurecat.lizzie.enginegame;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineGameInfo;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.DesktopTimeControl;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.Utils;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Owns product batch admission, GamePlan, product transaction identity, BatchState statistics,
 * successor planning after exact retirement, and the public snapshot.
 */
public final class EngineGameModule implements EngineGameControl, EngineGameState {
  private final Object lock = new Object();
  private EngineGameSnapshot snapshot = new EngineGameSnapshot.Idle();
  private EngineGameBatchState batch;
  private EngineGamePlan plan;
  private EngineGameTransaction transaction;
  private StartObserver observer;
  private boolean observerCompleted;
  private boolean pendingSuccessor;
  private BatchSummary lastSummary;
  private EngineGameCompletionFacts lastCompletion;

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
    int firstIndex = manager.resolveEngineGameParticipant(spec.first());
    int secondIndex = manager.resolveEngineGameParticipant(spec.second());
    if (firstIndex < 0 || secondIndex < 0 || firstIndex == secondIndex) {
      presentRejection(new Rejection.InvalidParticipantCombination());
      return new Acceptance.Rejected(new Rejection.InvalidParticipantCombination());
    }
    synchronized (lock) {
      if (!(snapshot instanceof EngineGameSnapshot.Idle)) {
        return new Acceptance.Rejected(new Rejection.OccupiedLifecycle());
      }
    }
    EngineGameBatchState acceptedBatch = new EngineGameBatchState(spec, firstIndex, secondIndex);
    EngineGamePlan acceptedPlan = createPlan(acceptedBatch);
    EngineGameTransaction acceptedTransaction = new EngineGameTransaction(acceptedPlan);
    EngineGameInfo gameInfo = EngineGameInfoFactory.from(acceptedPlan, acceptedBatch);
    synchronized (lock) {
      if (!(snapshot instanceof EngineGameSnapshot.Idle)) {
        return new Acceptance.Rejected(new Rejection.OccupiedLifecycle());
      }
      this.batch = acceptedBatch;
      this.plan = acceptedPlan;
      this.transaction = acceptedTransaction;
      this.observer = observer;
      this.observerCompleted = false;
      this.pendingSuccessor = false;
      this.lastCompletion = null;
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
    synchronized (lock) {
      if (batch == acceptedBatch) {
        batch.rememberOutputIdentity(gameInfo.batchGameName, gameInfo.SF);
      }
    }
    return new Acceptance.Accepted();
  }

  @Override
  public void stop() {
    StartObserver pending = null;
    boolean starting;
    boolean betweenGames;
    EngineManager manager;
    synchronized (lock) {
      if (snapshot instanceof EngineGameSnapshot.Idle) {
        return;
      }
      starting =
          snapshot instanceof EngineGameSnapshot.BatchActive active
              && active.activity() instanceof GameActivity.Starting;
      betweenGames =
          snapshot instanceof EngineGameSnapshot.BatchActive active
              && active.activity() instanceof GameActivity.BetweenGames;
      if (starting && observer != null && !observerCompleted) {
        pending = observer;
        observerCompleted = true;
        observer = null;
      }
      pendingSuccessor = false;
      if (batch != null && batch.completedGames() > 0) {
        plan = null;
        transaction = null;
      } else {
        clearAcceptedLocked();
      }
      publishLocked(new EngineGameSnapshot.Idle());
      manager = Lizzie.engineManager;
    }
    if (pending != null) {
      pending.startFailed(new StartFailure.CancelledByUser());
    }
    if (betweenGames) {
      return;
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
      if (batch == null || !(snapshot instanceof EngineGameSnapshot.BatchActive)) {
        return;
      }
      batch.setBatchLimit(gameCount);
      EngineGameSnapshot.BatchActive active = (EngineGameSnapshot.BatchActive) snapshot;
      if (pendingSuccessor
          && active.activity() instanceof GameActivity.BetweenGames
          && !batch.shouldCreateSuccessor()) {
        pendingSuccessor = false;
        plan = null;
        transaction = null;
        publishLocked(new EngineGameSnapshot.Idle());
      } else {
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

  public boolean successorPending() {
    synchronized (lock) {
      return pendingSuccessor;
    }
  }

  public BatchSummary lastSummary() {
    synchronized (lock) {
      return lastSummary;
    }
  }

  public EngineGameCompletionFacts lastCompletion() {
    synchronized (lock) {
      return lastCompletion;
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
        recordCompletionLocked(outcome, product);
        if (batch != null && batch.shouldCreateSuccessor()) {
          pendingSuccessor = true;
          publishLocked(
              new EngineGameSnapshot.BatchActive(
                  batch.summary(), new GameActivity.BetweenGames()));
        } else {
          pendingSuccessor = false;
          plan = null;
          transaction = null;
          publishLocked(new EngineGameSnapshot.Idle());
        }
      }
    }
    EngineManager.syncProductBatchSummaryReaders();
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
    boolean stopped = EngineManager.stopEngineGameIfCurrent(ownerToken, index, false);
    return (product != null && product.alreadyCompleted()) || stopped;
  }

  /**
   * Called after the exact owner retirement callback. Creates the next GamePlan only then.
   *
   * @return false when a pending successor failed to start
   */
  public boolean onOwnerRetired() {
    EngineGameInfo nextInfo;
    synchronized (lock) {
      boolean betweenGames =
          snapshot instanceof EngineGameSnapshot.BatchActive active
              && active.activity() instanceof GameActivity.BetweenGames;
      if (!pendingSuccessor || batch == null || !betweenGames) {
        pendingSuccessor = false;
        return true;
      }
      if (!batch.shouldCreateSuccessor()) {
        pendingSuccessor = false;
        plan = null;
        transaction = null;
        publishLocked(new EngineGameSnapshot.Idle());
        return true;
      }
      pendingSuccessor = false;
      batch.beginSuccessor();
      plan = createPlan(batch);
      transaction = new EngineGameTransaction(plan);
      nextInfo = EngineGameInfoFactory.from(plan, batch);
      publishLocked(
          new EngineGameSnapshot.BatchActive(batch.summary(), new GameActivity.Starting()));
    }
    EngineManager manager = Lizzie.engineManager;
    boolean started = manager != null && manager.startEngineGame(nextInfo, false);
    if (!started) {
      synchronized (lock) {
        plan = null;
        transaction = null;
        publishLocked(new EngineGameSnapshot.Idle());
      }
      return false;
    }
    synchronized (lock) {
      if (batch != null) {
        batch.rememberOutputIdentity(nextInfo.batchGameName, nextInfo.SF);
      }
    }
    return true;
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
        clearAcceptedLocked();
      } else {
        pendingSuccessor = false;
        plan = null;
        transaction = null;
      }
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
      lastSummary = null;
      lastCompletion = null;
      clearAcceptedLocked();
      snapshot = new EngineGameSnapshot.Idle();
    }
  }

  private void recordCompletionLocked(GameOutcome outcome, EngineGameTransaction product) {
    if (batch == null || product == null || product.plan() == null) {
      return;
    }
    EngineGamePlan current = product.plan();
    boolean firstPlayedBlack = current.firstIndex() == current.blackIndex();
    long[] visits = sideVisits();
    long firstVisits = firstPlayedBlack ? visits[0] : visits[1];
    long secondVisits = firstPlayedBlack ? visits[1] : visits[0];
    lastCompletion =
        new EngineGameCompletionFacts(
            outcome,
            current.gameOrdinal(),
            current.openingIndex(),
            firstPlayedBlack,
            engineTime(current.firstIndex()),
            engineTime(current.secondIndex()),
            firstVisits,
            secondVisits);
    batch.apply(lastCompletion);
    lastSummary = batch.summary();
  }

  private void clearAcceptedLocked() {
    pendingSuccessor = false;
    batch = null;
    plan = null;
    transaction = null;
  }

  private void publishLocked(EngineGameSnapshot next) {
    snapshot = next;
    if (next instanceof EngineGameSnapshot.BatchActive active) {
      lastSummary = active.batch();
    }
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

  private static EngineGamePlan createPlan(EngineGameBatchState batch) {
    EngineGameBatchSpec spec = batch.spec();
    boolean firstIsBlack = batch.firstIsBlack();
    EngineParticipantIdentity black = firstIsBlack ? spec.first() : spec.second();
    EngineParticipantIdentity white = firstIsBlack ? spec.second() : spec.first();
    int blackIndex = firstIsBlack ? batch.firstIndex() : batch.secondIndex();
    int whiteIndex = firstIsBlack ? batch.secondIndex() : batch.firstIndex();
    EngineGameSideLimits blackLimits = firstIsBlack ? spec.firstLimits() : spec.secondLimits();
    EngineGameSideLimits whiteLimits = firstIsBlack ? spec.secondLimits() : spec.firstLimits();
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
    if (openingIndex >= 0 && LizzieFrame.toolbar != null) {
      LizzieFrame.toolbar.currentEnginePkSgfNum = openingIndex;
    }
    return new EngineGamePlan(
        black,
        white,
        blackIndex,
        whiteIndex,
        batch.firstIndex(),
        batch.secondIndex(),
        spec.playMode(),
        blackLimits,
        whiteLimits,
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

  private static long engineTime(int index) {
    EngineManager manager = Lizzie.engineManager;
    if (manager == null
        || manager.engineList == null
        || index < 0
        || index >= manager.engineList.size()) {
      return 0L;
    }
    Leelaz engine = manager.engineList.get(index);
    return engine == null ? 0L : engine.pkMoveTimeGame;
  }

  private static long[] sideVisits() {
    long blackPlayouts = 0;
    long whitePlayouts = 0;
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return new long[] {0L, 0L};
    }
    BoardHistoryNode node = Lizzie.board.getHistory().getStart();
    if (node == null) {
      return new long[] {0L, 0L};
    }
    while (node.next().isPresent()) {
      if (node.getData().isHistoryActionNode()
          && node.getData().lastMoveColor.equals(Stone.WHITE)) {
        blackPlayouts += node.getData().getPlayouts();
      }
      if (node.getData().isHistoryActionNode()
          && node.getData().lastMoveColor.equals(Stone.BLACK)) {
        whitePlayouts += node.getData().getPlayouts();
      }
      node = node.next().get();
    }
    if (node.getData().isHistoryActionNode() && node.getData().lastMoveColor.equals(Stone.WHITE)) {
      blackPlayouts += node.getData().getPlayouts();
    }
    if (node.getData().isHistoryActionNode() && node.getData().lastMoveColor.equals(Stone.BLACK)) {
      whitePlayouts += node.getData().getPlayouts();
    }
    return new long[] {blackPlayouts, whitePlayouts};
  }

  private static StartFailure startFailureFrom(Throwable cause) {
    String detail = cause == null || cause.getMessage() == null ? "" : cause.getMessage();
    if (detail.contains("deadline expired")) {
      return new StartFailure.Timeout();
    }
    return new StartFailure.ParticipantStartupFailed();
  }
}
