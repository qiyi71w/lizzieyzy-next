package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.enginegame.EngineGameSnapshotFixtures;
import java.lang.reflect.Constructor;
import java.util.ListResourceBundle;
import java.util.ResourceBundle;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import org.junit.jupiter.api.Test;

class MenuEngineSwitchUiStateTest {
  private static final String SWITCHING_KEY = "LizzieFrame.prompt.switching";

  @Test
  void partiallyInitializedMenuUsesApplicationBundleOrSafeFallback() {
    ResourceBundle applicationBundle = bundle("application switching");

    assertEquals(
        "application switching",
        Menu.engineSwitchUiMessage(null, applicationBundle, SWITCHING_KEY, "switching..."));
    assertEquals(
        "switching...",
        Menu.engineSwitchUiMessage(null, null, SWITCHING_KEY, "switching..."));
  }

  @Test
  void menuBundleWinsAndMissingKeyFallsBackWithoutBreakingTheEdt() {
    ResourceBundle menuBundle = bundle("menu switching");
    ResourceBundle applicationBundle = bundle("application switching");

    assertEquals(
        "menu switching",
        Menu.engineSwitchUiMessage(
            menuBundle, applicationBundle, SWITCHING_KEY, "switching..."));
    assertEquals(
        "switching...",
        Menu.engineSwitchUiMessage(menuBundle, applicationBundle, "missing", "switching..."));
  }

  @Test
  void failedSwitchRestoresOnlyAnActuallyAvailablePreviousEngineIcon() throws Exception {
    JFontMenuItem[] items = {new JFontMenuItem("old"), new JFontMenuItem("target")};
    Icon playing = new ImageIcon(new byte[] {1});
    Icon stopped = new ImageIcon(new byte[] {2});
    EngineManager.EngineSwitchUiSnapshot failed = failedSnapshot(0, 1);

    Menu.applyEngineSwitchItemIcons(items, failed, playing, stopped, true);

    assertSame(playing, items[0].getIcon());
    assertSame(stopped, items[1].getIcon());

    Menu.applyEngineSwitchItemIcons(items, failed, playing, stopped, false);

    assertSame(stopped, items[0].getIcon());
    assertSame(stopped, items[1].getIcon());
  }

  @Test
  void twentyFirstAndTwentySecondEngineReplayActiveStateIntoFreshMoreItem()
      throws Exception {
    Icon playing = new ImageIcon(new byte[] {1});
    Icon stopped = new ImageIcon(new byte[] {2});
    for (int activeIndex : new int[] {20, 21}) {
      JFontMenu menu = new JFontMenu("no engine");
      JFontMenuItem[] items = freshEngineItems();
      EngineManager.EngineSwitchUiSnapshot active =
          snapshot(
              EngineManager.EngineSwitchUiPhase.ACTIVE,
              activeIndex,
              "Engine " + (activeIndex + 1),
              activeIndex,
              "Engine " + (activeIndex + 1),
              19,
              "Engine 20",
              "");

      Menu.applyEngineSwitchPresentation(
          menu, items, active, playing, stopped, true, "switching...");

      assertEquals(
          "[" + (activeIndex + 1) + "]: Engine " + (activeIndex + 1), menu.getText());
      assertSame(playing, items[20].getIcon());
      assertTrue(items[20].isEnabled());
    }
  }

  @Test
  void twentySecondEngineReplayShowsSwitchingBeforeAnotherPublication() throws Exception {
    Icon playing = new ImageIcon(new byte[] {1});
    Icon stopped = new ImageIcon(new byte[] {2});
    JFontMenu menu = new JFontMenu("no engine");
    JFontMenuItem[] items = freshEngineItems();
    EngineManager.EngineSwitchUiSnapshot switching =
        snapshot(
            EngineManager.EngineSwitchUiPhase.SWITCHING,
            19,
            "Engine 20",
            21,
            "Engine 22",
            19,
            "Engine 20",
            "");

    Menu.applyEngineSwitchPresentation(
        menu, items, switching, playing, stopped, true, "switching...");

    assertEquals("[22]: Engine 22  ·  switching...", menu.getText());
    assertSame(playing, items[19].getIcon());
    assertSame(stopped, items[20].getIcon());
    assertTrue(items[20].isEnabled());
  }

  @Test
  void ordinaryPdaFollowsPrimaryWhileEngineGamePreservesEitherLiveOwner() throws Exception {
    Leelaz previousPrimary = Lizzie.leelaz;
    Leelaz previousSecondary = Lizzie.leelaz2;
    Leelaz secondary = new Leelaz("");
    secondary.started = true;
    secondary.isLoaded = true;
    try {
      Lizzie.setPrimaryEngine(null);
      Lizzie.leelaz2 = secondary;
      EngineGameSnapshotFixtures.publishIdle();

      assertTrue(
          Menu.shouldApplyEnginePdaUpdate(null),
          "no-primary ordinary analysis must allow a queued PDA hide");
      assertFalse(
          Menu.shouldApplyEnginePdaUpdate(secondary),
          "ordinary PDA must never be sourced from a secondary-only owner");

      EngineGameSnapshotFixtures.publishPlaying();
      assertFalse(
          Menu.shouldApplyEnginePdaUpdate(null),
          "engine-game must not accept a stale hide while either participant is live");
      assertTrue(
          Menu.shouldApplyEnginePdaUpdate(secondary),
          "engine-game preserves its two-participant PDA semantics");
    } finally {
      Lizzie.setPrimaryEngine(previousPrimary);
      Lizzie.leelaz2 = previousSecondary;
      EngineGameSnapshotFixtures.publishIdle();
    }
  }

  private static EngineManager.EngineSwitchUiSnapshot failedSnapshot(
      int previousIndex, int targetIndex) throws Exception {
    return snapshot(
        EngineManager.EngineSwitchUiPhase.FAILED,
        previousIndex,
        "Old",
        targetIndex,
        "Target",
        previousIndex,
        "Old",
        "failed");
  }

  private static EngineManager.EngineSwitchUiSnapshot snapshot(
      EngineManager.EngineSwitchUiPhase phase,
      int activeIndex,
      String activeName,
      int targetIndex,
      String targetName,
      int rollbackIndex,
      String rollbackName,
      String failureDetail)
      throws Exception {
    Constructor<EngineManager.EngineSwitchUiSnapshot> constructor =
        EngineManager.EngineSwitchUiSnapshot.class.getDeclaredConstructor(
            long.class,
            boolean.class,
            EngineManager.EngineSwitchUiPhase.class,
            int.class,
            String.class,
            int.class,
            String.class,
            String.class,
            int.class,
            String.class,
            Leelaz.class,
            Leelaz.class,
            Leelaz.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        1L,
        true,
        phase,
        activeIndex,
        activeName,
        targetIndex,
        targetName,
        failureDetail,
        rollbackIndex,
        rollbackName,
        null,
        null,
        null);
  }

  private static JFontMenuItem[] freshEngineItems() {
    JFontMenuItem[] items = new JFontMenuItem[21];
    for (int index = 0; index < items.length; index++) {
      items[index] =
          new JFontMenuItem(index == 20 ? "More engines" : "Engine " + (index + 1));
    }
    return items;
  }

  private static ResourceBundle bundle(String value) {
    return new ListResourceBundle() {
      @Override
      protected Object[][] getContents() {
        return new Object[][] {{SWITCHING_KEY, value}};
      }
    };
  }
}
