package featurecat.lizzie.enginegame;

/**
 * Stable engine-vs-engine participant identity: command plus name, matching {@code
 * EnginePkIdentity}. Mutable catalog indexes are not accepted input.
 */
public record EngineParticipantIdentity(String commands, String name) {
  public EngineParticipantIdentity {
    commands = commands == null ? "" : commands;
    name = name == null ? "" : name;
  }
}
