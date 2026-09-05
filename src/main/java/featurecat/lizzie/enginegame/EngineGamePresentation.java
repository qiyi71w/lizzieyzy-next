package featurecat.lizzie.enginegame;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.Leelaz;
import java.util.ResourceBundle;

/**
 * Single presentation mapping from sealed State or exact history record. Readers do not copy
 * boolean compatibility logic or read a second current-state bag.
 */
public final class EngineGamePresentation {
  private EngineGamePresentation() {}

  public static EngineGameSnapshot current() {
    return Lizzie.engineGame == null
        ? new EngineGameSnapshot.Idle()
        : Lizzie.engineGame.current();
  }

  public static boolean showLiveBatchScores(EngineGameSnapshot snapshot) {
    EngineGameView view = snapshot == null ? null : snapshot.view();
    return snapshot != null && snapshot.playing() && view != null && view.batch();
  }

  public static int blackWins(EngineGameSnapshot snapshot) {
    if (!(snapshot instanceof EngineGameSnapshot.BatchActive active)) {
      return 0;
    }
    EngineGameView view = snapshot.view();
    if (view != null && !view.firstIsBlack()) {
      return active.batch().secondWins();
    }
    return active.batch().firstWins();
  }

  public static int whiteWins(EngineGameSnapshot snapshot) {
    if (!(snapshot instanceof EngineGameSnapshot.BatchActive active)) {
      return 0;
    }
    EngineGameView view = snapshot.view();
    if (view != null && !view.firstIsBlack()) {
      return active.batch().firstWins();
    }
    return active.batch().secondWins();
  }

  public static EngineGameRecordContext historyContext(GameInfo info) {
    if (info == null) {
      return null;
    }
    if (info.engineGameRecord() != null) {
      return info.engineGameRecord().context();
    }
    return info.engineGameRecordContext();
  }

  public static EngineGameParticipantDescriptor blackDescriptor(GameInfo info) {
    EngineGameRecordContext context = historyContext(info);
    if (context != null) {
      return context.black();
    }
    return null;
  }

  public static EngineGameParticipantDescriptor whiteDescriptor(GameInfo info) {
    EngineGameRecordContext context = historyContext(info);
    if (context != null) {
      return context.white();
    }
    return null;
  }

  public static boolean blackKatago(GameInfo info, EngineGameSnapshot live) {
    EngineGameParticipantDescriptor descriptor = blackDescriptor(info);
    if (descriptor != null) {
      return descriptor.katago();
    }
    Leelaz engine = blackEngine(live);
    return engine != null && engine.isKatago;
  }

  public static boolean whiteKatago(GameInfo info, EngineGameSnapshot live) {
    EngineGameParticipantDescriptor descriptor = whiteDescriptor(info);
    if (descriptor != null) {
      return descriptor.katago();
    }
    Leelaz engine = whiteEngine(live);
    return engine != null && engine.isKatago;
  }

  public static boolean blackSai(GameInfo info, EngineGameSnapshot live) {
    EngineGameParticipantDescriptor descriptor = blackDescriptor(info);
    if (descriptor != null) {
      return descriptor.sai();
    }
    Leelaz engine = blackEngine(live);
    return engine != null && engine.isSai;
  }

  public static boolean whiteSai(GameInfo info, EngineGameSnapshot live) {
    EngineGameParticipantDescriptor descriptor = whiteDescriptor(info);
    if (descriptor != null) {
      return descriptor.sai();
    }
    Leelaz engine = whiteEngine(live);
    return engine != null && engine.isSai;
  }

  public static int blackSpecificRules(GameInfo info, EngineGameSnapshot live) {
    EngineGameParticipantDescriptor descriptor = blackDescriptor(info);
    if (descriptor != null) {
      return descriptor.usingSpecificRules();
    }
    Leelaz engine = blackEngine(live);
    return engine == null ? 0 : engine.usingSpecificRules;
  }

  public static int whiteSpecificRules(GameInfo info, EngineGameSnapshot live) {
    EngineGameParticipantDescriptor descriptor = whiteDescriptor(info);
    if (descriptor != null) {
      return descriptor.usingSpecificRules();
    }
    Leelaz engine = whiteEngine(live);
    return engine == null ? 0 : engine.usingSpecificRules;
  }

  public static Leelaz engine(int index) {
    EngineManager manager = Lizzie.engineManager;
    if (manager == null
        || manager.engineList == null
        || index < 0
        || index >= manager.engineList.size()) {
      return null;
    }
    return manager.engineList.get(index);
  }

  public static Leelaz blackEngine(EngineGameSnapshot snapshot) {
    EngineGameView view = snapshot == null ? null : snapshot.view();
    return view == null ? null : engine(view.blackIndex());
  }

  public static Leelaz whiteEngine(EngineGameSnapshot snapshot) {
    EngineGameView view = snapshot == null ? null : snapshot.view();
    return view == null ? null : engine(view.whiteIndex());
  }

  public static Leelaz firstEngine(EngineGameSnapshot snapshot) {
    EngineGameView view = snapshot == null ? null : snapshot.view();
    return view == null ? null : engine(view.firstIndex());
  }

  public static Leelaz secondEngine(EngineGameSnapshot snapshot) {
    EngineGameView view = snapshot == null ? null : snapshot.view();
    return view == null ? null : engine(view.secondIndex());
  }

  public static Leelaz sideToMoveEngine(EngineGameSnapshot snapshot, boolean blackToPlay) {
    return blackToPlay ? blackEngine(snapshot) : whiteEngine(snapshot);
  }

  public static void sendToParticipants(
      EngineGameSnapshot snapshot, java.util.function.Consumer<Leelaz> action) {
    if (action == null) {
      return;
    }
    Leelaz first = firstEngine(snapshot);
    Leelaz second = secondEngine(snapshot);
    if (first != null) {
      action.accept(first);
    }
    if (second != null) {
      action.accept(second);
    }
  }

  public static boolean participantHasKataGoPda(EngineGameSnapshot snapshot) {
    Leelaz first = firstEngine(snapshot);
    Leelaz second = secondEngine(snapshot);
    return (first != null && first.isKataGoPda) || (second != null && second.isKataGoPda);
  }

  public static GameInfo currentHistoryInfo() {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return null;
    }
    return Lizzie.board.getHistory().getGameInfo();
  }

  public static String matchRulesCaption(
      EngineGameSnapshot snapshot,
      MatchRulesSnapshot live,
      GameInfo info,
      ResourceBundle bundle) {
    if (bundle == null) {
      return "";
    }
    if (info != null) {
      if (info.engineGameRecord() != null && info.engineGameRecord().matchRules() != null) {
        return info.engineGameRecord().matchRules().mainSummary(bundle);
      }
      if (info.engineGameRecordContext() != null
          && info.engineGameRecordContext().matchRules() != null) {
        return info.engineGameRecordContext().matchRules().mainSummary(bundle);
      }
    }
    if (live == null) {
      return "";
    }
    boolean idle = snapshot instanceof EngineGameSnapshot.Idle;
    if (!idle
        || live.phase() == MatchRulesSnapshot.Phase.FAILED
        || live.phase() == MatchRulesSnapshot.Phase.PREPARING) {
      return live.mainSummary(bundle);
    }
    return "";
  }

}
