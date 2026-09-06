package featurecat.lizzie.enginegame;

/** Asks whether an unverified batch may continue. Tests inject a decision; Swing prompts. */
public interface MatchRulesConsent {
  boolean confirmUnverified(MatchRulesAdmission.Decision decision);
}
