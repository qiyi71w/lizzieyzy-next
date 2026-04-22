package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.BoardHistoryNode;

final class SyncResumeState {
  final BoardHistoryNode node;
  final SyncRemoteContext remoteContext;

  SyncResumeState(BoardHistoryNode node, SyncRemoteContext remoteContext) {
    this.node = node;
    this.remoteContext = remoteContext;
  }
}
