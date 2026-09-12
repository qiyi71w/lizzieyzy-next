package featurecat.lizzie.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;

/** Offline, immutable function search over localized catalog text. */
public final class FunctionSearch {
  private static final String BUNDLE_NAME = "l10n.DisplayStrings";
  private static final List<Locale> MAINTAINED_LOCALES =
      List.of(
          Locale.ROOT,
          Locale.US,
          Locale.CHINA,
          Locale.TAIWAN,
          Locale.forLanguageTag("zh-HK"),
          Locale.JAPAN,
          Locale.KOREA,
          Locale.forLanguageTag("th-TH"));
  private static final ResourceBundle.Control NO_DEFAULT_FALLBACK =
      ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

  private final List<IndexedEntry> indexedEntries;

  /** Builds the production search index from the eight maintained property bundles. */
  public FunctionSearch() {
    this(FunctionCatalog.entries(), loadMaintainedBundles());
  }

  /**
   * Builds an immutable search index from caller-provided catalog data and bundles.
   *
   * <p>This constructor is also the seam used by tests. Resource text is read and normalized here;
   * the query path never loads resources or consults application state.
   */
  public FunctionSearch(List<FunctionCatalog.Entry> entries, List<ResourceBundle> bundles) {
    Objects.requireNonNull(entries, "entries");
    Objects.requireNonNull(bundles, "bundles");
    List<FunctionCatalog.Entry> entrySnapshot = List.copyOf(entries);
    List<ResourceBundle> bundleSnapshot = List.copyOf(bundles);
    indexedEntries = buildIndex(entrySnapshot, bundleSnapshot);
  }

  /** Searches all indexed functions, returning one best-ranked match per stable ID. */
  public List<Match> search(String query, Locale effectiveLocale) {
    Query normalizedQuery = Query.of(query);
    Locale locale = effectiveLocale == null ? Locale.ROOT : effectiveLocale;
    Map<String, ScoredMatch> bestById = new HashMap<>();

    for (IndexedEntry indexedEntry : indexedEntries) {
      for (LocalizedFields localized : indexedEntry.localizations()) {
        ScoredMatch candidate =
            score(indexedEntry.entry().id(), localized, normalizedQuery, locale);
        if (candidate == null) {
          continue;
        }
        ScoredMatch previous = bestById.get(candidate.id());
        if (previous == null || compareScores(candidate, previous) < 0) {
          bestById.put(candidate.id(), candidate);
        }
      }
      ScoredMatch crossLocale = scoreAcrossLocales(indexedEntry, normalizedQuery, locale);
      if (crossLocale != null) {
        ScoredMatch previous = bestById.get(crossLocale.id());
        if (previous == null || compareScores(crossLocale, previous) < 0) {
          bestById.put(crossLocale.id(), crossLocale);
        }
      }
    }

    if (normalizedQuery.empty()) {
      // Empty search is category browsing: every catalog entry is a WORDS result.
      for (IndexedEntry indexedEntry : indexedEntries) {
        bestById.putIfAbsent(
            indexedEntry.entry().id(),
            new ScoredMatch(
                indexedEntry.entry().id(),
                MatchLevel.WORDS,
                0,
                0,
                localeDistance(Locale.ROOT, locale)));
      }
    }

    List<ScoredMatch> sorted = new ArrayList<>(bestById.values());
    sorted.sort(FunctionSearch::compareScores);
    List<Match> matches = new ArrayList<>(sorted.size());
    for (ScoredMatch candidate : sorted) {
      matches.add(new Match(candidate.id(), candidate.level()));
    }
    return List.copyOf(matches);
  }

  private static List<IndexedEntry> buildIndex(
      List<FunctionCatalog.Entry> entries, List<ResourceBundle> bundles) {
    List<IndexedEntry> result = new ArrayList<>(entries.size());
    for (FunctionCatalog.Entry entry : entries) {
      List<LocalizedFields> localizations = new ArrayList<>(bundles.size());
      for (ResourceBundle bundle : bundles) {
        localizations.add(index(entry, bundle));
      }
      List<Field> strong = new ArrayList<>();
      List<Field> weak = new ArrayList<>();
      for (LocalizedFields localized : localizations) {
        strong.addAll(localized.strongFields());
        weak.addAll(localized.weakFields());
      }
      result.add(
          new IndexedEntry(
              entry, List.copyOf(localizations), new LocalizedFields(Locale.ROOT, strong, weak)));
    }
    return List.copyOf(result);
  }

  private static LocalizedFields index(FunctionCatalog.Entry entry, ResourceBundle bundle) {
    String title = resource(bundle, entry.titleKey(), entry.id());
    List<Field> strong = new ArrayList<>();
    strong.add(field(FieldKind.TITLE, title));
    strong.add(field(FieldKind.DESCRIPTION, resource(bundle, entry.descriptionKey(), "")));
    strong.add(field(FieldKind.CATEGORY, resource(bundle, entry.categoryKey(), "")));
    for (String pathKey : entry.pathKeys()) {
      strong.add(field(FieldKind.PATH, resource(bundle, pathKey, "")));
    }
    if (!entry.shortcut().isBlank()) {
      strong.add(field(FieldKind.SHORTCUT, entry.shortcut()));
    }

    List<String> strongAliases = aliases(resource(bundle, entry.strongAliasesKey(), ""));
    if (strongAliases.isEmpty() && !title.isBlank()) {
      // A missing/blank alias value still leaves the localized title searchable.
      strongAliases = List.of(title);
    }
    for (String alias : strongAliases) {
      strong.add(field(FieldKind.STRONG_ALIAS, alias));
    }

    List<Field> weak = new ArrayList<>();
    for (String alias : aliases(resource(bundle, entry.weakAliasesKey(), ""))) {
      weak.add(field(FieldKind.WEAK_ALIAS, alias));
    }
    return new LocalizedFields(bundle.getLocale(), List.copyOf(strong), List.copyOf(weak));
  }

  private static String resource(ResourceBundle bundle, String key, String fallback) {
    if (key == null || key.isBlank()) {
      return fallback;
    }
    try {
      return bundle.containsKey(key) ? bundle.getString(key) : fallback;
    } catch (MissingResourceException | ClassCastException exception) {
      return fallback;
    }
  }

  private static List<String> aliases(String value) {
    String[] parts = value == null ? new String[0] : value.split("\\|", -1);
    List<String> result = new ArrayList<>(parts.length);
    for (String part : parts) {
      String normalized = normalize(part);
      if (!normalized.isEmpty() && !result.contains(normalized)) {
        result.add(normalized);
      }
    }
    return List.copyOf(result);
  }

  private static Field field(FieldKind kind, String text) {
    String normalized = normalize(text);
    return new Field(kind, normalized, latinWords(normalized));
  }

  /**
   * Normalizes only for matching: compatibility form, root-locale case folding, and Unicode
   * whitespace collapse. In particular, accents and other meaningful marks are retained.
   */
  private static String normalize(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    String nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    StringBuilder collapsed = new StringBuilder(nfkc.length());
    boolean pendingSpace = false;
    for (int offset = 0; offset < nfkc.length(); ) {
      int codePoint = nfkc.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (Character.isWhitespace(codePoint)
          || Character.isSpaceChar(codePoint)
          || codePoint == 0x0085) {
        pendingSpace = collapsed.length() > 0;
      } else {
        if (pendingSpace) {
          collapsed.append(' ');
          pendingSpace = false;
        }
        collapsed.appendCodePoint(codePoint);
      }
    }
    return collapsed.toString();
  }

  private static List<String> latinWords(String normalized) {
    if (normalized.isEmpty()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    int start = -1;
    for (int index = 0; index < normalized.length(); index++) {
      char character = normalized.charAt(index);
      boolean letter = Character.isLetter(character);
      if (letter && start < 0) {
        start = index;
      } else if (!letter && start >= 0) {
        String candidate = normalized.substring(start, index);
        if (isAsciiLatinWord(candidate)) {
          result.add(candidate);
        }
        start = -1;
      }
    }
    if (start >= 0) {
      String candidate = normalized.substring(start);
      if (isAsciiLatinWord(candidate)) {
        result.add(candidate);
      }
    }
    return List.copyOf(result);
  }

  private static ScoredMatch scoreAcrossLocales(
      IndexedEntry indexedEntry, Query query, Locale effectiveLocale) {
    String id = indexedEntry.entry().id();
    List<LocalizedFields> localizations = indexedEntry.localizations();
    if (query.empty() || localizations.size() < 2) {
      return null;
    }
    LocalizedFields combined = indexedEntry.combined();
    List<Field> strong = combined.strongFields();
    List<Field> weak = combined.weakFields();
    if (allTokensMatch(query.words(), strong)) {
      ScoredMatch base =
          scored(
              id,
              MatchLevel.WORDS,
              query,
              combined,
              bestTokenField(query.words(), strong),
              false,
              effectiveLocale);
      return withAggregateLocaleDistance(base, localizations, query, false, false, effectiveLocale);
    }

    List<Field> allFields = combined.allFields();
    if (allTokensMatch(query.words(), allFields)) {
      ScoredMatch base =
          scored(
              id,
              MatchLevel.WEAK_ALIAS,
              query,
              combined,
              bestTokenField(query.words(), weak),
              true,
              effectiveLocale);
      return withAggregateLocaleDistance(base, localizations, query, true, false, effectiveLocale);
    }

    if (allTokensMatchWithOneTypo(query.words(), strong)) {
      ScoredMatch base =
          scored(
              id,
              MatchLevel.TYPO,
              query,
              combined,
              bestTypoField(query.words(), strong),
              false,
              effectiveLocale);
      return withAggregateLocaleDistance(base, localizations, query, false, true, effectiveLocale);
    }
    return null;
  }

  private static ScoredMatch withAggregateLocaleDistance(
      ScoredMatch base,
      List<LocalizedFields> localizations,
      Query query,
      boolean includeWeak,
      boolean allowTypo,
      Locale effectiveLocale) {
    return new ScoredMatch(
        base.id(),
        base.level(),
        base.coverage(),
        base.fieldQuality(),
        aggregateLocaleDistance(localizations, query, includeWeak, allowTypo, effectiveLocale));
  }

  private static int aggregateLocaleDistance(
      List<LocalizedFields> localizations,
      Query query,
      boolean includeWeak,
      boolean allowTypo,
      Locale effectiveLocale) {
    int worstDistance = 0;
    for (String queryWord : query.words()) {
      int bestDistance = Integer.MAX_VALUE;
      for (LocalizedFields localized : localizations) {
        List<Field> fields = includeWeak ? localized.allFields() : localized.strongFields();
        for (Field field : fields) {
          boolean matches = field.text().contains(queryWord);
          if (!matches && allowTypo) {
            for (String targetWord : field.latinWords()) {
              if (oneEditAway(queryWord, targetWord)) {
                matches = true;
                break;
              }
            }
          }
          if (matches) {
            bestDistance =
                Math.min(bestDistance, localeDistance(localized.locale(), effectiveLocale));
          }
        }
      }
      if (bestDistance != Integer.MAX_VALUE) {
        worstDistance = Math.max(worstDistance, bestDistance);
      }
    }
    return worstDistance;
  }

  private static ScoredMatch score(
      String id, LocalizedFields localized, Query query, Locale effectiveLocale) {
    if (query.empty()) {
      return new ScoredMatch(
          id, MatchLevel.WORDS, 0, 0, localeDistance(localized.locale(), effectiveLocale));
    }

    Field exact = exactTitleOrAlias(localized.strongFields(), query.normalized());
    if (exact != null) {
      return scored(id, MatchLevel.EXACT, query, localized, exact, false, effectiveLocale);
    }

    Field phrase = phraseField(localized.strongFields(), query.normalized());
    if (phrase != null) {
      return scored(id, MatchLevel.PHRASE, query, localized, phrase, false, effectiveLocale);
    }

    if (allTokensMatch(query.words(), localized.strongFields())) {
      return scored(
          id,
          MatchLevel.WORDS,
          query,
          localized,
          bestTokenField(query.words(), localized.strongFields()),
          false,
          effectiveLocale);
    }

    List<Field> allFields = localized.allFields();
    if (allTokensMatch(query.words(), allFields)) {
      return scored(
          id,
          MatchLevel.WEAK_ALIAS,
          query,
          localized,
          bestTokenField(query.words(), localized.weakFields()),
          true,
          effectiveLocale);
    }

    if (allTokensMatchWithOneTypo(query.words(), localized.strongFields())) {
      return scored(
          id,
          MatchLevel.TYPO,
          query,
          localized,
          bestTypoField(query.words(), localized.strongFields()),
          false,
          effectiveLocale);
    }
    return null;
  }

  private static ScoredMatch scored(
      String id,
      MatchLevel level,
      Query query,
      LocalizedFields localized,
      Field primaryField,
      boolean includeWeak,
      Locale effectiveLocale) {
    int coverage = coverage(query, localized, includeWeak, level == MatchLevel.TYPO);
    int quality = primaryField == null ? 0 : fieldQuality(primaryField.kind());
    return new ScoredMatch(
        id, level, coverage, quality, localeDistance(localized.locale(), effectiveLocale));
  }

  private static Field exactTitleOrAlias(List<Field> fields, String query) {
    for (Field field : fields) {
      if ((field.kind() == FieldKind.TITLE || field.kind() == FieldKind.STRONG_ALIAS)
          && field.text().equals(query)) {
        return field;
      }
    }
    return null;
  }

  private static Field phraseField(List<Field> fields, String query) {
    Field best = null;
    for (Field field : fields) {
      if (!field.text().isEmpty()
          && field.text().contains(query)
          && (best == null || fieldQuality(field.kind()) > fieldQuality(best.kind()))) {
        best = field;
      }
    }
    return best;
  }

  private static boolean allTokensMatch(List<String> queryWords, List<Field> fields) {
    for (String queryWord : queryWords) {
      boolean matched = false;
      for (Field field : fields) {
        if (field.text().contains(queryWord)) {
          matched = true;
          break;
        }
      }
      if (!matched) {
        return false;
      }
    }
    return true;
  }

  private static boolean allTokensMatchWithOneTypo(List<String> queryWords, List<Field> fields) {
    boolean typoUsed = false;
    for (String queryWord : queryWords) {
      boolean exact = false;
      boolean typo = false;
      for (Field field : fields) {
        if (field.text().contains(queryWord)) {
          exact = true;
          break;
        }
        for (String targetWord : field.latinWords()) {
          if (oneEditAway(queryWord, targetWord)) {
            typo = true;
            break;
          }
        }
        if (typo) {
          break;
        }
      }
      if (exact) {
        continue;
      }
      if (!typo) {
        return false;
      }
      typoUsed = true;
    }
    return typoUsed;
  }

  private static Field bestTokenField(List<String> queryWords, List<Field> fields) {
    Field best = null;
    int bestQuality = -1;
    for (Field field : fields) {
      for (String queryWord : queryWords) {
        if (field.text().contains(queryWord)) {
          int quality = fieldQuality(field.kind());
          if (quality > bestQuality) {
            best = field;
            bestQuality = quality;
          }
          break;
        }
      }
    }
    return best;
  }

  private static Field bestTypoField(List<String> queryWords, List<Field> fields) {
    Field best = null;
    int bestQuality = -1;
    for (Field field : fields) {
      boolean matched = false;
      for (String queryWord : queryWords) {
        if (field.text().contains(queryWord)) {
          matched = true;
          break;
        }
        for (String targetWord : field.latinWords()) {
          if (oneEditAway(queryWord, targetWord)) {
            matched = true;
            break;
          }
        }
        if (matched) {
          break;
        }
      }
      if (matched && fieldQuality(field.kind()) > bestQuality) {
        best = field;
        bestQuality = fieldQuality(field.kind());
      }
    }
    return best;
  }

  /** Coverage is the total number of query-token characters covered by indexed fields. */
  private static int coverage(
      Query query, LocalizedFields localized, boolean includeWeak, boolean allowTypo) {
    List<Field> fields = includeWeak ? localized.allFields() : localized.strongFields();
    int result = 0;
    for (String queryWord : query.words()) {
      int best = 0;
      for (Field field : fields) {
        if (field.text().contains(queryWord)) {
          best = Math.max(best, queryWord.codePointCount(0, queryWord.length()));
        } else if (allowTypo) {
          for (String targetWord : field.latinWords()) {
            if (oneEditAway(queryWord, targetWord)) {
              best = Math.max(best, Math.max(0, queryWord.length() - 1));
              break;
            }
          }
        }
      }
      result += best;
    }
    return result;
  }

  private static int fieldQuality(FieldKind kind) {
    return switch (kind) {
      case TITLE, STRONG_ALIAS -> 4;
      case DESCRIPTION -> 3;
      case PATH -> 2;
      case CATEGORY, SHORTCUT, WEAK_ALIAS -> 1;
    };
  }

  private static boolean oneEditAway(String query, String target) {
    if (!isAsciiLatinWord(query)
        || !isAsciiLatinWord(target)
        || query.length() < 4
        || Math.abs(query.length() - target.length()) > 1
        || query.equals(target)) {
      return false;
    }

    if (query.length() == target.length()) {
      int firstDifference = -1;
      int differenceCount = 0;
      for (int index = 0; index < query.length(); index++) {
        if (query.charAt(index) != target.charAt(index)) {
          if (firstDifference < 0) {
            firstDifference = index;
          }
          differenceCount++;
        }
      }
      if (differenceCount == 1) {
        return true;
      }
      return differenceCount == 2
          && firstDifference + 1 < query.length()
          && query.charAt(firstDifference) == target.charAt(firstDifference + 1)
          && query.charAt(firstDifference + 1) == target.charAt(firstDifference)
          && (firstDifference + 2 >= query.length()
              || query
                  .substring(firstDifference + 2)
                  .equals(target.substring(firstDifference + 2)));
    }

    String longer = query.length() > target.length() ? query : target;
    String shorter = query.length() > target.length() ? target : query;
    int longIndex = 0;
    int shortIndex = 0;
    boolean skipped = false;
    while (longIndex < longer.length() && shortIndex < shorter.length()) {
      if (longer.charAt(longIndex) == shorter.charAt(shortIndex)) {
        longIndex++;
        shortIndex++;
      } else if (skipped) {
        return false;
      } else {
        skipped = true;
        longIndex++;
      }
    }
    return true;
  }

  private static boolean isAsciiLatinWord(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 'a' || character > 'z') {
        return false;
      }
    }
    return true;
  }

  private static int localeDistance(Locale candidate, Locale effective) {
    if (candidate.equals(effective)) {
      return 0;
    }
    if (!candidate.getLanguage().isEmpty()
        && candidate.getLanguage().equalsIgnoreCase(effective.getLanguage())) {
      return 1;
    }
    if (candidate.getLanguage().isEmpty()) {
      return 2;
    }
    return 3;
  }

  private static int compareScores(ScoredMatch left, ScoredMatch right) {
    int comparison = Integer.compare(left.level().ordinal(), right.level().ordinal());
    if (comparison != 0) {
      return comparison;
    }
    comparison = Integer.compare(right.coverage(), left.coverage());
    if (comparison != 0) {
      return comparison;
    }
    comparison = Integer.compare(right.fieldQuality(), left.fieldQuality());
    if (comparison != 0) {
      return comparison;
    }
    comparison = Integer.compare(left.localeDistance(), right.localeDistance());
    if (comparison != 0) {
      return comparison;
    }
    return left.id().compareTo(right.id());
  }

  private static List<ResourceBundle> loadMaintainedBundles() {
    ClassLoader loader = FunctionSearch.class.getClassLoader();
    List<ResourceBundle> result = new ArrayList<>(MAINTAINED_LOCALES.size());
    for (Locale locale : MAINTAINED_LOCALES) {
      try {
        result.add(ResourceBundle.getBundle(BUNDLE_NAME, locale, loader, NO_DEFAULT_FALLBACK));
      } catch (MissingResourceException exception) {
        throw new IllegalStateException(
            "Missing maintained localization bundle for " + locale, exception);
      }
    }
    return List.copyOf(result);
  }

  private enum FieldKind {
    TITLE,
    DESCRIPTION,
    CATEGORY,
    PATH,
    SHORTCUT,
    STRONG_ALIAS,
    WEAK_ALIAS
  }

  private record Field(FieldKind kind, String text, List<String> latinWords) {}

  private record LocalizedFields(
      Locale locale, List<Field> strongFields, List<Field> weakFields, List<Field> allFields) {
    LocalizedFields(Locale locale, List<Field> strong, List<Field> weak) {
      this(locale, List.copyOf(strong), List.copyOf(weak), joinFields(strong, weak));
    }
  }

  private static List<Field> joinFields(List<Field> strong, List<Field> weak) {
    if (weak.isEmpty()) return List.copyOf(strong);
    List<Field> combined = new ArrayList<>(strong.size() + weak.size());
    combined.addAll(strong);
    combined.addAll(weak);
    return List.copyOf(combined);
  }

  private record IndexedEntry(
      FunctionCatalog.Entry entry, List<LocalizedFields> localizations, LocalizedFields combined) {}

  private record ScoredMatch(
      String id, MatchLevel level, int coverage, int fieldQuality, int localeDistance) {}

  private record Query(String normalized, List<String> words) {
    static Query of(String query) {
      String normalized = normalize(query == null ? "" : query);
      if (normalized.isEmpty()) {
        return new Query("", List.of());
      }
      return new Query(normalized, List.copyOf(Arrays.asList(normalized.split(" "))));
    }

    boolean empty() {
      return normalized.isEmpty();
    }
  }

  public enum MatchLevel {
    EXACT,
    PHRASE,
    WORDS,
    WEAK_ALIAS,
    TYPO
  }

  public record Match(String id, MatchLevel level) {
    public Match {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(level, "level");
    }
  }
}
