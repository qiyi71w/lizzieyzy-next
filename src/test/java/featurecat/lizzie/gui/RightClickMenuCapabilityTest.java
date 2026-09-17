package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.analysis.Leelaz.MoveFocusCapability;
import org.junit.jupiter.api.Test;

class RightClickMenuCapabilityTest {
  @Test
  void onlyConfirmedUnsupportedEnginesShowUpgradeGuidance() {
    assertEquals(
        "RightClickMenu.trackPoint.upgradeRequired",
        RightClickMenu.trackingUnavailableKey(MoveFocusCapability.UNSUPPORTED, false));
    assertEquals(
        "RightClickMenu.trackPoint.upgradeTooltip",
        RightClickMenu.trackingUnavailableKey(MoveFocusCapability.UNSUPPORTED, true));
    for (MoveFocusCapability capability : MoveFocusCapability.values()) {
      if (capability == MoveFocusCapability.UNSUPPORTED) continue;
      assertEquals(
          "RightClickMenu.trackPoint.requiresCapability",
          RightClickMenu.trackingUnavailableKey(capability, false));
      assertEquals(
          "RightClickMenu.trackPoint.unavailableTooltip",
          RightClickMenu.trackingUnavailableKey(capability, true));
    }
  }
}
