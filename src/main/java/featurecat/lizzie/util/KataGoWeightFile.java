package featurecat.lizzie.util;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Rules for KataGo weight downloads that nothing else vouches for: where from, named how, whole.
 */
final class KataGoWeightFile {
  /** A plain weight file name: no folders, so it stays in the folder it is resolved against. */
  private static final Pattern PLAIN_NAME =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.(?:bin|txt)\\.gz");

  private KataGoWeightFile() {}

  /**
   * Whether a download link read from the networks page may be followed: HTTPS to the KataGo
   * training site, ending in a plain weight file name. The page is not ours, so a link elsewhere,
   * or a name with a folder in it (on Windows a backslash is one), is dropped.
   */
  static boolean isTrustedListing(String url) {
    try {
      URI uri = new URI(url);
      String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
      String path = uri.getPath() == null ? "" : uri.getPath();
      return "https".equalsIgnoreCase(uri.getScheme())
          && (host.equals("katagotraining.org") || host.endsWith(".katagotraining.org"))
          && isPlainName(path.substring(path.lastIndexOf('/') + 1));
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /** {@code folder.resolve(name)}, refusing a name that is not a plain weight file name. */
  static Path inFolder(Path folder, String name) throws IOException {
    if (!isPlainName(name)) {
      throw new IOException("Unsafe weight file name: " + name);
    }
    return folder.resolve(name);
  }

  static boolean isPlainName(String name) {
    return name != null && PLAIN_NAME.matcher(name).matches();
  }

  /**
   * For a weight listed without size or digest (the rows read from the networks page) only its own
   * format can tell. KataGo weights are gzip files, whose trailer carries a CRC and the length, so
   * a cut-off file fails to read to the end. A size above 1 MiB used to be taken as whole, and a
   * stopped download was then installed as it was; other names keep that rule.
   *
   * @param info the listing, for the file's name; the path may be the download's ".part" file
   */
  static boolean isWhole(Path path, KataGoAutoSetupHelper.RemoteWeightInfo info)
      throws IOException {
    String name = info == null ? path.getFileName().toString() : info.fileName();
    if (!name.toLowerCase(Locale.ROOT).endsWith(".gz")) {
      return Files.size(path) > 1024L * 1024L;
    }
    try (InputStream input = new GZIPInputStream(Files.newInputStream(path), 64 * 1024)) {
      byte[] buffer = new byte[64 * 1024];
      while (input.read(buffer) >= 0) {}
      return true;
    } catch (ZipException | EOFException e) {
      return false;
    }
  }
}
