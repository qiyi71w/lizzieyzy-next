package featurecat.lizzie.search;

import java.util.List;
import java.util.Objects;

/** Immutable metadata for the functions exposed by offline search. */
public final class FunctionCatalog {
  private static final List<Entry> ENTRIES =
      List.of(
          new Entry(
              "weights.download",
              "BottomToolbar.downloadWeight",
              "FunctionSearch.description.weights",
              "FunctionSearch.category.engine",
              TargetType.WINDOW,
              List.of(
                  "Menu.settings",
                  "Menu.autoSetup",
                  "AutoSetup.navWeights",
                  "AutoSetup.officialWeightTab"),
              "",
              "FunctionSearch.aliases.weights",
              "FunctionSearch.weakAliases.weights"),
          new Entry(
              "engine.acceleration",
              "AutoSetup.accelerationTitle",
              "FunctionSearch.description.acceleration",
              "FunctionSearch.category.engine",
              TargetType.WINDOW,
              List.of("Menu.settings", "Menu.autoSetup", "AutoSetup.navAcceleration"),
              "",
              "FunctionSearch.aliases.acceleration",
              "FunctionSearch.weakAliases.acceleration"),
          new Entry(
              "settings.black-winrate",
              "Menu.alwaysShowBlackWinrate",
              "FunctionSearch.description.blackWinrate",
              "FunctionSearch.category.settings",
              TargetType.SETTING,
              List.of(
                  "Menu.settings", "Menu.comprehensiveSettings", "ConfigDialog2.modern.nav.engine"),
              "",
              "FunctionSearch.aliases.blackWinrate",
              "FunctionSearch.weakAliases.blackWinrate"));

  private FunctionCatalog() {}

  /** Returns the immutable production catalog. */
  public static List<Entry> entries() {
    return ENTRIES;
  }

  /** The kind of destination owned by a catalog entry. */
  public enum TargetType {
    WINDOW,
    SETTING
  }

  /** Stable function metadata; all collection state is defensively immutable. */
  public record Entry(
      String id,
      String titleKey,
      String descriptionKey,
      String categoryKey,
      TargetType targetType,
      List<String> pathKeys,
      String shortcut,
      String strongAliasesKey,
      String weakAliasesKey) {
    public Entry {
      id = requireText(id, "id");
      titleKey = requireText(titleKey, "titleKey");
      descriptionKey = requireText(descriptionKey, "descriptionKey");
      categoryKey = requireText(categoryKey, "categoryKey");
      targetType = Objects.requireNonNull(targetType, "targetType");
      pathKeys = pathKeys == null ? List.of() : List.copyOf(pathKeys);
      shortcut = shortcut == null ? "" : shortcut;
      strongAliasesKey = strongAliasesKey == null ? "" : strongAliasesKey;
      weakAliasesKey = weakAliasesKey == null ? "" : weakAliasesKey;
    }

    private static String requireText(String value, String field) {
      return Objects.requireNonNull(value, field);
    }
  }
}
