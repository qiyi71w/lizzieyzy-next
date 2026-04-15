package featurecat.lizzie.util;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

public final class ResourceImageCache {
  private static final ConcurrentHashMap<String, BufferedImage> IMAGE_CACHE =
      new ConcurrentHashMap<String, BufferedImage>();
  private static final ConcurrentHashMap<String, ImageIcon> ICON_CACHE =
      new ConcurrentHashMap<String, ImageIcon>();
  private static volatile ImageLoader imageLoader = ResourceImageCache::loadFromClasspath;

  private ResourceImageCache() {}

  public static BufferedImage getImage(String resourcePath) throws IOException {
    validatePath(resourcePath);
    try {
      return IMAGE_CACHE.computeIfAbsent(resourcePath, ResourceImageCache::loadImageUnchecked);
    } catch (UncheckedIOException error) {
      throw error.getCause();
    }
  }

  public static ImageIcon getIcon(String resourcePath) throws IOException {
    validatePath(resourcePath);
    try {
      return ICON_CACHE.computeIfAbsent(resourcePath, ResourceImageCache::loadIconUnchecked);
    } catch (UncheckedIOException error) {
      throw error.getCause();
    }
  }

  static void setImageLoaderForTest(ImageLoader loader) {
    imageLoader = Objects.requireNonNull(loader);
    clearCacheForTest();
  }

  static void resetImageLoaderForTest() {
    imageLoader = ResourceImageCache::loadFromClasspath;
    clearCacheForTest();
  }

  static void clearCacheForTest() {
    IMAGE_CACHE.clear();
    ICON_CACHE.clear();
  }

  private static BufferedImage loadImageUnchecked(String resourcePath) {
    try {
      return imageLoader.load(resourcePath);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static ImageIcon loadIconUnchecked(String resourcePath) {
    try {
      return new ImageIcon(getImage(resourcePath));
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static BufferedImage loadFromClasspath(String resourcePath) throws IOException {
    try (InputStream stream = ResourceImageCache.class.getResourceAsStream(resourcePath)) {
      if (stream == null) {
        throw new IOException("Missing classpath image resource: " + resourcePath);
      }
      BufferedImage image = ImageIO.read(stream);
      if (image == null) {
        throw new IOException("Unsupported classpath image resource: " + resourcePath);
      }
      return image;
    }
  }

  private static void validatePath(String resourcePath) {
    if (resourcePath == null || resourcePath.isBlank()) {
      throw new IllegalArgumentException("resourcePath must not be blank");
    }
  }

  @FunctionalInterface
  interface ImageLoader {
    BufferedImage load(String resourcePath) throws IOException;
  }
}
