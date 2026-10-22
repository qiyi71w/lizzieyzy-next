package featurecat.lizzie.enginegame;

import featurecat.lizzie.Config;
import java.util.Objects;

/**
 * Opening analysis parameters of the two engine-game participant positions ("Engine 1" and "Engine
 * 2"). A batch freezes them and applies them at the start of every game. They belong to positions,
 * not to engine profiles or colors; zero breadth turns the parameter off.
 */
public record EngineGameOpeningParameters(double firstWrn, double secondWrn) {
  public static final double MAX_WRN = 2.0;
  public static final EngineGameOpeningParameters NEUTRAL = new EngineGameOpeningParameters(0, 0);
  static final String FIRST_WRN_KEY = "engine-game-first-wrn";
  static final String SECOND_WRN_KEY = "engine-game-second-wrn";

  public EngineGameOpeningParameters {
    if (!validWrn(firstWrn) || !validWrn(secondWrn)) {
      throw new IllegalArgumentException("Engine-game breadth must be between 0 and " + MAX_WRN);
    }
  }

  public double wrn(boolean firstPosition) {
    return firstPosition ? firstWrn : secondWrn;
  }

  public static boolean validWrn(double value) {
    return Double.isFinite(value) && value >= 0 && value <= MAX_WRN;
  }

  /** Parses user input; null for blank, partial, non-finite or out-of-range text. */
  public static Double parseWrn(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      double value = Double.parseDouble(text.trim());
      return validWrn(value) ? value : null;
    } catch (NumberFormatException invalid) {
      return null;
    }
  }

  /** The last saved opening settings. Missing or invalid values, as in older configs, are zero. */
  public static EngineGameOpeningParameters stored(Config config) {
    if (config == null || config.uiConfig == null) {
      return NEUTRAL;
    }
    return new EngineGameOpeningParameters(
        storedWrn(config, FIRST_WRN_KEY), storedWrn(config, SECOND_WRN_KEY));
  }

  public static void persist(Config config, EngineGameOpeningParameters parameters) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(parameters, "parameters");
    if (config.uiConfig != null) {
      config.uiConfig.put(FIRST_WRN_KEY, parameters.firstWrn());
      config.uiConfig.put(SECOND_WRN_KEY, parameters.secondWrn());
    }
  }

  private static double storedWrn(Config config, String key) {
    double value = config.uiConfig.optDouble(key, 0);
    return validWrn(value) ? value : 0;
  }
}
