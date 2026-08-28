package featurecat.lizzie.enginegame;

import java.util.Objects;

public sealed interface Acceptance {
  record Accepted() implements Acceptance {}

  record Rejected(Rejection reason) implements Acceptance {
    public Rejected {
      reason = Objects.requireNonNull(reason, "reason");
    }
  }
}
