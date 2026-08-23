package featurecat.lizzie.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns the AI形势 request context and the currently visible immutable snapshot.
 *
 * <p>Any context change increments the generation and clears the snapshot before a matching update
 * can publish again.
 */
public final class AiPositionController {
  private final List<AiPositionProvider> providers;
  private final Runnable displayChanged;

  private boolean open;
  private AiPositionProvider provider;
  private AiPositionRequestContext context;
  private long generation;
  private Optional<AiPositionSnapshot> snapshot = Optional.empty();

  public AiPositionController(List<AiPositionProvider> providers, Runnable displayChanged) {
    if (providers == null || providers.isEmpty()) {
      throw new IllegalArgumentException("AI position providers required");
    }
    this.providers = new ArrayList<AiPositionProvider>(providers);
    this.displayChanged = Objects.requireNonNull(displayChanged, "displayChanged");
  }

  public synchronized boolean open(AiPositionRequestContext requestedContext) {
    Objects.requireNonNull(requestedContext, "requestedContext");
    AiPositionProvider selected = select(requestedContext);
    if (selected == null) {
      close();
      return false;
    }
    if (open && provider == selected && context != null && context.matches(requestedContext)) {
      return true;
    }
    stopCurrent();
    open = true;
    provider = selected;
    context = requestedContext;
    generation++;
    snapshot = Optional.empty();
    provider.start(context, generation);
    displayChanged.run();
    return true;
  }

  public synchronized void sync(AiPositionRequestContext currentContext) {
    if (!open) {
      return;
    }
    if (currentContext == null || select(currentContext) == null) {
      close();
      return;
    }
    if (context != null && context.matches(currentContext)) {
      return;
    }
    open(currentContext);
  }

  public synchronized void close() {
    if (!open && snapshot.isEmpty()) {
      return;
    }
    stopCurrent();
    open = false;
    provider = null;
    context = null;
    generation++;
    snapshot = Optional.empty();
    displayChanged.run();
  }

  public synchronized void acceptLine(long expectedGeneration, String line) {
    if (!open || expectedGeneration != generation || context == null) {
      return;
    }
    Optional<AiPositionSearchUpdate> update = AiPositionSearchUpdate.parse(line);
    if (update.isEmpty()) {
      return;
    }
    snapshot = Optional.of(AiPositionSnapshot.from(context, generation, update.get()));
    displayChanged.run();
  }

  public synchronized boolean isOpen() {
    return open;
  }

  public synchronized long generation() {
    return generation;
  }

  public synchronized Optional<AiPositionSnapshot> snapshot() {
    return snapshot;
  }

  public synchronized Optional<AiPositionSnapshot> visibleSnapshot(
      AiPositionRequestContext currentContext) {
    if (!open || snapshot.isEmpty() || currentContext == null || context == null) {
      return Optional.empty();
    }
    if (!context.matches(currentContext)) {
      return Optional.empty();
    }
    return snapshot;
  }


  public synchronized Optional<AiPositionRequestContext> context() {
    return Optional.ofNullable(context);
  }

  private AiPositionProvider select(AiPositionRequestContext requestedContext) {
    for (int i = 0; i < providers.size(); i++) {
      AiPositionProvider candidate = providers.get(i);
      if (candidate.supports(requestedContext)) {
        return candidate;
      }
    }
    return null;
  }

  private void stopCurrent() {
    AiPositionProvider current = provider;
    long stoppingGeneration = generation;
    if (current != null) {
      current.stop(stoppingGeneration);
    }
  }
}
