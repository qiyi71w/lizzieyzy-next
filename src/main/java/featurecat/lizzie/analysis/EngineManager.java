package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.DesktopTimeControl;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.SgfWinLossList;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.EngineCountDown;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.util.Utils;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.json.JSONException;
import org.json.JSONObject;

public class EngineManager {
  private static final Set<Leelaz> REMOTE_ENGINES_RESTARTING = ConcurrentHashMap.newKeySet();
  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;
  public static boolean isUpdating = false;
  public List<Leelaz> engineList;
  public static int currentEngineNo;
  private int engineNo = 1;
  public static int currentEngineNo2 = -1;
  public static boolean isEmpty = false;
  String name = "";
  public static EngineGameInfo engineGameInfo = new EngineGameInfo();
  public static boolean isEngineGame = false;
  public static boolean isPreEngineGame = false;
  public static boolean isSaveingEngineSGF = false;
  public EngineCountDown playingAgainstHumanEngineCountDown;
  public EngineCountDown firstEngineCountDown;
  public EngineCountDown secondEngineCountDown;
  private ScheduledThreadPoolExecutor timeScheduled;
  private int timeScheduledTimes;
  Timer timer;

  public EngineManager(Config config, int index, boolean loadDefault)
      throws JSONException, IOException {
    ArrayList<EngineData> engineData = Utils.getEngineData();
    if (index > engineData.size() - 1) {
      index = 0;
    }
    engineList = new ArrayList<Leelaz>();
    // engineList.add(lz);
    for (int i = 0; i < engineData.size(); i++) {
      EngineData engineDt = engineData.get(i);
      Leelaz e;
      e = new Leelaz(engineDt.commands);
      e.preload = engineDt.preload;
      e.width = engineDt.width;
      e.height = engineDt.height;
      e.oriWidth = engineDt.width;
      e.oriHeight = engineDt.height;
      e.komi = engineDt.komi;
      e.orikomi = engineDt.komi;
      e.useJavaSSH = engineDt.useJavaSSH;
      e.ip = engineDt.ip;
      e.port = engineDt.port;
      e.useKeyGen = engineDt.useKeyGen;
      e.keyGenPath = engineDt.keyGenPath;
      e.userName = engineDt.userName;
      e.password = engineDt.password;
      e.initialCommand = engineDt.initialCommand;
      e.gtpConfigurationProtocol = engineDt.gtpConfigurationProtocol;
      e.gtpConfigurationProfile = copyProfile(engineDt.gtpConfigurationProfile);
      if (i == index || loadDefault && engineDt.isDefault) {
        if (engineDt.isDefault) index = engineDt.index;
        Board restoreBoard = Lizzie.board;
        boolean boardShapeChanges = e.oriWidth != 19 || e.oriHeight != 19;
        InitialEngineStartupSynchronization startupSynchronization = null;
        if (restoreBoard != null) {
          try {
            startupSynchronization =
                InitialEngineStartupSynchronization.capture(e, restoreBoard, boardShapeChanges);
          } catch (RuntimeException startupBarrierFailure) {
            startupBarrierFailure.printStackTrace();
            e.isLoaded = false;
            showEngineSynchronizationFailure(e);
          }
        }
        if (boardShapeChanges) {
          Board.boardWidth = e.oriWidth;
          Board.boardHeight = e.oriHeight;
          Zobrist.init();
          Lizzie.board.clear(false);
        }
        Lizzie.setPrimaryEngine(e);
        e.preload = true;
        e.firstLoad = true;
        final InitialEngineStartupSynchronization frozenStartupSynchronization =
            startupSynchronization;
        if (restoreBoard == null || frozenStartupSynchronization != null) {
          new Thread() {
            public void run() {
              try {
                try {
                  e.startEngine(engineDt.index);
                  Menu.engineMenu.setText("[" + (e.currentEngineN() + 1) + "]: " + e.oriEnginename);
                } catch (IOException e2) {
                  e2.printStackTrace();
                  return;
                }
                if (currentEngineNo > 20) LizzieFrame.menu.changeEngineIcon(20, 3);
                else LizzieFrame.menu.changeEngineIcon(currentEngineNo, 3);
                if (restoreBoard == null || frozenStartupSynchronization == null) {
                  return;
                }
                if (!waitForEngineSynchronizationReadiness(e)) {
                  e.isLoaded = false;
                  showEngineSynchronizationFailure(e);
                  return;
                }
                frozenStartupSynchronization.run();
              } catch (RuntimeException failure) {
                failure.printStackTrace();
                e.isLoaded = false;
                showEngineSynchronizationFailure(e);
              } finally {
                if (frozenStartupSynchronization != null) {
                  frozenStartupSynchronization.close();
                }
              }
            }
          }.start();
        }
      } else {
        if (e.preload) {
          new Thread() {
            public void run() {
              try {
                e.startEngine(engineDt.index);
              } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
            }
          }.start();
        }
      }
      engineList.add(e);
    }
    currentEngineNo = index;
    engineNo = index;
    if (index == -1) {
      Lizzie.leelaz.isKatago = true;
      Lizzie.leelaz.isLoaded = true;
      Menu.engineMenu.setText(resourceBundle.getString("Menu.noEngine"));
      if (Lizzie.config.isDoubleEngineMode())
        Menu.engineMenu2.setText(resourceBundle.getString("Menu.noEngine"));
      isEmpty = true;
      LizzieFrame.menu.updateMenuStatusForEngine();
      Lizzie.frame.reSetLoc();
      Lizzie.frame.addInput(false);

      SwingUtilities.invokeLater(
          new Runnable() {
            public void run() {
              if (Lizzie.config.uiConfig.optBoolean("show-badmoves-frame", false)) {
                Lizzie.frame.toggleBadMoves();
                Lizzie.frame.setVisible(true);
              }
              if (Lizzie.config.uiConfig.optBoolean("show-suggestions-frame", false)) {
                Lizzie.frame.toggleBestMoves();
                Lizzie.frame.setVisible(true);
              }
            }
          });
    }
    Lizzie.gtpConsole.console.setText("");
    autoCheckEngineAlive(Lizzie.config.autoCheckEngineAlive);
    if (Lizzie.config.uiConfig.optBoolean("autoload-empty", false) && Lizzie.config.showStatus)
      Lizzie.frame.refresh();
  }

  EngineManager(List<Leelaz> engines) {
    engineList = engines;
  }

  public void autoCheckEngineAlive(boolean enable) {
    if (enable) {
      if (timer == null) {
        timer =
            new Timer(
                5000,
                new ActionListener() {
                  public void actionPerformed(ActionEvent evt) {
                    checkEngineAlive();
                    try {
                    } catch (Exception e) {
                    }
                  }
                });
        timer.start();
      } else timer.start();
    } else {
      if (timer != null) timer.stop();
    }
  }

  public boolean startEngineGame(
      int engineBlack,
      int engineWhite,
      int timeBlack,
      int timeWhite,
      int playoutsBlack,
      int playoutsWhite,
      int firstPlayoutsBlack,
      int firstPlayoutsWhite,
      boolean isBatchGame,
      int batchGameNumber,
      String batchGameName,
      boolean isContinueGame,
      boolean isGenmove,
      boolean isExchange,
      boolean checkGameMaxMove,
      int maxGameMoves) {
    if (isGenmove
        && DesktopTimeControl.rejectsEngineGame(
            engineList, engineBlack, engineWhite, Lizzie.config.pkAdvanceTimeSettings)) {
      Lizzie.frame.showUnsupportedWebSocketAdvancedClock();
      return false;
    }
    if (Lizzie.frame.isTrying) Lizzie.frame.tryPlay(false);
    engineGameInfo = new EngineGameInfo();
    if (Lizzie.frame.isShowingHeatmap) Lizzie.leelaz.toggleHeatmap(true);
    if (!isEmpty && Lizzie.leelaz != null) {
      Lizzie.leelaz.clearBestMoves();
    }
    Lizzie.frame.hasEnginePkTitile = false;
    Lizzie.frame.enginePkTitile = "";
    if (engineBlack == engineWhite) {
      Utils.showMsg(resourceBundle.getString("EngineManager.engineGameSameEngine"));
      return false;
    }
    if (!isGenmove) {
      if (timeBlack <= 0 && playoutsBlack <= 0 && firstPlayoutsBlack <= 0) {
        Utils.showMsg(resourceBundle.getString("EngineManager.engineGameBlackSettingWrong"));
        return false;
      }
      if (timeWhite <= 0 && playoutsWhite <= 0 && firstPlayoutsWhite <= 0) {
        Utils.showMsg(resourceBundle.getString("EngineManager.engineGameWhiteSettingWrong"));
        return false;
      }
    }
    engineGameInfo = new EngineGameInfo();
    engineGameInfo.isGenmove = isGenmove;
    engineGameInfo.blackEngineIndex = engineBlack;
    engineGameInfo.whiteEngineIndex = engineWhite;
    engineGameInfo.firstEngineIndex = engineBlack;
    engineGameInfo.secondEngineIndex = engineWhite;
    engineGameInfo.timeBlack = timeBlack;
    engineGameInfo.timeWhite = timeWhite;
    engineGameInfo.timeFirstEngine = timeBlack;
    engineGameInfo.timeSecondEngine = timeWhite;
    engineGameInfo.playoutsBlack = playoutsBlack;
    engineGameInfo.playoutsWhite = playoutsWhite;
    engineGameInfo.playoutsFirstEngine = playoutsBlack;
    engineGameInfo.firstPlayoutsFirstEngine = firstPlayoutsBlack;
    engineGameInfo.playoutsSecondEngine = playoutsWhite;
    engineGameInfo.firstPlayoutsSecondEngine = firstPlayoutsWhite;
    engineGameInfo.firstPlayoutsBlack = firstPlayoutsBlack;
    engineGameInfo.firstPlayoutsWhite = firstPlayoutsWhite;
    engineGameInfo.isBatchGame = isBatchGame;
    engineGameInfo.batchNumber = batchGameNumber;
    engineGameInfo.isExchange = isExchange;
    engineGameInfo.batchNumberCurrent = 1;
    engineGameInfo.isContinueGame = isContinueGame;
    engineGameInfo.handicap = Lizzie.config.newEngineGameHandicap;
    engineGameInfo.komi = Lizzie.config.newEngineGameKomi;
    engineGameInfo.blackMinMove = Lizzie.config.firstEngineMinMove;
    engineGameInfo.blackResignMoveCounts = Lizzie.config.firstEngineResignMoveCounts;
    engineGameInfo.blackResignWinrate = Lizzie.config.firstEngineResignWinrate;

    engineGameInfo.whiteMinMove = Lizzie.config.secondEngineMinMove;
    engineGameInfo.whiteResignMoveCounts = Lizzie.config.secondEngineResignMoveCounts;
    engineGameInfo.whiteResignWinrate = Lizzie.config.secondEngineResignWinrate;
    if (checkGameMaxMove) engineGameInfo.setMaxGameMoves(maxGameMoves);
    else engineGameInfo.setMaxGameMoves(-1);
    engineGameInfo.SF = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
    if (Lizzie.frame.enginePkSgfWinLoss != null)
      engineGameInfo.engineGameSgfWinLoss = Lizzie.frame.enginePkSgfWinLoss;
    isEmpty = false;
    if (isGenmove) {
      engineGameInfo.settingFirst =
          resourceBundle.getString("EngineGameInfo.settingFirst"); // "第一引擎设置:";
      if (Lizzie.config.pkAdvanceTimeSettings) {
        engineGameInfo.settingFirst +=
            resourceBundle.getString("EngineGameInfo.time") + Lizzie.config.advanceBlackTimeTxt;
        engineGameInfo.settingSecond +=
            resourceBundle.getString("EngineGameInfo.time") + Lizzie.config.advanceWhiteTimeTxt;
        firstEngineCountDown = new EngineCountDown();
        boolean firstEngineParseSucess =
            firstEngineCountDown.setEngineCountDown(
                Lizzie.config.advanceBlackTimeTxt, engineList.get(engineGameInfo.firstEngineIndex));
        secondEngineCountDown = new EngineCountDown();
        boolean secondEngineParseSucess =
            secondEngineCountDown.setEngineCountDown(
                Lizzie.config.advanceWhiteTimeTxt,
                engineList.get(engineGameInfo.secondEngineIndex));
        if (!firstEngineParseSucess) {
          firstEngineCountDown = null;
          Utils.showMsgNoModal(
              resourceBundle.getString("EngineManager.parseAdvcanceTimeSettingsFailed"));
        }
        if (!secondEngineParseSucess) {
          secondEngineCountDown = null;
          Utils.showMsgNoModal(
              resourceBundle.getString("EngineManager.parseAdvcanceTimeSettingsFailed"));
        }

      } else {
        if (engineGameInfo.timeFirstEngine > 0)
          engineGameInfo.settingFirst +=
              resourceBundle.getString("EngineGameInfo.time")
                  + engineGameInfo.timeFirstEngine
                  + resourceBundle.getString("SGFParse.seconds");

        engineGameInfo.settingFirst +=
            "\r\n"
                + resourceBundle.getString("EngineGameInfo.command")
                + engineList.get(engineGameInfo.firstEngineIndex).getEngineCommand();

        engineGameInfo.settingSecond =
            resourceBundle.getString("EngineGameInfo.settingSecond"); // "第二引擎设置:";
        if (engineGameInfo.timeSecondEngine > 0)
          engineGameInfo.settingSecond +=
              resourceBundle.getString("EngineGameInfo.time")
                  + engineGameInfo.timeSecondEngine
                  + resourceBundle.getString("SGFParse.seconds");

        engineGameInfo.settingSecond +=
            "\r\n"
                + resourceBundle.getString("EngineGameInfo.command")
                + engineList.get(engineGameInfo.secondEngineIndex).getEngineCommand();
      }
    } else {
      engineGameInfo.settingFirst =
          resourceBundle.getString("EngineGameInfo.settingFirst"); // "第一引擎设置:";
      if (engineGameInfo.timeFirstEngine > 0)
        engineGameInfo.settingFirst +=
            resourceBundle.getString("EngineGameInfo.time")
                + engineGameInfo.timeFirstEngine
                + resourceBundle.getString("SGFParse.seconds");
      if (engineGameInfo.playoutsFirstEngine > 0)
        engineGameInfo.settingFirst +=
            resourceBundle.getString("EngineGameInfo.totalVisits")
                + engineGameInfo.playoutsFirstEngine;
      if (engineGameInfo.firstPlayoutsFirstEngine > 0)
        engineGameInfo.settingFirst +=
            resourceBundle.getString("EngineGameInfo.firstVisits")
                + engineGameInfo.firstPlayoutsFirstEngine;

      engineGameInfo.settingFirst +=
          "\r\n"
              + resourceBundle.getString("EngineGameInfo.resignThreshold")
              + Lizzie.config.firstEngineMinMove
              + resourceBundle.getString("EngineGameInfo.resignThreshold2")
              + Lizzie.config.firstEngineResignMoveCounts
              + resourceBundle.getString("EngineGameInfo.resignThreshold3")
              + Lizzie.config.firstEngineResignWinrate;

      engineGameInfo.settingFirst +=
          "\r\n"
              + resourceBundle.getString("EngineGameInfo.command")
              + engineList.get(engineGameInfo.firstEngineIndex).getEngineCommand();

      engineGameInfo.settingSecond =
          resourceBundle.getString("EngineGameInfo.settingSecond"); // "第二引擎设置:";
      if (engineGameInfo.timeSecondEngine > 0)
        engineGameInfo.settingSecond +=
            resourceBundle.getString("EngineGameInfo.time")
                + engineGameInfo.timeSecondEngine
                + resourceBundle.getString("SGFParse.seconds");
      if (engineGameInfo.playoutsSecondEngine > 0)
        engineGameInfo.settingSecond +=
            resourceBundle.getString("EngineGameInfo.totalVisits")
                + engineGameInfo.playoutsSecondEngine;
      if (engineGameInfo.firstPlayoutsSecondEngine > 0)
        engineGameInfo.settingSecond +=
            resourceBundle.getString("EngineGameInfo.firstVisits")
                + engineGameInfo.firstPlayoutsSecondEngine;
      engineGameInfo.settingSecond +=
          "\r\n"
              + resourceBundle.getString("EngineGameInfo.resignThreshold")
              + Lizzie.config.secondEngineMinMove
              + resourceBundle.getString("EngineGameInfo.resignThreshold2")
              + Lizzie.config.secondEngineResignMoveCounts
              + resourceBundle.getString("EngineGameInfo.resignThreshold3")
              + Lizzie.config.secondEngineResignWinrate;

      engineGameInfo.settingSecond +=
          "\r\n"
              + resourceBundle.getString("EngineGameInfo.command")
              + engineList.get(engineGameInfo.secondEngineIndex).getEngineCommand();
    }

    if (engineGameInfo.isContinueGame) {
      engineGameInfo.continueGameList = Lizzie.board.getMoveList();
    }
    if (engineGameInfo.isBatchGame) {
      if (batchGameName.equals("")) {
        // batchPkName = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String SF = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        SF =
            getEngineName(engineGameInfo.firstEngineIndex)
                + "_VS_"
                + getEngineName(engineGameInfo.secondEngineIndex)
                + "_"
                + SF;
        SF = SF.replaceAll("[/\\\\:*?|]", ".");
        SF = SF.replaceAll("[\"<>]", "'");
        engineGameInfo.batchGameName = SF;
      } else {
        engineGameInfo.batchGameName = batchGameName;
      }
    }

    Lizzie.frame.removeInput(true);
    LizzieFrame.winrateGraph.resetMaxScoreLead();
    Lizzie.frame.isPlayingAgainstLeelaz = false;
    Lizzie.frame.isAnaPlayingAgainstLeelaz = false;

    Lizzie.config.isAutoAna = false;
    Lizzie.board.isPkBoard = true;
    LizzieFrame.toolbar.lblenginePkResult.setText("0:0");
    Menu.engineMenu.setText(resourceBundle.getString("EngineManager.engineGamePlaying")); // 对战中
    LizzieFrame.menu.toggleEngineMenuStatus(true, false);
    // 禁用某些按钮
    LizzieFrame.toolbar.enableDisabelForEngineGame(false);
    // 开始新的一局
    startNewEngineGame(true);
    return true;
  }

  public ArrayList<Movelist> getStartListForEnginePk() {
    if (engineGameInfo.isContinueGame) {
      return engineGameInfo.continueGameList;
    }
    if (Lizzie.config.chkEngineSgfStart) {
      int length = Lizzie.frame.enginePKSgfString.size();
      if (Lizzie.config.engineSgfStartRandom) {
        Random random = new Random();
        LizzieFrame.toolbar.currentEnginePkSgfNum = random.nextInt(length);
      } else {
        LizzieFrame.toolbar.currentEnginePkSgfNum = Lizzie.frame.enginePKSgfNum % length;
        Lizzie.frame.enginePKSgfNum++;
      }
      return Lizzie.frame.enginePKSgfString.get(LizzieFrame.toolbar.currentEnginePkSgfNum);
    }
    return null;
  }

  private ArrayList<Movelist> prepareEngineGameBoard(boolean firstTime, boolean analysisMode) {
    Lizzie.board.clear(true);
    ArrayList<Movelist> startList = getStartListForEnginePk();
    if (startList != null) {
      if (analysisMode) {
        Lizzie.board.setMoveList(startList, false, true);
      } else {
        Lizzie.board.setlist(startList);
      }
    } else if (firstTime) {
      int width = engineList.get(engineGameInfo.blackEngineIndex).width;
      int height = engineList.get(engineGameInfo.blackEngineIndex).height;
      if (width != Board.boardWidth || height != Board.boardHeight) {
        Lizzie.board.reopen(width, height);
      }
    }

    GameInfo gameInfo = Lizzie.board.getHistory().getGameInfo();
    gameInfo.setKomiNoMenu(engineGameInfo.komi);
    gameInfo.setHandicap(0);
    if (startList == null && engineGameInfo.handicap >= 2) {
      Lizzie.board.setupFixedHandicap(engineGameInfo.handicap);
    }
    return startList;
  }

  private String formateSaveString(String filename) {
    filename = filename.replaceAll("[/\\\\:*?|]", ".");
    filename = filename.replaceAll("[\"<>]", "'");
    return filename;
  }

  private void saveEngineGameFile(int resignIndex) {
    File file = new File("");
    String courseFile = "";
    try {
      courseFile = file.getCanonicalPath();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    String sf = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());

    String df = "";
    if (engineGameInfo.isBatchGame) {
      df =
          engineGameInfo.batchNumberCurrent
              + (Lizzie.config.chkPkStartNum ? (Lizzie.config.pkStartNum - 1) : 0)
              + "_"
              + (Lizzie.config.chkEngineSgfStart
                  ? resourceBundle.getString("EngineGameInfo.openingSGFindex")
                      + LizzieFrame.toolbar.currentEnginePkSgfNum
                      + "_"
                  : "");
    }
    df =
        df
            + resourceBundle.getString("Leelaz.black")
            + "("
            + Lizzie.engineManager.engineList.get(engineGameInfo.blackEngineIndex).currentEnginename
            + ")"
            + "_vs_"
            + resourceBundle.getString("Leelaz.white")
            + "("
            + engineList.get(engineGameInfo.whiteEngineIndex).currentEnginename
            + ")";
    // 添加结果
    if (engineList.get(resignIndex).doublePass) {
      df += resourceBundle.getString("EngineManager.doublePassFileName"); // "_双pass对局";
    } else if (Lizzie.board.getHistory().getMoveNumber() > engineGameInfo.getMaxGameMoves()) {
      df += resourceBundle.getString("EngineManager.outOfMoveFileName"); // "_超手数对局";
    } else {
      if (resignIndex == engineGameInfo.whiteEngineIndex) {
        GameInfo gameInfo = Lizzie.board.getHistory().getGameInfo();
        gameInfo.setResult(resourceBundle.getString("Leelaz.blackWin"));
        df =
            df
                + "_"
                + resourceBundle.getString("Leelaz.black")
                + "("
                + engineList.get(engineGameInfo.blackEngineIndex).currentEnginename
                + ")"
                + resourceBundle.getString("Leelaz.win");
      } else {
        GameInfo gameInfo = Lizzie.board.getHistory().getGameInfo();
        gameInfo.setResult(resourceBundle.getString("Leelaz.whiteWin"));
        df =
            df
                + "_"
                + resourceBundle.getString("Leelaz.white")
                + "("
                + engineList.get(engineGameInfo.whiteEngineIndex).currentEnginename
                + ")"
                + resourceBundle.getString("Leelaz.win");
      }
    }
    df = df + "_" + sf;
    // 增加如果已命名,则保存在命名的文件夹下
    df = formateSaveString(df);

    File autoSaveFile;
    File autoSaveFile2 = null;
    if (engineGameInfo.isBatchGame) {
      autoSaveFile =
          new File(
              courseFile
                  + File.separator
                  + "EngineGames"
                  + File.separator
                  + engineGameInfo.batchGameName
                  + File.separator
                  + df
                  + ".sgf");
      autoSaveFile2 =
          new File(
              courseFile
                  + File.separator
                  + "EngineGames"
                  + File.separator
                  + engineGameInfo.SF
                  + File.separator
                  + df
                  + ".sgf");
    } else {
      autoSaveFile =
          new File(courseFile + File.separator + "EngineGames" + File.separator + df + ".sgf");
      autoSaveFile2 =
          new File(courseFile + File.separator + "EngineGames" + File.separator + df + ".sgf");
    }

    File fileParent = autoSaveFile.getParentFile();
    if (!fileParent.exists()) {
      fileParent.mkdirs();
    }
    try {
      SGFParser.save(Lizzie.board, autoSaveFile.getPath());
      if (LizzieFrame.toolbar.enginePkSaveWinrate) {
        String autoSavePng;
        if (engineGameInfo.isBatchGame) {
          autoSavePng =
              courseFile
                  + File.separator
                  + "EngineGames"
                  + File.separator
                  + engineGameInfo.batchGameName
                  + File.separator
                  + df
                  + ".png";

        } else {
          autoSavePng = courseFile + File.separator + "EngineGames" + File.separator + df + ".png";
        }
        Lizzie.frame.saveImage(
            Lizzie.frame.statx,
            Lizzie.frame.staty,
            (int) (Lizzie.frame.grw * 1.03),
            Lizzie.frame.grh + Lizzie.frame.stath,
            autoSavePng);
      }
    } catch (IOException e) {
      // TODO Auto-generated catch block
      if (engineGameInfo.isBatchGame) {
        try {
          File fileParent2 = autoSaveFile2.getParentFile();
          if (!fileParent2.exists()) {
            fileParent2.mkdirs();
          }
          SGFParser.save(Lizzie.board, autoSaveFile2.getPath());

          if (LizzieFrame.toolbar.enginePkSaveWinrate) {

            String autoSavePng2 = null;
            if (engineGameInfo.isBatchGame) {
              autoSavePng2 =
                  courseFile
                      + File.separator
                      + "EngineGames"
                      + File.separator
                      + engineGameInfo.SF
                      + File.separator
                      + df
                      + ".png";
            } else {
              autoSavePng2 =
                  courseFile + File.separator + "EngineGames" + File.separator + df + ".png";
            }
            Lizzie.frame.saveImage(
                Lizzie.frame.statx,
                Lizzie.frame.staty,
                (int) (Lizzie.frame.grw * 1.03),
                Lizzie.frame.grh + Lizzie.frame.stath,
                autoSavePng2);
          }
        } catch (IOException e1) {
          // TODO Auto-generated catch block
          e1.printStackTrace();
        }
      }
      e.printStackTrace();
    }
  }

  private void writeToFile(
      File file,
      String settingAll,
      String settingB,
      String settingW,
      String resultB,
      String resultW,
      String resultOther)
      throws IOException {

    try (FileOutputStream writerStream = new FileOutputStream(file);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(writerStream, "UTF-8"))) {
      double games = (double) engineGameInfo.batchNumberCurrent;
      double wr =
          (double) engineGameInfo.getFirstEngineWins()
              / (double)
                  (engineGameInfo.getFirstEngineWins() + engineGameInfo.getSecondEngineWins());

      double elo = Math.log10(1.0 / wr - 1.0) * 400;
      double zxwr = (wr + 1.0 / (2.0 * games)) / (1.0 + 1.0 / games);
      double zxwrc =
          1.0
              * Math.sqrt(wr * (1.0 - wr) / games + 1.0 / ((2.0 * games) * (2.0 * games)))
              / (1.0 + 1.0 / games);
      double zxwr2 = (wr + 4.0 / (2.0 * games)) / (1.0 + 4.0 / games);
      double zxwrc2 =
          2.0
              * Math.sqrt(wr * (1.0 - wr) / games + 4.0 / ((2.0 * games) * (2.0 * games)))
              / (1.0 + 4.0 / games);
      double zxwr3 = (wr + 9.0 / (2.0 * games)) / (1.0 + 9.0 / games);
      double zxwrc3 =
          3.0
              * Math.sqrt(wr * (1.0 - wr) / games + 9.0 / ((2.0 * games) * (2.0 * games)))
              / (1.0 + 9.0 / games);
      double elo2 = Math.log10(1.0 / ((zxwr2 > 0.5 ? zxwr2 + zxwrc2 : zxwr2 - zxwrc2)) - 1.0) * 400;

      writer.write(
          settingAll
              + resourceBundle.getString("EngineGameInfo.backgroundPonder")
              + (Lizzie.config.enginePkPonder
                  ? resourceBundle.getString("EngineGameInfo.yes")
                  : resourceBundle.getString("EngineGameInfo.no")));
      writer.write("\r\n");
      writer.write("\r\n");
      writer.write(settingB);
      writer.write("\r\n");
      writer.write("\r\n");
      writer.write(settingW);
      writer.write("\r\n");
      writer.write("\r\n");
      writer.write(
          resourceBundle.getString("EngineGameInfo.totalGameResults")
              + engineGameInfo.batchNumberCurrent
              + resourceBundle.getString("EngineGameInfo.gameScore")
              + engineGameInfo.getFirstEngineWins()
              + ":"
              + engineGameInfo.getSecondEngineWins()
              + resourceBundle.getString("EngineGameInfo.gameWinrate")
              + String.format(Locale.ENGLISH, "%.2f", wr * 100)
              + "%");
      writer.write("\r\n");
      writer.write("\r\n");
      writer.write(resultB);
      writer.write("\r\n");
      writer.write(resultW);
      writer.write("\r\n");
      writer.write("\r\n");
      writer.write(resourceBundle.getString("EngineGameInfo.timeVisitsTips"));
      writer.write("\r\n");
      writer.write("\r\n");
      writer.write(resultOther);
      writer.write("\r\n");
      writer.write("\r\n");
      if (engineGameInfo.getFirstEngineWins() == 0 || engineGameInfo.getFirstEngineWins() == 0)
        writer.write(
            resourceBundle.getString("EngineGameInfo.secondEngineElo")
                + resourceBundle.getString("EngineGameInfo.elo100Wr"));
      else {
        writer.write(
            resourceBundle.getString("EngineGameInfo.secondEngineElo")
                + (elo > 0 ? "+" : "")
                + String.format(Locale.ENGLISH, "%.2f", elo)
                + " ± "
                + (zxwr2 + zxwrc2 < 1 && zxwr2 + zxwrc2 > 0
                    ? String.format(Locale.ENGLISH, "%.2f", Math.abs(elo2 - elo))
                    : ""));
        if (EngineManager.engineGameInfo.batchNumberCurrent < 50)
          writer.write("?(" + resourceBundle.getString("EngineGameInfo.notEnoughGames") + ")");
      }
      writer.write("\r\n");
      writer.write(
          resourceBundle.getString("EngineGameInfo.oneStdev")
              + String.format(Locale.ENGLISH, "%.2f", zxwr * 100)
              + "% ± "
              + String.format(Locale.ENGLISH, "%.2f", zxwrc * 100)
              + "%");
      writer.write("\r\n");
      writer.write(
          resourceBundle.getString("EngineGameInfo.twoStdev")
              + String.format(Locale.ENGLISH, "%.2f", zxwr2 * 100)
              + "% ± "
              + String.format(Locale.ENGLISH, "%.2f", zxwrc2 * 100)
              + "%");
      writer.write("\r\n");
      writer.write(
          resourceBundle.getString("EngineGameInfo.threeStdev")
              + String.format(Locale.ENGLISH, "%.2f", zxwr3 * 100)
              + "% ± "
              + String.format(Locale.ENGLISH, "%.2f", zxwrc3 * 100)
              + "%");
      writer.write("\r\n");

      Lizzie.frame.hasEnginePkTitile = true;
      Lizzie.frame.enginePkTitile =
          engineGameInfo.getFirstEngineWins()
              + ":"
              + engineGameInfo.getSecondEngineWins()
              + " "
              + engineList.get(engineGameInfo.firstEngineIndex).oriEnginename
              + " VS "
              + engineList.get(engineGameInfo.secondEngineIndex).oriEnginename
              + resourceBundle.getString("EngineGameInfo.titleWinRate")
              + String.format(Locale.ENGLISH, "%.1f", wr * 100)
              + "%"
              + " 2σ "
              + String.format(Locale.ENGLISH, "%.2f", zxwr2 * 100)
              + "%±"
              + String.format(Locale.ENGLISH, "%.2f", zxwrc2 * 100)
              + "%";

      if (Lizzie.config.chkEngineSgfStart) {
        writer.write("\r\n");
        writer.write(
            resourceBundle.getString("EngineGameInfo.sgfStartNumber")
                + Lizzie.frame.enginePKSgfString.size());
        writer.write("\r\n");
        for (SgfWinLossList wl : Lizzie.frame.enginePkSgfWinLoss) {
          writer.write(
              resourceBundle.getString("EngineGameInfo.sgfStartOpen")
                  + (Lizzie.config.isChinese ? "" : " ")
                  + wl.SgfNumber
                  + ":\n"
                  + resourceBundle.getString("EngineGameInfo.engine1")
                  + ": "
                  + resourceBundle.getString("EngineGameInfo.allWins")
                  + wl.engineOneWins
                  + resourceBundle.getString("EngineGameInfo.sgfStartBlackWin")
                  + wl.engineOneWinsAsBlack
                  + resourceBundle.getString("EngineGameInfo.sgfStartWhiteWin")
                  + wl.engineOneWinsAsWhite
                  + "   "
                  + resourceBundle.getString("EngineGameInfo.engine2")
                  + ": "
                  + resourceBundle.getString("EngineGameInfo.allWins")
                  + wl.engineTwoWins
                  + resourceBundle.getString("EngineGameInfo.sgfStartBlackWin")
                  + wl.engineTwoWinsAsBlack
                  + resourceBundle.getString("EngineGameInfo.sgfStartWhiteWin")
                  + wl.engineTwoWinsAsWhite);
          writer.write("\r\n");
        }
      }
      writer.flush();
    }
  }

  private void savePkTxt(
      String settingB,
      String settingW,
      String settingAll,
      String resultB,
      String resultW,
      String resultOther) {
    File file = new File("");
    String courseFile = "";
    try {
      courseFile = file.getCanonicalPath();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    // 增加如果已命名,则保存在命名的文件夹下
    File autoSaveFile;
    File autoSaveFile2 = null;
    autoSaveFile =
        new File(
            courseFile
                + File.separator
                + "EngineGames"
                + File.separator
                + engineGameInfo.batchGameName
                + File.separator
                + resourceBundle.getString("Leelaz.result")
                + engineGameInfo.SF
                + ".txt");
    autoSaveFile2 =
        new File(
            courseFile
                + File.separator
                + "EngineGames"
                + File.separator
                + engineGameInfo.SF
                + File.separator
                + resourceBundle.getString("Leelaz.result")
                + engineGameInfo.SF
                + ".txt");

    File fileParent = autoSaveFile.getParentFile();
    if (!fileParent.exists()) {
      fileParent.mkdirs();
    }
    try {
      writeToFile(autoSaveFile, settingAll, settingB, settingW, resultB, resultW, resultOther);
    } catch (IOException e) {
      // TODO Auto-generated catch block

      try {
        File fileParent2 = autoSaveFile2.getParentFile();
        if (!fileParent2.exists()) {
          fileParent2.mkdirs();
        }
        writeToFile(autoSaveFile2, settingAll, settingB, settingW, resultB, resultW, resultOther);

      } catch (IOException e1) {
        // TODO Auto-generated catch block
        e1.printStackTrace();
      }
      e.printStackTrace();
    }
  }

  public void stopEngineGame(int resgnEngineIndex, boolean mannul) {
    SGFParser.appendComment();
    isPreEngineGame = false;
    if (!isEngineGame) return;
    isEngineGame = false;
    isSaveingEngineSGF = true;
    stopCountDown();
    LizzieFrame.menu.toggleDoubleMenuGameStatus();
    LizzieFrame.toolbar.isPkStop = false;
    Lizzie.frame.hasEnginePkTitile = true;
    Lizzie.frame.enginePkTitile = "";
    // 保存SGF文件
    if (mannul) {
      engineList.get(engineGameInfo.blackEngineIndex).notPondering();
      engineList.get(engineGameInfo.blackEngineIndex).nameCmd();
      engineList.get(engineGameInfo.whiteEngineIndex).notPondering();
      engineList.get(engineGameInfo.whiteEngineIndex).nameCmd();
      engineList.get(engineGameInfo.blackEngineIndex).played = false;
      engineList.get(engineGameInfo.whiteEngineIndex).played = false;
      changeEngIcoForEndPk();
      LizzieFrame.toolbar.enableDisabelForEngineGame(true);
      Lizzie.frame.addInput(true);
      if (engineGameInfo.isBatchGame && engineGameInfo.batchNumberCurrent > 1) {
        File file = new File("");
        String courseFile = "";
        try {
          courseFile = file.getCanonicalPath();
        } catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        String passandMove = "";
        if (engineGameInfo.doublePassGame > 0)
          passandMove =
              resourceBundle.getString("EngineGameInfo.doublePassGame")
                  + engineGameInfo.doublePassGame;
        if (engineGameInfo.maxMoveGame > 0)
          passandMove +=
              (passandMove.equals("") ? "" : " ")
                  + resourceBundle.getString("EngineGameInfo.outOfMoveGame")
                  + engineGameInfo.maxMoveGame;
        Utils.showMsgNoModal(
            (resourceBundle.getString("EngineGameInfo.batchGameEndAndScore")
                + engineList.get(engineGameInfo.firstEngineIndex).oriEnginename
                + "   "
                + engineGameInfo.getFirstEngineWins()
                + ":"
                + engineGameInfo.getSecondEngineWins()
                + "   "
                + engineList.get(engineGameInfo.secondEngineIndex).oriEnginename
                + (passandMove.equals("") ? "" : " ")
                + passandMove
                + ","
                + resourceBundle.getString("EngineGameInfo.engineGameEndHintKifuPos")
                + courseFile
                + File.separator
                + "EngineGames"));
      }
      isSaveingEngineSGF = false;
      return;
    }
    SGFParser.appendGameTimeAndPlayouts();
    if (engineGameInfo.isBatchGame || LizzieFrame.toolbar.AutosavePk) {
      saveEngineGameFile(resgnEngineIndex);
    }
    if (engineGameInfo.isBatchGame) {
      if (engineList.get(resgnEngineIndex).doublePass) {
        engineGameInfo.doublePassGame++;
      } else if (engineList.get(resgnEngineIndex).outOfMoveNum) {
        engineGameInfo.maxMoveGame++;
      } else {
        if (resgnEngineIndex == engineGameInfo.firstEngineIndex) {
          if (resgnEngineIndex == engineGameInfo.blackEngineIndex) {
            engineGameInfo.secondEngineWinAsWhite++;
            for (SgfWinLossList wl : engineGameInfo.engineGameSgfWinLoss) {
              if (wl.SgfNumber == LizzieFrame.toolbar.currentEnginePkSgfNum) {
                wl.engineTwoWins++;
                wl.engineTwoWinsAsWhite++;
                break;
              }
            }
          } else {
            engineGameInfo.secondEngineWinAsBlack++;
            for (SgfWinLossList wl : engineGameInfo.engineGameSgfWinLoss) {
              if (wl.SgfNumber == LizzieFrame.toolbar.currentEnginePkSgfNum) {
                wl.engineTwoWins++;
                wl.engineTwoWinsAsBlack++;
                break;
              }
            }
          }
        } else {
          if (resgnEngineIndex == engineGameInfo.blackEngineIndex) {
            engineGameInfo.firstEngineWinAsWhite++;
            for (SgfWinLossList wl : engineGameInfo.engineGameSgfWinLoss) {
              if (wl.SgfNumber == LizzieFrame.toolbar.currentEnginePkSgfNum) {
                wl.engineOneWins++;
                wl.engineOneWinsAsWhite++;
                break;
              }
            }
          } else {
            engineGameInfo.firstEngineWinAsBlack++;
            for (SgfWinLossList wl : engineGameInfo.engineGameSgfWinLoss) {
              if (wl.SgfNumber == LizzieFrame.toolbar.currentEnginePkSgfNum) {
                wl.engineOneWins++;
                wl.engineOneWinsAsBlack++;
                break;
              }
            }
          }
        }
      }
      // 保存对局结果txt

      // resultOther, resultFirst, resultSecond;
      engineGameInfo.resultFirst =
          resourceBundle.getString("EngineGameInfo.engine1")
              + "("
              + engineList.get(engineGameInfo.firstEngineIndex).oriEnginename
              + "):\n"
              + resourceBundle.getString("EngineGameInfo.allWins")
              + ": "
              + engineGameInfo.getFirstEngineWins();
      engineGameInfo.resultFirst +=
          " "
              + resourceBundle.getString("EngineGameInfo.sgfStartBlackWin")
              + ": "
              + engineGameInfo.firstEngineWinAsBlack
              + " "
              + resourceBundle.getString("EngineGameInfo.sgfStartWhiteWin")
              + ": "
              + engineGameInfo.firstEngineWinAsWhite;
      engineGameInfo.resultFirst +=
          resourceBundle.getString("EngineGameInfo.totalTime")
              + engineGameInfo.firstEngineTotleTime / (float) 1000
              + resourceBundle.getString("SGFParse.seconds");
      engineGameInfo.resultFirst +=
          resourceBundle.getString("EngineGameInfo.result.totalVisits")
              + engineGameInfo.firstEngineTotlePlayouts;

      engineGameInfo.resultSecond =
          resourceBundle.getString("EngineGameInfo.engine2")
              + "("
              + engineList.get(engineGameInfo.secondEngineIndex).oriEnginename
              + "):\n"
              + resourceBundle.getString("EngineGameInfo.allWins")
              + ": "
              + engineGameInfo.getSecondEngineWins();
      engineGameInfo.resultSecond +=
          " "
              + resourceBundle.getString("EngineGameInfo.sgfStartBlackWin")
              + ": "
              + engineGameInfo.secondEngineWinAsBlack
              + " "
              + resourceBundle.getString("EngineGameInfo.sgfStartWhiteWin")
              + ": "
              + engineGameInfo.secondEngineWinAsWhite;
      engineGameInfo.resultSecond +=
          resourceBundle.getString("EngineGameInfo.totalTime")
              + engineGameInfo.secondEngineTotleTime / (float) 1000
              + resourceBundle.getString("SGFParse.seconds");
      engineGameInfo.resultSecond +=
          resourceBundle.getString("EngineGameInfo.result.totalVisits")
              + engineGameInfo.secondEngineTotlePlayouts;

      engineGameInfo.resultOther =
          resourceBundle.getString("EngineGameInfo.doublePassGame") + engineGameInfo.doublePassGame;
      engineGameInfo.resultOther +=
          " "
              + resourceBundle.getString("EngineGameInfo.outOfMoveGame")
              + engineGameInfo.maxMoveGame;
      if (engineGameInfo.isGenmove) {
        engineGameInfo.settingAll =
            resourceBundle.getString("EngineGameInfo.otherSettings")
                + resourceBundle.getString("EngineGameInfo.genmoveMode");
        engineGameInfo.settingAll +=
            resourceBundle.getString("EngineGameInfo.komi")
                + +Lizzie.board.getHistory().getGameInfo().getKomi();

        if (engineGameInfo.isBatchGame) {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.totalGames") + engineGameInfo.batchNumber;
        }
        if (engineGameInfo.isContinueGame) {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.continueGame")
                  + resourceBundle.getString("EngineGameInfo.yes");
        } else {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.continueGame")
                  + resourceBundle.getString("EngineGameInfo.no");
        }
        if (engineGameInfo.isExchange) {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.exchange")
                  + resourceBundle.getString("EngineGameInfo.yes"); // " 交换黑白: 是";
        } else {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.exchange")
                  + resourceBundle.getString("EngineGameInfo.no"); // " 交换黑白: 否";
        }

        engineGameInfo.settingAll +=
            resourceBundle.getString("EngineGameInfo.maxMoves") + engineGameInfo.getMaxGameMoves();
      } else {
        engineGameInfo.settingAll =
            resourceBundle.getString("EngineGameInfo.otherSettings")
                + resourceBundle.getString("EngineGameInfo.analyzeMode");
        engineGameInfo.settingAll +=
            resourceBundle.getString("EngineGameInfo.komi")
                + Lizzie.board.getHistory().getGameInfo().getKomi();
        //      engineGameInfo.settingAll +=
        //          " 认输阈值:连续"
        //              + Lizzie.frame.toolbar.pkResignMoveCounts
        //              + "手,胜率低于"
        //              + Lizzie.frame.toolbar.pkResginWinrate
        //              + "%";
        if (engineGameInfo.isBatchGame) {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.totalGames") + engineGameInfo.batchNumber;
        }
        if (engineGameInfo.isContinueGame) {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.continueGame")
                  + resourceBundle.getString("EngineGameInfo.yes"); // " 续弈: 是";
        } else {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.continueGame")
                  + resourceBundle.getString("EngineGameInfo.no"); // " 续弈: 否";
        }
        if (engineGameInfo.isExchange) {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.exchange")
                  + resourceBundle.getString("EngineGameInfo.yes"); // " 交换黑白: 是";
        } else {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.exchange")
                  + resourceBundle.getString("EngineGameInfo.no"); // " 交换黑白: 否";
        }

        engineGameInfo.settingAll +=
            resourceBundle.getString("EngineGameInfo.maxMoves") + engineGameInfo.getMaxGameMoves();

        if (LizzieFrame.toolbar.isRandomMove) {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.randomPlay1") //    " 随机落子: 前"
                  + LizzieFrame.toolbar.randomMove
                  + resourceBundle.getString("EngineGameInfo.randomPlay2") // "手,胜率不低于首位"
                  + LizzieFrame.toolbar.randomDiffWinrate
                  + "%";
          if (Lizzie.config.checkRandomVisits)
            engineGameInfo.settingAll +=
                resourceBundle.getString("EngineGameInfo.randomPlay3") // ",计算量不低于最高值"
                    + String.format(Locale.ENGLISH, "%.1f", Lizzie.config.percentsRandomVisits)
                    + "%";
        }
      }
      savePkTxt(
          engineGameInfo.settingFirst,
          engineGameInfo.settingSecond,
          engineGameInfo.settingAll,
          engineGameInfo.resultFirst,
          engineGameInfo.resultSecond,
          engineGameInfo.resultOther);

      if (engineGameInfo.batchNumberCurrent < engineGameInfo.batchNumber) {
        engineGameInfo.batchNumberCurrent++;
        if (engineGameInfo.isExchange) engineGameInfo.exChangeBlackWhite();
        isSaveingEngineSGF = false;
        startNewEngineGame(false);
        return;
      }
    }
    LizzieFrame.toolbar.enableDisabelForEngineGame(true);
    Lizzie.board.clearBestMovesAfter(Lizzie.board.getHistory().getStart());
    File file = new File("");
    String courseFile = "";
    try {
      courseFile = file.getCanonicalPath();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    // showmsg 多局
    if (engineGameInfo.isBatchGame) {
      String passandMove = "";
      if (engineGameInfo.doublePassGame > 0)
        passandMove =
            passandMove
                + resourceBundle.getString("EngineGameInfo.doublePassGame")
                + engineGameInfo.doublePassGame;
      if (engineGameInfo.maxMoveGame > 0)
        passandMove =
            passandMove
                + (passandMove.equals("") ? "" : " ")
                + resourceBundle.getString("EngineGameInfo.outOfMoveGame")
                + engineGameInfo.maxMoveGame;
      Utils.showMsgNoModal(
          (resourceBundle.getString("EngineGameInfo.batchGameEndAndScore")
              + engineList.get(engineGameInfo.firstEngineIndex).oriEnginename
              + "   "
              + engineGameInfo.getFirstEngineWins()
              + ":"
              + engineGameInfo.getSecondEngineWins()
              + "   "
              + engineList.get(engineGameInfo.secondEngineIndex).oriEnginename
              + (passandMove.equals("") ? "" : " ")
              + passandMove
              + ","
              + resourceBundle.getString("EngineGameInfo.engineGameEndHintKifuPos")
              + courseFile
              + File.separator
              + "EngineGames"));
    } else {
      // 单局
      String jg = resourceBundle.getString("EngineGameInfo.gameFinished"); // "对战已结束，";
      if (engineList.get(resgnEngineIndex).outOfMoveNum)
        jg = jg + resourceBundle.getString("EngineGameInfo.finishedByMoves"); // "超过手数限制";
      else {
        if (engineList.get(resgnEngineIndex).doublePass) {
          jg = jg + resourceBundle.getString("EngineGameInfo.finishedByDoublePass"); // "双Pass对局";
        } else if (resgnEngineIndex == engineGameInfo.blackEngineIndex) {
          // df=df+"_白胜";
          jg =
              jg
                  + resourceBundle.getString("GameInfoDialog.white")
                  + "("
                  + engineList.get(engineGameInfo.whiteEngineIndex).oriEnginename
                  + ")"
                  + resourceBundle.getString("EngineGameInfo.finishedWin");
        } else {
          jg =
              jg
                  + resourceBundle.getString("GameInfoDialog.black")
                  + "("
                  + engineList.get(engineGameInfo.blackEngineIndex).oriEnginename
                  + ")"
                  + resourceBundle.getString("EngineGameInfo.finishedWin");
        }
      }
      if (LizzieFrame.toolbar.AutosavePk) {
        jg =
            jg
                + ","
                + resourceBundle.getString("EngineGameInfo.engineGameEndHintKifuPos")
                + courseFile
                + File.separator
                + "EngineGames";
      }
      Utils.showMsgNoModal(jg);
    }
    isSaveingEngineSGF = false;
    engineList.get(engineGameInfo.blackEngineIndex).notPondering();
    engineList.get(engineGameInfo.blackEngineIndex).nameCmd();
    engineList.get(engineGameInfo.whiteEngineIndex).notPondering();
    engineList.get(engineGameInfo.whiteEngineIndex).nameCmd();
    engineList.get(engineGameInfo.blackEngineIndex).played = false;
    engineList.get(engineGameInfo.whiteEngineIndex).played = false;
    Lizzie.frame.addInput(true);
    changeEngIcoForEndPk();
  }

  public void startNewEngineGame(boolean firstTime) {
    Leelaz currentForegroundEngine = Lizzie.leelaz;
    if (currentForegroundEngine != null) {
      if (!currentForegroundEngine.beginExclusiveGtpLifecycleTransition()) {
        showForegroundEngineLeaseInUse();
        return;
      }
      try {
        isPreEngineGame = true;
      } finally {
        currentForegroundEngine.endExclusiveGtpLifecycleTransition();
      }
    } else {
      isPreEngineGame = true;
    }
    // engineGameInfo
    Lizzie.frame.setResult("");
    if (firstTime) {
      killOtherEngines(engineGameInfo.blackEngineIndex, engineGameInfo.whiteEngineIndex);
      Lizzie.leelaz.notPondering();
      if (currentEngineNo == engineGameInfo.blackEngineIndex
          || currentEngineNo == engineGameInfo.whiteEngineIndex) {
        Lizzie.leelaz.nameCmd();
        Lizzie.leelaz.clearBestMoves();
      } else {
        if (!isEmpty) {
          try {
            Lizzie.leelaz.normalQuit();
          } catch (Exception ex) {
          }
        }
      }
    }
    if (!engineGameInfo.isGenmove) {
      // 分析模式对战
      ArrayList<Movelist> startList = prepareEngineGameBoard(firstTime, true);
      if (!firstTime) {
        engineList.get(engineGameInfo.blackEngineIndex).notPondering();
        engineList.get(engineGameInfo.blackEngineIndex).clear();
        engineList.get(engineGameInfo.whiteEngineIndex).notPondering();
        engineList.get(engineGameInfo.whiteEngineIndex).clear();
      }
      PkEngineSynchronization blackSynchronization =
          startEngineForPkSynchronization(engineGameInfo.blackEngineIndex);
      PkEngineSynchronization whiteSynchronization =
          startEngineForPkSynchronization(engineGameInfo.whiteEngineIndex);
      Runnable runnable =
          new Runnable() {
            public void run() {
              if (!finishPkEngineSynchronizations(
                  blackSynchronization, whiteSynchronization)) {
                return;
              }
              if (startList != null) {
                try {
                  Thread.sleep(1000);
                } catch (InterruptedException e) {
                  // TODO Auto-generated catch block
                  e.printStackTrace();
                }
              }
              Lizzie.frame.reSetLoc();
              Lizzie.frame.clearWRNforGame(false);
              if (Lizzie.config.autoLoadLzsaiEngineVisits) {
                Lizzie.engineManager
                    .engineList
                    .get(engineGameInfo.blackEngineIndex)
                    .sendCommand("lz-setoption name Visits value 1000000000");
                Lizzie.engineManager
                    .engineList
                    .get(engineGameInfo.whiteEngineIndex)
                    .sendCommand("lz-setoption name Visits value 1000000000");
              }
              Lizzie.engineManager
                  .engineList
                  .get(engineGameInfo.blackEngineIndex)
                  .sendCommand("clear_cache");
              Lizzie.engineManager
                  .engineList
                  .get(engineGameInfo.whiteEngineIndex)
                  .sendCommand("clear_cache");
              if (firstTime) {
                if (engineList.get(engineGameInfo.firstEngineIndex).isKatago) {
                  if (!engineList.get(engineGameInfo.firstEngineIndex).recentRulesLine.equals("")
                      && engineList.get(engineGameInfo.firstEngineIndex).recentRulesLine.length()
                          > 2) {
                    engineGameInfo.settingFirst +=
                        "\r\n"
                            + resourceBundle.getString("EngineGameInfo.rules")
                            + ": "
                            + new String(
                                engineList
                                    .get(engineGameInfo.firstEngineIndex)
                                    .recentRulesLine
                                    .substring(2));
                  }
                }

                if (engineList.get(engineGameInfo.secondEngineIndex).isKatago) {
                  if (!engineList.get(engineGameInfo.secondEngineIndex).recentRulesLine.equals("")
                      && engineList.get(engineGameInfo.secondEngineIndex).recentRulesLine.length()
                          > 2) {
                    engineGameInfo.settingSecond +=
                        "\r\n"
                            + resourceBundle.getString("EngineGameInfo.rules")
                            + ": "
                            + new String(
                                engineList
                                    .get(engineGameInfo.secondEngineIndex)
                                    .recentRulesLine
                                    .substring(2));
                  }
                }
              }
              if (Lizzie.board.getHistory().isBlacksTurn()) {
                Lizzie.setPrimaryEngine(engineList.get(engineGameInfo.blackEngineIndex));
              } else {
                Lizzie.setPrimaryEngine(engineList.get(engineGameInfo.whiteEngineIndex));
              }
              int cmdNumberTemp = Lizzie.leelaz.cmdNumber;
              Runnable runnable1 =
                  new Runnable() {
                    public void run() {
                      while (!Lizzie.leelaz.isResponseUpToDate()) {
                        try {
                          Thread.sleep(100);
                        } catch (InterruptedException e) {
                          // TODO Auto-generated catch block
                          e.printStackTrace();
                        }
                      }
                      Lizzie.leelaz.ponder();
                      Lizzie.leelaz.clearBestMoves();
                    }
                  };
              Thread thread1 = new Thread(runnable1);
              thread1.start();

              Runnable runnable =
                  new Runnable() {
                    public void run() {
                      while (Lizzie.leelaz.cmdNumber == cmdNumberTemp) {
                        try {
                          Thread.sleep(100);
                        } catch (InterruptedException e) {
                          // TODO Auto-generated catch block
                          e.printStackTrace();
                        }
                      }
                      isEngineGame = true;
                      isPreEngineGame = false;
                      Lizzie.leelaz.played = false;
                      setInfoAfterEngineGame();
                      if (firstTime) {
                        Lizzie.frame.resetMovelistFrameandAnalysisFrame();
                        LizzieFrame.menu.updateMenuStatusForEngine();
                      }
                    }
                  };
              Thread thread = new Thread(runnable);
              thread.start();
            }
          };
      Thread thread = new Thread(runnable);
      thread.start();
    } else {
      // genmove对战
      if (engineList.get(engineGameInfo.blackEngineIndex) != null) {
        engineList.get(engineGameInfo.blackEngineIndex).clearBestMoves();
      }
      if (engineList.get(engineGameInfo.whiteEngineIndex) != null) {
        engineList.get(engineGameInfo.whiteEngineIndex).clearBestMoves();
      }
      ArrayList<Movelist> startList = prepareEngineGameBoard(firstTime, false);
      PkEngineSynchronization blackSynchronization =
          startEngineForPkSynchronization(engineGameInfo.blackEngineIndex);
      PkEngineSynchronization whiteSynchronization =
          startEngineForPkSynchronization(engineGameInfo.whiteEngineIndex);
      Runnable runnable =
          new Runnable() {
            public void run() {
              if (!finishPkEngineSynchronizations(
                  blackSynchronization, whiteSynchronization)) {
                return;
              }
              Lizzie.frame.reSetLoc();
              Lizzie.frame.clearWRNforGame(true);
              isEngineGame = true;
              isPreEngineGame = false;
              engineList.get(engineGameInfo.blackEngineIndex).nameCmd();
              engineList.get(engineGameInfo.blackEngineIndex).notPondering();
              engineList.get(engineGameInfo.whiteEngineIndex).nameCmd();
              engineList.get(engineGameInfo.whiteEngineIndex).notPondering();

              if (Lizzie.config.pkAdvanceTimeSettings) {
                Lizzie.engineManager
                    .engineList
                    .get(engineGameInfo.blackEngineIndex)
                    .sendCommand(Lizzie.config.advanceBlackTimeTxt);
                Lizzie.engineManager
                    .engineList
                    .get(engineGameInfo.whiteEngineIndex)
                    .sendCommand(Lizzie.config.advanceWhiteTimeTxt);
                if (firstEngineCountDown != null || secondEngineCountDown != null) {
                  if (firstEngineCountDown != null)
                    firstEngineCountDown.initialize(engineGameInfo.isFirstEnginePlayBlack());
                  if (secondEngineCountDown != null)
                    secondEngineCountDown.initialize(!engineGameInfo.isFirstEnginePlayBlack());
                  StartCountDown();
                }
              } else {
                DesktopTimeControl.sendEngineGameFixedTime(
                    Lizzie.engineManager.engineList.get(engineGameInfo.whiteEngineIndex),
                    engineGameInfo.timeWhite);
                DesktopTimeControl.sendEngineGameFixedTime(
                    Lizzie.engineManager.engineList.get(engineGameInfo.blackEngineIndex),
                    engineGameInfo.timeBlack);
              }
              Lizzie.engineManager
                  .engineList
                  .get(engineGameInfo.blackEngineIndex)
                  .sendCommand("clear_cache");
              Lizzie.engineManager
                  .engineList
                  .get(engineGameInfo.whiteEngineIndex)
                  .sendCommand("clear_cache");
              if (startList != null) {
                try {
                  Thread.sleep(1000);
                } catch (InterruptedException e) {
                  // TODO Auto-generated catch block
                  e.printStackTrace();
                }
              }
              if (Lizzie.board.getHistory().isBlacksTurn()) {
                Lizzie.setPrimaryEngine(engineList.get(engineGameInfo.blackEngineIndex));
                Lizzie.leelaz.genmoveForPk("b");
                Lizzie.setPrimaryEngine(engineList.get(engineGameInfo.whiteEngineIndex));
              } else {
                Lizzie.setPrimaryEngine(engineList.get(engineGameInfo.whiteEngineIndex));
                Lizzie.leelaz.genmoveForPk("w");
                Lizzie.setPrimaryEngine(engineList.get(engineGameInfo.blackEngineIndex));
              }
              setInfoAfterEngineGame();
              if (firstTime) {
                Lizzie.frame.resetMovelistFrameandAnalysisFrame();
                LizzieFrame.menu.updateMenuStatusForEngine();
                if (engineList.get(engineGameInfo.firstEngineIndex).isKatago) {
                  if (!engineList.get(engineGameInfo.firstEngineIndex).recentRulesLine.equals("")
                      && engineList.get(engineGameInfo.firstEngineIndex).recentRulesLine.length()
                          > 2) {
                    engineGameInfo.settingFirst +=
                        "\r\n"
                            + resourceBundle.getString("EngineGameInfo.rules")
                            + ": "
                            + new String(
                                engineList
                                    .get(engineGameInfo.firstEngineIndex)
                                    .recentRulesLine
                                    .substring(2));
                  }
                }

                if (engineList.get(engineGameInfo.secondEngineIndex).isKatago) {
                  if (!engineList.get(engineGameInfo.secondEngineIndex).recentRulesLine.equals("")
                      && engineList.get(engineGameInfo.secondEngineIndex).recentRulesLine.length()
                          > 2) {
                    engineGameInfo.settingSecond +=
                        "\r\n"
                            + resourceBundle.getString("EngineGameInfo.rules")
                            + ": "
                            + new String(
                                engineList
                                    .get(engineGameInfo.secondEngineIndex)
                                    .recentRulesLine
                                    .substring(2));
                  }
                }
              }
            }
          };
      Thread thread = new Thread(runnable);
      thread.start();
    }
  }

  private void setInfoAfterEngineGame() {
    Lizzie.frame.setPlayers(
        engineList.get(engineGameInfo.whiteEngineIndex).oriEnginename,
        engineList.get(engineGameInfo.blackEngineIndex).oriEnginename);
    GameInfo gameInfo = Lizzie.board.getHistory().getGameInfo();
    gameInfo.setPlayerWhite(engineList.get(engineGameInfo.whiteEngineIndex).oriEnginename);
    gameInfo.setPlayerBlack(engineList.get(engineGameInfo.blackEngineIndex).oriEnginename);
    Lizzie.frame.updateTitle();
    LizzieFrame.menu.toggleDoubleMenuGameStatus();
  }

  //  private void checkEngineNotHang() {
  //    if (isEngineGame
  //        && !Lizzie.frame.toolbar.isGenmoveToolbar
  //        && !Lizzie.frame.toolbar.isPkStop
  //        && System.currentTimeMillis() - startInfoTime > 1000 * 240) {
  //      Lizzie.leelaz.process.destroy();
  //      Lizzie.gtpConsole.addLine("EnginePkHangs");
  //      startInfoTime = System.currentTimeMillis();
  //    }
  //    //    try {
  //    //      timer3.stop();
  //    //      // timer3 = null;
  //    //    } catch (Exception ex) {
  //    //
  //    //    }
  //  }

  private void checkEngineAlive() {
    if (isEmpty) return;
    if (!isEngineGame && Lizzie.leelaz != null) {
      if (Lizzie.leelaz.isStarted()
          && Lizzie.leelaz.canCheckAlive
          && Lizzie.leelaz.isProcessDead()) {
        if (Lizzie.leelaz.useRemoteCompute) {
          restartRemoteEngineInBackground(Lizzie.leelaz, currentEngineNo);
        } else {
          try {
            restartEngineAutomatically(Lizzie.leelaz, currentEngineNo);
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      }
      if (Lizzie.leelaz.useJavaSSH && Lizzie.leelaz.isLoaded() && Lizzie.leelaz.canCheckAlive) {
        if (Lizzie.leelaz.javaSSHClosed)
          try {
            restartEngineAutomatically(Lizzie.leelaz, currentEngineNo);
          } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
      }
    }
    //   if (isEngineGame) {
    //    {
    // checkEngineNotHang();
    checkEnginePK();
    // if (Lizzie.leelaz.resigned) Lizzie.leelaz.pkResign();
    //        if (Lizzie.leelaz.isPondering() && (timer3 == null || !timer3.isRunning())) {
    //          timer3 =
    //              new Timer(
    //                  5000,
    //                  new ActionListener() {
    //                    public void actionPerformed(ActionEvent evt) {
    //
    //
    //                      try {
    //                      } catch (Exception e) {
    //                      }
    //                    }
    //                  });
    //          timer3.start();
    //        }
    //      }
    //      if ((timer2 == null || !timer2.isRunning())) {
    //        timer2 =
    //            new Timer(
    //                20000,
    //                new ActionListener() {
    //                  public void actionPerformed(ActionEvent evt) {
    //                    checkEnginePK();
    //                    try {
    //                    } catch (Exception e) {
    //                    }
    //                  }
    //                });
    //        timer2.start();
    //    }
    //   }
  }

  private void checkEnginePK() {
    if (!isEngineGame) {
      return;
    }
    if (engineList.get(engineGameInfo.firstEngineIndex).canCheckAlive
        && ((engineList.get(engineGameInfo.firstEngineIndex).isProcessDead())
            || (engineList.get(engineGameInfo.firstEngineIndex).useJavaSSH
                && engineList.get(engineGameInfo.firstEngineIndex).javaSSHClosed))) {
      try {
        restartEngineForPk(engineGameInfo.firstEngineIndex);
      } catch (Exception e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
    if (engineList.get(engineGameInfo.secondEngineIndex).canCheckAlive
        && ((engineList.get(engineGameInfo.secondEngineIndex).isProcessDead())
            || (engineList.get(engineGameInfo.secondEngineIndex).useJavaSSH
                && engineList.get(engineGameInfo.secondEngineIndex).javaSSHClosed))) {
      try {
        restartEngineForPk(engineGameInfo.secondEngineIndex);
      } catch (Exception e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
    //    try {
    //      timer2.stop();
    //      // timer2 = null;
    //    } catch (Exception ex) {
    //
    //    }
  }

  public void updateEngines() throws JSONException, IOException {
    isUpdating = true;
    int preIndex = currentEngineNo;
    Leelaz previousForegroundEngine = Lizzie.leelaz;
    Leelaz previousSecondaryEngine = Lizzie.leelaz2;
    String currentEngineName =
        previousForegroundEngine == null ? "" : previousForegroundEngine.oriEnginename;
    String currentSecondaryEngineName =
        previousSecondaryEngine == null ? "" : previousSecondaryEngine.oriEnginename;
    ArrayList<EngineData> engineData = Utils.getEngineData();
    EngineData selectedEngineData = null;
    EngineData selectedSecondaryData = null;
    for (EngineData engineDt : engineData) {
      if (selectedEngineData == null && engineDt.name.equals(currentEngineName)) {
        selectedEngineData = engineDt;
      }
      if (selectedSecondaryData == null
          && !currentSecondaryEngineName.isEmpty()
          && engineDt.name.equals(currentSecondaryEngineName)
          && engineDt != selectedEngineData) {
        selectedSecondaryData = engineDt;
      }
    }
    Board restoreBoard = Lizzie.board;
    boolean resumePonder =
        previousForegroundEngine != null
            && previousForegroundEngine.isPonderingOrWasPonderingBeforeTracking();
    Leelaz preparedTarget = null;
    Leelaz preparedMirror = null;
    InitialEngineStartupSynchronization lifecycleSynchronization = null;
    if (selectedEngineData != null) {
      preparedTarget = createUnstartedEngine(selectedEngineData);
      if (selectedSecondaryData != null && previousSecondaryEngine != null) {
        preparedMirror = createUnstartedEngine(selectedSecondaryData);
      }
      boolean exactEligible =
          restoreBoard != null
              && preparedTarget.oriWidth == Board.boardWidth
              && preparedTarget.oriHeight == Board.boardHeight
              && (preparedMirror == null
                  || (preparedMirror.oriWidth == Board.boardWidth
                      && preparedMirror.oriHeight == Board.boardHeight));
      try {
        lifecycleSynchronization =
            InitialEngineStartupSynchronization.capture(
                previousForegroundEngine,
                preparedTarget,
                preparedMirror,
                restoreBoard,
                !exactEligible,
                resumePonder);
        lifecycleSynchronization.beginLifecycleCompletionClaim();
      } catch (InitialStartupReservationException leaseInUse) {
        if (lifecycleSynchronization != null) {
          lifecycleSynchronization.close();
        }
        isUpdating = false;
        showForegroundEngineLeaseInUse();
        return;
      } catch (RuntimeException startupFailure) {
        if (lifecycleSynchronization != null) {
          lifecycleSynchronization.close();
        }
        isUpdating = false;
        startupFailure.printStackTrace();
        preparedTarget.isLoaded = false;
        if (preparedMirror != null) {
          preparedMirror.isLoaded = false;
        }
        showEngineSynchronizationFailure(preparedTarget);
        return;
      }
    }
    boolean updateSyncDelegated = false;
    final InitialEngineStartupSynchronization frozenLifecycleSynchronization =
        lifecycleSynchronization;
    try {
      if (lifecycleSynchronization == null) {
        if (!killAllEngines()) {
          isUpdating = false;
          return;
        }
      } else {
        killAllEnginesUnderReservation();
      }
      final Leelaz frozenPreparedTarget = preparedTarget;
      final Leelaz frozenPreparedMirror = preparedMirror;
      engineList = new ArrayList<Leelaz>();
      // engineList.add(lz);
      boolean loadLeelaz = false;
      for (int i = 0; i < engineData.size(); i++) {
        EngineData engineDt = engineData.get(i);
        Leelaz e;
        if (engineDt == selectedEngineData) {
          e = frozenPreparedTarget;
        } else if (engineDt == selectedSecondaryData) {
          e = frozenPreparedMirror;
        } else {
          e = createUnstartedEngine(engineDt);
        }
        if (!loadLeelaz && engineDt.name.equals(currentEngineName)) {
          loadLeelaz = true;
          if (e.oriWidth != Board.boardWidth || e.oriHeight != Board.boardHeight) {
            Board.boardWidth = e.oriWidth;
            Board.boardHeight = e.oriHeight;
            Zobrist.init();
            Lizzie.board.clear(false);
          }
          Lizzie.setPrimaryEngine(e);
          e.preload = true;
          e.firstLoad = true;
          currentEngineNo = i;
          engineNo = i;
          final EngineData frozenSelectedSecondaryData = selectedSecondaryData;
          Thread replacementStart =
              new Thread() {
                public void run() {
                  boolean synchronizationScheduled = false;
                  boolean targetStarted = false;
                  boolean mirrorStarted = false;
                  try {
                    try {
                      e.startEngine(engineDt.index);
                      targetStarted = true;
                      if (frozenPreparedMirror != null) {
                        frozenPreparedMirror.startEngine(
                            frozenSelectedSecondaryData == null
                                ? -1
                                : frozenSelectedSecondaryData.index);
                        mirrorStarted = true;
                      }
                      Menu.engineMenu.setText(
                          "[" + (e.currentEngineN() + 1) + "]: " + e.oriEnginename);
                    } catch (IOException e2) {
                      e.isLoaded = false;
                      if (frozenPreparedMirror != null) {
                        frozenPreparedMirror.isLoaded = false;
                      }
                      if (mirrorStarted) {
                        try {
                          frozenPreparedMirror.forceQuit();
                        } catch (RuntimeException ignored) {
                          // Failure cleanup is best effort; endpoint remains unavailable.
                        }
                      }
                      if (targetStarted) {
                        try {
                          e.forceQuit();
                        } catch (RuntimeException ignored) {
                          // Failure cleanup is best effort; endpoint remains unavailable.
                        }
                      }
                      e.markLifecycleBoardSynchronizationFailed(e2.getMessage(), false);
                      if (frozenPreparedMirror != null) {
                        frozenPreparedMirror.markLifecycleBoardSynchronizationFailed(
                            e2.getMessage(), false);
                      }
                      showEngineSynchronizationFailure(e);
                      e2.printStackTrace();
                      return;
                    }
                    if (currentEngineNo > 20) LizzieFrame.menu.changeEngineIcon(20, 3);
                    else LizzieFrame.menu.changeEngineIcon(currentEngineNo, 3);
                    Runnable syncBoard =
                        () -> {
                          frozenLifecycleSynchronization.runUntilStable();
                          frozenLifecycleSynchronization.confirmFinalBoardSynchronization(
                              frozenLifecycleSynchronization::initializeUpdateRestore,
                              detail -> {
                                e.isLoaded = false;
                                e.markLifecycleBoardSynchronizationFailed(detail, false);
                                if (frozenPreparedMirror != null) {
                                  frozenPreparedMirror.isLoaded = false;
                                  frozenPreparedMirror.markLifecycleBoardSynchronizationFailed(
                                      detail, false);
                                }
                                showEngineSynchronizationFailure(e);
                              });
                        };
                    synchronizeUpdateEnginesWhenReady(
                        e,
                        frozenPreparedMirror,
                        syncBoard,
                        frozenLifecycleSynchronization::close);
                    synchronizationScheduled = true;
                  } finally {
                    if (!synchronizationScheduled) {
                      frozenLifecycleSynchronization.close();
                    }
                  }
                }
              };
          updateSyncDelegated = true;
          try {
            replacementStart.start();
          } catch (RuntimeException failure) {
            updateSyncDelegated = false;
            throw failure;
          }
        } else if (engineDt == selectedSecondaryData) {
          Lizzie.leelaz2 = e;
          e.preload = true;
          e.firstLoad = true;
          currentEngineNo2 = i;
        } else if (e.preload) {
          new Thread() {
            public void run() {
              try {
                e.startEngine(engineDt.index);
              } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
            }
          }.start();
        }
        engineList.add(e);
      }
      if (!loadLeelaz && preIndex >= 0) {
        switchEngine(preIndex, true);
      }

      int j = LizzieFrame.toolbar.enginePkBlack.getItemCount();
      LizzieFrame.toolbar.removeEngineLis();
      for (int i = 0; i < j; i++) {
        LizzieFrame.toolbar.enginePkBlack.removeItemAt(0);
        LizzieFrame.toolbar.enginePkWhite.removeItemAt(0);
      }
      for (int i = 0; i < engineData.size(); i++) {
        EngineData engineDt = engineData.get(i);
        LizzieFrame.toolbar.enginePkBlack.addItem("[" + (i + 1) + "]" + engineDt.name);
        LizzieFrame.toolbar.enginePkWhite.addItem("[" + (i + 1) + "]" + engineDt.name);
      }
      LizzieFrame.toolbar.engineBlackToolbar = 0;
      LizzieFrame.toolbar.engineWhiteToolbar = 0;
      LizzieFrame.toolbar.addEngineLis();
      LizzieFrame.menu.updateEngineMenu();
      if (!isEmpty) {
        Menu.engineMenu.setText(
            "["
                + (EngineManager.currentEngineNo > 0
                    ? EngineManager.currentEngineNo + 1
                    : engineNo + 1)
                + "]: "
                + Lizzie.leelaz.oriEnginename);
      }
      isUpdating = false;
    } finally {
      if (!updateSyncDelegated && frozenLifecycleSynchronization != null) {
        frozenLifecycleSynchronization.close();
      }
    }
  }

  private void restartRemoteEngineInBackground(Leelaz engine, int index) {
    if (engine == null) {
      return;
    }
    Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
    if (attempt == null) {
      return;
    }
    if (!REMOTE_ENGINES_RESTARTING.add(engine)) {
      attempt.close();
      return;
    }
    engine.canCheckAlive = false;
    Thread restartThread =
        new Thread(
            () -> {
              boolean restartStarted = false;
              try {
                if (Lizzie.leelaz != engine
                    || currentEngineNo != index
                    || isEmpty
                    || !engine.isProcessDead()) {
                  engine.canCheckAlive = true;
                  return;
                }
                attempt.restartClosedEngine(index);
                restartStarted = true;
              } catch (IOException | RuntimeException failure) {
                failure.printStackTrace();
              } finally {
                if (!restartStarted) {
                  attempt.close();
                }
                REMOTE_ENGINES_RESTARTING.remove(engine);
              }
            },
            "lizzie-remote-engine-restart");
    restartThread.setDaemon(true);
    try {
      restartThread.start();
    } catch (RuntimeException failure) {
      attempt.close();
      REMOTE_ENGINES_RESTARTING.remove(engine);
      engine.canCheckAlive = true;
      throw failure;
    }
  }

  void restartUnresponsiveRemoteEngine(Leelaz engine, int index) {
    if (engine == null
        || engine != Lizzie.leelaz
        || index != currentEngineNo
        || isEmpty
        || !engine.isProcessDead()) {
      return;
    }
    restartRemoteEngineInBackground(engine, index);
  }

  private void restartEngineAutomatically(Leelaz engine, int index) throws IOException {
    Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
    if (attempt == null) {
      return;
    }
    try {
      attempt.restartClosedEngine(index);
    } catch (IOException | RuntimeException failure) {
      attempt.close();
      throw failure;
    }
  }

  public void refreshEngineCatalog() throws JSONException, IOException {
    if (engineList == null) {
      updateEngines();
      return;
    }
    ArrayList<EngineData> engineData = Utils.getEngineData();
    List<Leelaz> previousEngines = new ArrayList<Leelaz>(engineList);
    boolean[] matched = new boolean[previousEngines.size()];
    List<Leelaz> refreshedEngines = new ArrayList<Leelaz>();
    Leelaz currentMainEngine = Lizzie.leelaz;
    Leelaz currentSecondaryEngine = Lizzie.leelaz2;
    int refreshedCurrentEngineNo = -1;
    int refreshedCurrentEngineNo2 = -1;

    for (int i = 0; i < engineData.size(); i++) {
      EngineData engineDt = engineData.get(i);
      int matchIndex = findMatchingEngine(previousEngines, matched, engineDt, i);
      Leelaz engine =
          matchIndex >= 0 ? previousEngines.get(matchIndex) : createUnstartedEngine(engineDt);
      applySavedEngineMetadata(engine, engineDt, i);
      refreshedEngines.add(engine);
      if (engine == currentMainEngine) refreshedCurrentEngineNo = i;
      if (engine == currentSecondaryEngine) refreshedCurrentEngineNo2 = i;
    }

    if (!isEmpty && currentMainEngine != null && refreshedCurrentEngineNo < 0) {
      updateEngines();
      return;
    }

    engineList = refreshedEngines;
    if (refreshedCurrentEngineNo >= 0) {
      currentEngineNo = refreshedCurrentEngineNo;
      engineNo = refreshedCurrentEngineNo;
    }
    currentEngineNo2 = refreshedCurrentEngineNo2;
    refreshEngineSelectionControls(engineData);
    LizzieFrame.menu.updateEngineMenu();
    if (!isEmpty && currentEngineNo >= 0 && currentEngineNo < engineList.size()) {
      Menu.engineMenu.setText(
          "[" + (currentEngineNo + 1) + "]: " + engineList.get(currentEngineNo).oriEnginename);
    }
  }

  private int findMatchingEngine(
      List<Leelaz> previousEngines, boolean[] matched, EngineData engineDt, int preferredIndex) {
    if (preferredIndex >= 0
        && preferredIndex < previousEngines.size()
        && !matched[preferredIndex]
        && isSameEngineProcess(previousEngines.get(preferredIndex), engineDt)) {
      matched[preferredIndex] = true;
      return preferredIndex;
    }
    for (int i = 0; i < previousEngines.size(); i++) {
      if (!matched[i] && isSameEngineProcess(previousEngines.get(i), engineDt)) {
        matched[i] = true;
        return i;
      }
    }
    return -1;
  }

  private boolean isSameEngineProcess(Leelaz engine, EngineData engineDt) {
    return engine != null
        && engineDt != null
        && safeEquals(engine.oriEngineCommand, engineDt.commands)
        && engine.oriWidth == engineDt.width
        && engine.oriHeight == engineDt.height
        && engine.useJavaSSH == engineDt.useJavaSSH
        && safeEquals(engine.ip, engineDt.ip)
        && safeEquals(engine.port, engineDt.port)
        && safeEquals(engine.userName, engineDt.userName);
  }

  private boolean safeEquals(String first, String second) {
    if (first == null) return second == null;
    return first.equals(second);
  }

  private Leelaz createUnstartedEngine(EngineData engineDt) throws JSONException, IOException {
    Leelaz engine = new Leelaz(engineDt.commands);
    applySavedEngineMetadata(engine, engineDt, engineDt.index);
    return engine;
  }

  private void applySavedEngineMetadata(Leelaz engine, EngineData engineDt, int index) {
    engine.preload = engineDt.preload;
    engine.width = engineDt.width;
    engine.height = engineDt.height;
    engine.oriWidth = engineDt.width;
    engine.oriHeight = engineDt.height;
    engine.komi = engineDt.komi;
    engine.orikomi = engineDt.komi;
    engine.useJavaSSH = engineDt.useJavaSSH;
    engine.ip = engineDt.ip;
    engine.port = engineDt.port;
    engine.useKeyGen = engineDt.useKeyGen;
    engine.keyGenPath = engineDt.keyGenPath;
    engine.userName = engineDt.userName;
    engine.password = engineDt.password;
    engine.initialCommand = engineDt.initialCommand;
    engine.gtpConfigurationProtocol = engineDt.gtpConfigurationProtocol;
    engine.gtpConfigurationProfile = copyProfile(engineDt.gtpConfigurationProfile);
    engine.getEngineName(index);
  }

  private static JSONObject copyProfile(JSONObject profile) {
    return profile == null ? null : new JSONObject(profile.toString());
  }

  private void refreshEngineSelectionControls(ArrayList<EngineData> engineData) {
    int j = LizzieFrame.toolbar.enginePkBlack.getItemCount();
    LizzieFrame.toolbar.removeEngineLis();
    for (int i = 0; i < j; i++) {
      LizzieFrame.toolbar.enginePkBlack.removeItemAt(0);
      LizzieFrame.toolbar.enginePkWhite.removeItemAt(0);
    }
    for (int i = 0; i < engineData.size(); i++) {
      EngineData engineDt = engineData.get(i);
      LizzieFrame.toolbar.enginePkBlack.addItem("[" + (i + 1) + "]" + engineDt.name);
      LizzieFrame.toolbar.enginePkWhite.addItem("[" + (i + 1) + "]" + engineDt.name);
    }
    LizzieFrame.toolbar.engineBlackToolbar = 0;
    LizzieFrame.toolbar.engineWhiteToolbar = 0;
    LizzieFrame.toolbar.addEngineLis();
  }

  public boolean killAllEngines() {
    Leelaz currentForegroundEngine = Lizzie.leelaz;
    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        currentForegroundEngine == null
            ? null
            : currentForegroundEngine.beginExclusiveGtpLifecycleReservation();
    if (currentForegroundEngine != null && reservation == null) {
      showForegroundEngineLeaseInUse();
      return false;
    }
    try {
      killAllEnginesUnderReservation();
    } finally {
      if (reservation != null) {
        reservation.close();
      }
    }
    return true;
  }

  private void killAllEnginesUnderReservation() {
    // currentEngineNo = -1;
    for (int i = 0; i < engineList.size(); i++) {
      if (engineList.get(i).isStarted()) {
        try {
          engineList.get(i).forceQuit();
        } catch (Exception e) {
        }
      }
    }
    currentEngineNo2 = -1;
    currentEngineNo = -1;
    isEmpty = true;
    Lizzie.leelaz.notPondering();
    Lizzie.leelaz.isLoaded = true;
    Menu.engineMenu.setText(resourceBundle.getString("Menu.noEngine"));
    Lizzie.frame.invalidateTrackingAnalysis();
    Lizzie.frame.refresh();
  }

  public void forceKillAllEngines() {
    // currentEngineNo = -1;
    for (int i = 0; i < engineList.size(); i++) {
      if (engineList.get(i).isStarted()) {
        try {
          engineList.get(i).forceQuit();
        } catch (Exception e) {
        }
      }
    }
    Lizzie.leelaz.notPondering();
  }

  public void reStartEngine() {
    // currentEngineNo = -1;
    if (isEmpty || Lizzie.leelaz == null) return;
    Leelaz currentForegroundEngine = Lizzie.leelaz;
    boolean restartPonderIntent = currentForegroundEngine.isPonderingOrWasPonderingBeforeTracking();
    int restartEngineIndex = currentEngineNo;
    if (rejectSameEngineSelection(restartEngineIndex, true)) return;
    Leelaz restartTarget = engineList.get(restartEngineIndex);
    PreparedEngineSwitch preparedSwitch;
    try {
      preparedSwitch = prepareEngineSwitch(restartEngineIndex, true, true);
    } catch (Leelaz.ExactSnapshotRestoreAdmissionException
        | InitialStartupReservationException conflict) {
      showForegroundEngineLeaseInUse();
      return;
    } catch (RuntimeException failure) {
      restartTarget.isLoaded = false;
      showEngineSynchronizationFailure(restartTarget);
      return;
    }
    if (preparedSwitch != null) {
      restartTarget = preparedSwitch.targetEngine;
    }
    if (preparedSwitch == null) {
    EngineLifecycleReservations reservations =
          reservePreparedEngineSwitch(currentForegroundEngine, restartTarget, null);
    if (reservations == null) {
      showForegroundEngineLeaseInUse();
      return;
    }
    if (!attachRestartInteractionGate(reservations)) {
      return;
    }
      shutdownEngineForRestart(restartTarget);
      switchEngineInternal(
          restartEngineIndex,
          true,
          null,
          releaseEngineLifecycleAfterBoardSync(
              currentForegroundEngine,
              restartTarget,
              true,
              true,
              restartPonderIntent,
              reservations,
              null));
      return;
    }
    InitialEngineStartupSynchronization lifecycleSynchronization =
        preparedSwitch.initialStartupSynchronization;
    EngineLifecycleReservations reservations =
        reservePreparedEngineSwitch(currentForegroundEngine, restartTarget, preparedSwitch);
    if (reservations == null) {
      lifecycleSynchronization.close();
      showForegroundEngineLeaseInUse();
      return;
    }
    lifecycleSynchronization.installReservations(reservations);
    if (!lifecycleSynchronization.attachRestartInteractionGate()) {
      showEngineSynchronizationFailure(restartTarget);
      return;
    }
    shutdownEngineForRestart(restartTarget);
    switchEngineInternal(
        restartEngineIndex,
        true,
        preparedSwitch,
        releaseEngineLifecycleAfterBoardSync(
            currentForegroundEngine,
            restartTarget,
            true,
            true,
            restartPonderIntent,
            lifecycleSynchronization::close,
            lifecycleSynchronization.isTrackingFirstWinner(),
            preparedSwitch.lifecycleRestore));
  }

  public void reStartEngine(int index) {
    // currentEngineNo = -1;
    if (isEmpty || Lizzie.leelaz == null) return;
    if (rejectSameEngineSelection(index, true)) return;
    Leelaz currentForegroundEngine = Lizzie.leelaz;
    boolean restartPonderIntent = currentForegroundEngine.isPonderingOrWasPonderingBeforeTracking();
    Leelaz targetEngine = engineList.get(index);
    PreparedEngineSwitch preparedSwitch;
    try {
      preparedSwitch = prepareEngineSwitch(index, true, true);
    } catch (Leelaz.ExactSnapshotRestoreAdmissionException
        | InitialStartupReservationException conflict) {
      showForegroundEngineLeaseInUse();
      return;
    } catch (RuntimeException failure) {
      targetEngine.isLoaded = false;
      showEngineSynchronizationFailure(targetEngine);
      return;
    }
    if (preparedSwitch != null) {
      targetEngine = preparedSwitch.targetEngine;
    }
    if (preparedSwitch == null) {
    EngineLifecycleReservations reservations =
          reservePreparedEngineSwitch(currentForegroundEngine, targetEngine, null);
    if (reservations == null) {
        showForegroundEngineLeaseInUse();
        return;
      }
    if (!attachRestartInteractionGate(reservations)) {
      return;
    }
      shutdownEngineForRestart(targetEngine);
      switchEngineInternal(
          index,
          true,
          null,
          releaseEngineLifecycleAfterBoardSync(
              currentForegroundEngine,
              targetEngine,
              true,
              true,
              restartPonderIntent,
              reservations,
              null));
      return;
    }
    InitialEngineStartupSynchronization lifecycleSynchronization =
        preparedSwitch.initialStartupSynchronization;
    EngineLifecycleReservations reservations =
        reservePreparedEngineSwitch(currentForegroundEngine, targetEngine, preparedSwitch);
    if (reservations == null) {
      lifecycleSynchronization.close();
      showForegroundEngineLeaseInUse();
      return;
    }
    lifecycleSynchronization.installReservations(reservations);
    if (!lifecycleSynchronization.attachRestartInteractionGate()) {
      showEngineSynchronizationFailure(targetEngine);
      return;
    }
    shutdownEngineForRestart(targetEngine);
    switchEngineInternal(
        index,
        true,
        preparedSwitch,
        releaseEngineLifecycleAfterBoardSync(
            currentForegroundEngine,
            targetEngine,
            true,
            true,
            restartPonderIntent,
            lifecycleSynchronization::close,
            lifecycleSynchronization.isTrackingFirstWinner(),
            preparedSwitch.lifecycleRestore));
  }

  public void reStartEngine2() {
    // currentEngineNo = -1;
    if (Lizzie.leelaz2 == null || currentEngineNo2 < 0 || currentEngineNo2 >= engineList.size())
      return;
    int restartEngineIndex = currentEngineNo2;
    if (rejectSameEngineSelection(restartEngineIndex, false)) return;
    boolean restartPonderIntent =
        Lizzie.leelaz != null && Lizzie.leelaz.isPonderingOrWasPonderingBeforeTracking();
    Leelaz secondaryTarget = engineList.get(restartEngineIndex);
    PreparedEngineSwitch preparedSwitch;
    try {
      preparedSwitch = prepareEngineSwitch(restartEngineIndex, false, true);
    } catch (Leelaz.ExactSnapshotRestoreAdmissionException
        | InitialStartupReservationException conflict) {
      showForegroundEngineLeaseInUse();
      return;
    } catch (RuntimeException failure) {
      secondaryTarget.isLoaded = false;
      showEngineSynchronizationFailure(secondaryTarget);
      return;
    }
    if (preparedSwitch != null) {
      secondaryTarget = preparedSwitch.targetEngine;
    }
    if (preparedSwitch == null) {
      EngineLifecycleReservations reservations =
          reservePreparedEngineSwitch(Lizzie.leelaz, secondaryTarget, null);
      if (reservations == null) {
        showForegroundEngineLeaseInUse();
        return;
      }
      shutdownEngineForRestart(secondaryTarget);
      switchEngineInternal(
          restartEngineIndex,
          false,
          null,
          releaseEngineLifecycleAfterBoardSync(
              Lizzie.leelaz,
              secondaryTarget,
              false,
              true,
              restartPonderIntent,
              reservations,
              null));
      return;
    }
    InitialEngineStartupSynchronization lifecycleSynchronization =
        preparedSwitch.initialStartupSynchronization;
    EngineLifecycleReservations reservations =
        reservePreparedEngineSwitch(Lizzie.leelaz, secondaryTarget, preparedSwitch);
    if (reservations == null) {
      lifecycleSynchronization.close();
      showForegroundEngineLeaseInUse();
      return;
    }
    lifecycleSynchronization.installReservations(reservations);
    shutdownEngineForRestart(secondaryTarget);
    switchEngineInternal(
        restartEngineIndex,
        false,
        preparedSwitch,
        releaseEngineLifecycleAfterBoardSync(
            Lizzie.leelaz,
            secondaryTarget,
            false,
            true,
            restartPonderIntent,
            lifecycleSynchronization::close,
            lifecycleSynchronization.isTrackingFirstWinner(),
            preparedSwitch.lifecycleRestore));
  }

  private void shutdownEngineForRestart(Leelaz engine) {
    try {
      engine.isNormalEnd = true;
      engine.shutdown();
      Thread.sleep(200);
      engine.started = false;
      engine.isLoaded = false;
      if (engine.isLeela0110) {
        engine.leela0110StopPonder();
      }
    } catch (Exception ignored) {
    }
  }

  public void killOtherEngines() {
    for (int i = 0; i < engineList.size(); i++) {
      if (engineList.get(i).isStarted()) {
        if (engineList.get(i) != Lizzie.leelaz)
          try {
            // engineList.get(i).normalQuit();
            engineList.get(i).forceQuit();
          } catch (Exception e) {
          }
      }
    }
    currentEngineNo2 = -1;
  }

  public void killOtherEngines(int engineBlack, int engineWhite) {
    for (int i = 0; i < engineList.size(); i++) {
      if (engineList.get(i).isStarted()) {
        if (i != engineBlack && i != engineWhite) engineList.get(i).normalQuit();
      }
    }
  }

  public void killThisEngines() {
    Leelaz currentForegroundEngine = Lizzie.leelaz;
    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        currentForegroundEngine == null
            ? null
            : currentForegroundEngine.beginExclusiveGtpLifecycleReservation();
    if (currentForegroundEngine != null && reservation == null) {
      showForegroundEngineLeaseInUse();
      return;
    }
    try {
      if (engineList.get(currentEngineNo).isStarted()) {
        engineList.get(currentEngineNo).forceQuit();
      }
      currentEngineNo = -1;
      isEmpty = true;
      Lizzie.leelaz.isLoaded = true;
      Lizzie.leelaz.notPondering();
      Lizzie.leelaz.clearBestMoves();
      Lizzie.frame.invalidateTrackingAnalysis();
    } finally {
      if (reservation != null) {
        reservation.close();
      }
    }
  }

  public void killThisEngines2() {
    engineList.get(currentEngineNo2).normalQuit();
    currentEngineNo2 = -1;
    Lizzie.leelaz2.notPondering();
    Lizzie.leelaz2.clearBestMoves();
  }

  protected void showForegroundEngineLeaseInUse() {
    Utils.showMsg(Lizzie.resourceBundle.getString("AnalysisSettings.reuseStatus.existing_lease"));
  }

  /**
   * Switch the Engine by index number
   *
   * @param index engine index
   */
  public void startEngineForPk(int index) {
    startEngineForPkSynchronization(index);
  }

  PkEngineSynchronization startEngineForPkSynchronization(int index) {
    PkEngineSynchronization completion = new PkEngineSynchronization();
    if (index < 0 || index >= this.engineList.size()) {
      completion.fail();
      return completion;
    }
    Leelaz newEng = engineList.get(index);
    newEng.outOfMoveNum = false;
    newEng.blackResignMoveCounts = 0;
    newEng.whiteResignMoveCounts = 0;
    newEng.doublePass = false;
    newEng.resigned = false;
    newEng.isResigning = false;
    newEng.width = Board.boardWidth;
    newEng.height = Board.boardHeight;
    newEng.pkMoveTimeGame = 0;
    Board restoreBoard = Lizzie.board;
    Leelaz proposedRestoreMirror = newEng.resolveLoadSgfMirrorEngine();
    InitialEngineStartupSynchronization lifecycleSynchronization = null;
    try {
      lifecycleSynchronization =
          InitialEngineStartupSynchronization.capturePrepared(
              null, newEng, proposedRestoreMirror, restoreBoard, false, false);
      lifecycleSynchronization.acquireReservation();
      lifecycleSynchronization.beginLifecycleCompletionClaim();
      lifecycleSynchronization.completePkSynchronizationAfterClaimRelease(completion);
    } catch (InitialStartupReservationException
        | Leelaz.ExactSnapshotRestoreAdmissionException conflict) {
      if (lifecycleSynchronization != null) {
        lifecycleSynchronization.close();
      }
      showForegroundEngineLeaseInUse();
      completion.fail();
      return completion;
    }
    final InitialEngineStartupSynchronization frozenLifecycleSynchronization =
        lifecycleSynchronization;
    try {
      newEng.notPondering();
      newEng.clearBestMoves();
      newEng.komi = lifecycleSynchronization.pendingRoute.rootKomi.floatValue();
      if (!newEng.isStarted()) {
        try {
          newEng.startEngine(index);
        } catch (IOException failure) {
          newEng.isLoaded = false;
          failure.printStackTrace();
          lifecycleSynchronization.close();
          completion.fail();
          return completion;
        }
      } else {
        newEng.canRestoreDymPda = false;
        newEng.boardSizeForEngine(newEng.width, newEng.height);
        newEng.sendCommand("komi " + newEng.komi);
        newEng.pkMoveStartTime = System.currentTimeMillis();
      }
      newEng.isResigning = false;
      newEng.clearWithoutPonder();
      Runnable syncBoard =
          () -> {
            frozenLifecycleSynchronization.runUntilStable(true);
            frozenLifecycleSynchronization.confirmFinalBoardSynchronization(
                () -> {
                  try {
                    if (newEng.isKataGoPda) {
                      newEng.sendCommand("dympdacap " + newEng.pdaCap);
                    }
                    completion.markSuccessful();
                  } finally {
                    frozenLifecycleSynchronization.close();
                  }
                },
                detail -> {
                  try {
                    failPkEngineSynchronization(newEng);
                  } finally {
                    frozenLifecycleSynchronization.close();
                  }
                });
          };
      Lizzie.frame.clearKataEstimate();
      synchronizePkEngineWhenReady(
          newEng, syncBoard, frozenLifecycleSynchronization, completion);
    } catch (RuntimeException failure) {
      newEng.isLoaded = false;
      lifecycleSynchronization.close();
      completion.fail();
      return completion;
    }
    return completion;
  }

  boolean finishPkEngineSynchronizations(
      PkEngineSynchronization blackSynchronization,
      PkEngineSynchronization whiteSynchronization) {
    boolean blackReady = blackSynchronization.await();
    boolean whiteReady = whiteSynchronization.await();
    if (blackReady && whiteReady) {
      return true;
    }
    clearEngineGame();
    return false;
  }

  public void clearEngineGame() {
    if (isEngineGame || isPreEngineGame) {
      Lizzie.frame.addInput(true);
      isPreEngineGame = false;
      if (!isEngineGame) return;
      isEngineGame = false;
      LizzieFrame.menu.toggleDoubleMenuGameStatus();
      LizzieFrame.toolbar.isPkStop = false;
    }
  }

  public void restartEngineForPk(int index) {
    if (index < 0 || index >= this.engineList.size()) return;
    Leelaz targetEngine = engineList.get(index);
    Board restoreBoard = Lizzie.board;
    Leelaz proposedRestoreMirror = targetEngine.resolveLoadSgfMirrorEngine();
    InitialEngineStartupSynchronization lifecycleSynchronization = null;
    try {
      lifecycleSynchronization =
          InitialEngineStartupSynchronization.capturePrepared(
              null, targetEngine, proposedRestoreMirror, restoreBoard, false, false);
      lifecycleSynchronization.acquireReservation();
      lifecycleSynchronization.beginLifecycleCompletionClaim();
    } catch (InitialStartupReservationException
        | Leelaz.ExactSnapshotRestoreAdmissionException conflict) {
      if (lifecycleSynchronization != null) {
        lifecycleSynchronization.close();
      }
      showForegroundEngineLeaseInUse();
      return;
    }
    try {
      restartEngineForPkInternal(index, targetEngine, lifecycleSynchronization);
    } catch (RuntimeException failure) {
      targetEngine.isLoaded = false;
      lifecycleSynchronization.close();
      throw failure;
    }
  }

  private void restartEngineForPkInternal(
      int index,
      Leelaz newEng,
      InitialEngineStartupSynchronization lifecycleSynchronization) {
    newEng.isLoaded = false;
    newEng.played = false;
    newEng.width = Board.boardWidth;
    newEng.height = Board.boardHeight;
    newEng.komi = lifecycleSynchronization.pendingRoute.rootKomi.floatValue();
    try {
      newEng.startEngine(index);
    } catch (IOException failure) {
      newEng.isLoaded = false;
      failure.printStackTrace();
      lifecycleSynchronization.close();
      return;
    }
    EngineManager.currentEngineNo = index;
    Runnable syncBoard =
        () -> {
          lifecycleSynchronization.runUntilStable(false);
          lifecycleSynchronization.confirmFinalBoardSynchronization(
              () -> {
                try {
                  newEng.nameCmd();
                  newEng.setResponseUpToDate();
                  if (engineGameInfo.isGenmove) {
                    if (Lizzie.config.pkAdvanceTimeSettings) {
                      newEng.sendCommand(Lizzie.config.advanceBlackTimeTxt);
                    } else if (index == engineGameInfo.whiteEngineIndex
                        && engineGameInfo.timeWhite > 0) {
                      DesktopTimeControl.sendEngineGameFixedTime(
                          newEng, engineGameInfo.timeWhite);
                    } else if (index == engineGameInfo.blackEngineIndex
                        && engineGameInfo.timeBlack > 0) {
                      DesktopTimeControl.sendEngineGameFixedTime(
                          newEng, engineGameInfo.timeBlack);
                    }
                    if (Lizzie.board.getHistory().isBlacksTurn()) {
                      Lizzie.setPrimaryEngine(engineList.get(engineGameInfo.blackEngineIndex));
                      Lizzie.leelaz.genmoveForPk("b");
                    } else {
                      Lizzie.setPrimaryEngine(engineList.get(engineGameInfo.whiteEngineIndex));
                      Lizzie.leelaz.genmoveForPk("w");
                    }
                  } else if (Lizzie.board.getHistory().isBlacksTurn()) {
                    engineList.get(engineGameInfo.blackEngineIndex).ponder();
                  } else {
                    engineList.get(engineGameInfo.whiteEngineIndex).ponder();
                  }
                } finally {
                  lifecycleSynchronization.close();
                }
              },
              detail -> {
                try {
                  failPkEngineSynchronization(newEng);
                } finally {
                  lifecycleSynchronization.close();
                }
              });
        };
    synchronizePkEngineWhenReady(newEng, syncBoard, lifecycleSynchronization);
  }


  public void switchEngine(int index, boolean isMain) {
    switchEngineIfAvailable(index, isMain, true);
  }

  /**
   * Attempts an engine switch without showing the generic exclusive-task popup.
   *
   * <p>Configuration workflows use this after coordinating any interruptible quick analysis so they
   * can report failure in their own status area instead of claiming that a switch succeeded.
   */
  public boolean switchEngineIfAvailable(int index, boolean isMain) {
    return switchEngineIfAvailable(index, isMain, false, null);
  }

  /**
   * Switches an engine as part of a UI flow that already owns the foreground engine mode.
   *
   * <p>The retained reservation is accepted only for the current foreground engine. It allows the
   * same new-game flow to deepen its lifecycle reservation without weakening exclusion against any
   * unrelated task.
   */
  public boolean switchEngineIfAvailable(
      int index,
      boolean isMain,
      Leelaz.EngineModeReservation retainedForegroundReservation) {
    return switchEngineIfAvailable(index, isMain, true, retainedForegroundReservation);
  }

  private boolean switchEngineIfAvailable(int index, boolean isMain, boolean showConflict) {
    return switchEngineIfAvailable(index, isMain, showConflict, null);
  }

  private boolean switchEngineIfAvailable(
      int index,
      boolean isMain,
      boolean showConflict,
      Leelaz.EngineModeReservation retainedForegroundReservation) {
    if (engineList == null || index < 0 || index >= engineList.size()) {
      return false;
    }
    if (rejectSameEngineSelection(index, isMain)) {
      return false;
    }
    Leelaz currentForegroundEngine = Lizzie.leelaz;
    Object retainedLifecycleOwner = null;
    if (retainedForegroundReservation != null) {
      retainedLifecycleOwner =
          retainedForegroundReservation.lifecycleOwnerFor(currentForegroundEngine);
      if (!isMain || currentForegroundEngine == null || retainedLifecycleOwner == null) {
        if (showConflict) {
          showForegroundEngineLeaseInUse();
        }
        return false;
      }
    }
    boolean foregroundActivation = isMain && isEmpty && currentEngineNo < 0;
    PreparedEngineSwitch preparedSwitch;
    try {
      preparedSwitch =
          prepareEngineSwitch(
              index, isMain, false, foregroundActivation, retainedLifecycleOwner);
    } catch (Leelaz.ExactSnapshotRestoreAdmissionException conflict) {
      if (showConflict) {
        showForegroundEngineLeaseInUse();
      }
      return false;
    } catch (InitialStartupReservationException conflict) {
      if (showConflict) {
        showForegroundEngineLeaseInUse();
      }
      return false;
    } catch (RuntimeException startupFailure) {
      startupFailure.printStackTrace();
      Leelaz failedTarget = engineList.get(index);
      failedTarget.isLoaded = false;
      showEngineSynchronizationFailure(failedTarget);
      return false;
    }
    Leelaz targetEngine =
        preparedSwitch == null ? engineList.get(index) : preparedSwitch.targetEngine;
    if (preparedSwitch == null) {
      EngineLifecycleReservations reservations =
          reservePreparedEngineSwitch(currentForegroundEngine, targetEngine, null);
      if (reservations == null) {
        if (showConflict) {
          showForegroundEngineLeaseInUse();
    }
        return false;
      }
      Runnable afterSync =
          targetEngine == currentForegroundEngine && !foregroundActivation
              ? reservations::close
              : releaseEngineLifecycleAfterBoardSync(
                  currentForegroundEngine, targetEngine, isMain, false, false, reservations, null);
      switchEngineInternal(index, isMain, null, afterSync);
      return true;
    }
    currentForegroundEngine = preparedSwitch.lifecycleRestore.previousEngine;
    InitialEngineStartupSynchronization lifecycleSynchronization =
        preparedSwitch.initialStartupSynchronization;
    if (!foregroundActivation) {
      EngineLifecycleReservations reservations =
        reservePreparedEngineSwitch(currentForegroundEngine, targetEngine, preparedSwitch);
    if (reservations == null) {
        lifecycleSynchronization.close();
      if (showConflict) {
        showForegroundEngineLeaseInUse();
      }
      return false;
    }
      lifecycleSynchronization.installReservations(reservations);
    }
    Runnable afterSync =
        foregroundActivation
            ? lifecycleSynchronization::close
            : releaseEngineLifecycleAfterBoardSync(
                currentForegroundEngine,
                targetEngine,
                isMain,
                false,
                false,
                lifecycleSynchronization::close,
                lifecycleSynchronization.isTrackingFirstWinner(),
                preparedSwitch.lifecycleRestore);
    switchEngineInternal(index, isMain, preparedSwitch, afterSync);
    return true;
  }

  private boolean rejectSameEngineSelection(int index, boolean isMain) {
    if (Lizzie.config == null
        || !Lizzie.config.isDoubleEngineMode()
        || index != (isMain ? currentEngineNo2 : currentEngineNo)) {
      return false;
    }
    showSameEngineSelection();
    return true;
  }

  protected void showSameEngineSelection() {
    Utils.showMsg(resourceBundle.getString("EngineManager.sameEngineHint"));
  }

  private Runnable releaseEngineLifecycleAfterBoardSync(
      Leelaz current,
      Leelaz target,
      boolean isMain,
      boolean explicitRestart,
      boolean restartPonderIntent,
      EngineLifecycleReservations reservations,
      PreparedLifecycleRestore lifecycleRestore) {
    return releaseEngineLifecycleAfterBoardSync(
        current,
        target,
        isMain,
        explicitRestart,
        restartPonderIntent,
        reservations::close,
        reservations.isTrackingFirstWinner(),
        lifecycleRestore);
  }

  private Runnable releaseEngineLifecycleAfterBoardSync(
      Leelaz current,
      Leelaz target,
      boolean isMain,
      boolean explicitRestart,
      boolean restartPonderIntent,
      Runnable releaseLifecycle,
      boolean trackingFirstWinner,
      PreparedLifecycleRestore lifecycleRestore) {
    boolean targetWasUnrestored = target != null && target.hasUnrestoredReadBoardGmaState();
    boolean readBoardRecovery =
        (explicitRestart
                && (targetWasUnrestored
                    || (current != null && current.hasUnrestoredReadBoardGmaState())))
            || (!explicitRestart && current != null && current.hasUnrestoredReadBoardGmaState());
    if (!explicitRestart && lifecycleRestore != null) {
      return () ->
          lifecycleRestore.confirmBoardSynchronization(
              () -> {
                try {
                  lifecycleRestore.initializeAfterRestore(false);
                  lifecycleRestore.resumePonderAfterSuccessfulSynchronization();
                } finally {
                  releaseLifecycle.run();
                }
              },
              (failedEngine, detail) -> {
                failLifecycleBoardSynchronization(
                    target, failedEngine, detail, targetWasUnrestored, releaseLifecycle);
              });
    }
    if (explicitRestart && !isMain && target != null) {
      return () -> {
        if (Lizzie.leelaz2 != target || !target.isStarted() || !target.isLoaded()) {
          target.isLoaded = false;
          target.markLifecycleBoardSynchronizationFailed(
              "restart engine was unavailable before board synchronization", targetWasUnrestored);
          showEngineSynchronizationFailure(target);
          releaseLifecycle.run();
          return;
        }
        confirmLifecycleBoardSynchronization(
            lifecycleRestore,
            target,
            () -> {
              try {
                target.completeSecondaryExplicitRestartBoardSynchronization();
                if (restartPonderIntent
                    && current != null
                    && current == Lizzie.leelaz
                    && current.isStarted()
                    && current.isLoaded()
                    && !current.isCheckingName) {
                  current.ponder();
                }
                target.setResponseUpToDate();
              } finally {
                releaseLifecycle.run();
              }
            },
            (failedEngine, detail) ->
                failLifecycleBoardSynchronization(
                    target, failedEngine, detail, targetWasUnrestored, releaseLifecycle));
      };
    }
    if (!isMain
        || current == null
        || target == null
        || (!explicitRestart && !trackingFirstWinner && !readBoardRecovery)) {
      return () -> {
        try {
          if (lifecycleRestore != null) {
            lifecycleRestore.resumePonderAfterSuccessfulSynchronization();
          }
        } finally {
          releaseLifecycle.run();
        }
      };
    }
    return () -> {
      if (Lizzie.leelaz != target || !target.isStarted() || !target.isLoaded()) {
        if (explicitRestart) {
          settleBoardSynchronizationFailure(
              target,
              "restart engine was unavailable before board synchronization",
              targetWasUnrestored,
              reservations);
          return;
        }
        releaseLifecycle.run();
        return;
      }
      confirmLifecycleBoardSynchronization(
          lifecycleRestore,
          target,
          () -> {
            try {
              if (explicitRestart && target.hasUnrestoredReadBoardGmaState()) {
                target.completeReadBoardGmaRecoveryAfterBoardSync();
              }
              if (explicitRestart) {
                if (lifecycleRestore != null) {
                  lifecycleRestore.initializeAfterExplicitRestart(restartPonderIntent);
                } else {
                  target.initializeAfterExplicitRestartBoardSynchronization(restartPonderIntent);
                }
              } else if (lifecycleRestore != null) {
                lifecycleRestore.resumePonderAfterSuccessfulSynchronization();
              }
            } finally {
              releaseLifecycle.run();
            }
          },
          (failedEngine, detail) ->
              failLifecycleBoardSynchronization(
                  target, failedEngine, detail, targetWasUnrestored, releaseLifecycle));
    };
  }

  private void confirmLifecycleBoardSynchronization(
      PreparedLifecycleRestore lifecycleRestore,
      Leelaz target,
      Runnable onSuccess,
      java.util.function.BiConsumer<Leelaz, String> onFailure) {
    if (lifecycleRestore != null) {
      lifecycleRestore.confirmBoardSynchronization(onSuccess, onFailure);
    } else {
      target.confirmBoardSynchronization(onSuccess, detail -> onFailure.accept(target, detail));
    }
  }

  private void failLifecycleBoardSynchronization(
      Leelaz target,
      Leelaz failedEngine,
      String detail,
      boolean targetWasUnrestored,
      Runnable releaseLifecycle) {
    target.isLoaded = false;
    target.markLifecycleBoardSynchronizationFailed(detail, targetWasUnrestored);
    if (failedEngine != null && failedEngine != target) {
      failedEngine.isLoaded = false;
      failedEngine.markLifecycleBoardSynchronizationFailed(
          detail, failedEngine.hasUnrestoredReadBoardGmaState());
    }
    showEngineSynchronizationFailure(target);
    releaseLifecycle.run();
  }

  protected void switchEngineInternal(int index, boolean isMain, Runnable afterSync) {
    if (rejectSameEngineSelection(index, isMain)) {
      if (afterSync != null) afterSync.run();
      return;
    }
    switchEngineInternal(index, isMain, prepareEngineSwitch(index, isMain), afterSync);
  }

  protected void showContributingEngineSwitchUnavailable() {
    Utils.showMsg(resourceBundle.getString("Contribute.tips.contributingAndStartAnotherLizzieYzy"));
  }

  protected void switchEngineInternal(
      int index, boolean isMain, PreparedEngineSwitch preparedSwitch, Runnable afterSync) {
    boolean syncScheduled = false;
    try {
    if (Lizzie.frame.isContributing) {
      showContributingEngineSwitchUnavailable();
      return;
    }

    engineNo = index;
      if (rejectSameEngineSelection(index, isMain)) {
      return;
    }
      if (isEmpty && !preparedSwitch.foregroundActivation) isEmpty = false;
      Leelaz newEng = preparedSwitch.targetEngine;
    if (newEng == null) return;
      InitialEngineStartupSynchronization lifecycleSynchronization =
          preparedSwitch.initialStartupSynchronization;
    // newEng.isReadyForGenmoveGame = false;
      boolean changeBoard = preparedSwitch.targetBoardSizeChanges;
      boolean changeOriBoard = preparedSwitch.targetOriginalBoardSizeChanges;
      boolean isEmptyBoard = preparedSwitch.boardEmpty;

    // Lizzie.frame.menu.showPda(false);
      if (isEmptyBoard && changeOriBoard && isMain)
        Lizzie.board.reopenOnlyBoard(newEng.oriWidth, newEng.oriHeight);
      if (preparedSwitch.previousEngine != null) {
        Leelaz curEng = preparedSwitch.previousEngine;
        // curEng.switching = true;
        try {
          if (!Lizzie.config.fastChange) {
            curEng.normalQuit();
          } else {
            if (curEng.isLeela0110) curEng.leela0110StopPonder();
            curEng.nameCmdfornoponder();
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
        curEng.notPondering();
      }
      if (isMain) Lizzie.setPrimaryEngine(newEng);
      else Lizzie.leelaz2 = newEng;
      if (isMain && Lizzie.frame != null) {
        Lizzie.frame.invalidateTrackingAnalysis();
      }
      newEng.komi = preparedSwitch.targetKomi;
      if (!newEng.isStarted()) {
        newEng.isLoaded = false;
        if (isEmptyBoard && isMain) {
          newEng.width = newEng.oriWidth;
          newEng.height = newEng.oriHeight;
        } else {
          newEng.width = preparedSwitch.boardWidth;
          newEng.height = preparedSwitch.boardHeight;
        }
        newEng.startEngine(index);
      } else {
        // newEng.getEngineName(index);
        newEng.canRestoreDymPda = false;
        if (!(isEmptyBoard && changeBoard) || !isMain) {
          newEng.width = preparedSwitch.boardWidth;
          newEng.height = preparedSwitch.boardHeight;
          newEng.boardSizeForEngine(newEng.width, newEng.height);
        }
        if (isEmptyBoard && changeOriBoard && isMain) {
          newEng.width = newEng.oriWidth;
          newEng.height = newEng.oriHeight;
          newEng.boardSizeForEngine(newEng.width, newEng.height);
        }
        newEng.sendCommand("komi " + newEng.komi);
        newEng.isCheckingName = true;
        newEng.sendCommand("name");

        Lizzie.board.getHistory().getGameInfo().setKomi(newEng.komi);
        Lizzie.config.leelaversion = newEng.version;
        Runnable runnable =
            new Runnable() {
              public void run() {
                LizzieFrame.toolbar.reSetButtonLocation();
                if (Lizzie.frame.resetMovelistFrameandAnalysisFrame())
                  Lizzie.frame.setVisible(true);
              }
            };
        Thread thread = new Thread(runnable);
        thread.start();
      }
      newEng.anaGameResignCount = 0;
      if (isMain) {
        Runnable syncBoard =
            new Runnable() {
              public void run() {
                newEng.notPondering();
                lifecycleSynchronization.runUntilStable();
                if (newEng == Lizzie.leelaz) {
                  boolean foregroundStartup = preparedSwitch.foregroundActivation;
                  if (foregroundStartup) {
                    currentEngineNo = index;
                    isEmpty = false;
                    try {
                      lifecycleSynchronization.initializeAfterRestore();
                    } catch (RuntimeException initializationFailure) {
                      if (Lizzie.leelaz == newEng && currentEngineNo == index) {
                        currentEngineNo = -1;
                        isEmpty = true;
                      }
                      throw initializationFailure;
                    }
                  }
                  Lizzie.board.clearBestMovesAfterForFirstEngine(
                      Lizzie.board.getHistory().getStart());
                  if (!foregroundStartup) {
                  currentEngineNo = index;
                  }
                  int selectedEngineNo = foregroundStartup ? index : currentEngineNo;
                  Menu.engineMenu.setText(
                      "[" + (selectedEngineNo + 1) + "]: " + newEng.oriEnginename);

                  changeEngIco(1);
                  LizzieFrame.toolbar.reSetButtonLocation();
                  LizzieFrame.boardRenderer.removeKataEstimateImage();
                  if (Lizzie.frame.floatBoard != null)
                    Lizzie.frame.floatBoard.boardRenderer.removeKataEstimateImage();
                  if (Lizzie.config.showSubBoard)
                    LizzieFrame.subBoardRenderer.removeKataEstimateImage();
                  if (selectedEngineNo > 20) LizzieFrame.menu.changeEngineIcon(20, 3);
                  else LizzieFrame.menu.changeEngineIcon(selectedEngineNo, 3);
                  if (!preparedSwitch.explicitRestart && !foregroundStartup) {
                    newEng.setResponseUpToDate();
                  }
                }
              }
            };
        synchronizeEngineWhenReady(newEng, syncBoard, afterSync);
        syncScheduled = true;
      } else if (Lizzie.leelaz2 != null) {
        Runnable syncBoard =
            new Runnable() {
              public void run() {
                EngineManager.currentEngineNo2 = index;
                if (currentEngineNo2 > 20) LizzieFrame.menu.changeEngineIcon2(20, 3);
                else LizzieFrame.menu.changeEngineIcon2(currentEngineNo2, 3);

                Lizzie.board.clearBestMovesAfterForSecondEngine(
                    Lizzie.board.getHistory().getStart());
                lifecycleSynchronization.runUntilStable();
                Menu.engineMenu2.setText(
                    "[" + (currentEngineNo2 + 1) + "]: " + newEng.currentEnginename);
                changeEngIco(2);
                LizzieFrame.boardRenderer2.removeKataEstimateImage();
                if (!preparedSwitch.explicitRestart) {
                  newEng.setResponseUpToDate();
                }
              }
            };
        synchronizeEngineWhenReady(newEng, syncBoard, afterSync);
        syncScheduled = true;
      }
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      if (!syncScheduled && afterSync != null) {
        if (preparedSwitch != null && preparedSwitch.initialStartupSynchronization != null) {
          preparedSwitch.initialStartupSynchronization.close();
        } else {
          afterSync.run();
        }
      }
    }
  }

  private PreparedEngineSwitch prepareEngineSwitch(int index, boolean isMain) {
    return prepareEngineSwitch(index, isMain, false, false);
  }

  private PreparedEngineSwitch prepareEngineSwitch(
      int index, boolean isMain, boolean explicitRestart) {
    return prepareEngineSwitch(index, isMain, explicitRestart, false);
  }

  private PreparedEngineSwitch prepareEngineSwitch(
      int index, boolean isMain, boolean explicitRestart, boolean foregroundActivation) {
    return prepareEngineSwitch(index, isMain, explicitRestart, foregroundActivation, null);
  }

  private PreparedEngineSwitch prepareEngineSwitch(
      int index,
      boolean isMain,
      boolean explicitRestart,
      boolean foregroundActivation,
      Object retainedLifecycleOwner) {
    Board restoreBoard = Lizzie.board;
    if (restoreBoard == null) {
      return null;
    }
    Leelaz targetEngine = engineList.get(index);
    Leelaz previousEngine = isMain ? Lizzie.leelaz : Lizzie.leelaz2;
    boolean resumePonder =
        foregroundActivation
            || (previousEngine != null && previousEngine.isPonderingOrWasPonderingBeforeTracking());
    Leelaz proposedRestoreMirror =
        Lizzie.config.isDoubleEngineMode() ? (isMain ? Lizzie.leelaz2 : Lizzie.leelaz) : null;
    InitialEngineStartupSynchronization lifecycleSynchronization =
        foregroundActivation
            ? InitialEngineStartupSynchronization.capture(
            previousEngine,
            targetEngine,
            proposedRestoreMirror,
                restoreBoard,
                false,
                resumePonder)
            : InitialEngineStartupSynchronization.capturePrepared(
                previousEngine,
                targetEngine,
                proposedRestoreMirror,
                restoreBoard,
                false,
            resumePonder);
    PreparedLifecycleRestore lifecycleRestore = lifecycleSynchronization.pendingRoute;
    BoardFrame frame = lifecycleSynchronization.capturedFrame;
    int boardWidth = frame.boardWidth;
    int boardHeight = frame.boardHeight;
    boolean targetBoardSizeChanges =
        targetEngine.width != boardWidth || targetEngine.height != boardHeight;
    boolean targetOriginalBoardSizeChanges =
        targetEngine.oriWidth != boardWidth || targetEngine.oriHeight != boardHeight;
    float targetKomi =
        !isMain || frame.changedKomi || lifecycleRestore.exactRestore.isPresent()
            ? (float) frame.komi
            : targetEngine.orikomi;
    return new PreparedEngineSwitch(
        targetEngine,
        previousEngine,
        lifecycleRestore,
        lifecycleSynchronization,
        boardWidth,
        boardHeight,
        frame.boardEmpty,
        targetBoardSizeChanges,
        targetOriginalBoardSizeChanges,
        targetKomi,
        explicitRestart,
        foregroundActivation);
  }

  private void synchronizeUpdateEnginesWhenReady(
      Leelaz target, Leelaz mirror, Runnable synchronization, Runnable afterSync) {
    Thread synchronizationThread =
        new Thread(
            () -> {
              try {
                if (!waitForEngineSynchronizationReadiness(target)
                    || (mirror != null && !waitForEngineSynchronizationReadiness(mirror))) {
                  target.isLoaded = false;
                  if (mirror != null) {
                    mirror.isLoaded = false;
                  }
                  showEngineSynchronizationFailure(target);
                  return;
                }
                synchronization.run();
              } catch (RuntimeException failure) {
                target.isLoaded = false;
                target.markLifecycleBoardSynchronizationFailed(
                    failure.getMessage() == null
                        ? "update engine board synchronization failed"
                        : failure.getMessage(),
                    false);
                if (mirror != null) {
                  mirror.isLoaded = false;
                  mirror.markLifecycleBoardSynchronizationFailed(
                      failure.getMessage() == null
                          ? "update engine board synchronization failed"
                          : failure.getMessage(),
                      false);
                }
                failure.printStackTrace();
                showEngineSynchronizationFailure(target);
              } finally {
                if (afterSync != null) {
                  afterSync.run();
                }
              }
            },
            "lizzie-update-engine-synchronization");
    synchronizationThread.start();
  }

  protected void synchronizeEngineWhenReady(
      Leelaz engine, Runnable synchronization, Runnable afterSync) {
    Runnable restartScopedSynchronization =
        engine.withCurrentRestartBootstrapReceipt(synchronization);
    Runnable restartScopedAfterSync =
        afterSync == null ? null : engine.withCurrentRestartBootstrapReceipt(afterSync);
    Runnable restartBootstrapFailure =
        engine.currentRestartBootstrapFailureAction(
            "restart engine did not complete startup and board synchronization");
    Runnable restartBoardSynchronizationFailure =
        engine.currentRestartBoardSynchronizationFailureAction(
            "restart engine board synchronization failed");
    Thread synchronizationThread =
        new Thread(
            () -> {
              try {
                if (!waitForEngineSynchronizationReadiness(engine)) {
                  engine.isLoaded = false;
                  restartBootstrapFailure.run();
                  showEngineSynchronizationFailure(engine);
                  return;
                }
                restartScopedSynchronization.run();
              } catch (RuntimeException ex) {
                engine.isLoaded = false;
                restartBoardSynchronizationFailure.run();
                ex.printStackTrace();
                showEngineSynchronizationFailure(engine);
              } finally {
                if (restartScopedAfterSync != null) {
                  restartScopedAfterSync.run();
                }
              }
            },
            "lizzie-engine-switch-synchronization");
    synchronizationThread.start();
  }

  private void synchronizePkEngineWhenReady(
      Leelaz engine,
      Runnable synchronization,
      InitialEngineStartupSynchronization lifecycleSynchronization) {
    synchronizePkEngineWhenReady(
        engine, synchronization, lifecycleSynchronization, new PkEngineSynchronization());
  }

  private void synchronizePkEngineWhenReady(
      Leelaz engine,
      Runnable synchronization,
      InitialEngineStartupSynchronization lifecycleSynchronization,
      PkEngineSynchronization completion) {
    Thread synchronizationThread =
        new Thread(
            () -> {
              try {
                if (!waitForEngineSynchronizationReadiness(engine)) {
                  failPkEngineSynchronization(engine);
                  lifecycleSynchronization.close();
                  completion.fail();
                  return;
                }
                synchronization.run();
              } catch (RuntimeException failure) {
                failPkEngineSynchronization(engine);
                lifecycleSynchronization.close();
                completion.fail();
                failure.printStackTrace();
              }
            },
            "lizzie-pk-engine-synchronization");
    synchronizationThread.start();
  }

  private void failPkEngineSynchronization(Leelaz engine) {
    engine.isLoaded = false;
  }

  private boolean waitForEngineSynchronizationReadiness(Leelaz engine) {
    if (engine == null) {
      return false;
    }
    long now = System.nanoTime();
    long deadline =
        now
            + TimeUnit.MILLISECONDS.toNanos(
                Math.max(1L, engineSynchronizationTimeoutMillis(engine)));
    boolean tuningTimeoutApplied = false;
    while (true) {
      if (!engine.isStarted() || engine.isDownWithError || engine.isNormalEnd) {
        return false;
      }
      if (engine.isLoaded() && !engine.isCheckingName) {
        return true;
      }
      now = System.nanoTime();
      if (!tuningTimeoutApplied && engine.isTuning) {
        deadline =
            now
                + TimeUnit.MILLISECONDS.toNanos(
                    Math.max(1L, engine.engineTuningSynchronizationTimeoutMillis()));
        tuningTimeoutApplied = true;
      }
      if (now >= deadline) {
        return false;
      }
      long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - now));
      try {
        Thread.sleep(Math.min(100L, remainingMillis));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  protected long engineSynchronizationTimeoutMillis(Leelaz engine) {
    return engine.engineStartupSynchronizationTimeoutMillis();
  }

  protected void showEngineSynchronizationFailure(Leelaz engine) {
    SwingUtilities.invokeLater(
        () -> Utils.showMsg(resourceBundle.getString("Leelaz.engineFailed")));
  }

  private EngineLifecycleReservations reservePreparedEngineSwitch(
      Leelaz current, Leelaz target, PreparedEngineSwitch preparedSwitch) {
    if (preparedSwitch == null) {
      return reserveEngineLifecycle(current, target, null);
    }
    return reserveEngineLifecycle(preparedSwitch.lifecycleRestore);
  }

  private EngineLifecycleReservations reserveEngineLifecycle(PreparedLifecycleRestore restore) {
    return reserveEngineLifecycle(restore.previousEngine, restore.targetEngine, restore.owner());
  }

  private EngineLifecycleReservations reserveEngineLifecycle(
      Leelaz current, Leelaz target, Object owner) {
    Leelaz.ExclusiveGtpLifecycleReservation targetReservation = null;
    if (target != null && target != current) {
      targetReservation =
          owner == null
              ? target.beginExclusiveGtpLifecycleReservation()
              : target.beginExclusiveGtpLifecycleReservation(owner);
      if (targetReservation == null) {
        return null;
      }
    }
    Leelaz.ExclusiveGtpLifecycleReservation currentReservation =
        current == null
            ? null
            : owner == null
                ? current.beginExclusiveGtpLifecycleReservation()
                : current.beginExclusiveGtpLifecycleReservation(owner);
    if (current != null && currentReservation == null) {
      if (targetReservation != null) targetReservation.close();
      return null;
    }
    return new EngineLifecycleReservations(currentReservation, targetReservation);
  }

  private boolean attachRestartInteractionGate(EngineLifecycleReservations reservations) {
    try {
      if (reservations != null
          && reservations.isTrackingFirstWinner()
          && Lizzie.frame != null
          && Lizzie.frame.isDisplayable()) {
        reservations.interactionGate = Lizzie.frame.beginRestartInteractionGate();
      }
      return true;
    } catch (RuntimeException failure) {
      try {
        reservations.close();
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      showEngineSynchronizationFailure(Lizzie.leelaz);
      return false;
    }
  }

  private static final class PreparedLifecycleRestore {
    private final Leelaz previousEngine;
    private final Leelaz targetEngine;
    private final Leelaz mirrorEngine;
    private final Object owner;
    private final Leelaz.ExactSnapshotRestoreAdmission admission;
    private final Optional<ExactSnapshotEngineRestore.PreparedRestore> exactRestore;
    private final ArrayList<Movelist> rootMoves;
    private final Double rootKomi;
    private final boolean resumePonder;
    private final AtomicBoolean rootReplayExecuted = new AtomicBoolean(false);

    private PreparedLifecycleRestore(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        Object owner,
        Leelaz.ExactSnapshotRestoreAdmission admission,
        Optional<ExactSnapshotEngineRestore.PreparedRestore> exactRestore,
        ArrayList<Movelist> rootMoves,
        Double rootKomi,
        boolean resumePonder) {
      this.previousEngine = previousEngine;
      this.targetEngine = targetEngine;
      this.mirrorEngine = mirrorEngine;
      this.owner = owner;
      this.admission = admission;
      this.exactRestore = exactRestore;
      this.rootMoves = Movelist.copyList(rootMoves);
      this.rootKomi = rootKomi;
      this.resumePonder = resumePonder;
    }

    private static PreparedLifecycleRestore capture(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        BoardHistoryNode historyTarget,
        Double komi,
        ArrayList<Movelist> rootMoves,
        boolean resumePonder) {
      return capture(
          previousEngine,
          targetEngine,
          mirrorEngine,
          historyTarget,
          komi,
          rootMoves,
          resumePonder,
          null);
    }
    private static PreparedLifecycleRestore capture(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        Object owner,
        BoardHistoryNode historyTarget,
        Double komi,
        ArrayList<Movelist> rootMoves,
        boolean resumePonder) {
      if (owner == null) {
        throw new IllegalArgumentException("owner");
      }
      return capture(
          previousEngine,
          targetEngine,
          mirrorEngine,
          historyTarget,
          komi,
          rootMoves,
          resumePonder,
          owner);
    }


    private static PreparedLifecycleRestore capture(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        BoardHistoryNode historyTarget,
        Double komi,
        ArrayList<Movelist> rootMoves,
        boolean resumePonder,
        Object retainedLifecycleOwner) {
      if (targetEngine == null) {
        throw new IllegalArgumentException("targetEngine");
      }
      Object owner = retainedLifecycleOwner == null ? new Object() : retainedLifecycleOwner;
      Leelaz.ExactSnapshotRestoreAdmission admission =
          targetEngine.captureExactSnapshotRestoreAdmission(
              Leelaz.ExactSnapshotRestoreOwner.LIFECYCLE, owner, mirrorEngine);
      Optional<ExactSnapshotEngineRestore.PreparedRestore> exactRestore = Optional.empty();
      if (historyTarget != null) {
        exactRestore = ExactSnapshotEngineRestore.prepare(admission, historyTarget);
      }
      return new PreparedLifecycleRestore(
          previousEngine,
          targetEngine,
          mirrorEngine,
          owner,
          admission,
          exactRestore,
          rootMoves,
          komi,
          resumePonder);
    }

    private Object owner() {
      return owner;
    }

    private void executeRootReplay(Board board, boolean loadEngine, boolean isEngineGame) {
      if (!rootReplayExecuted.compareAndSet(false, true)) {
        throw new IllegalStateException("Lifecycle root replay has already been executed");
      }
      targetEngine.requireExactSnapshotRestoreAdmission(admission);
      if (mirrorEngine != null) {
        mirrorEngine.requireExactSnapshotRestoreAdmission(admission);
      }
      Runnable replay =
          () ->
              board.resendMoveToEngineFromRoot(
                  targetEngine, mirrorEngine, loadEngine, isEngineGame, rootMoves, rootKomi);
      targetEngine.withExactSnapshotRestoreAdmission(
          admission,
          () -> {
            if (mirrorEngine == null) {
              replay.run();
            } else {
              mirrorEngine.withExactSnapshotRestoreAdmission(admission, replay);
            }
          });
    }

    private void confirmBoardSynchronization(
        Runnable onSuccess, java.util.function.BiConsumer<Leelaz, String> onFailure) {
      confirmBoardSynchronization(
          targetEngine,
          () -> {
        if (mirrorEngine == null) {
          onSuccess.run();
        } else {
          confirmBoardSynchronization(mirrorEngine, onSuccess, onFailure);
        }
          },
          onFailure);
    }

    private static void confirmBoardSynchronization(
        Leelaz engine,
        Runnable onSuccess,
        java.util.function.BiConsumer<Leelaz, String> onFailure) {
      if (!engine.isStarted() || !engine.isLoaded()) {
        onFailure.accept(engine, "engine was unavailable before board synchronization");
        return;
      }
      engine.confirmBoardSynchronization(onSuccess, detail -> onFailure.accept(engine, detail));
    }

    private void initializeAfterRestore(boolean isEngineGame) {
      Lizzie.initializeAfterVersionCheck(isEngineGame, targetEngine, false);
    }

    private void initializeAfterExplicitRestart(boolean resumePonder) {
      targetEngine.initializeAfterExplicitRestartBoardSynchronization(resumePonder);
    }

    private void resumePonderAfterSuccessfulSynchronization() {
      if (resumePonder
          && targetEngine != null
          && targetEngine.isStarted()
          && targetEngine.isLoaded()
          && !targetEngine.isCheckingName) {
        targetEngine.ponder();
        targetEngine.setResponseUpToDate();
      }
    }
  }

  protected static final class PreparedEngineSwitch {
    private final Leelaz targetEngine;
    private final Leelaz previousEngine;
    private final Optional<ExactSnapshotEngineRestore.PreparedRestore> exactRestore;
    private final PreparedLifecycleRestore lifecycleRestore;
    private final int boardWidth;
    private final int boardHeight;
    private final boolean boardEmpty;
    private final boolean targetBoardSizeChanges;
    private final boolean targetOriginalBoardSizeChanges;
    private final float targetKomi;
    private final boolean explicitRestart;
    private final boolean foregroundActivation;
    private final InitialEngineStartupSynchronization initialStartupSynchronization;

    private PreparedEngineSwitch(
        Leelaz targetEngine,
        Leelaz previousEngine,
        PreparedLifecycleRestore lifecycleRestore,
        InitialEngineStartupSynchronization initialStartupSynchronization,
        int boardWidth,
        int boardHeight,
        boolean boardEmpty,
        boolean targetBoardSizeChanges,
        boolean targetOriginalBoardSizeChanges,
        float targetKomi,
        boolean explicitRestart,
        boolean foregroundActivation) {
      this.targetEngine = targetEngine;
      this.previousEngine = previousEngine;
      this.exactRestore = lifecycleRestore.exactRestore;
      this.lifecycleRestore = lifecycleRestore;
      this.initialStartupSynchronization = initialStartupSynchronization;
      this.boardWidth = boardWidth;
      this.boardHeight = boardHeight;
      this.boardEmpty = boardEmpty;
      this.targetBoardSizeChanges = targetBoardSizeChanges;
      this.targetOriginalBoardSizeChanges = targetOriginalBoardSizeChanges;
      this.targetKomi = targetKomi;
      this.explicitRestart = explicitRestart;
      this.foregroundActivation = foregroundActivation;
    }
  }

  private static final class EngineLifecycleReservations implements AutoCloseable {
    private Leelaz.ExclusiveGtpLifecycleReservation current;
    private Leelaz.ExclusiveGtpLifecycleReservation target;
    private LizzieFrame.RestartInteractionGate interactionGate;

    private EngineLifecycleReservations(
        Leelaz.ExclusiveGtpLifecycleReservation current,
        Leelaz.ExclusiveGtpLifecycleReservation target) {
      this.current = current;
      this.target = target;
    }

    private boolean isTrackingFirstWinner() {
      return current != null && current.isTrackingFirstWinner();
    }

    @Override
    public void close() {
      Leelaz.ExclusiveGtpLifecycleReservation targetToClose;
      Leelaz.ExclusiveGtpLifecycleReservation currentToClose;
      LizzieFrame.RestartInteractionGate gateToClose;
      synchronized (this) {
        targetToClose = target;
        currentToClose = current;
        gateToClose = interactionGate;
        target = null;
        current = null;
        interactionGate = null;
      }
      try {
        if (targetToClose != null) {
          targetToClose.close();
        }
      } finally {
        try {
          if (currentToClose != null) {
            currentToClose.close();
          }
        } finally {
          if (gateToClose != null) {
            gateToClose.close();
          }
        }
      }
    }
  }

  static final class PkEngineSynchronization {
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile boolean successful;

    private void markSuccessful() {
      successful = true;
    }

    private void complete() {
      completed.countDown();
    }

    private void fail() {
      complete();
    }

    boolean isComplete() {
      return completed.getCount() == 0;
    }

    boolean await() {
      boolean interrupted = false;
      while (true) {
        try {
          completed.await();
          break;
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      return successful;
    }
  }

  private static final class InitialStartupReservationException extends IllegalStateException {
    private InitialStartupReservationException(String message) {
      super(message);
    }
  }

  /**
   * Engine lifecycle board restore barrier (Issue #223).
   *
   * <p>Owns one lifecycle owner identity, owner-local previous/target reservations, captured
   * target/mirror gates and immutable restore routes. Navigation remains available while ordinary
   * live-board updates are suppressed on the captured targets. Each completed route releases its
   * reservations before comparing a new Board frame and, when stale, captures and reserves a new
   * catch-up route under the same owner.
   */
  static final class InitialEngineStartupSynchronization implements AutoCloseable {
    private final Leelaz previousEngine;
    private final Leelaz targetEngine;
    private final Leelaz mirrorEngine;
    private final Board board;
    private final boolean resumePonder;
    private final boolean ensureRootReplayKomiTransport;
    private final Object lifecycleOwner = new Object();
    private Leelaz.LifecycleCompletionClaim completionClaim;
    private EngineLifecycleReservations reservations;
    private LizzieFrame.RestartInteractionGate interactionGate;
    private PreparedLifecycleRestore pendingRoute;
    private BoardFrame capturedFrame;
    private boolean stable;
    private boolean engineGameInitialization;
    private boolean trackingFirstWinner;
    private final AtomicBoolean barriersEnded = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** Test seam: runs outside the board lock before each restore route execution. */
    Runnable beforeRestore;

    /** Test seam: runs outside the board lock immediately before each reservation release. */
    Runnable beforeReservationRelease;

    /** Test seam: runs outside the board lock immediately after each reservation release. */
    Runnable afterReservationRelease;

    private InitialEngineStartupSynchronization(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        Board board,
        boolean resumePonder,
        boolean ensureRootReplayKomiTransport) {
      this.previousEngine = previousEngine;
      this.targetEngine = targetEngine;
      this.mirrorEngine = mirrorEngine == targetEngine ? null : mirrorEngine;
      this.board = board;
      this.resumePonder = resumePonder;
      this.ensureRootReplayKomiTransport = ensureRootReplayKomiTransport;
    }

    static InitialEngineStartupSynchronization capture(
        Leelaz targetEngine, Board board, boolean forceRootReplay) {
      return capture(null, targetEngine, null, board, forceRootReplay, false);
    }

    /**
     * Captures target, mirror, immutable route, Board frame, lifecycle reservations and ordinary
     * update gates before the first external lifecycle side effect.
     */
    static InitialEngineStartupSynchronization capture(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        Board board,
        boolean forceRootReplay,
        boolean resumePonder) {
      if (targetEngine == null) {
        throw new IllegalArgumentException("targetEngine");
      }
      if (board == null) {
        throw new IllegalArgumentException("board");
      }
      InitialEngineStartupSynchronization coordination =
          new InitialEngineStartupSynchronization(
              previousEngine, targetEngine, mirrorEngine, board, resumePonder, false);
      coordination.beginSynchronizationBarriers();
      try {
        coordination.acquireReservation();
        synchronized (board) {
          coordination.pendingRoute = coordination.captureRoute(forceRootReplay);
          coordination.capturedFrame = BoardFrame.capture(board);
        }
        return coordination;
      } catch (RuntimeException failure) {
        try {
          coordination.close();
        } catch (RuntimeException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
        throw failure;
      }
    }

    static InitialEngineStartupSynchronization capturePrepared(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        Board board,
        boolean forceRootReplay,
        boolean resumePonder) {
      if (targetEngine == null) {
        throw new IllegalArgumentException("targetEngine");
      }
      if (board == null) {
        throw new IllegalArgumentException("board");
      }
      InitialEngineStartupSynchronization coordination =
          new InitialEngineStartupSynchronization(
              previousEngine, targetEngine, mirrorEngine, board, resumePonder, true);
      coordination.beginSynchronizationBarriers();
      try {
        synchronized (board) {
          coordination.pendingRoute = coordination.captureRoute(forceRootReplay);
          coordination.capturedFrame = BoardFrame.capture(board);
        }
        return coordination;
      } catch (RuntimeException failure) {
        coordination.close();
        throw failure;
      }
    }

    /** Executes the restore rounds and, on a stable restore point, marks the engine ready once. */
    void run() {
      runUntilStable();
      initializeAfterRestore();
    }

    /** Executes immutable restore and catch-up rounds without publishing engine readiness. */
    private void runUntilStable() {
      runUntilStable(false);
    }

    private void runUntilStable(boolean engineGameInitialization) {
      this.engineGameInitialization = engineGameInitialization;
      while (!stable) {
        if (beforeRestore != null) {
          beforeRestore.run();
        }
        executePendingRoute(engineGameInitialization);
        if (beforeReservationRelease != null) {
          beforeReservationRelease.run();
        }
        releaseReservation();
        if (afterReservationRelease != null) {
          afterReservationRelease.run();
        }
        synchronized (board) {
          BoardFrame currentFrame = BoardFrame.capture(board);
          if (capturedFrame.matches(currentFrame)) {
            endSynchronizationBarriers();
            stable = true;
          } else {
            capturedFrame = currentFrame;
            pendingRoute = captureRoute(false);
          }
        }
        if (!stable) {
          acquireReservation();
        }
      }
    }

    private void executePendingRoute(boolean engineGameInitialization) {
      reconcileCapturedBoardSize();
      PreparedLifecycleRestore route = pendingRoute;
      if (route.exactRestore.isPresent()) {
        if (engineGameInitialization) {
          board.resendMoveToEngine(
              targetEngine, false, route.exactRestore.orElseThrow(), true);
        } else {
          board.resendMoveToEngine(targetEngine, false, route.exactRestore.orElseThrow());
        }
      } else {
        if (ensureRootReplayKomiTransport) {
          ensureRootReplayKomiCommand(targetEngine, route);
          if (mirrorEngine != null) {
            ensureRootReplayKomiCommand(mirrorEngine, route);
          }
        }
        route.executeRootReplay(board, false, engineGameInitialization);
      }
    }

    private void reconcileCapturedBoardSize() {
      reconcileCapturedBoardSize(targetEngine);
      if (mirrorEngine != null) {
        reconcileCapturedBoardSize(mirrorEngine);
      }
    }

    private void reconcileCapturedBoardSize(Leelaz engine) {
      int frameWidth = capturedFrame.boardWidth;
      int frameHeight = capturedFrame.boardHeight;
      if (engine.width == frameWidth && engine.height == frameHeight) {
        return;
      }
      String command =
          frameWidth != frameHeight
              ? "rectangular_boardsize " + frameWidth + " " + frameHeight
              : "boardsize " + frameWidth;
      PreparedLifecycleRestore route = pendingRoute;
      engine.withExactSnapshotRestoreAdmission(
          route.admission,
          () -> {
            engine.sendCapturedRestoreCommand(command);
            engine.width = frameWidth;
            engine.height = frameHeight;
          });
    }

    private void ensureRootReplayKomiCommand(Leelaz engine, PreparedLifecycleRestore route) {
      if (route.rootKomi == null) {
        return;
      }
      float capturedKomi = (float) (route.rootKomi == 0.0 ? 0.0 : route.rootKomi);
      if (Float.compare(engine.komi, capturedKomi) != 0) {
        return;
      }
      String command = "komi " + (route.rootKomi == 0.0 ? "0" : route.rootKomi);
      engine.withExactSnapshotRestoreAdmission(
          route.admission, () -> engine.sendCapturedRestoreCommand(command));
    }

    private PreparedLifecycleRestore captureRoute(boolean forceRootReplay) {
      BoardHistoryList history = board.getHistory();
      BoardHistoryNode historyTarget =
          forceRootReplay || history == null ? null : history.getCurrentHistoryNode();
      Double currentGameKomi =
          history == null || history.getGameInfo() == null ? null : history.getGameInfo().getKomi();
      return PreparedLifecycleRestore.capture(
          previousEngine,
          targetEngine,
          mirrorEngine,
          lifecycleOwner,
          historyTarget,
          currentGameKomi,
          Movelist.copyList(board.getMoveList()),
          resumePonder);
    }

    private void beginSynchronizationBarriers() {
      targetEngine.beginInitialBoardSynchronization();
      if (mirrorEngine != null) {
        mirrorEngine.beginInitialBoardSynchronization();
      }
    }

    private void beginLifecycleCompletionClaim() {
      Leelaz.LifecycleCompletionClaim claim =
          targetEngine.tryBeginLifecycleCompletion(lifecycleOwner, mirrorEngine);
      if (claim == null) {
        throw new InitialStartupReservationException(
            "Engine lifecycle completion claim was rejected");
      }
      completionClaim = claim;
    }

    private void completePkSynchronizationAfterClaimRelease(
        PkEngineSynchronization completion) {
      Leelaz.LifecycleCompletionClaim claim = completionClaim;
      if (claim == null) {
        throw new IllegalStateException("Engine lifecycle completion claim is unavailable");
      }
      claim.runAfterEndpointRelease(completion::complete);
    }

    private void endSynchronizationBarriers() {
      if (barriersEnded.compareAndSet(false, true)) {
        try {
          targetEngine.endInitialBoardSynchronization();
        } finally {
          if (mirrorEngine != null) {
            mirrorEngine.endInitialBoardSynchronization();
          }
        }
      }
    }

    private void acquireReservation() {
      Leelaz.ExclusiveGtpLifecycleReservation targetReservation = null;
      if (targetEngine != previousEngine) {
        targetReservation = targetEngine.beginExclusiveGtpLifecycleReservation(lifecycleOwner);
        if (targetReservation == null) {
          throw new InitialStartupReservationException(
              "Engine lifecycle target reservation was rejected");
        }
      }
      Leelaz.ExclusiveGtpLifecycleReservation previousReservation =
          previousEngine == null
              ? null
              : previousEngine.beginExclusiveGtpLifecycleReservation(lifecycleOwner);
      if (previousEngine != null && previousReservation == null) {
        if (targetReservation != null) {
          targetReservation.close();
        }
        throw new InitialStartupReservationException(
            "Engine lifecycle previous reservation was rejected");
      }
      installReservations(new EngineLifecycleReservations(previousReservation, targetReservation));
    }

    private void installReservations(EngineLifecycleReservations preparedReservations) {
      if (preparedReservations == null) {
        throw new InitialStartupReservationException("Engine lifecycle reservation was rejected");
      }
      boolean accepted = false;
      synchronized (this) {
        if (!closed.get() && reservations == null) {
          reservations = preparedReservations;
          trackingFirstWinner |= preparedReservations.isTrackingFirstWinner();
          accepted = true;
        }
      }
      if (!accepted) {
        preparedReservations.close();
        throw new InitialStartupReservationException(
            "Engine lifecycle reservation could not be installed");
      }
    }

    private void releaseReservation() {
      EngineLifecycleReservations activeReservations;
      synchronized (this) {
        activeReservations = reservations;
        reservations = null;
      }
      if (activeReservations != null) {
        activeReservations.close();
      }
    }

    private boolean isTrackingFirstWinner() {
      return trackingFirstWinner;
    }

    private boolean attachRestartInteractionGate() {
      try {
        if (trackingFirstWinner && Lizzie.frame != null && Lizzie.frame.isDisplayable()) {
          interactionGate = Lizzie.frame.beginRestartInteractionGate();
        }
        return true;
      } catch (RuntimeException failure) {
        close();
        return false;
      }
    }

    private void initializeAfterRestore() {
      if (initialized.compareAndSet(false, true)) {
        Lizzie.initializeAfterVersionCheck(false, targetEngine);
      }
    }

    /**
     * Completes the update-engine restore at the stable restore point: marks the engine ready
     * exactly once without starting pondering, then resumes ponder only when the captured update
     * intent requested it. Must be called after {@link #runUntilStable()} has converged.
     */
    private void initializeUpdateRestore() {
      if (initialized.compareAndSet(false, true)) {
        Lizzie.initializeAfterVersionCheck(false, targetEngine, false);
      }
      PreparedLifecycleRestore route = pendingRoute;
      if (route != null) {
        route.resumePonderAfterSuccessfulSynchronization();
      }
    }

    private void confirmFinalBoardSynchronization(
        Runnable onSuccess, java.util.function.Consumer<String> onFailure) {
      Leelaz.LifecycleCompletionClaim claim = completionClaim;
      if (claim == null) {
        throw new IllegalStateException("Engine lifecycle completion claim is unavailable");
      }
      claim.startBoardSynchronizationAttempt(
          () -> completeFinalBoardSynchronizationAttempt(onSuccess, onFailure),
          detail -> claim.completeFailure(detail, onFailure));
    }

    private void completeFinalBoardSynchronizationAttempt(
        Runnable onSuccess, java.util.function.Consumer<String> onFailure) {
      Leelaz.LifecycleCompletionClaim claim = completionClaim;
      try {
        boolean catchUpRequired;
        synchronized (board) {
          BoardFrame currentFrame = BoardFrame.capture(board);
          catchUpRequired = !capturedFrame.matches(currentFrame);
          if (catchUpRequired) {
            stable = false;
            barriersEnded.set(false);
            beginSynchronizationBarriers();
            capturedFrame = currentFrame;
            pendingRoute = captureRoute(false);
          }
        }
        if (!catchUpRequired) {
          claim.completeSuccess(onSuccess, onFailure);
          return;
        }
        acquireReservation();
        runUntilStable(engineGameInitialization);
        claim.continueBoardSynchronizationAttempt(
            () -> completeFinalBoardSynchronizationAttempt(onSuccess, onFailure),
            detail -> claim.completeFailure(detail, onFailure));
      } catch (RuntimeException failure) {
        claim.completeFailure(
            failure.getMessage() == null
                ? "lifecycle completion catch-up failed"
                : failure.getMessage(),
            onFailure);
      }
    }

    /** Releases owner resources and ends all captured engine gates. */
    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        try {
          releaseReservation();
        } finally {
          try {
            if (interactionGate != null) {
              interactionGate.close();
            }
          } finally {
            endSynchronizationBarriers();
            Leelaz.LifecycleCompletionClaim claim = completionClaim;
            if (claim != null) {
              claim.abandonBeforeFence();
            }
          }
        }
      }
    }
  }

  /**
   * Immutable identity of the board position the startup restore route was captured for. Captured
   * and compared inside {@code synchronized (Board)} so it is consistent with history navigation.
   */
  static final class BoardFrame {
    private final BoardHistoryNode root;
    private final BoardHistoryNode current;
    private final long contextRevision;
    private final boolean blackToPlay;
    private final double komi;
    private final Zobrist zobrist;
    private final int boardWidth;
    private final int boardHeight;
    private final boolean boardEmpty;
    private final boolean changedKomi;

    private BoardFrame(
        BoardHistoryNode root,
        BoardHistoryNode current,
        long contextRevision,
        boolean blackToPlay,
        double komi,
        Zobrist zobrist,
        int boardWidth,
        int boardHeight,
        boolean boardEmpty,
        boolean changedKomi) {
      this.root = root;
      this.current = current;
      this.contextRevision = contextRevision;
      this.blackToPlay = blackToPlay;
      this.komi = komi;
      this.zobrist = zobrist;
      this.boardWidth = boardWidth;
      this.boardHeight = boardHeight;
      this.boardEmpty = boardEmpty;
      this.changedKomi = changedKomi;
    }

    static BoardFrame capture(Board board) {
      BoardHistoryList history = board == null ? null : board.getHistory();
      BoardData data = history == null ? null : history.getData();
      Zobrist zobrist = data == null ? null : data.zobrist;
      return new BoardFrame(
          history == null ? null : history.getStart(),
          history == null ? null : history.getCurrentHistoryNode(),
          board == null ? 0L : board.getContextRevision(),
          history != null && history.isBlacksTurn(),
          history == null || history.getGameInfo() == null
              ? Double.NaN
              : history.getGameInfo().getKomi(),
          zobrist == null ? null : zobrist.clone(),
          Board.boardWidth,
          Board.boardHeight,
          history != null && history.getStart() == history.getEnd(),
          history != null && history.getGameInfo() != null && history.getGameInfo().changedKomi);
    }

    int boardWidth() {
      return boardWidth;
    }

    int boardHeight() {
      return boardHeight;
    }

    boolean matches(BoardFrame other) {
      return other != null
          && root == other.root
          && current == other.current
          && contextRevision == other.contextRevision
          && blackToPlay == other.blackToPlay
          && Double.compare(komi, other.komi) == 0
          && java.util.Objects.equals(zobrist, other.zobrist)
          && boardWidth == other.boardWidth
          && boardHeight == other.boardHeight
          && boardEmpty == other.boardEmpty
          && changedKomi == other.changedKomi;
    }
  }

  public void changeEngIcoForEndPk() {
    // Lizzie.frame.subBoardRenderer.reverseBestmoves = false;
    //  Lizzie.frame.boardRenderer.reverseBestmoves = false;
    clearFirstSecondEngineCountDown();
    Menu.engineMenu.setEnabled(true);
    if (Lizzie.board.getData().blackToPlay) {
      // switchEngine(Lizzie.frame.toolbar.engineWhite);
      Lizzie.setPrimaryEngine(engineList.get(engineGameInfo.firstEngineIndex));
      engineList.get(engineGameInfo.firstEngineIndex).nameCmd();

      // switchEngine(Lizzie.frame.toolbar.engineBlack);
    } else {
      // switchEngine(Lizzie.frame.toolbar.engineBlack);
      Lizzie.setPrimaryEngine(engineList.get(engineGameInfo.secondEngineIndex));
      engineList.get(engineGameInfo.secondEngineIndex).nameCmd();
      // engineList.get(Lizzie.frame.toolbar.engineWhite).clear();
      // switchEngine(Lizzie.frame.toolbar.engineWhite);
    }
    // this.currentEngineNo = Lizzie.leelaz.currentEngineN();
    // double komi = Lizzie.board.getHistory().getGameInfo().getKomi();
    Lizzie.config.notStartPondering = true;
    // switchEngine(Lizzie.leelaz.currentEngineN(), true);
    // Lizzie.board.setKomi(komi);
    //  Lizzie.board.clearAfterMove();
    EngineManager.currentEngineNo = Lizzie.leelaz.currentEngineN();
    Menu.engineMenu.setText(
        resourceBundle.getString("EngineManager.engine")
            + (Lizzie.leelaz.currentEngineN() + 1)
            + ": "
            + Lizzie.leelaz.oriEnginename);
    changeEngIco(1);
    LizzieFrame.menu.setBtnRankMark();
    if (engineList.get(engineGameInfo.whiteEngineIndex).isKatago
        || engineList.get(engineGameInfo.whiteEngineIndex).isSai)
      Lizzie.board.isPkBoardKataW = true;
    else if (engineList.get(engineGameInfo.blackEngineIndex).isKatago
        || engineList.get(engineGameInfo.blackEngineIndex).isSai)
      Lizzie.board.isPkBoardKataB = true;
    Lizzie.config.chkPkStartNum = false;
    Lizzie.frame.restoreWRN(engineGameInfo.isGenmove);
    Lizzie.frame.refresh();
  }

  public String getEngineName(int index) {
    return engineList.get(index).getEngineName(index);
  }

  private void changeEngIco(int index) {
    LizzieFrame.menu.changeicon(index);
  }

  public static boolean isEngineGame() {
    return isPreEngineGame || isEngineGame;
  }

  //  public void setEngineCountDown(
  //      EngineCountDown engineCountDown,
  //      int leftMinutes,
  //      int countDownSeconds,
  //      int countDownMoves,
  //      Leelaz engine) {
  //    engineCountDown.leftSeconds = leftMinutes * 60;
  //    engineCountDown.countDownSeconds = countDownSeconds;
  //    engineCountDown.countDownMoves = countDownMoves;
  //    engineCountDown.engine = engine;
  //  }

  private void clearFirstSecondEngineCountDown() {
    firstEngineCountDown = null;
    secondEngineCountDown = null;
  }

  public void clearPlayingAgainstHumanEngineCountDown() {
    playingAgainstHumanEngineCountDown = null;
  }

  public void stopCountDown() {
    if (timeScheduled != null) {
      timeScheduled.shutdownNow();
      timeScheduled = null;
    }
  }

  public void StartCountDown() {
    stopCountDown();
    timeScheduledTimes = 0;
    timeScheduled = new ScheduledThreadPoolExecutor(1);
    timeScheduled.scheduleAtFixedRate(
        new Runnable() {
          @Override
          public void run() {
            timeScheduledTimes++;
            if (timeScheduledTimes >= 10) {
              timeScheduledTimes = 0;
              EngineCountDown countDown = null;
              if (isEngineGame) {
                if (LizzieFrame.toolbar.isPkStop) return;
                if (Lizzie.board.getHistory().isBlacksTurn()) {
                  if (firstEngineCountDown != null && firstEngineCountDown.isPlayBlack)
                    countDown = firstEngineCountDown;
                  else if (secondEngineCountDown != null && secondEngineCountDown.isPlayBlack)
                    countDown = secondEngineCountDown;
                } else {
                  if (firstEngineCountDown != null && !firstEngineCountDown.isPlayBlack)
                    countDown = firstEngineCountDown;
                  else if (secondEngineCountDown != null && !secondEngineCountDown.isPlayBlack)
                    countDown = secondEngineCountDown;
                }
              } else if (Lizzie.frame.isPlayingAgainstLeelaz
                  && playingAgainstHumanEngineCountDown != null
                  && Lizzie.board.getHistory().isBlacksTurn()
                      == playingAgainstHumanEngineCountDown.isPlayBlack)
                countDown = playingAgainstHumanEngineCountDown;
              if (countDown != null) {
                countDown.countDownCentiseconds();
              }
            }
          }
        },
        0,
        1,
        TimeUnit.MILLISECONDS);
  }
}
