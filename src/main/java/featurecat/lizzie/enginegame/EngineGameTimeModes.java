package featurecat.lizzie.enginegame;

import featurecat.lizzie.gui.DesktopTimeControl;

public final class EngineGameTimeModes {
  private EngineGameTimeModes() {}

  public static DesktopTimeControl.SideMode sideMode(EngineGameTimeMode mode) {
    if (mode == null) {
      return DesktopTimeControl.SideMode.FIXED;
    }
    return switch (mode) {
      case ENGINE_OWNED -> DesktopTimeControl.SideMode.ENGINE_OWNED;
      case RAW_ADVANCED -> DesktopTimeControl.SideMode.RAW_ADVANCED;
      case FIXED -> DesktopTimeControl.SideMode.FIXED;
    };
  }
}
