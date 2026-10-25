package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.KataGoExperimentalBackendInstaller.Backend;
import featurecat.lizzie.util.KataGoExperimentalBackendInstaller.Status;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KataGoSetupRuntimeStateTest {
  @Test
  void windowsExperimentalPackagesStayDisabledOnOtherPlatforms() {
    for (String platform : new String[] {"Mac OS X", "Darwin", "Linux", "", "FreeBSD"}) {
      assertFalse(KataGoSetupRuntimeState.supportsExperimentalBackends(platform));
      assertFalse(state(false, false).canInstallExperimentalBackend(Backend.DIRECTML));
    }
    assertTrue(KataGoSetupRuntimeState.supportsExperimentalBackends("Windows 11"));
    assertTrue(state(true, false).canInstallExperimentalBackend(Backend.DIRECTML));
    assertFalse(state(true, true).canInstallExperimentalBackend(Backend.DIRECTML));
  }

  private static KataGoSetupRuntimeState state(boolean windows, boolean active) {
    return new KataGoSetupRuntimeState(
        null,
        null,
        Map.of(Backend.DIRECTML, new Status(Backend.DIRECTML, null, active, active)),
        0L,
        true,
        windows,
        "",
        false);
  }
}
