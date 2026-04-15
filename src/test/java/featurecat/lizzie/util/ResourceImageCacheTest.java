package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class ResourceImageCacheTest {
  @AfterEach
  void tearDown() {
    ResourceImageCache.resetImageLoaderForTest();
    ResourceImageCache.clearCacheForTest();
  }

  @Test
  void samePathReusesCachedImageAndIconInstances() throws Exception {
    BufferedImage image = ResourceImageCache.getImage("/assets/logo.png");

    assertSame(image, ResourceImageCache.getImage("/assets/logo.png"));
    assertSame(image, ResourceImageCache.getIcon("/assets/logo.png").getImage());
    assertSame(
        ResourceImageCache.getIcon("/assets/logo.png"),
        ResourceImageCache.getIcon("/assets/logo.png"));
  }

  @Test
  void missingResourceThrowsIOException() {
    IOException error =
        assertThrows(
            IOException.class, () -> ResourceImageCache.getImage("/assets/not-found-image.png"));

    assertTrue(error.getMessage().contains("/assets/not-found-image.png"));
  }

  @Test
  void loaderExceptionsArePropagatedAndDoNotPoisonCache() throws Exception {
    IOException failure = new IOException("boom");
    ResourceImageCache.setImageLoaderForTest(
        path -> {
          throw failure;
        });

    IOException thrown =
        assertThrows(IOException.class, () -> ResourceImageCache.getImage("/assets/injected.png"));
    assertSame(failure, thrown);

    AtomicInteger loads = new AtomicInteger();
    BufferedImage expected = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    ResourceImageCache.setImageLoaderForTest(
        path -> {
          loads.incrementAndGet();
          return expected;
        });

    assertSame(expected, ResourceImageCache.getImage("/assets/injected.png"));
    assertSame(expected, ResourceImageCache.getImage("/assets/injected.png"));
    assertEquals(1, loads.get());
  }
}
