package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Lizzie;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class CommentDisplayRendererTest {
  @Test
  void rendersOrdinaryCommentAsEscapedPlainText() {
    String html = CommentDisplayRenderer.render("Line 1  aligned\n<script>bad()</script> & text");

    assertTrue(html.contains("Line 1 &nbsp;aligned"));
    assertTrue(html.contains("&lt;script&gt;bad()&lt;/script&gt; &amp; text"));
    assertFalse(html.contains("<script>"));
  }

  @Test
  void hidesStorageMetadataAndSafelyRendersAiMarkdown() {
    String stored =
        TeacherCommentCodec.upsert(
            "User <comment>\n\nSecond line", "# Review\n- **Good** move\n`D4`", "test-model");

    String html = CommentDisplayRenderer.render(stored);

    assertTrue(html.contains("User &lt;comment&gt;"));
    assertTrue(html.contains("<h1>Review</h1>"));
    assertTrue(html.contains("<ul><li><strong>Good</strong> move</li></ul>"));
    assertTrue(html.contains("<code>D4</code>"));
    assertFalse(html.contains("LizzieYzy AI Commentary BEGIN"));
    assertFalse(html.contains("generatedAt="));
    assertFalse(html.contains("model=test-model"));
  }

  @Test
  void preservesEmptyLinesInPlainComments() {
    String html = CommentDisplayRenderer.render("first\n\nlast");

    assertTrue(html.contains("<div>first</div><div>&nbsp;</div><div>last</div>"));
  }

  @Test
  void preservesUserWhitespaceAroundStoredAiCommentary() {
    String stored = TeacherCommentCodec.upsert("User line  \n", "# Review", "test");

    String html = CommentDisplayRenderer.render(stored);

    assertTrue(html.contains("<div>User line &nbsp;</div><div>&nbsp;</div>"));
    assertTrue(html.contains("<h1>Review</h1>"));
  }

  @Test
  void rendersMarkdownTablesWithoutAllowingEmbeddedHtml() {
    String stored =
        TeacherCommentCodec.upsert(
            "",
            "| Move | Review |\n| --- | --- |\n| D4 | **Good** & <img src=x> |",
            "test-model");

    String html = CommentDisplayRenderer.render(stored);

    assertTrue(html.contains("<table><thead><tr><th>Move</th><th>Review</th></tr></thead>"));
    assertTrue(html.contains("<td>D4</td><td><strong>Good</strong> &amp; &lt;img src=x&gt;</td>"));
    assertFalse(html.contains("<img"));
  }

  @Test
  void separatesEnglishPersonalCommentaryFromMatchInfoWithDivider() {
    String html =
        renderWithBundle(
            AppLocale.ENGLISH.loadBundle(),
            "Looks like a fight.\n\n" + ENGLISH_KATA_MATCH_INFO);

    int personalAt = html.indexOf("Looks like a fight.");
    int dividerAt = html.indexOf("match-info-divider");
    int winRateAt = html.indexOf("win rate");
    assertTrue(personalAt >= 0);
    assertTrue(dividerAt > personalAt);
    assertTrue(winRateAt > dividerAt);
    assertTrue(html.contains("lead:"));
    assertTrue(html.contains("visits"));
    assertTrue(html.contains("komi:"));
  }

  @Test
  void separatesChinesePersonalCommentaryFromMatchInfoWithDivider() {
    String html =
        renderWithBundle(
            AppLocale.SIMPLIFIED_CHINESE.loadBundle(),
            "这手可以。\n\n" + CHINESE_KATA_MATCH_INFO);

    int personalAt = html.indexOf("这手可以。");
    int dividerAt = html.indexOf("match-info-divider");
    int winRateAt = html.indexOf("胜率");
    assertTrue(personalAt >= 0);
    assertTrue(dividerAt > personalAt);
    assertTrue(winRateAt > dividerAt);
    assertTrue(html.contains("领先"));
    assertTrue(html.contains("计算量"));
    assertTrue(html.contains("贴目"));
  }

  @Test
  void rendersMatchInfoWithoutDividerWhenThereIsNoPersonalCommentary() {
    String html = renderWithBundle(AppLocale.ENGLISH.loadBundle(), ENGLISH_KATA_MATCH_INFO);

    assertTrue(html.contains("win rate"));
    assertTrue(html.contains("lead:"));
    assertTrue(html.contains("visits"));
    assertTrue(html.contains("komi:"));
    assertFalse(html.contains("match-info-divider"));
  }

  @Test
  void keepsAiCommentaryAfterPersonalCommentaryAndMatchInfo() {
    String stored =
        TeacherCommentCodec.upsert(
            "My note\n\n" + ENGLISH_KATA_MATCH_INFO, "# Review", "test-model");

    String html = renderWithBundle(AppLocale.ENGLISH.loadBundle(), stored);

    int personalAt = html.indexOf("My note");
    int dividerAt = html.indexOf("match-info-divider");
    int winRateAt = html.indexOf("win rate");
    int aiAt = html.indexOf("ai-commentary");
    int reviewAt = html.indexOf("<h1>Review</h1>");
    assertTrue(personalAt >= 0);
    assertTrue(dividerAt > personalAt);
    assertTrue(winRateAt > dividerAt);
    assertTrue(aiAt > winRateAt);
    assertTrue(reviewAt > aiAt);
    assertFalse(html.contains("LizzieYzy AI Commentary BEGIN"));
  }

  @Test
  void keepsMixedCommentUnsplitWhenMatchInfoSeparationIsDisabled() {
    String mixed = "Looks like a fight.\n\n" + ENGLISH_KATA_MATCH_INFO;
    String html = renderWithBundle(AppLocale.ENGLISH.loadBundle(), mixed, false);

    assertTrue(html.contains("Looks like a fight."));
    assertTrue(html.contains("win rate"));
    assertFalse(html.contains("match-info-divider"));
  }

  private static final String ENGLISH_KATA_MATCH_INFO =
      "White win rate: 38.9% (-22.2%)\n"
          + "lead: -8.9 (-2.8) stdev: 13.4\n"
          + "(Transformer11BFlagship / 4.5k visits)\n"
          + "komi: 7.5";

  private static final String CHINESE_KATA_MATCH_INFO =
      "白棋 胜率: 38.9% (-22.2%)\n"
          + "领先: -8.9 (-2.8) 不确定度: 13.4\n"
          + "(Transformer11BFlagship / 4.5k 计算量)\n"
          + "贴目: 7.5";

  private static String renderWithBundle(ResourceBundle bundle, String comment) {
    return renderWithBundle(bundle, comment, true);
  }

  private static String renderWithBundle(
      ResourceBundle bundle, String comment, boolean separateMatchInfo) {
    ResourceBundle previous = Lizzie.resourceBundle;
    Lizzie.resourceBundle = bundle;
    try {
      return CommentDisplayRenderer.render(comment, separateMatchInfo);
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }
}
