package featurecat.lizzie.analysis;

/** Cross-package access to package-private response callbacks for process-level tests. */
public final class LeelazResponseTestBridge {
  private LeelazResponseTestBridge() {}

  public static void sendCommandWithResponse(Leelaz engine, String command, Runnable onResponse) {
    engine.sendCommandWithResponseForTest(command, onResponse);
  }
}
