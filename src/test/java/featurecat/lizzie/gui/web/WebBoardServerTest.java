package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

public class WebBoardServerTest {
  private WebBoardServer server;
  private final List<TestClient> ownedClients = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    AtomicReference<Exception> startupError = new AtomicReference<>();
    server = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 2) {
      @Override
      public void onStart() {
        ready.countDown();
      }

      @Override
      public void onError(org.java_websocket.WebSocket connection, Exception error) {
        startupError.compareAndSet(null, error);
        ready.countDown();
      }
    };
    server.start();
    assertTrue(ready.await(3, TimeUnit.SECONDS), "server did not become ready");
    assertNull(startupError.get(), "server startup failed");
    assertTrue(server.getPort() > 0, "server did not bind an ephemeral port");
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      ownedClients.forEach(TestClient::close);
      // Stop the owned server before awaiting clients, so a pending close handshake
      // cannot outlive the fixture and depend on the library's heartbeat timeout.
      if (server != null) server.stop(1000);
      for (TestClient client : ownedClients) {
        assertTrue(client.closed.await(3, TimeUnit.SECONDS), "owned client did not close");
      }
    } finally {
      if (server != null) server.stop(1000);
    }
  }

  @Test
  void rejectsConnectionsAboveLimit() throws Exception {
    CountDownLatch openLatch = new CountDownLatch(2);
    CountDownLatch closeLatch = new CountDownLatch(1);

    List<TestClient> clients = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      TestClient c = new TestClient(server.getPort(), openLatch, null);
      assertTrue(c.connectBlocking(2, TimeUnit.SECONDS));
      clients.add(c);
    }
    assertTrue(openLatch.await(3, TimeUnit.SECONDS));
    awaitServerConnections(2);

    TestClient rejected = new TestClient(server.getPort(), null, closeLatch);
    rejected.connectBlocking(2, TimeUnit.SECONDS);
    assertTrue(closeLatch.await(3, TimeUnit.SECONDS), "3rd connection should be closed");

  }

  @Test
  void sendsFullStateOnConnect() throws Exception {
    String fullState = "{\"type\":\"full_state\",\"boardWidth\":19}";
    server.broadcastFullState(fullState);

    CountDownLatch msgLatch = new CountDownLatch(1);
    AtomicReference<String> received = new AtomicReference<>();
    TestClient c =
        new TestClient(server.getPort(), null, null) {
          @Override
          public void onMessage(String msg) {
            received.set(msg);
            msgLatch.countDown();
          }
        };
    assertTrue(c.connectBlocking(2, TimeUnit.SECONDS));
    assertTrue(msgLatch.await(3, TimeUnit.SECONDS));
    assertTrue(received.get().contains("full_state"));
  }

  @RepeatedTest(10)
  void broadcastsToAllClients() throws Exception {
    CountDownLatch openLatch = new CountDownLatch(2);
    CountDownLatch msgLatch = new CountDownLatch(2);
    List<AtomicReference<String>> received = new ArrayList<>();

    List<TestClient> clients = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      AtomicReference<String> ref = new AtomicReference<>();
      received.add(ref);
      TestClient c =
          new TestClient(server.getPort(), openLatch, null) {
            @Override
            public void onMessage(String msg) {
              ref.set(msg);
              msgLatch.countDown();
            }
          };
      assertTrue(c.connectBlocking(2, TimeUnit.SECONDS));
      clients.add(c);
    }
    assertTrue(openLatch.await(3, TimeUnit.SECONDS));
    awaitServerConnections(2);

    server.broadcastMessage("{\"type\":\"test\"}");
    assertTrue(msgLatch.await(3, TimeUnit.SECONDS));

    for (AtomicReference<String> ref : received) {
      assertNotNull(ref.get());
      assertTrue(ref.get().contains("test"));
    }

  }

  @Test
  void onMessageDispatchesEnterTrialToHandler() {
    java.util.concurrent.atomic.AtomicReference<org.json.JSONObject> received =
        new java.util.concurrent.atomic.AtomicReference<>();
    WebBoardServer s = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 20);
    s.setMessageHandler((conn, json) -> received.set(json));
    s.onMessage(null, "{\"type\":\"enter_trial\",\"clientId\":\"abc\"}");
    assertNotNull(received.get());
    assertEquals("enter_trial", received.get().getString("type"));
    assertEquals("abc", received.get().getString("clientId"));
  }

  @Test
  void onMessageIgnoresMalformedJson() {
    java.util.concurrent.atomic.AtomicBoolean called =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    WebBoardServer s = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 20);
    s.setMessageHandler((conn, json) -> called.set(true));
    s.onMessage(null, "not json");
    assertFalse(called.get());
  }

  @Test
  void onMessageNoOpWhenHandlerNotSet() {
    WebBoardServer s = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 20);
    s.onMessage(null, "{\"type\":\"x\"}");
  }

  private void awaitServerConnections(int count) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (server.getConnections().size() != count && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertEquals(count, server.getConnections().size(), "server must register clients before broadcast");
  }

  private class TestClient extends WebSocketClient {
    private final CountDownLatch openLatch;
    private final CountDownLatch closeLatch;
    private final CountDownLatch closed = new CountDownLatch(1);

    TestClient(int port, CountDownLatch openLatch, CountDownLatch closeLatch) throws Exception {
      super(new URI("ws://127.0.0.1:" + port));
      this.openLatch = openLatch;
      this.closeLatch = closeLatch;
      ownedClients.add(this);
    }

    @Override
    public void onOpen(ServerHandshake h) {
      if (openLatch != null) openLatch.countDown();
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
      closed.countDown();
      if (closeLatch != null) closeLatch.countDown();
    }

    @Override
    public void onMessage(String msg) {}

    @Override
    public void onError(Exception e) {}
  }
}
