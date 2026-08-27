package featurecat.lizzie.enginegame;

import featurecat.lizzie.analysis.EngineGameInfo;
import featurecat.lizzie.gui.DesktopTimeControl;
import featurecat.lizzie.rules.Movelist;
import java.util.ArrayList;
import java.util.List;

/** Builds the still-live {@link EngineGameInfo} bag from a frozen first {@link EngineGamePlan}. */
final class EngineGameInfoFactory {
  private EngineGameInfoFactory() {}

  static EngineGameInfo from(EngineGamePlan plan) {
    EngineGameInfo info = new EngineGameInfo();
    info.blackEngineIndex = plan.blackIndex();
    info.whiteEngineIndex = plan.whiteIndex();
    info.firstEngineIndex = plan.blackIndex();
    info.secondEngineIndex = plan.whiteIndex();
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
    info.timeFirstEngine = timeBlack;
    info.timeSecondEngine = timeWhite;
    info.playoutsBlack = plan.blackLimits().visits();
    info.playoutsWhite = plan.whiteLimits().visits();
    info.playoutsFirstEngine = plan.blackLimits().visits();
    info.playoutsSecondEngine = plan.whiteLimits().visits();
    info.firstPlayoutsBlack = plan.blackLimits().firstMoveVisits();
    info.firstPlayoutsWhite = plan.whiteLimits().firstMoveVisits();
    info.firstPlayoutsFirstEngine = plan.blackLimits().firstMoveVisits();
    info.firstPlayoutsSecondEngine = plan.whiteLimits().firstMoveVisits();
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
    info.batchGameName = plan.output().batchName();
    info.openingFrozen = true;
    info.frozenStartList = toMoveList(plan.openingMoves());
    if (info.isContinueGame) {
      info.continueGameList = info.frozenStartList;
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
