package featurecat.lizzie.enginegame;

import featurecat.lizzie.analysis.EngineGameInfo;
import featurecat.lizzie.gui.DesktopTimeControl;
import featurecat.lizzie.gui.SgfWinLossList;
import featurecat.lizzie.rules.Movelist;
import java.util.ArrayList;
import java.util.List;

/** Builds the still-live {@link EngineGameInfo} bag from a frozen {@link EngineGamePlan}. */
final class EngineGameInfoFactory {
  private EngineGameInfoFactory() {}

  static EngineGameInfo from(EngineGamePlan plan, EngineGameBatchState batch) {
    EngineGameInfo info = new EngineGameInfo();
    info.blackEngineIndex = plan.blackIndex();
    info.whiteEngineIndex = plan.whiteIndex();
    info.firstEngineIndex = plan.firstIndex();
    info.secondEngineIndex = plan.secondIndex();
    info.isGenmove = plan.playMode() == EngineGamePlayMode.GENMOVE;
    info.blackTimeMode = EngineGameTimeModes.sideMode(plan.blackLimits().timeMode());
    info.whiteTimeMode = EngineGameTimeModes.sideMode(plan.whiteLimits().timeMode());
    info.advanceBlackTimeCmd = plan.blackLimits().advancedTimeCommand();
    info.advanceWhiteTimeCmd = plan.whiteLimits().advancedTimeCommand();
    int timeBlack = plan.blackLimits().timeSeconds();
    int timeWhite = plan.whiteLimits().timeSeconds();
    if (info.isGenmove) {
      timeBlack =
          DesktopTimeControl.fixedSecondsForToolbar(
              info.blackTimeMode, timeBlack > 0, timeBlack);
      timeWhite =
          DesktopTimeControl.fixedSecondsForToolbar(
              info.whiteTimeMode, timeWhite > 0, timeWhite);
    }
    info.timeBlack = timeBlack;
    info.timeWhite = timeWhite;
    boolean firstIsBlack = plan.firstIndex() == plan.blackIndex();
    info.timeFirstEngine = firstIsBlack ? timeBlack : timeWhite;
    info.timeSecondEngine = firstIsBlack ? timeWhite : timeBlack;
    info.playoutsBlack = plan.blackLimits().visits();
    info.playoutsWhite = plan.whiteLimits().visits();
    info.playoutsFirstEngine = firstIsBlack ? info.playoutsBlack : info.playoutsWhite;
    info.playoutsSecondEngine = firstIsBlack ? info.playoutsWhite : info.playoutsBlack;
    info.firstPlayoutsBlack = plan.blackLimits().firstMoveVisits();
    info.firstPlayoutsWhite = plan.whiteLimits().firstMoveVisits();
    info.firstPlayoutsFirstEngine =
        firstIsBlack ? info.firstPlayoutsBlack : info.firstPlayoutsWhite;
    info.firstPlayoutsSecondEngine =
        firstIsBlack ? info.firstPlayoutsWhite : info.firstPlayoutsBlack;
    info.isBatchGame = plan.batch();
    info.batchNumber = plan.batchLimit();
    info.batchNumberCurrent = plan.gameOrdinal();
    info.isExchange = plan.exchangeColors();
    info.isContinueGame = plan.continueGame();
    info.handicap = plan.handicap();
    info.komi = plan.komi();
    info.blackMinMove = plan.blackLimits().resign().minMove();
    info.blackResignMoveCounts = plan.blackLimits().resign().consecutiveMoves();
    info.blackResignWinrate = plan.blackLimits().resign().winrate();
    info.whiteMinMove = plan.whiteLimits().resign().minMove();
    info.whiteResignMoveCounts = plan.whiteLimits().resign().consecutiveMoves();
    info.whiteResignWinrate = plan.whiteLimits().resign().winrate();
    if (plan.maxMoveLimitEnabled()) {
      info.setMaxGameMoves(plan.maxMoves());
    } else {
      info.setMaxGameMoves(-1);
    }
    info.batchGameName =
        batch != null && !batch.batchGameName().isEmpty()
            ? batch.batchGameName()
            : plan.output().batchName();
    if (batch != null && !batch.timestamp().isEmpty()) {
      info.SF = batch.timestamp();
    }
    info.openingFrozen = true;
    info.frozenStartList = toMoveList(plan.openingMoves());
    if (info.isContinueGame) {
      info.continueGameList = info.frozenStartList;
    }
    if (batch != null) {
      BatchSummary summary = batch.summary();
      info.firstEngineWinAsBlack = summary.firstWinAsBlack();
      info.firstEngineWinAsWhite = summary.firstWinAsWhite();
      info.secondEngineWinAsBlack = summary.secondWinAsBlack();
      info.secondEngineWinAsWhite = summary.secondWinAsWhite();
      info.doublePassGame = summary.doublePassGames();
      info.maxMoveGame = summary.maxMoveGames();
      info.firstEngineTotleTime = (int) Math.min(Integer.MAX_VALUE, summary.firstTotalTimeMs());
      info.secondEngineTotleTime = (int) Math.min(Integer.MAX_VALUE, summary.secondTotalTimeMs());
      info.firstEngineTotlePlayouts = summary.firstTotalVisits();
      info.secondEngineTotlePlayouts = summary.secondTotalVisits();
      ArrayList<SgfWinLossList> rows = new ArrayList<>();
      for (OpeningStanding standing : summary.openingStandings()) {
        SgfWinLossList row = new SgfWinLossList();
        row.SgfNumber = standing.openingIndex();
        row.engineOneWins = standing.firstWins();
        row.engineOneWinsAsBlack = standing.firstWinsAsBlack();
        row.engineOneWinsAsWhite = standing.firstWinsAsWhite();
        row.engineTwoWins = standing.secondWins();
        row.engineTwoWinsAsBlack = standing.secondWinsAsBlack();
        row.engineTwoWinsAsWhite = standing.secondWinsAsWhite();
        rows.add(row);
      }
      info.engineGameSgfWinLoss = rows;
    }
    return info;
  }

  private static ArrayList<Movelist> toMoveList(List<EngineGameMove> moves) {
    if (moves == null || moves.isEmpty()) {
      return null;
    }
    ArrayList<Movelist> copied = new ArrayList<>(moves.size());
    for (EngineGameMove move : moves) {
      if (move == null) {
        continue;
      }
      Movelist listed = new Movelist();
      listed.x = move.x();
      listed.y = move.y();
      listed.movenum = move.moveNumber();
      listed.isblack = move.black();
      listed.ispass = move.pass();
      copied.add(listed);
    }
    return copied.isEmpty() ? null : copied;
  }
}
