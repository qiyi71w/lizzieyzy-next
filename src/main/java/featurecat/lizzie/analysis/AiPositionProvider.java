package featurecat.lizzie.analysis;

/** Source of AI形势 search updates for one request context. */
public interface AiPositionProvider {
  boolean supports(AiPositionRequestContext context);

  boolean start(AiPositionRequestContext context, long generation);

  void stop(long generation);
}
