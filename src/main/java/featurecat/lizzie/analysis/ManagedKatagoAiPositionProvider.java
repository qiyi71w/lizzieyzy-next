package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.BoardHistoryNode;
import java.util.Optional;
import org.json.JSONObject;

/** Claims a single-position KataGo lease through the resource coordinator. */
public final class ManagedKatagoAiPositionProvider implements AiPositionProvider {
  @FunctionalInterface
  public interface EmissionSink {
    void accept(
        long generation, long sequence, AiPositionSearchUpdate update, boolean blackPerspective);
  }

  private final AnalysisResourceCoordinator.SinglePositionEngineHost host;
  private final EmissionSink sink;
  private AnalysisResourceCoordinator.SinglePositionLease lease;
  private long generation;
  private AiPositionRequestContext context;

  public ManagedKatagoAiPositionProvider(
      AnalysisResourceCoordinator.SinglePositionEngineHost host, EmissionSink sink) {
    this.host = host;
    this.sink = sink;
  }

  @Override
  public boolean supports(AiPositionRequestContext context) {
    return context != null && (context.engine() == null || !context.engine().isKatago);
  }

  @Override
  public boolean start(AiPositionRequestContext context, long generation) {
    releaseLease();
    this.generation = generation;
    this.context = context;
    Optional<AnalysisResourceCoordinator.SinglePositionLease> claimed =
        AnalysisResourceCoordinator.claimSinglePosition(host);
    if (claimed.isEmpty()) {
      return false;
    }
    lease = claimed.get();
    BoardHistoryNode node =
        context.displayPositionIdentity() instanceof BoardHistoryNode
            ? (BoardHistoryNode) context.displayPositionIdentity()
            : null;
    JSONObject request =
        AiPositionAnalysisQuery.build(
            "ai-position-" + generation, context, node, AnalysisEngine.targetAnalysisVisits());
    if (lease.startQuery(request, this::onEmission)) {
      return true;
    }
    releaseLease();
    return false;
  }

  @Override
  public void stop(long generation) {
    if (this.generation != generation) {
      return;
    }
    releaseLease();
  }

  private void onEmission(int sequence, String json) {
    if (lease == null || lease.isReleased() || sink == null || context == null) {
      return;
    }
    Optional<AiPositionSearchUpdate> update = AiPositionSearchUpdate.parseAnalysisJson(json);
    if (update.isEmpty()) {
      return;
    }
    boolean duringSearch = false;
    try {
      duringSearch = new JSONObject(json).optBoolean("isDuringSearch", false);
    } catch (RuntimeException ignored) {
    }
    sink.accept(generation, sequence, update.get(), true);
    if (!duringSearch) {
      releaseLease();
    }
  }

  private void releaseLease() {
    AnalysisResourceCoordinator.SinglePositionLease current = lease;
    lease = null;
    if (current != null) {
      current.release();
    }
  }
}
