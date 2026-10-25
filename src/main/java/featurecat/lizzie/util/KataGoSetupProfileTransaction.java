package featurecat.lizzie.util;

import featurecat.lizzie.Config;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.BiFunction;
import org.json.JSONObject;

/** Persists one setup change on the existing owner shared with benchmark-result commits. */
final class KataGoSetupProfileTransaction {
  record Selection(String name, int index) {}

  record Saved(EngineData entry, int index, String name, boolean created) {}

  private KataGoSetupProfileTransaction() {}

  // Commands and labels are prepared before entering this method; the selection callback only
  // inspects the current saved entries. Model reads and directory scans stay outside this owner.
  static Saved save(
      Config config,
      SetupSnapshot snapshot,
      String engineCommand,
      String analysisCommand,
      Path analysisConfig,
      boolean makeDefault,
      BiFunction<ArrayList<EngineData>, String, Selection> selection)
      throws IOException {
    synchronized (Utils.class) {
      ArrayList<EngineData> engines = Utils.getEngineData();
      // Use the same transaction owner as benchmark commits, from the first read through save.
      // First-run flags and defaults must be valid before saveEngineSettings normalizes them.
      boolean firstRunSetup =
          !config.uiConfig.has("autoload-default")
              && !config.uiConfig.has("autoload-empty")
              && !config.uiConfig.has("autoload-last");
      boolean selectAsDefault = makeDefault || firstRunSetup;
      Selection selected = selection.apply(engines, engineCommand);
      String resolvedEngineName = selected.name();
      int engineIndex = selected.index();
      EngineData engineData;
      boolean createdEngine = engineIndex < 0;
      if (engineIndex >= 0) {
        engineData = engines.get(engineIndex);
      } else {
        engineData = new EngineData();
        engines.add(engineData);
        engineIndex = engines.size() - 1;
      }
      // Capture ownership before our rewrite; user-edited commands must not regain it by name.
      boolean managedProfile =
          BundledKataGoProfile.isManaged(engineData)
              || BundledKataGoProfile.canMigrate(engineData, snapshot.appRoot)
              || (createdEngine
                  && (KataGoAutoSetupHelper.getAutoSetupEngineName().equals(resolvedEngineName)
                      || "KataGo Bundled".equals(resolvedEngineName)));
      for (int i = 0; i < engines.size(); i++) {
        EngineData existing = engines.get(i);
        existing.index = i;
        if (selectAsDefault) existing.isDefault = false;
      }
      engineData.index = engineIndex;
      if (createdEngine
          || !KataGoAutoSetupHelper.getAutoSetupEngineName().equals(resolvedEngineName)) {
        engineData.name = resolvedEngineName;
      }
      engineData.commands = engineCommand;
      if (managedProfile
          || BundledKataGoProfile.isDefaultCommand(engineCommand, snapshot.appRoot, false)) {
        BundledKataGoProfile.claim(engineData);
      }
      engineData.preload = createdEngine ? false : engineData.preload;
      engineData.width = createdEngine || engineData.width <= 0 ? 19 : engineData.width;
      engineData.height = createdEngine || engineData.height <= 0 ? 19 : engineData.height;
      engineData.komi = createdEngine || !Float.isFinite(engineData.komi) ? 7.5F : engineData.komi;
      engineData.isDefault = selectAsDefault || engineData.isDefault;
      engineData.useJavaSSH = false;
      engineData.ip = "";
      engineData.port = "";
      engineData.userName = "";
      engineData.password = "";
      engineData.useKeyGen = false;
      engineData.keyGenPath = "";
      engineData.initialCommand =
          createdEngine ? "" : (engineData.initialCommand == null ? "" : engineData.initialCommand);
      if (createdEngine) {
        engineData.threadPolicy =
            new JSONObject()
                .put("source", "CFG")
                .put("sourceRevision", 0L)
                .put("initialSetup", true);
      }
      JSONObject candidateUi = new JSONObject(config.uiConfig.toString());
      // Only force autoload=default on a truly fresh install. Once the user has picked
      // "start with no engine" or "pick manually", respect that choice across setup runs.
      if (firstRunSetup) {
        candidateUi.put("autoload-default", true);
        candidateUi.put("autoload-empty", false);
        candidateUi.put("autoload-last", false);
      }
      if (selectAsDefault) candidateUi.put("default-engine", engineIndex);
      candidateUi.put(
          "katago-preferred-weight-path",
          snapshot.activeWeightPath.toAbsolutePath().normalize().toString());
      if (!config.analysisEngineCommandCustomized) {
        candidateUi.put("analysis-engine-command", analysisCommand);
      }
      candidateUi.put(
          "katago-auto-setup-weight-path",
          snapshot.activeWeightPath.toAbsolutePath().normalize().toString());
      candidateUi.put(
          "katago-auto-setup-engine-path",
          snapshot.enginePath.toAbsolutePath().normalize().toString());
      candidateUi.put(
          "katago-auto-setup-gtp-config-path",
          snapshot.gtpConfigPath.toAbsolutePath().normalize().toString());
      candidateUi.put(
          "katago-auto-setup-analysis-config-path",
          analysisConfig.toAbsolutePath().normalize().toString());
      candidateUi.put("katago-auto-setup-updated-at", System.currentTimeMillis());
      try {
        Utils.saveEngineSettings(engines, candidateUi);
      } catch (UncheckedIOException failure) {
        throw failure.getCause();
      }
      if (!config.analysisEngineCommandCustomized) config.analysisEngineCommand = analysisCommand;
      return new Saved(engineData, engineIndex, resolvedEngineName, createdEngine);
    }
  }
}
