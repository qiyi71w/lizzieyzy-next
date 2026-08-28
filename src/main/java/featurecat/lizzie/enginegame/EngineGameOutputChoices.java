package featurecat.lizzie.enginegame;

public record EngineGameOutputChoices(
    boolean autosave, boolean saveWinrateImage, String batchName) {
  public EngineGameOutputChoices {
    batchName = batchName == null ? "" : batchName;
  }
}
