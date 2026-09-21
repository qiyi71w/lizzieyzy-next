package featurecat.lizzie.enginegame;

/** Immutable presentation of a single game's runtime komi, separate from its frozen opening plan. */
public record EngineGameKomiState(double applied, double target, boolean pending, String failure) {
}
