package featurecat.lizzie.enginegame;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import java.util.ResourceBundle;
import javax.swing.SwingUtilities;

/**
 * Production PK chrome adapter. Applies existing toolbar/menu control state on the EDT and owns no
 * product state.
 */
public class SwingEngineGameChrome implements EngineGameChrome {
  public static final SwingEngineGameChrome INSTANCE = new SwingEngineGameChrome();

  @Override
  public void publish(EngineGameChromeTransition transition) {
    if (transition == null) {
      return;
    }
    Runnable apply = () -> applyOnEdt(transition);
    if (SwingUtilities.isEventDispatchThread()) {
      apply.run();
      return;
    }
    dispatch(apply);
  }

  void dispatch(Runnable apply) {
    SwingUtilities.invokeLater(apply);
  }

  void applyOnEdt(EngineGameChromeTransition transition) {
    BottomToolbar toolbar = LizzieFrame.toolbar;
    Menu menu = LizzieFrame.menu;
    switch (transition.kind()) {
      case STARTING -> applyStarting(toolbar, menu);
      case PLAYING, RESUMED -> applyRunning(toolbar, menu);
      case PAUSED -> applyPaused(toolbar, menu);
      case BETWEEN_GAMES -> {
        if (menu != null) {
          menu.toggleDoubleMenuGameStatus();
        }
      }
      case START_FAILED, USER_STOPPED, BATCH_ENDED, LATER_GAME_FAILED -> applyIdle(toolbar, menu);
    }
  }

  private static void applyStarting(BottomToolbar toolbar, Menu menu) {
    if (toolbar != null) {
      toolbar.enableDisabelForEngineGame(false);
      setPauseButtonText(toolbar, false);
      if (toolbar.lblenginePkResult != null) {
        toolbar.lblenginePkResult.setText("0:0");
      }
    }
    if (Menu.engineMenu != null) {
      ResourceBundle bundle = Lizzie.resourceBundle;
      if (bundle != null && bundle.containsKey("EngineManager.engineGamePlaying")) {
        Menu.engineMenu.setText(bundle.getString("EngineManager.engineGamePlaying"));
      }
    }
  }

  private static void applyRunning(BottomToolbar toolbar, Menu menu) {
    if (toolbar != null) {
      setPauseButtonText(toolbar, false);
    }
    if (menu != null) {
      menu.toggleDoubleMenuGameStatus();
    }
  }

  private static void applyPaused(BottomToolbar toolbar, Menu menu) {
    if (toolbar != null) {
      setPauseButtonText(toolbar, true);
    }
    if (menu != null) {
      menu.toggleDoubleMenuGameStatus();
    }
  }

  private static void applyIdle(BottomToolbar toolbar, Menu menu) {
    if (toolbar != null) {
      toolbar.enableDisabelForEngineGame(true);
      setPauseButtonText(toolbar, false);
    }
    if (Menu.engineMenu != null) {
      Menu.engineMenu.setEnabled(true);
    }
    if (menu != null) {
      menu.toggleDoubleMenuGameStatus();
    }
  }

  private static void setPauseButtonText(BottomToolbar toolbar, boolean paused) {
    if (toolbar == null || toolbar.btnEnginePkStop == null) {
      return;
    }
    toolbar.btnEnginePkStop.setText(
        paused ? text("BottomToolbar.detail.continue", "继续") : text("BottomToolbar.detail.pause", "暂停"));
  }

  private static String text(String key, String fallback) {
    ResourceBundle bundle = Lizzie.resourceBundle;
    if (bundle == null || key == null) {
      return fallback;
    }
    try {
      return bundle.containsKey(key) ? bundle.getString(key) : fallback;
    } catch (RuntimeException unavailable) {
      return fallback;
    }
  }
}
