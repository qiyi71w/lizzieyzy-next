package featurecat.lizzie.teacher;

import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.SGFParser.WinrateCommentSplit;
import java.util.Optional;

/** Builds trusted HTML for displaying user SGF comments and stored AI commentary. */
public final class CommentDisplayRenderer {
  private CommentDisplayRenderer() {}

  public static String render(String rawComment) {
    return render(rawComment, false);
  }

  public static String render(String rawComment, boolean separateMatchInfo) {
    String userComment = TeacherCommentCodec.removeBlocks(rawComment);
    Optional<String> aiCommentary = TeacherCommentCodec.extract(rawComment);
    String personalComment = userComment;
    String matchInfo = "";
    if (separateMatchInfo) {
      WinrateCommentSplit split = SGFParser.splitWinrateComment(userComment);
      personalComment = split.personalComment;
      matchInfo = split.matchInfo;
    }
    StringBuilder body = new StringBuilder("<html><body>");
    if (!personalComment.isEmpty()) {
      appendSgfComment(body, personalComment);
    }
    if (!personalComment.isEmpty() && !matchInfo.isEmpty()) {
      body.append("<div class='match-info-divider'>&nbsp;</div>");
    }
    if (!matchInfo.isEmpty()) {
      appendSgfComment(body, matchInfo);
    }
    boolean hasSgfText = !personalComment.isEmpty() || !matchInfo.isEmpty();
    if (aiCommentary.isPresent()) {
      if (hasSgfText) {
        body.append("<div class='comment-spacer'>&nbsp;</div>");
      }
      body.append("<div class='ai-commentary'>")
          .append("<div class='ai-commentary-title'><strong>")
          .append(
              SafeMarkdownRenderer.escape(
                  TeacherStrings.get("Teacher.title", "AI Commentary")))
          .append("</strong></div>")
          .append(SafeMarkdownRenderer.toBodyHtml(aiCommentary.get()))
          .append("</div>");
    }
    return body.append("</body></html>").toString();
  }

  private static void appendSgfComment(StringBuilder body, String text) {
    body.append("<div class='sgf-comment'>")
        .append(SafeMarkdownRenderer.plainTextToBodyHtml(text))
        .append("</div>");
  }
}
