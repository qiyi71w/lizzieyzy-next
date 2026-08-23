package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class ForegroundKatagoAiPositionProviderTest {

  @Test
  void supportsOnlyForegroundKataGo() throws Exception {
    ForegroundKatagoAiPositionProvider provider = new ForegroundKatagoAiPositionProvider();
    Leelaz kataGo = new Leelaz("");
    kataGo.isKatago = true;
    Leelaz other = new Leelaz("");
    other.isKatago = false;

    assertTrue(provider.supports(context(kataGo)));
    assertFalse(provider.supports(context(other)));
    assertFalse(provider.supports(context(null)));
  }

  @Test
  void addKataTagRequestsRootInfoAndOwnershipWithoutStartingAnotherProcess() throws Exception {
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Lizzie.config = allocate(Config.class);
      Lizzie.config.showKataGoEstimate = true;
      Lizzie.frame = null;
      Leelaz engine = new Leelaz("");
      String tag = engine.addKataTag();
      assertTrue(tag.contains("rootInfo true"));
      assertTrue(tag.contains("ownership true"));
    } finally {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
    }
  }

  private static AiPositionRequestContext context(Leelaz engine) {
    return new AiPositionRequestContext(
        "node", 1L, "[stones]", true, 19, 19, "chinese", 7.5, engine, 1L);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
