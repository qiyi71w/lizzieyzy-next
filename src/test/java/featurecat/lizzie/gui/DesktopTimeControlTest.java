package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class DesktopTimeControlTest {
  @Test
  void freshConfigurationUsesFixedTwoSecondMoveTime() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-time-default"));

    assertFalse(config.advanceTimeSettings);
    assertFalse(config.kataTimeSettings);
    assertEquals(2, config.maxGameThinkingTimeSeconds);
  }

  @Test
  void websocketHumanGameRejectsOnlyAdvancedClockSelections() throws Exception {
    Leelaz websocket = new Leelaz(RemoteComputeConfig.COMMAND_CUSTOM_WS);
    Leelaz local = new Leelaz("katago gtp");

    assertTrue(
        DesktopTimeControl.rejectsHumanGame(
            websocket, DesktopTimeControl.Mode.RAW_ADVANCED, false));
    assertTrue(
        DesktopTimeControl.rejectsHumanGame(
            websocket, DesktopTimeControl.Mode.KATAGO_ADVANCED, false));
    assertFalse(
        DesktopTimeControl.rejectsHumanGame(websocket, DesktopTimeControl.Mode.FIXED, false));
    assertFalse(
        DesktopTimeControl.rejectsHumanGame(websocket, DesktopTimeControl.Mode.RAW_ADVANCED, true));
    assertFalse(
        DesktopTimeControl.rejectsHumanGame(local, DesktopTimeControl.Mode.RAW_ADVANCED, false));
  }

  @Test
  void mixedEngineGameRejectsAdvancedClockWhenEitherSideIsWebsocket() throws Exception {
    Leelaz local = new Leelaz("katago gtp");
    Leelaz websocket = new Leelaz(RemoteComputeConfig.COMMAND_CUSTOM_WS);

    assertTrue(DesktopTimeControl.rejectsEngineGame(List.of(local, websocket), 0, 1, true));
    assertTrue(DesktopTimeControl.rejectsEngineGame(List.of(websocket, local), 0, 1, true));
    assertFalse(DesktopTimeControl.rejectsEngineGame(List.of(local, local), 0, 1, true));
    assertFalse(DesktopTimeControl.rejectsEngineGame(List.of(local, websocket), 0, 1, false));
  }

  @Test
  void humanTimeModeChangesOnlyWhenSelectionIsCommitted() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-time-transaction"));
    config.uiConfig = new JSONObject();

    DesktopTimeControl.Mode draft = DesktopTimeControl.Mode.KATAGO_ADVANCED;
    assertFalse(config.advanceTimeSettings);
    assertFalse(config.kataTimeSettings);
    assertFalse(config.uiConfig.has("advance-time-settings"));
    assertFalse(config.uiConfig.has("kata-time-settings"));

    DesktopTimeControl.commitHumanSelection(config, draft, 2);

    assertFalse(config.advanceTimeSettings);
    assertTrue(config.kataTimeSettings);
    assertEquals(2, config.kataTimeType);
    assertFalse(config.uiConfig.getBoolean("advance-time-settings"));
    assertTrue(config.uiConfig.getBoolean("kata-time-settings"));
    assertEquals(2, config.uiConfig.getInt("kata-time-type"));
  }

  @Test
  void rejectedWebsocketSubmissionWarnsWithoutCommandsOrConfigurationChanges() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-time-rejected"));
    config.uiConfig = new JSONObject();
    RecordingLeelaz websocket =
        new RecordingLeelaz(RemoteComputeConfig.COMMAND_CUSTOM_WS);
    AtomicInteger warnings = new AtomicInteger();

    boolean accepted =
        DesktopTimeControl.submitHumanSelection(
            config,
            websocket,
            DesktopTimeControl.Mode.RAW_ADVANCED,
            1,
            false,
            warnings::incrementAndGet);

    assertFalse(accepted);
    assertEquals(1, warnings.get());
    assertFalse(config.advanceTimeSettings);
    assertFalse(config.kataTimeSettings);
    assertFalse(config.uiConfig.has("advance-time-settings"));
    assertFalse(config.uiConfig.has("kata-time-settings"));
    assertEquals(List.of(), websocket.commands);
  }

  @Test
  void engineGameAdvancedModeChangesOnlyWhenSelectionIsCommitted() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-pk-time-transaction"));
    config.uiConfig = new JSONObject();

    boolean draftAdvanced = true;
    assertFalse(config.pkAdvanceTimeSettings);
    assertFalse(config.uiConfig.has("pk-advance-time-settings"));

    DesktopTimeControl.commitEngineGameSelection(config, draftAdvanced);

    assertTrue(config.pkAdvanceTimeSettings);
    assertTrue(config.uiConfig.getBoolean("pk-advance-time-settings"));
  }

  @Test
  void websocketEngineGameFixedTimeUsesNoneAndRealMaxTime() throws Exception {
    RecordingLeelaz websocket = new RecordingLeelaz(RemoteComputeConfig.COMMAND_CUSTOM_WS);

    DesktopTimeControl.sendEngineGameFixedTime(websocket, 4);

    assertEquals(
        List.of("kata-time_settings none", "kata-set-param maxTime 4"), websocket.commands);
  }

  @Test
  void localAndSshEngineGameFixedTimeKeepStandardGtpCommand() throws Exception {
    RecordingLeelaz local = new RecordingLeelaz("katago gtp");
    RecordingLeelaz ssh = new RecordingLeelaz("ssh host katago gtp");

    DesktopTimeControl.sendEngineGameFixedTime(local, 4);
    DesktopTimeControl.sendEngineGameFixedTime(ssh, 5);

    assertEquals(List.of("time_settings 0 4 1"), local.commands);
    assertEquals(List.of("time_settings 0 5 1"), ssh.commands);
  }

  @Test
  void engineGameWithoutClockSendsNoTimeCommand() throws Exception {
    RecordingLeelaz websocket = new RecordingLeelaz(RemoteComputeConfig.COMMAND_CUSTOM_WS);

    DesktopTimeControl.sendEngineGameFixedTime(websocket, -1);

    assertEquals(List.of(), websocket.commands);
  }

  @Test
  void engineOwnedModeWinsOverOtherHumanClockFlags() {
    assertEquals(
        DesktopTimeControl.Mode.ENGINE_OWNED,
        DesktopTimeControl.selectedMode(true, true, true));
    assertEquals(
        DesktopTimeControl.Mode.FIXED, DesktopTimeControl.selectedMode(false, false, false));
  }

  @Test
  void commitHumanEngineOwnedPersistsNoTimeFlagAndClearsClientClockModes() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-time-engine-owned"));
    config.uiConfig = new JSONObject();
    config.advanceTimeSettings = true;
    config.kataTimeSettings = true;

    DesktopTimeControl.commitHumanSelection(config, DesktopTimeControl.Mode.ENGINE_OWNED, 1);

    assertTrue(config.genmoveGameNoTime);
    assertFalse(config.advanceTimeSettings);
    assertFalse(config.kataTimeSettings);
    assertTrue(config.uiConfig.getBoolean("genmove-game-notime"));
    assertFalse(config.uiConfig.getBoolean("advance-time-settings"));
    assertFalse(config.uiConfig.getBoolean("kata-time-settings"));
  }

  @Test
  void websocketAllowsHumanEngineOwnedTime() throws Exception {
    Leelaz websocket = new Leelaz(RemoteComputeConfig.COMMAND_CUSTOM_WS);

    assertFalse(
        DesktopTimeControl.rejectsHumanGame(
            websocket, DesktopTimeControl.Mode.ENGINE_OWNED, false));
  }

  @Test
  void engineOwnedModeDoesNotEmitClientTimeOverride() {
    assertFalse(
        DesktopTimeControl.shouldEmitClientTimeOverride(DesktopTimeControl.Mode.ENGINE_OWNED));
    assertTrue(DesktopTimeControl.shouldEmitClientTimeOverride(DesktopTimeControl.Mode.FIXED));
    assertTrue(
        DesktopTimeControl.shouldEmitClientTimeOverride(DesktopTimeControl.Mode.RAW_ADVANCED));
    assertTrue(
        DesktopTimeControl.shouldEmitClientTimeOverride(DesktopTimeControl.Mode.KATAGO_ADVANCED));
  }

  @Test
  void onlyFixedModeConsumesThePerMoveSecondsField() {
    assertTrue(DesktopTimeControl.usesFixedMoveSeconds(DesktopTimeControl.Mode.FIXED));
    assertFalse(DesktopTimeControl.usesFixedMoveSeconds(DesktopTimeControl.Mode.ENGINE_OWNED));
    assertFalse(DesktopTimeControl.usesFixedMoveSeconds(DesktopTimeControl.Mode.RAW_ADVANCED));
    assertFalse(DesktopTimeControl.usesFixedMoveSeconds(DesktopTimeControl.Mode.KATAGO_ADVANCED));
  }

  @Test
  void automaticEngineReadySendsHumanTimeOnlyForGenmoveGames() {
    assertTrue(DesktopTimeControl.shouldSendHumanTimeOnEngineReady(true, false));
    assertFalse(DesktopTimeControl.shouldSendHumanTimeOnEngineReady(false, true));
    assertFalse(DesktopTimeControl.shouldSendHumanTimeOnEngineReady(false, false));
    assertFalse(DesktopTimeControl.shouldSendHumanTimeOnEngineReady(true, true));
  }

  @Test
  void engineOwnedSideModeWinsOverAdvancedFlag() {
    assertEquals(
        DesktopTimeControl.SideMode.ENGINE_OWNED,
        DesktopTimeControl.selectedEngineGameSideMode(true, true));
    assertEquals(
        DesktopTimeControl.SideMode.FIXED,
        DesktopTimeControl.selectedEngineGameSideMode(false, false));
  }

  @Test
  void commitEngineGameSidesPersistsIndependentlyAndMigratesLegacyFlag() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-pk-side-mode"));
    config.uiConfig = new JSONObject();
    config.pkAdvanceTimeSettings = true;

    assertEquals(
        DesktopTimeControl.SideMode.RAW_ADVANCED,
        DesktopTimeControl.loadEngineGameSideMode(config, true));
    assertEquals(
        DesktopTimeControl.SideMode.RAW_ADVANCED,
        DesktopTimeControl.loadEngineGameSideMode(config, false));

    DesktopTimeControl.commitEngineGameSelection(
        config,
        DesktopTimeControl.SideMode.ENGINE_OWNED,
        DesktopTimeControl.SideMode.FIXED);

    assertEquals(
        DesktopTimeControl.SideMode.ENGINE_OWNED,
        DesktopTimeControl.loadEngineGameSideMode(config, true));
    assertEquals(
        DesktopTimeControl.SideMode.FIXED,
        DesktopTimeControl.loadEngineGameSideMode(config, false));
    assertFalse(config.pkAdvanceTimeSettings);
    assertFalse(config.uiConfig.getBoolean("pk-advance-time-settings"));
  }

  @Test
  void engineGameSideModeAcceptsCaseInsensitiveSavedValues() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-pk-side-mode-case"));
    config.uiConfig = new JSONObject();
    config.uiConfig.put("pk-black-time-mode", " engine_owned ");

    assertEquals(
        DesktopTimeControl.SideMode.ENGINE_OWNED,
        DesktopTimeControl.loadEngineGameSideMode(config, true));
  }

  @Test
  void invalidEngineGameSideModeFallsBackToLegacySelection() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-pk-side-mode-invalid"));
    config.uiConfig = new JSONObject();
    config.uiConfig.put("pk-black-time-mode", "UNKNOWN_MODE");
    config.uiConfig.put("pk-white-time-mode", 42);
    config.pkAdvanceTimeSettings = true;

    assertEquals(
        DesktopTimeControl.SideMode.RAW_ADVANCED,
        DesktopTimeControl.loadEngineGameSideMode(config, true));
    assertEquals(
        DesktopTimeControl.SideMode.RAW_ADVANCED,
        DesktopTimeControl.loadEngineGameSideMode(config, false));

    config.pkAdvanceTimeSettings = false;
    assertEquals(
        DesktopTimeControl.SideMode.FIXED,
        DesktopTimeControl.loadEngineGameSideMode(config, true));
  }

  @Test
  void websocketEngineGameRejectsOnlyRawAdvancedSides() throws Exception {
    Leelaz local = new Leelaz("katago gtp");
    Leelaz websocket = new Leelaz(RemoteComputeConfig.COMMAND_CUSTOM_WS);

    assertTrue(
        DesktopTimeControl.rejectsEngineGame(
            List.of(local, websocket),
            0,
            1,
            DesktopTimeControl.SideMode.FIXED,
            DesktopTimeControl.SideMode.RAW_ADVANCED));
    assertFalse(
        DesktopTimeControl.rejectsEngineGame(
            List.of(local, websocket),
            0,
            1,
            DesktopTimeControl.SideMode.ENGINE_OWNED,
            DesktopTimeControl.SideMode.FIXED));
  }

  @Test
  void mixedEngineGameApplySendsOnlyClientOwnedSide() throws Exception {
    RecordingLeelaz black = new RecordingLeelaz("zen gtp");
    RecordingLeelaz white = new RecordingLeelaz("katago gtp");

    DesktopTimeControl.applyEngineGameTime(
        black, DesktopTimeControl.SideMode.ENGINE_OWNED, 10, "time_settings 120 2 1");
    DesktopTimeControl.applyEngineGameTime(
        white, DesktopTimeControl.SideMode.FIXED, 10, "time_settings 120 2 1");

    assertEquals(List.of(), black.commands);
    assertEquals(List.of("time_settings 0 10 1"), white.commands);
  }

  @Test
  void toolbarSecondsAffectOnlyFixedSides() {
    assertEquals(
        -1,
        DesktopTimeControl.fixedSecondsForToolbar(
            DesktopTimeControl.SideMode.ENGINE_OWNED, true, 10));
    assertEquals(
        -1,
        DesktopTimeControl.fixedSecondsForToolbar(DesktopTimeControl.SideMode.FIXED, false, 10));
    assertEquals(
        10,
        DesktopTimeControl.fixedSecondsForToolbar(DesktopTimeControl.SideMode.FIXED, true, 10));
  }

  @Test
  void rawAdvancedApplyUsesThatSideCommandOnly() throws Exception {
    RecordingLeelaz white = new RecordingLeelaz("katago gtp");

    DesktopTimeControl.applyEngineGameTime(
        white,
        DesktopTimeControl.SideMode.RAW_ADVANCED,
        10,
        "time_settings 30 5 1");

    assertEquals(List.of("time_settings 30 5 1"), white.commands);
  }

  @Test
  void capturedEngineGameTimeIgnoresLaterLiveConfig() throws Exception {
    RecordingLeelaz white = new RecordingLeelaz("katago gtp");

    DesktopTimeControl.applyEngineGameTime(
        white,
        DesktopTimeControl.SideMode.RAW_ADVANCED,
        10,
        "time_settings 30 5 1");

    assertEquals(List.of("time_settings 30 5 1"), white.commands);
  }

  @Test
  void capturedEngineOwnedRestartStaysSilent() throws Exception {
    RecordingLeelaz black = new RecordingLeelaz("zen gtp");

    DesktopTimeControl.applyEngineGameTime(
        black, DesktopTimeControl.SideMode.ENGINE_OWNED, 10, "time_settings 120 2 1");

    assertEquals(List.of(), black.commands);
  }




  private static final class RecordingLeelaz extends Leelaz {
    private final List<String> commands = new ArrayList<>();

    private RecordingLeelaz(String command) throws Exception {
      super(command);
    }

    @Override
    public void sendCommand(String command) {
      commands.add(command);
    }
  }
}
