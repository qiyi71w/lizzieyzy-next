package featurecat.lizzie.analysis;

/**
 * Reuses the current foreground KataGo search stream. Does not start a second process.
 *
 * <p>Analyze tags and line delivery live on {@link Leelaz}; this provider only claims the
 * foreground KataGo engine.
 */
public final class ForegroundKatagoAiPositionProvider implements AiPositionProvider {
  @Override
  public boolean supports(AiPositionRequestContext context) {
    return context != null && context.engine() != null && context.engine().isKatago;
  }

  @Override
  public boolean start(AiPositionRequestContext context, long generation) {
    // The existing kata-analyze stream is already running or will be restarted by ponder().
    return true;
  }

  @Override
  public void stop(long generation) {
    // Closing AI形势 must not stop ordinary foreground analysis.
  }
}
