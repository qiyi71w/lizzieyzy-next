package featurecat.lizzie.enginegame;

import featurecat.lizzie.gui.DesktopTimeControl;

public enum EngineGameTimeMode {
  ENGINE_OWNED,
  FIXED,
  RAW_ADVANCED;

  public static EngineGameTimeMode fromDesktop(DesktopTimeControl.SideMode mode) {
    if (mode == null) {
      return FIXED;
    }
    return switch (mode) {
      case ENGINE_OWNED -> ENGINE_OWNED;
      case RAW_ADVANCED -> RAW_ADVANCED;
      case FIXED -> FIXED;
    };
  }
}
