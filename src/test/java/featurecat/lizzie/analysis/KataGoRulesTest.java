package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KataGoRulesTest {

  @Test
  void chineseSimpleAndPositionalShareChineseSummaryButAreNotEquivalent() {
    KataGoRules simple = KataGoRules.parse("chinese").orElseThrow();
    KataGoRules positional = KataGoRules.parse("chinese-ogs").orElseThrow();

    assertEquals(KataGoRules.Summary.CHINESE, simple.summary());
    assertEquals(KataGoRules.Summary.CHINESE, positional.summary());
    assertEquals(1, simple.legacyClassification());
    assertEquals(1, positional.legacyClassification());
    assertFalse(simple.semanticallyEquals(positional));
  }

  @Test
  void jsonKeyOrderDoesNotChangeEquality() {
    KataGoRules first =
        KataGoRules.parse(
                "{\"ko\":\"SIMPLE\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
                    + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true}")
            .orElseThrow();
    KataGoRules second =
        KataGoRules.parse(
                "{\"friendlyPassOk\":true,\"whiteHandicapBonus\":\"N\",\"hasButton\":false,"
                    + "\"suicide\":false,\"tax\":\"NONE\",\"scoring\":\"AREA\",\"ko\":\"SIMPLE\"}")
            .orElseThrow();

    assertTrue(first.semanticallyEquals(second));
    assertTrue(first.semanticallyEquals(KataGoRules.parse("chinese").orElseThrow()));
  }

  @Test
  void extraFieldsAreKeptAndParticipateInEquality() {
    KataGoRules withExtra =
        KataGoRules.parse(
                "{\"ko\":\"SIMPLE\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
                    + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true,"
                    + "\"experimentalKo\":\"X\"}")
            .orElseThrow();
    KataGoRules chinese = KataGoRules.parse("chinese").orElseThrow();

    assertTrue(withExtra.hasField("experimentalKo"));
    assertEquals("X", withExtra.string("experimentalKo"));
    assertFalse(withExtra.semanticallyEquals(chinese));
    assertTrue(
        withExtra
            .overlayEditor("AREA", "SIMPLE", false, "NONE", "N", false)
            .semanticallyEquals(withExtra));
  }

  @Test
  void missingRequiredFieldCannotProveEquivalenceToACompletePreset() {
    KataGoRules incomplete =
        KataGoRules.parse(
                "{\"ko\":\"SIMPLE\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
                    + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\"}")
            .orElseThrow();

    assertFalse(incomplete.hasRequiredFields());
    assertFalse(incomplete.semanticallyEquals(KataGoRules.parse("chinese").orElseThrow()));
  }

  @Test
  void officialPresetsExpandKnownFields() {
    KataGoRules trompTaylor = KataGoRules.parse("tromp-taylor").orElseThrow();
    assertEquals("POSITIONAL", trompTaylor.string("ko"));
    assertEquals("AREA", trompTaylor.string("scoring"));
    assertTrue(trompTaylor.bool("suicide"));
    assertEquals("0", trompTaylor.string("whiteHandicapBonus"));
    assertFalse(trompTaylor.bool("friendlyPassOk"));
    assertTrue(trompTaylor.hasRequiredFields());
  }
}
