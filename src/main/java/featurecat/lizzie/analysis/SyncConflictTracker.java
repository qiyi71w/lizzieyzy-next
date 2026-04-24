package featurecat.lizzie.analysis;

final class SyncConflictTracker {
  enum Decision {
    HOLD,
    REBUILD
  }

  private String pendingConflictKey = "";
  private boolean pendingConflict;

  Decision evaluate(String conflictKey) {
    if (!pendingConflict || !pendingConflictKey.equals(conflictKey)) {
      pendingConflictKey = conflictKey;
      pendingConflict = true;
      return Decision.HOLD;
    }
    clear();
    return Decision.REBUILD;
  }

  void clear() {
    pendingConflictKey = "";
    pendingConflict = false;
  }
}
