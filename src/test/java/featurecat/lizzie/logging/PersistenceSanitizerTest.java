package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PersistenceSanitizerTest {
  private static final List<String> CANARIES =
      List.of(
          "CANARY_BARE_BEARER_01",
          "CANARY_ESCAPED_PASSWORD_02",
          "CANARY_UNICODE_COLON_03",
          "CANARY_UNICODE_EQUAL_04",
          "CANARY_RAW_QUOTED_SUFFIX_05",
          "CANARY_EMBEDDED_QUOTED_SUFFIX_06",
          "CANARY_UNICODE_NAME_07",
          "CANARY_DOUBLE_UNICODE_NAME_08",
          "CANARY_PERCENT_PASSWORD_09",
          "CANARY_PERCENT_KEY_10",
          "CANARY_PERCENT_DOUBLE_11",
          "CANARY_PERCENT_MIXED_HEX_12",
          "CANARY_PERCENT_OVERDEPTH_ASSIGNMENT_13",
          "CANARY_PERCENT_OVERDEPTH_KEY_14",
          "CANARY_PERCENT_OVERDEPTH_MIXED_HEX_15",
          "CANARY_PERCENT_OVERDEPTH_HEADER_16",
          "CANARY_CLI_API_KEY",
          "CANARY_CLI_QUOTED_KEY",
          "CANARY_PERCENT_OVERDEPTH_BOUNDARY_17");

  private static final List<String> PAYLOADS =
      List.of(
          "Bearer CANARY_BARE_BEARER_01",
          "payload={\\\"password\\\":\\\"CANARY_ESCAPED_PASSWORD_02\\\"}",
          "Authorization\\u003a Bearer CANARY_UNICODE_COLON_03",
          "password\\u003dCANARY_UNICODE_EQUAL_04",
          "payload={\"password\":\"prefix\\\"CANARY_RAW_QUOTED_SUFFIX_05\"}",
          escapedEmbeddedJson("CANARY_EMBEDDED_QUOTED_SUFFIX_06"),
          "passw\\u006frd=CANARY_UNICODE_NAME_07",
          "passw\\\\u006frd=CANARY_DOUBLE_UNICODE_NAME_08",
          "body=password%3DCANARY_PERCENT_PASSWORD_09",
          "body=%70assword=CANARY_PERCENT_KEY_10",
          "body=token%253DCANARY_PERCENT_DOUBLE_11",
          "body=passw%4Frd%3dCANARY_PERCENT_MIXED_HEX_12",
          "body=password%252525253DCANARY_PERCENT_OVERDEPTH_ASSIGNMENT_13",
          "body=%2525252570assword=CANARY_PERCENT_OVERDEPTH_KEY_14",
          "body=passw%254Frd%252525253dCANARY_PERCENT_OVERDEPTH_MIXED_HEX_15",
          "Authorization%252525253A Bearer CANARY_PERCENT_OVERDEPTH_HEADER_16",
          "engine.exe --api-key CANARY_CLI_API_KEY gtp",
          "engine.exe --api-key 'CANARY_CLI_QUOTED_KEY with spaces' gtp",
          "body=opaque%2525252520token=CANARY_PERCENT_OVERDEPTH_BOUNDARY_17");

  @Test
  void encodedCredentialFormsAreRedactedWithoutCopyingCanaries() {
    assertSanitized(new PersistenceSanitizer());
  }

  @Test
  void percentCredentialMatchingPreservesEncodedPrefixAndNeverRewritesValue() {
    PersistenceSanitizer sanitizer = new PersistenceSanitizer();

    assertEquals(
        "body=password%3D<redacted>",
        sanitizer.sanitize("body=password%3DCANARY_PERCENT_PASSWORD_09"));
    assertEquals(
        "body=%70assword=<redacted>",
        sanitizer.sanitize("body=%70assword=CANARY_PERCENT_KEY_10"));
    assertEquals(
        "body=token%253D<redacted>",
        sanitizer.sanitize("body=token%253DCANARY_PERCENT_DOUBLE_11"));
    assertEquals(
        "body=passw%4Frd%3d<redacted>",
        sanitizer.sanitize("body=passw%4Frd%3dCANARY_PERCENT_MIXED_HEX_12"));
    assertEquals(
        "body=secret%2525253D<redacted>",
        sanitizer.sanitize("body=secret%2525253DCANARY_PERCENT_MAX_DEPTH"));
    assertEquals(
        "body=password%252525253D<redacted>",
        sanitizer.sanitize(
            "body=password%252525253DCANARY_PERCENT_OVERDEPTH_ASSIGNMENT_13"));
    assertEquals(
        "body=%2525252570assword=<redacted>",
        sanitizer.sanitize("body=%2525252570assword=CANARY_PERCENT_OVERDEPTH_KEY_14"));
    assertEquals(
        "body=passw%254Frd%252525253d<redacted>",
        sanitizer.sanitize(
            "body=passw%254Frd%252525253dCANARY_PERCENT_OVERDEPTH_MIXED_HEX_15"));
    assertEquals(
        "Authorization%252525253A <redacted>",
        sanitizer.sanitize(
            "Authorization%252525253A Bearer CANARY_PERCENT_OVERDEPTH_HEADER_16"));
    assertEquals(
        "body=opaque%2525252520token=<redacted>",
        sanitizer.sanitize(
            "body=opaque%2525252520token=CANARY_PERCENT_OVERDEPTH_BOUNDARY_17"));
  }

  @Test
  void percentCredentialScanLimitFailsClosedWithoutExposingTheValue() {
    String beyondScanLimit = "%" + "25".repeat(64) + "3D";
    String canary = "CANARY_PERCENT_BEYOND_SCAN_LIMIT_18";
    String sanitized =
        new PersistenceSanitizer().sanitize("body=token" + beyondScanLimit + canary);

    assertEquals(PersistenceSanitizer.FAILURE_MARKER, sanitized);
    assertFalse(sanitized.contains(canary));
  }

  @Test
  void exportSanitizerAlsoRedactsEncodedCredentialForms() {
    ExportSanitizer sanitizer = new ExportSanitizer();
    for (String payload : PAYLOADS) {
      String sanitized = sanitizer.sanitize(payload);
      assertNoCanary(sanitized);
      assertTrue(
          sanitized.contains("<redacted") || sanitized.contains("<redacted-credential>"),
          sanitized);
    }
  }

  @Test
  void nonCredentialPercentEncodingIsPreservedAndUrlPolicyIsUnchanged() {
    String ordinary = "progress=90%25 note=hello%20world code=%7Bplain%7D";
    String deepOrdinary = "opaque=%2525252541";
    String url = "https://example.invalid/a%2Fb?q=keep%20me";

    assertEquals(ordinary, new PersistenceSanitizer().sanitize(ordinary));
    assertEquals(deepOrdinary, new PersistenceSanitizer().sanitize(deepOrdinary));
    assertEquals(url, new PersistenceSanitizer().sanitize(url));

    ExportSanitizer export = new ExportSanitizer();
    assertEquals(ordinary, export.sanitize(ordinary));
    assertEquals(deepOrdinary, export.sanitize(deepOrdinary));
    String sanitizedUrl = export.sanitize(url);
    assertTrue(sanitizedUrl.contains("<redacted-url>"), sanitizedUrl);
    assertFalse(sanitizedUrl.contains("example.invalid"), sanitizedUrl);
  }

  private static void assertSanitized(PersistenceSanitizer sanitizer) {
    for (String payload : PAYLOADS) {
      String sanitized = sanitizer.sanitize(payload);
      assertNoCanary(sanitized);
      assertTrue(sanitized.contains("<redacted>"), sanitized);
    }
  }

  private static void assertNoCanary(String sanitized) {
    for (String canary : CANARIES) {
      assertFalse(sanitized.contains(canary), sanitized);
    }
  }

  private static String escapedEmbeddedJson(String canary) {
    String slash = "\\";
    String quote = "\"";
    return "payload={"
        + slash
        + quote
        + "password"
        + slash
        + quote
        + ":"
        + slash
        + quote
        + "prefix"
        + slash
        + slash
        + slash
        + quote
        + canary
        + slash
        + quote
        + "}";
  }
}
