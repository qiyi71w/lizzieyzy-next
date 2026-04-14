package featurecat.lizzie.analysis;

import java.util.Arrays;

final class SyncConflictTracker {
  enum Decision {
    APPLY,
    HOLD,
    REBUILD
  }

  private int[] pendingSnapshot = new int[0];
  private boolean pendingConflict = false;

  Decision evaluate(int[] snapshot, boolean canApplyIncrementally) {
    if (canApplyIncrementally) {
      clear();
      return Decision.APPLY;
    }
    if (!pendingConflict || !Arrays.equals(pendingSnapshot, snapshot)) {
      pendingSnapshot = Arrays.copyOf(snapshot, snapshot.length);
      pendingConflict = true;
      return Decision.HOLD;
    }
    clear();
    return Decision.REBUILD;
  }

  void clear() {
    pendingSnapshot = new int[0];
    pendingConflict = false;
  }
}
