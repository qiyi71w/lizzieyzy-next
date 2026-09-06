package featurecat.lizzie.analysis;

/**
 * Test-only settlement for match-owner rules commands. Production never installs a hook. A
 * stateful fixture mutates effective rules here instead of echoing the request.
 */
public interface MatchRulesTestHook {
  void query(Leelaz engine);

  void apply(Leelaz engine, KataGoRules requested);
}
