package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.IOException;

/** Inventory and lifecycle adapter for the current auxiliary analysis engine. */
public final class LizzieAiPositionEngineHost
    implements AnalysisResourceCoordinator.SinglePositionEngineHost {

  @Override
  public boolean hasIdlePreloaded() {
    AnalysisEngine engine = currentEngine();
    return engine != null
        && engine.isLoaded()
        && !engine.usesSharedForegroundEngine()
        && !engine.isAnalysisInProgress();
  }

  @Override
  public boolean isAnalysisInProgress() {
    AnalysisEngine engine = currentEngine();
    return engine != null && engine.isAnalysisInProgress();
  }

  @Override
  public boolean isAutomaticBackgroundTask() {
    AnalysisEngine engine = currentEngine();
    return engine != null && engine.isAutomaticBackgroundTask();
  }

  @Override
  public boolean hasConfiguredKatago() {
    return Lizzie.config != null
        && Lizzie.config.analysisEngineCommand != null
        && !Lizzie.config.analysisEngineCommand.trim().isEmpty();
  }

  @Override
  public boolean wouldReuseNonKatagoForeground() {
    return Lizzie.config != null
        && Lizzie.config.analysisReuseCurrentEngine
        && (Lizzie.leelaz == null || !Lizzie.leelaz.isKatago);
  }

  @Override
  public AnalysisEngine borrowIdle() {
    return currentEngine();
  }

  @Override
  public AnalysisEngine preemptAutomaticAndStart() {
    AnalysisEngine current = currentEngine();
    if (current != null) {
      current.clearRequestCallbacks();
      current.normalQuit();
      assignEngine(null);
    }
    return lazyStart();
  }

  @Override
  public AnalysisEngine lazyStart() {
    try {
      AnalysisEngine engine = AnalysisEngine.createManagedAiPositionEngine();
      if (engine.isLoaded()) {
        assignEngine(engine);
        return engine;
      }
      engine.normalQuit();
    } catch (IOException ignored) {
    }
    return null;
  }

  @Override
  public void abandon(AnalysisEngine engine) {
    if (currentEngine() == engine) {
      assignEngine(null);
    }
  }

  @Override
  public boolean shouldKeepAlive(AnalysisEngine engine, boolean ownsProcess) {
    if (!ownsProcess) {
      return true;
    }
    if (Lizzie.config == null) {
      return false;
    }
    return Lizzie.config.analysisEnginePreLoad || !Lizzie.config.analysisAutoQuit;
  }

  private static AnalysisEngine currentEngine() {
    LizzieFrame frame = Lizzie.frame;
    return frame == null ? null : frame.analysisEngine;
  }

  private static void assignEngine(AnalysisEngine engine) {
    if (Lizzie.frame != null) {
      Lizzie.frame.analysisEngine = engine;
    }
  }
}
