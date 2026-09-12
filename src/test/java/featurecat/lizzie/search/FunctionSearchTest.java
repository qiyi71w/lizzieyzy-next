package featurecat.lizzie.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class FunctionSearchTest {
  private static final FunctionCatalog.Entry ENTRY =
      new FunctionCatalog.Entry(
          "weights.download",
          "title",
          "description",
          "category",
          FunctionCatalog.TargetType.WINDOW,
          List.of("path"),
          "",
          "strong",
          "weak");

  @Test
  void catalogAndPathsCannotBeModifiedByConsumers() {
    List<FunctionCatalog.Entry> entries = FunctionCatalog.entries();
    assertThrows(UnsupportedOperationException.class, () -> entries.add(entries.get(0)));
    FunctionCatalog.Entry pathEntry =
        entries.stream().filter(entry -> !entry.pathKeys().isEmpty()).findFirst().orElseThrow();
    assertThrows(UnsupportedOperationException.class, () -> pathEntry.pathKeys().add("other"));
  }

  @Test
  void wholeGameBatchAndSyncTasksResolveToDistinctFunctions() {
    FunctionSearch search = new FunctionSearch();
    assertEquals("whole-game-deep-analysis", search.search("全盘深度分析", Locale.CHINESE).get(0).id());
    assertEquals("batch-analysis", search.search("批量分析", Locale.CHINESE).get(0).id());
    assertEquals("sync.board", search.search("同步棋盘", Locale.CHINESE).get(0).id());
    assertEquals("sync.settings", search.search("配置同步", Locale.CHINESE).get(0).id());
    assertEquals("engine.remote", search.search("远程算力", Locale.CHINESE).get(0).id());
    assertEquals("engine.select", search.search("引擎选择", Locale.CHINESE).get(0).id());
    assertEquals("files.recent", search.search("最近棋谱", Locale.CHINESE).get(0).id());
    assertEquals("tools.quick-start", search.search("快速启动", Locale.CHINESE).get(0).id());
  }

  @Test
  void distinctGameIntentAndSharedToolbarSurfaceRemainSearchable() {
    FunctionSearch search = new FunctionSearch();
    assertEquals("game.stop-human", search.search("终止人机对局", Locale.CHINA).getFirst().id());
    assertEquals("analysis.toggle", search.search("暂停分析", Locale.CHINA).getFirst().id());
    for (Locale locale :
        List.of(
            Locale.US,
            Locale.CHINA,
            Locale.TAIWAN,
            Locale.forLanguageTag("zh-HK"),
            Locale.JAPAN,
            Locale.KOREA,
            Locale.forLanguageTag("th-TH"))) {
      ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
      assertEquals(
          "toolbar.detailed",
          search.search(bundle.getString("Accessibility.toolbarDetails"), locale).getFirst().id());
      assertEquals(
          "game.stop-human",
          search.search(bundle.getString("Menu.breakGame"), locale).getFirst().id());
    }
  }

  @Test
  void saveAsAndWholeGameAnalysisAdvertiseTheirExistingKeyboardCommands() {
    assertEquals("S", FunctionCatalog.entry("files.save-as").shortcut());
    assertEquals("Ctrl+Shift+B", FunctionCatalog.entry("whole-game-deep-analysis").shortcut());
  }

  @Test
  void everyLocalizedDestinationCanBeRenderedAndFoundByItsRealTitle() {
    FunctionSearch search = new FunctionSearch();
    for (Locale locale :
        List.of(
            Locale.ROOT,
            Locale.US,
            Locale.CHINA,
            Locale.TAIWAN,
            Locale.forLanguageTag("zh-HK"),
            Locale.JAPAN,
            Locale.KOREA,
            Locale.forLanguageTag("th-TH"))) {
      ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
      for (FunctionCatalog.Entry entry : FunctionCatalog.entries()) {
        String title = bundle.getString(entry.titleKey());
        bundle.getString(entry.categoryKey());
        bundle.getString(entry.descriptionKey());
        for (String path : entry.pathKeys()) bundle.getString(path);
        List<FunctionSearch.Match> matches = search.search(title, locale);
        assertTrue(
            matches.stream().anyMatch(match -> entry.id().equals(match.id())),
            () -> locale + ": " + title + " must find " + entry.id());
      }
    }
  }

  @Test
  void nfkcWhitespaceAndAccentsAreHandledWithoutStrippingMeaningfulMarks() {
    FunctionSearch search =
        new FunctionSearch(
            List.of(ENTRY),
            List.of(
                bundle(
                    Locale.US,
                    Map.of(
                        "title", "Ｕｐｄａｔｅ\u00a0Ｗｅｉｇｈｔ",
                        "description", "Official café weights",
                        "category", "engine",
                        "path", "Settings",
                        "strong", "",
                        "weak", ""))));

    assertEquals(
        List.of(new FunctionSearch.Match("weights.download", FunctionSearch.MatchLevel.EXACT)),
        search.search(" update\u2003weight ", Locale.US));
    assertTrue(search.search("cafe", Locale.US).isEmpty());
    assertEquals(
        List.of(new FunctionSearch.Match("weights.download", FunctionSearch.MatchLevel.PHRASE)),
        search.search("café", Locale.US));
  }

  @Test
  void multilingualStrongAliasesBeatCurrentLocaleWeakAliases() {
    FunctionCatalog.Entry aliasEntry =
        new FunctionCatalog.Entry(
            "engine.acceleration",
            "title-a",
            "description-a",
            "category-a",
            FunctionCatalog.TargetType.WINDOW,
            List.of("path-a"),
            "",
            "strong-a",
            "weak-a");
    FunctionCatalog.Entry weakEntry =
        new FunctionCatalog.Entry(
            "settings.black-winrate",
            "title-b",
            "description-b",
            "category-b",
            FunctionCatalog.TargetType.SETTING,
            List.of("path-b"),
            "",
            "strong-b",
            "weak-b");
    FunctionSearch search =
        new FunctionSearch(
            List.of(aliasEntry, weakEntry),
            List.of(
                bundle(
                    Locale.US,
                    Map.of(
                        "title-a", "Turbo setup",
                        "strong-a", "",
                        "weak-a", "",
                        "title-b", "Black display",
                        "strong-b", "",
                        "weak-b", "gpu")),
                bundle(
                    Locale.CHINA,
                    Map.of(
                        "title-a", "显卡配置",
                        "strong-a", "显卡加速|gpu",
                        "weak-a", "",
                        "title-b", "黑方显示",
                        "strong-b", "",
                        "weak-b", "")),
                bundle(
                    Locale.ROOT,
                    Map.of(
                        "title-a", "Turbo setup",
                        "strong-a", "",
                        "weak-a", "",
                        "title-b", "Black display",
                        "strong-b", "",
                        "weak-b", ""))));

    assertEquals(
        List.of(
            new FunctionSearch.Match("engine.acceleration", FunctionSearch.MatchLevel.EXACT),
            new FunctionSearch.Match(
                "settings.black-winrate", FunctionSearch.MatchLevel.WEAK_ALIAS)),
        search.search("gpu", Locale.US));
    assertEquals(
        List.of(new FunctionSearch.Match("engine.acceleration", FunctionSearch.MatchLevel.EXACT)),
        search.search("显卡加速", Locale.US));
  }

  @Test
  void reverseAndCrossFieldWordsUseAndMatching() {
    FunctionSearch search =
        new FunctionSearch(
            List.of(ENTRY),
            List.of(
                bundle(
                    Locale.US,
                    Map.of(
                        "title", "Official weights",
                        "description", "Download models",
                        "category", "engine",
                        "path", "Settings",
                        "strong", "",
                        "weak", "")),
                bundle(
                    Locale.CHINA,
                    Map.of(
                        "title", "官方权重",
                        "description", "",
                        "category", "",
                        "path", "",
                        "strong", "",
                        "weak", ""))));

    FunctionSearch.Match expected =
        new FunctionSearch.Match("weights.download", FunctionSearch.MatchLevel.WORDS);
    assertEquals(List.of(expected), search.search("download weights", Locale.US));
    assertEquals(List.of(expected), search.search("weights download", Locale.US));
    assertTrue(search.search("download missing", Locale.US).isEmpty());
    assertEquals(List.of(expected), search.search("download 权重", Locale.US));
  }

  @Test
  void oneEditTypoIncludesTransposeButRespectsLengthBoundaries() {
    FunctionSearch search =
        new FunctionSearch(
            List.of(ENTRY),
            List.of(
                bundle(
                    Locale.US,
                    Map.of(
                        "title", "weights",
                        "description", "",
                        "category", "",
                        "path", "",
                        "strong", "",
                        "weak", ""))));

    assertEquals(
        List.of(new FunctionSearch.Match("weights.download", FunctionSearch.MatchLevel.TYPO)),
        search.search("weihgts", Locale.US));

    FunctionSearch deletionSearch =
        new FunctionSearch(
            List.of(ENTRY),
            List.of(
                bundle(
                    Locale.US,
                    Map.of(
                        "title", "weight",
                        "description", "",
                        "category", "",
                        "path", "",
                        "strong", "",
                        "weak", ""))));
    assertEquals(
        List.of(new FunctionSearch.Match("weights.download", FunctionSearch.MatchLevel.TYPO)),
        deletionSearch.search("wight", Locale.US));
    assertTrue(search.search("wet", Locale.US).isEmpty());
    assertTrue(search.search("wets", Locale.US).isEmpty());
    assertTrue(search.search("wigt", Locale.US).isEmpty());
  }

  @Test
  void duplicateStableIdsProduceOnlyTheBestSingleMatch() {
    FunctionSearch search =
        new FunctionSearch(
            List.of(ENTRY, ENTRY),
            List.of(
                bundle(
                    Locale.US,
                    Map.of(
                        "title", "Download weights",
                        "description", "",
                        "category", "",
                        "path", "",
                        "strong", "",
                        "weak", ""))));

    assertEquals(
        List.of(new FunctionSearch.Match("weights.download", FunctionSearch.MatchLevel.EXACT)),
        search.search("download weights", Locale.US));
  }

  @Test
  void emptyQueryBrowsesEveryCatalogEntryAtWordsLevel() {
    FunctionSearch search =
        new FunctionSearch(List.of(ENTRY), List.of(bundle(Locale.US, Map.of())));
    assertEquals(
        List.of(new FunctionSearch.Match("weights.download", FunctionSearch.MatchLevel.WORDS)),
        search.search("\u2003", Locale.US));
  }

  @Test
  void bestPhraseFieldOutranksDescriptionRegardlessOfFieldOrder() {
    List<FunctionCatalog.Entry> entries =
        List.of("a-description", "z-alias").stream()
            .map(
                id ->
                    new FunctionCatalog.Entry(
                        id,
                        id + ".title",
                        id + ".description",
                        "category",
                        FunctionCatalog.TargetType.WINDOW,
                        List.of(),
                        "",
                        id + ".strong",
                        "weak"))
            .toList();
    FunctionSearch search =
        new FunctionSearch(
            entries,
            List.of(
                bundle(
                    Locale.US,
                    Map.of(
                        "a-description.title", "Device configuration",
                        "a-description.description", "Configure gpu",
                        "z-alias.title", "Acceleration",
                        "z-alias.description", "Configure gpu",
                        "z-alias.strong", "gpu acceleration"))));
    assertEquals(
        List.of(
            new FunctionSearch.Match("z-alias", FunctionSearch.MatchLevel.PHRASE),
            new FunctionSearch.Match("a-description", FunctionSearch.MatchLevel.PHRASE)),
        search.search("gpu", Locale.US));
  }

  private static ResourceBundle bundle(Locale locale, Map<String, String> values) {
    return new MapBundle(locale, values);
  }

  private static final class MapBundle extends ResourceBundle {
    private final Locale locale;
    private final Map<String, String> values;

    private MapBundle(Locale locale, Map<String, String> values) {
      this.locale = locale;
      this.values = Map.copyOf(values);
    }

    @Override
    protected Object handleGetObject(String key) {
      return values.get(key);
    }

    @Override
    public Enumeration<String> getKeys() {
      return java.util.Collections.enumeration(values.keySet());
    }

    @Override
    public Locale getLocale() {
      return locale;
    }
  }
}
