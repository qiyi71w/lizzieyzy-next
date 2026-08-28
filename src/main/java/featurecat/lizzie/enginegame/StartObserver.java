package featurecat.lizzie.enginegame;

/** One-shot observer for the first accepted game only. */
public interface StartObserver {
  void playing();

  void startFailed(StartFailure failure);
}
