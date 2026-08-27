package featurecat.lizzie.enginegame;

import java.util.Objects;

/**
 * Immutable record context attached to one exact history at game creation. Production always
 * carries the frozen {@link EngineGamePlan}.
 */
public record EngineGameRecordContext(
    EngineGamePlan plan,
    EngineGameParticipantDescriptor black,
    EngineGameParticipantDescriptor white) {
  public EngineGameRecordContext {
    black = Objects.requireNonNull(black, "black");
    white = Objects.requireNonNull(white, "white");
  }

  /** Formatting-only marker for engine-save SNAPSHOT serialization tests. */
  public static EngineGameRecordContext saveFormattingMarker() {
    EngineParticipantIdentity identity = new EngineParticipantIdentity("", "");
    EngineGameParticipantDescriptor descriptor =
        new EngineGameParticipantDescriptor(identity, "", false, false, 0);
    return new EngineGameRecordContext(null, descriptor, descriptor);
  }

  public int openingIndex() {
    return plan == null ? -1 : plan.openingIndex();
  }

  public int blackIndex() {
    return plan == null ? -1 : plan.blackIndex();
  }

  public int whiteIndex() {
    return plan == null ? -1 : plan.whiteIndex();
  }
}
