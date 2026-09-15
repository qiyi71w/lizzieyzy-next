package featurecat.lizzie.analysis;

/** Test-scope access to the explicit local snapshot-file trust seam. */
public final class SnapshotFileAccessTestBridge {
  private SnapshotFileAccessTestBridge() {}

  public static void trustDirectLocalSnapshotFileAccessForTest(Leelaz engine) {
    engine.trustDirectLocalSnapshotFileAccessForTest();
  }
}
