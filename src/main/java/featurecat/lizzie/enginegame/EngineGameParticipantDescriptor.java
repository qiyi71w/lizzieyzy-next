package featurecat.lizzie.enginegame;

import java.util.Objects;

/** Frozen participant facts for one exact history. */
public record EngineGameParticipantDescriptor(
    EngineParticipantIdentity identity,
    String displayName,
    boolean katago,
    boolean sai,
    int usingSpecificRules) {
  public EngineGameParticipantDescriptor {
    identity = Objects.requireNonNull(identity, "identity");
    displayName = displayName == null ? "" : displayName;
  }
}
