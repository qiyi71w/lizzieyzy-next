package featurecat.lizzie.update;

/** Immutable 更新检查选择快照 captured at check time. */
public final class UpdateCheckSelection {
  public final UpdateChannel channel;
  public final UpdateSource effectiveSource;
  public final String installedVersion;

  public UpdateCheckSelection(
      UpdateChannel channel, UpdateSource effectiveSource, String installedVersion) {
    this.channel = channel == null ? UpdateChannel.STABLE : channel;
    this.effectiveSource = effectiveSource == null ? UpdateSource.OFFICIAL_SITE : effectiveSource;
    this.installedVersion = installedVersion;
  }

  public static UpdateCheckSelection of(
      UpdateChannel channel, UpdateSource selectedSource, String installedVersion) {
    UpdateChannel resolved = channel == null ? UpdateChannel.STABLE : channel;
    UpdateSource effective =
        resolved == UpdateChannel.BETA
            ? UpdateSource.GITHUB
            : selectedSource == null ? UpdateSource.OFFICIAL_SITE : selectedSource;
    return new UpdateCheckSelection(resolved, effective, installedVersion);
  }
}
