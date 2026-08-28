package featurecat.lizzie.enginegame;

import java.util.Objects;

/**
 * Opaque owner-issued lifecycle handle for one product transaction. The module may store and pass
 * this binding but cannot inspect owner internals.
 */
public final class LifecycleBinding {
  private final Object ownerToken;

  LifecycleBinding(Object ownerToken) {
    this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
  }

  public static LifecycleBinding ofOwner(Object ownerToken) {
    return new LifecycleBinding(ownerToken);
  }

  public boolean sameOwner(Object ownerToken) {
    return this.ownerToken == ownerToken;
  }

  public Object ownerToken() {
    return ownerToken;
  }
}
