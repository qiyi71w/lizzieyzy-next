package featurecat.lizzie.enginegame;

import featurecat.lizzie.analysis.KataGoRules;
import java.util.List;
import java.util.ResourceBundle;

/** Product choices for match setup, independent of KataGo's full preset registry. */
public enum MatchRuleOption {
  CHINESE("chinese", "LizzieFrame.currentRules.chinese"),
  JAPANESE_KOREAN("japanese", "MatchRules.option.japaneseKorean"),
  AGA_BGA("aga", "MatchRules.option.agaBga"),
  NEW_ZEALAND("new-zealand", "MatchRules.option.newZealand"),
  TROMP_TAYLOR("tromp-taylor", "MatchRules.option.trompTaylor");

  private static final List<MatchRuleOption> OPTIONS = List.of(values());
  private final KataGoRules rules;
  private final String labelKey;

  MatchRuleOption(String preset, String labelKey) {
    this.rules = KataGoRules.parse(preset).orElseThrow();
    this.labelKey = labelKey;
  }

  public static List<MatchRuleOption> options() {
    return OPTIONS;
  }

  public KataGoRules rules() {
    return rules;
  }

  public String displayName(ResourceBundle bundle) {
    return bundle.getString(labelKey);
  }

  /** Only exact parameter matches are standard choices; all other values remain custom. */
  public static MatchRuleOption matching(KataGoRules value) {
    for (MatchRuleOption option : OPTIONS) {
      if (option.rules.semanticallyEquals(value)) {
        return option;
      }
    }
    return null;
  }
}
