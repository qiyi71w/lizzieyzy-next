package featurecat.lizzie.enginegame;

import featurecat.lizzie.Config;
import featurecat.lizzie.analysis.KataGoRules;
import java.util.Objects;
import java.util.Optional;

/**
 * Last accepted match-rule selection, independent of the KataGo startup default. Prefill never
 * proves either participant's effective rules.
 */
public final class EngineGameMatchRulesSelection {
  public static final String CONFIG_KEY = "engine-game-match-rules";

  private EngineGameMatchRulesSelection() {}

  public static Optional<KataGoRules> stored(Config config) {
    String raw = rawStored(config);
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    return KataGoRules.parse(raw);
  }

  public static boolean storedIsCorrupt(Config config) {
    String raw = rawStored(config);
    return !raw.isEmpty() && KataGoRules.parse(raw).isEmpty();
  }

  public static KataGoRules prefill(Config config) {
    Optional<KataGoRules> stored = stored(config);
    if (stored.isPresent()) {
      return stored.get();
    }
    if (config != null && config.autoLoadKataRules) {
      KataGoRules startup = KataGoRules.parse(config.kataRules).orElse(null);
      if (startup != null) {
        return startup;
      }
    }
    return KataGoRules.parse("chinese").orElseThrow();
  }

  public static void persist(Config config, KataGoRules rules) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(rules, "rules");
    config.engineGameMatchRules = rules.toGtpArgument();
    if (config.uiConfig != null) {
      config.uiConfig.put(CONFIG_KEY, config.engineGameMatchRules);
    }
  }

  private static String rawStored(Config config) {
    if (config == null || config.engineGameMatchRules == null) {
      return "";
    }
    return config.engineGameMatchRules.trim();
  }
}
